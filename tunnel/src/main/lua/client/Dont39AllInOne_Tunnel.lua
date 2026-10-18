-- Dont39 All-in-One: tunnel. A "Tunnel" choice (Off / TCP / KCP) and port in the add/edit server dialog, which the
-- Java side stores per server address and reads when the game connects; and a check before each join that refuses a
-- join to a tunnel entry when the Java side cannot carry it, instead of letting it go over UDP. The server list shows
-- the tunnel of each entry that has one.

require "ISUI/ISMPEditServer"
require "ISUI/ISLabel"
require "ISUI/ISTextEntryBox"
require "ISUI/ISComboBox"
require "OptionScreens/MultiplayerUI"

-- not "tunnel: ", which prefixes the Java side's status lines
local PREFIX = "[Dont39AllInOne] tunnel dialog: "
local GUARD_PREFIX = "[Dont39AllInOne] tunnel join check: "
local GAP = 10 -- ISMPEditServer's UI_BORDER_SPACING
local MAX_PORT = 65535
local DEFAULT_PORT = 16262 -- what vanilla saves when the port field is not a number
local STATE = "Dont39AllInOne_Tunnel" -- this file's state on a dialog instance
local VALID = { r = 0, g = 0, b = 0, a = 0.5 } -- ISMPEditServer:prerender's colors
local INVALID = { r = 0.5, g = 0, b = 0, a = 0.5 }
local MODES = { "off", "tcp", "kcp" }
local MODE_TEXT = { off = "Off", tcp = "Tcp", kcp = "Kcp" } -- UI_Dont39AllInOne_Tunnel_<text>, _<text>Tooltip
-- in Zomboid/Lua, written by TunnelStore.kt; the released TCP-only file is read while the new one does not exist
local STORE = "Dont39AllInOne_Tunnel.txt"
local RELEASED_STORE = "Dont39AllInOne_TcpTunnel.txt"

-- Never throws.
local function log(prefix, message)
    pcall(function() DebugType.Mod:println(prefix .. message) end)
end

local function trim(text)
    return string.match(text, "^%s*(.-)%s*$")
end

-- as text: the Java side would get Lua numbers as doubles
local function portText(number)
    return tostring(math.floor(number))
end

-- ---- join check ----

-- The file's lines, nil when it does not exist.
local function readLines(name)
    local reader = getFileReader(name, false)
    if not reader then return nil end
    local lines = {}
    local ok, err = pcall(function()
        local line = reader:readLine()
        while line do
            lines[#lines + 1] = line
            line = reader:readLine()
        end
    end)
    reader:close()
    if not ok then error("reading Zomboid/Lua/" .. name .. ": " .. tostring(err)) end
    return lines
end

-- Every stored address ("<host>:<game port>") with its mode and both ports (0 when never set); nil when there is no
-- store. Parsed as TunnelStore.kt does.
local function readStore()
    local name, released = STORE, false
    local lines = readLines(STORE)
    if not lines then
        name, released = RELEASED_STORE, true
        lines = readLines(RELEASED_STORE)
        if not lines then return nil end
    end
    local entries = {}
    for i, line in ipairs(lines) do
        if not string.match(line, "^%s*$") then
            local entryKey, value = string.match(line, "^(.*)=([^=]*)$")
            local mode, tcp, kcp = nil, nil, nil
            if entryKey then
                entryKey, value = trim(entryKey), trim(value)
                if released then
                    tcp = string.match(value, "^%d+$")
                    mode, kcp = tcp and "tcp", "0"
                else
                    mode, tcp, kcp = string.match(value, "^(%a+)%s*,%s*(%d+)%s*,%s*(%d+)$")
                end
            end
            if not entryKey or entryKey == "" or (mode ~= "off" and mode ~= "tcp" and mode ~= "kcp") then
                error("Zomboid/Lua/" .. name .. " line " .. i .. " is not a tunnel entry: " .. line)
            end
            -- the last line of an address wins, as in the Java side's map
            entries[entryKey] = { mode = mode, tcp = tonumber(tcp), kcp = tonumber(kcp) }
        end
    end
    return entries
end

-- Why the join to [ip]:[port] must not go on, translated; nil to let it.
local function refusal(ip, port)
    if type(ip) ~= "string" or port == nil then return nil end
    local number = tonumber(type(port) == "string" and trim(port) or port)
    -- not a port GameClient.doConnect parses, or a Steam ID join; neither has an entry
    if not number or number ~= math.floor(number) or (number == 0 and getSteamModeActive()) then return nil end
    local entries = readStore()
    local entry = entries and entries[trim(ip) .. ":" .. portText(number)]
    if not entry or entry.mode == "off" then return nil end
    if not Dont39AllInOne_getTunnelProblem then
        return getText("UI_Dont39AllInOne_Tunnel_Failed_NoJava")
    end
    local problem = Dont39AllInOne_getTunnelProblem()
    if problem then
        return getText("UI_Dont39AllInOne_Tunnel_Failed_Inactive", problem)
    end
    return nil
end

-- every dedicated-server join from vanilla Lua goes through this global
local vanillaServerConnect = serverConnect
if not vanillaServerConnect then
    error(GUARD_PREFIX .. "the game's serverConnect function was not found")
end

serverConnect = function(userName, password, ip, localIP, port, ...)
    local ok, refused, trace = pcall(refusal, ip, port)
    if not ok then
        -- fail closed: letting the join through would be the UDP fallback the player did not choose
        pcall(function()
            DebugType.Mod:error(GUARD_PREFIX .. "refused a join after an error: " .. tostring(refused) .. "\n"
                .. tostring(trace))
        end)
        refused = getText("UI_Dont39AllInOne_Tunnel_Failed_Check", tostring(refused))
    end
    if refused then
        log(GUARD_PREFIX, "refused the join to " .. tostring(ip) .. ":" .. tostring(port) .. ": " .. refused)
        -- as vanilla's unknown host: the join screen shows the text and a Back button
        triggerEvent("OnConnectFailed", refused)
        return
    end
    return vanillaServerConnect(userName, password, ip, localIP, port, ...)
end

-- ---- dialog ----

if not ISMPEditServer or not ISMPEditServer.initialise or not ISMPEditServer.onClick then
    error(PREFIX .. "the game's add/edit server dialog (ISMPEditServer) was not found")
end

local vanillaInitialise = ISMPEditServer.initialise
local vanillaOnClick = ISMPEditServer.onClick
local disabled = false -- after an error in the dialog; final for the session
local reportedNoJava = false

-- [trace]: the Lua stack where the error was raised (the game's pcall returns it third).
local function fail(what, err, trace)
    if disabled then return end
    disabled = true
    pcall(function()
        DebugType.Mod:error(PREFIX .. "disabled after an error while " .. what .. ": " .. tostring(err) .. "\n"
            .. tostring(trace))
    end)
end

-- absent when ZombieBuddy did not load this mod's Java code
local function javaLoaded()
    return Dont39AllInOne_getTunnelMode ~= nil and Dont39AllInOne_getTunnelTcpPort ~= nil
        and Dont39AllInOne_getTunnelKcpPort ~= nil and Dont39AllInOne_setTunnel ~= nil
end

-- The port as text, "" when empty, nil when it is no port.
local function portValue(text)
    text = trim(text)
    if text == "" then return "" end
    local number = tonumber(text)
    if not number or number ~= math.floor(number) or number < 1 or number > MAX_PORT then return nil end
    return portText(number)
end

-- Makes room below the tunnel rows for vanilla's password widgets: the port row only while a tunnel is chosen.
local function layout(st)
    local shift = (st.mode == "off" and 1 or 2) * st.rowHeight
    st.password:setY(st.passwordY + shift)
    st.eye:setY(st.eyeY + shift)
    st.dialog:setHeight(st.height + shift)
end

-- Follows the combo box: keeps the shown port in its tunnel's slot and shows the chosen tunnel's.
local function sync(st)
    local mode = st.combo:getOptionData(st.combo.selected)
    if not MODE_TEXT[mode] then error("the tunnel choice has no mode: " .. tostring(mode)) end
    if mode == st.mode then return end
    if st.mode ~= "off" then
        -- what is no port is not kept
        st.ports[st.mode] = portValue(st.entry:getInternalText()) or ""
    end
    st.mode = mode
    local on = mode ~= "off"
    st.entry:setText(on and st.ports[mode] or "")
    if not on and st.entry:isFocused() then st.entry:unfocus() end
    st.entry:setVisible(on)
    st.portLabel:setVisible(on)
    layout(st)
end

local function validate(self, st)
    sync(st)
    if st.mode == "off" then return end
    local value = portValue(st.entry:getInternalText())
    if value and value ~= "" then
        st.entry.backgroundColor = VALID
    else
        st.entry.backgroundColor = INVALID
        self.saveBtn:setEnable(false)
    end
end

local function attach(self)
    local name, port, password, eye = self.serverName, self.serverPort, self.serverPassword, self.seePasswordBtn
    if not name or not port or not password or not eye or not self.saveBtn then
        error("the dialog is not laid out as expected")
    end
    local font = port.font or UIFont.Medium
    local textManager = getTextManager()
    local x = name:getX()
    local height = port:getHeight()
    local comboY = port:getBottom() + GAP
    local entryY = comboY + height + GAP

    local tunnelText = getText("UI_Dont39AllInOne_Tunnel_Tunnel")
    local portLabelText = getText("UI_Dont39AllInOne_Tunnel_Port")
    -- one column for both rows, after the wider label, as vanilla's port row; fields end where vanilla's do
    local fieldX = x + GAP
        + math.max(textManager:MeasureStringX(font, tunnelText), textManager:MeasureStringX(font, portLabelText))
    local fieldWidth = name:getRight() - fieldX

    -- top-aligned like vanilla's port label
    local function label(text, y)
        local l = ISLabel:new(x, y, textManager:getFontHeight(font), text, 1, 1, 1, 1, font, true)
        l:initialise()
        l:instantiate()
        return l
    end

    local tunnelLabel = label(tunnelText, comboY)
    local combo = ISComboBox:new(fieldX, comboY, fieldWidth, height, nil, nil)
    combo.font = font
    combo:initialise()
    combo:instantiate()
    for _, mode in ipairs(MODES) do
        local key = "UI_Dont39AllInOne_Tunnel_" .. MODE_TEXT[mode]
        combo:addOptionWithData(getText(key), mode, getText(key .. "Tooltip"))
    end

    local portLabel = label(portLabelText, entryY)
    local entry = ISTextEntryBox:new("", fieldX, entryY, fieldWidth, height)
    entry.font = font
    entry:initialise()
    entry:instantiate()
    entry:setOnlyNumbers(true)
    entry:setMasked(getCore():getOptionStreamerMode())
    entry:setTooltip(getText("UI_Dont39AllInOne_Tunnel_PortTooltip"))

    local st = {
        combo = combo, entry = entry, portLabel = portLabel, mode = "off", ports = { tcp = "", kcp = "" },
        dialog = self, password = password, eye = eye, rowHeight = height + GAP,
        passwordY = password:getY(), eyeY = eye:getY(), height = self:getHeight(),
    }
    local mode = "off"
    if self.server then
        st.oldHost = self.server:getIp()
        st.oldPort = portText(self.server:getPort())
        mode = Dont39AllInOne_getTunnelMode(st.oldHost, st.oldPort)
        st.ports.tcp = Dont39AllInOne_getTunnelTcpPort(st.oldHost, st.oldPort) or ""
        st.ports.kcp = Dont39AllInOne_getTunnelKcpPort(st.oldHost, st.oldPort) or ""
    end
    combo:selectData(mode)
    if combo:getOptionData(combo.selected) ~= mode then error("unknown stored tunnel mode " .. tostring(mode)) end
    entry:setVisible(false)
    portLabel:setVisible(false)
    sync(st)
    -- sync lays out only on a change, and the dialog starts as off
    layout(st)

    self:addChild(tunnelLabel)
    self:addChild(combo)
    self:addChild(portLabel)
    self:addChild(entry)
    self:setY((getCore():getScreenHeight() - self:getHeight()) / 2)

    -- Tab: port, then the tunnel port when shown, then the password
    local portKey = port.onOtherKey
    port.onOtherKey = function(box, key)
        if not disabled and key == Keyboard.KEY_TAB and st.mode ~= "off" then
            local ok, err, trace = pcall(function()
                box:unfocus()
                entry:focus()
            end)
            if ok then return end
            fail("moving the focus to the tunnel port", err, trace)
        end
        if portKey then return portKey(box, key) end
    end
    entry.onOtherKey = function(box, key)
        if key == Keyboard.KEY_TAB then
            box:unfocus()
            password:focus()
        end
    end

    -- on this instance only: vanilla enables Save every frame
    local prerender = self.prerender
    self.prerender = function(dialog)
        prerender(dialog)
        if disabled then return end
        local ok, err, trace = pcall(validate, dialog, st)
        if not ok then
            fail("checking the tunnel port", err, trace)
        end
    end

    self[STATE] = st
end

function ISMPEditServer:initialise(...)
    -- outside pcall, so vanilla's errors surface as without this mod
    vanillaInitialise(self, ...)
    if disabled then return end
    if not javaLoaded() then
        if not reportedNoJava then
            reportedNoJava = true
            log(PREFIX, "no tunnel choice: the Java part of Dont39 All-in-One is not loaded")
        end
        return
    end
    local ok, err, trace = pcall(attach, self)
    if not ok then
        fail("adding the tunnel choice", err, trace)
    end
end

-- What to store, read before vanilla closes the dialog; nil when there is nothing to store.
local function collect(self, st)
    sync(st)
    local ports = { tcp = st.ports.tcp, kcp = st.ports.kcp }
    if st.mode ~= "off" then
        local value = portValue(st.entry:getInternalText())
        if not value or value == "" then
            error("the " .. st.mode .. " port is not a number between 1 and " .. MAX_PORT)
        end
        ports[st.mode] = value
    end
    -- a new entry without a tunnel leaves one stored for the same address
    if not st.oldHost and st.mode == "off" and ports.tcp == "" and ports.kcp == "" then return nil end
    return {
        oldHost = st.oldHost,
        oldPort = st.oldPort,
        -- as vanilla saves the entry, which is what a join then connects to
        host = trim(self.serverAddress:getInternalText()),
        port = portText(tonumber(trim(self.serverPort:getInternalText())) or DEFAULT_PORT),
        mode = st.mode,
        tcp = ports.tcp,
        kcp = ports.kcp,
    }
end

-- The server list's tags by address, read from the store on first draw; cleared whenever the store may have changed.
local listTags = nil

local function store(save)
    Dont39AllInOne_setTunnel(save.oldHost, save.oldPort, save.host, save.port, save.mode, save.tcp, save.kcp)
end

function ISMPEditServer:onClick(button, ...)
    local st = self[STATE]
    local save = nil
    if st and not disabled and button and button.internal == "SAVE" then
        local ok, result, trace = pcall(collect, self, st)
        if ok then
            save = result
        else
            fail("reading the tunnel choice", result, trace)
        end
    end

    vanillaOnClick(self, button, ...)

    if save and not disabled then
        local ok, err, trace = pcall(store, save)
        if not ok then
            fail("saving the tunnel to Zomboid/Lua/" .. STORE, err, trace)
        end
        -- vanilla refilled the list before the tunnel was stored
        listTags = nil
    end
end

-- ---- server list ----

local LIST_PREFIX = "[Dont39AllInOne] tunnel server list: "
local SEPARATOR = " | " -- what MultiplayerUI:drawAccountListItem puts between the address and the version
local TAG_ALPHA = 0.9 -- drawAccountListItem's

if not MultiplayerUI or not MultiplayerUI.refreshList then
    error(LIST_PREFIX .. "the game's server list (MultiplayerUI) was not found")
end

local vanillaRefreshList = MultiplayerUI.refreshList
local listDisabled = false -- after an error in the list; final for the session

local function listFail(what, err, trace)
    if listDisabled then return end
    listDisabled = true
    pcall(function()
        DebugType.Mod:error(LIST_PREFIX .. "tunnel tags off after an error while " .. what .. ": " .. tostring(err)
            .. "\n" .. tostring(trace))
    end)
end

-- The tag of [server]'s address, nil when it has no tunnel.
local function listTag(server)
    if not listTags then
        local tags = {}
        for key, entry in pairs(readStore() or {}) do
            local port = (entry.mode == "tcp" and entry.tcp) or (entry.mode == "kcp" and entry.kcp) or 0
            if port ~= 0 then
                local name = getText("UI_Dont39AllInOne_Tunnel_" .. MODE_TEXT[entry.mode])
                tags[key] = { name = name, withPort = name .. " " .. portText(port) }
            end
        end
        listTags = tags
    end
    return listTags[trim(server:getIp()) .. ":" .. portText(server:getPort())]
end

-- After vanilla's "address | version" line of a server item, at the place drawAccountListItem's layout ends it.
local function drawTag(list, y, item)
    local entry = item.item
    if not entry or entry.type ~= "server" then return end
    local server = entry.server
    local tag = listTag(server)
    if not tag then return end
    local textManager = getTextManager()
    local version = server:getVersion()
    local iconSize = list.itemheight - GAP
    local x = iconSize + GAP
        + textManager:MeasureStringX(UIFont.Small, server:getDisplayAddress())
        + textManager:MeasureStringX(UIFont.Small, SEPARATOR)
        + textManager:MeasureStringX(UIFont.NewSmall, version and ("v " .. version) or "v -")
    local textY = y + textManager:getFontHeight(UIFont.Medium) + GAP
    list:drawText(SEPARATOR, x, textY, 1, 1, 1, TAG_ALPHA, UIFont.Small)
    x = x + textManager:MeasureStringX(UIFont.Small, SEPARATOR)
    -- streamer mode hides the port, as vanilla hides the address
    local text = getCore():getOptionStreamerMode() and tag.name or tag.withPort
    list:drawText(text, x, textY, 1, 1, 1, TAG_ALPHA, UIFont.NewSmall)
end

-- On the list instance, not the class: vanilla copies its renderer into the list when it builds it.
local function hookList(list)
    if list[STATE] then return end
    local draw = list.doDrawItem
    if type(draw) ~= "function" then error("the server list has no item renderer") end
    list[STATE] = true
    list.doDrawItem = function(box, y, item, alt)
        local result = draw(box, y, item, alt)
        if not listDisabled then
            local ok, err, trace = pcall(drawTag, box, y, item)
            if not ok then
                listFail("drawing a server's tunnel", err, trace)
            end
        end
        return result
    end
end

function MultiplayerUI:refreshList(...)
    vanillaRefreshList(self, ...)
    listTags = nil
    if listDisabled then return end
    local ok, err, trace = pcall(function()
        if not self.accountList then error("the list of saved servers (accountList) was not found") end
        hookList(self.accountList)
    end)
    if not ok then
        listFail("hooking the list of saved servers", err, trace)
    end
end

-- a list built before this file was loaded
if MultiplayerUI.instance and MultiplayerUI.instance.accountList then
    local ok, err, trace = pcall(hookList, MultiplayerUI.instance.accountList)
    if not ok then
        listFail("hooking the list of saved servers", err, trace)
    end
end
