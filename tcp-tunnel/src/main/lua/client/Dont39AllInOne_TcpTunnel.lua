-- Dont39 All-in-One: tcp-tunnel. A "TCP port" field in the add/edit server dialog; the Java side stores it per server
-- address and reads it when the game connects.

require "ISUI/ISMPEditServer"
require "ISUI/ISLabel"
require "ISUI/ISTextEntryBox"

-- not "tcp-tunnel: ", which prefixes the Java side's status lines
local PREFIX = "[Dont39AllInOne] tcp-tunnel dialog: "
local GAP = 10 -- ISMPEditServer's UI_BORDER_SPACING
local MAX_PORT = 65535
local DEFAULT_PORT = 16262 -- what vanilla saves when the port field is not a number
local STATE = "Dont39AllInOne_TcpTunnel" -- this file's state on a dialog instance
local VALID = { r = 0, g = 0, b = 0, a = 0.5 } -- ISMPEditServer:prerender's colors
local INVALID = { r = 0.5, g = 0, b = 0, a = 0.5 }

-- Never throws.
local function log(message)
    pcall(function() DebugType.Mod:println(PREFIX .. message) end)
end

if not ISMPEditServer or not ISMPEditServer.initialise or not ISMPEditServer.onClick then
    error(PREFIX .. "the game's add/edit server dialog (ISMPEditServer) was not found")
end

local vanillaInitialise = ISMPEditServer.initialise
local vanillaOnClick = ISMPEditServer.onClick
local disabled = false -- after an error in this file; final for the session
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
    return Dont39AllInOne_getTcpTunnelPort ~= nil and Dont39AllInOne_setTcpTunnelPort ~= nil
end

-- as text: the Java side would get Lua numbers as doubles
local function portText(number)
    return tostring(math.floor(number))
end

-- The field's port as text, "" when empty, nil when it is no port.
local function tcpValue(st)
    local text = st.entry:getInternalText():trim()
    if text == "" then return "" end
    local number = tonumber(text)
    if not number or number ~= math.floor(number) or number < 0 or number > MAX_PORT then return nil end
    return portText(number)
end

local function validate(self, st)
    if tcpValue(st) then
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
    local labelText = getText("UI_Dont39AllInOne_TcpTunnel_Port")
    local x = name:getX()
    local y = port:getBottom() + GAP
    local offset = GAP + textManager:MeasureStringX(font, labelText)

    -- top-aligned like vanilla's port label
    local label = ISLabel:new(x, y, textManager:getFontHeight(font), labelText, 1, 1, 1, 1, font, true)
    label:initialise()
    label:instantiate()

    local entry = ISTextEntryBox:new("", x + offset, y, name:getWidth() - offset, port:getHeight())
    entry.font = font
    entry:initialise()
    entry:instantiate()
    entry:setOnlyNumbers(true)
    entry:setMasked(getCore():getOptionStreamerMode())
    entry:setTooltip(getText("UI_Dont39AllInOne_TcpTunnel_PortTooltip"))

    local st = { entry = entry }
    if self.server then
        st.oldHost = self.server:getIp()
        st.oldPort = portText(self.server:getPort())
        entry:setText(Dont39AllInOne_getTcpTunnelPort(st.oldHost, st.oldPort) or "")
    end

    self:addChild(label)
    self:addChild(entry)
    local shift = entry:getHeight() + GAP
    password:setY(password:getY() + shift)
    eye:setY(eye:getY() + shift)
    self:setHeight(self:getHeight() + shift)
    self:setY((getCore():getScreenHeight() - self:getHeight()) / 2)

    -- Tab: port, then this field, then the password
    local portKey = port.onOtherKey
    port.onOtherKey = function(box, key)
        if not disabled and key == Keyboard.KEY_TAB then
            local ok, err, trace = pcall(function()
                box:unfocus()
                entry:focus()
            end)
            if ok then return end
            fail("moving the focus to the TCP port", err, trace)
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
            fail("checking the TCP port", err, trace)
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
            log("no TCP port field: the Java part of Dont39 All-in-One is not loaded")
        end
        return
    end
    local ok, err, trace = pcall(attach, self)
    if not ok then
        fail("adding the TCP port field", err, trace)
    end
end

-- What to store, read before vanilla closes the dialog; nil when there is nothing to store.
local function collect(self, st)
    local tcp = tcpValue(st)
    if not tcp then
        error("the TCP port is not a number between 0 and " .. MAX_PORT)
    end
    -- a new entry without a TCP port leaves one stored for the same address
    if not st.oldHost and tcp == "" then return nil end
    return {
        oldHost = st.oldHost,
        oldPort = st.oldPort,
        -- as vanilla saves the entry, which is what a join then connects to
        host = self.serverAddress:getInternalText():trim(),
        port = portText(tonumber(self.serverPort:getInternalText():trim()) or DEFAULT_PORT),
        tcp = tcp,
    }
end

local function store(save)
    Dont39AllInOne_setTcpTunnelPort(save.oldHost, save.oldPort, save.host, save.port, save.tcp)
end

function ISMPEditServer:onClick(button, ...)
    local st = self[STATE]
    local save = nil
    if st and not disabled and button and button.internal == "SAVE" then
        local ok, result, trace = pcall(collect, self, st)
        if ok then
            save = result
        else
            fail("reading the TCP port", result, trace)
        end
    end

    vanillaOnClick(self, button, ...)

    if save and not disabled then
        local ok, err, trace = pcall(store, save)
        if not ok then
            fail("saving the TCP port to Zomboid/Lua/Dont39AllInOne_TcpTunnel.txt", err, trace)
        end
    end
end
