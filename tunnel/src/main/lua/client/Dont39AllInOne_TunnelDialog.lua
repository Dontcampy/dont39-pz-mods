-- Dont39 All-in-One: tunnel dialog. A "Tunnel" choice (Off / TCP / KCP) and port in the add/edit server dialog, which
-- the Java side stores per server address and reads when the game connects.

require "ISUI/ISMPEditServer"
require "ISUI/ISLabel"
require "ISUI/ISTextEntryBox"
require "ISUI/ISComboBox"

local Common = require "Dont39AllInOne_Common"
local Shared = require "Dont39AllInOne_TunnelShared"

local PREFIX = "[Dont39AllInOne] tunnel dialog: "
local GAP = Shared.GAP
local DEFAULT_PORT = 16262 -- what vanilla saves when the port field is not a number
local STATE = "Dont39AllInOne_Tunnel" -- this file's state on a dialog instance
local VALID = { r = 0, g = 0, b = 0, a = 0.5 } -- ISMPEditServer:prerender's colors
local INVALID = { r = 0.5, g = 0, b = 0, a = 0.5 }
local MODES = { "off", "tcp", "kcp" }

if not ISMPEditServer or not ISMPEditServer.initialise or not ISMPEditServer.onClick then
    error(PREFIX .. "the game's add/edit server dialog (ISMPEditServer) was not found")
end

local vanillaInitialise = ISMPEditServer.initialise
local vanillaOnClick = ISMPEditServer.onClick
local guard = Common.guard(PREFIX)
local fail = guard.fail
local reportedNoJava = false

-- absent when ZombieBuddy did not load this mod's Java code
local function javaLoaded()
    return Dont39AllInOne_getTunnelMode ~= nil and Dont39AllInOne_getTunnelTcpPort ~= nil
        and Dont39AllInOne_getTunnelKcpPort ~= nil and Dont39AllInOne_setTunnel ~= nil
end

-- The port as text, "" when empty, nil when it is no port.
local function portValue(text)
    text = Shared.trim(text)
    if text == "" then return "" end
    local number = tonumber(text)
    if not number or number ~= math.floor(number) or number < 1 or number > Shared.MAX_PORT then return nil end
    return Shared.portText(number)
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
    if not Shared.MODE_TEXT[mode] then error("the tunnel choice has no mode: " .. tostring(mode)) end
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
        local key = "UI_Dont39AllInOne_Tunnel_" .. Shared.MODE_TEXT[mode]
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
        st.oldPort = Shared.portText(self.server:getPort())
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
        if not guard.disabled and key == Keyboard.KEY_TAB and st.mode ~= "off" then
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
        if guard.disabled then return end
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
    if guard.disabled then return end
    if not javaLoaded() then
        if not reportedNoJava then
            reportedNoJava = true
            Common.log(PREFIX, "no tunnel choice: the Java part of Dont39 All-in-One is not loaded")
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
            error("the " .. st.mode .. " port is not a number between 1 and " .. Shared.MAX_PORT)
        end
        ports[st.mode] = value
    end
    -- a new entry without a tunnel leaves one stored for the same address
    if not st.oldHost and st.mode == "off" and ports.tcp == "" and ports.kcp == "" then return nil end
    return {
        oldHost = st.oldHost,
        oldPort = st.oldPort,
        -- as vanilla saves the entry, which is what a join then connects to
        host = Shared.trim(self.serverAddress:getInternalText()),
        port = Shared.portText(tonumber(Shared.trim(self.serverPort:getInternalText())) or DEFAULT_PORT),
        mode = st.mode,
        tcp = ports.tcp,
        kcp = ports.kcp,
    }
end

local function store(save)
    Dont39AllInOne_setTunnel(save.oldHost, save.oldPort, save.host, save.port, save.mode, save.tcp, save.kcp)
end

function ISMPEditServer:onClick(button, ...)
    local st = self[STATE]
    local save = nil
    if st and not guard.disabled and button and button.internal == "SAVE" then
        local ok, result, trace = pcall(collect, self, st)
        if ok then
            save = result
        else
            fail("reading the tunnel choice", result, trace)
        end
    end

    vanillaOnClick(self, button, ...)

    if save and not guard.disabled then
        local ok, err, trace = pcall(store, save)
        if not ok then
            fail("saving the tunnel to Zomboid/Lua/" .. Shared.STORE, err, trace)
        end
        -- vanilla refilled the server list before the tunnel was stored
        Shared.storeChanged()
    end
end
