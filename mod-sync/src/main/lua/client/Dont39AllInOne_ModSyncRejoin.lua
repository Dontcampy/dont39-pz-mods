-- Dont39 All-in-One: mod sync's rejoin offer. After a join stopped for a restart to use a server's Java code, the main
-- menu of a later launch offers to join that server again, through the vanilla join with its saved entry and account.
-- The Java side keeps the server; Dont39AllInOne_getModSyncRejoin's keys are listed in RejoinOffer.kt.

require "ISUI/ISPanel"
require "ISUI/ISButton"
require "ISUI/ISRichTextPanel"

local Common = require "Dont39AllInOne_Common"
local Shared = require "Dont39AllInOne_ModSyncShared"

local PREFIX = "[Dont39AllInOne] mod sync rejoin: "
local KEY = Shared.KEY
local PAD = Shared.PAD
local WIDTH = 460

local dialog = nil

local function closeDialog()
    if dialog then
        local d = dialog
        dialog = nil
        d:setVisible(false)
        d:removeFromUIManager()
    end
end

local guard = Common.guard(PREFIX, function()
    pcall(closeDialog)
end)
local fail = guard.fail

local Dialog = ISPanel:derive("Dont39AllInOne_ModSyncRejoinDialog")

-- [buttons]: { title, onClick, accept } each, side by side
function Dialog:new(body, buttons)
    local x = (getCore():getScreenWidth() - WIDTH) / 2
    local o = ISPanel:new(x, 0, WIDTH, 100)
    setmetatable(o, self)
    self.__index = self
    o.body = body
    o.buttons = buttons
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0.9 }
    o.borderColor = { r = 0.4, g = 0.4, b = 0.4, a = 1 }
    o.moveWithMouse = true
    return o
end

function Dialog:createChildren()
    ISPanel.createChildren(self)
    local buttonHeight = math.max(25, getTextManager():getFontHeight(UIFont.Small) + 6)

    local text = ISRichTextPanel:new(PAD, PAD, self.width - 2 * PAD, 10)
    text:initialise()
    text:instantiate()
    text:noBackground()
    text:setMargins(0, 0, 0, 0)
    self:addChild(text)
    text.text = self.body
    text:paginate()

    local y = text:getBottom() + PAD * 2
    local n = #self.buttons
    local buttonWidth = (self.width - (n + 1) * PAD) / n
    for i, b in ipairs(self.buttons) do
        local button = ISButton:new(PAD + (i - 1) * (buttonWidth + PAD), y, buttonWidth, buttonHeight, b.title, self,
            Dialog.onButton)
        button.answer = b
        button:initialise()
        button:instantiate()
        if b.accept then button:enableAcceptColor() else button:enableCancelColor() end
        self:addChild(button)
    end

    self:setHeight(y + buttonHeight + PAD)
    self:setY((getCore():getScreenHeight() - self:getHeight()) / 2)
end

function Dialog:onButton(button)
    closeDialog()
    local ok, err, trace = pcall(button.answer.onClick)
    if not ok then fail("taking the answer", err, trace) end
end

local function open(title, text, buttons)
    local width = WIDTH - 2 * PAD
    local head = " <TEXT> "
    if title then
        head = " <H2> " .. Shared.breakable(Shared.plain(title), UIFont.Medium, width) .. " <LINE> <TEXT> "
    end
    local body = head .. Shared.lines(text, width)
    dialog = Dialog:new(body, buttons)
    dialog:initialise()
    dialog:addToUIManager()
    dialog:setAlwaysOnTop(true)
end

local function trim(s)
    return string.match(s or "", "^%s*(.-)%s*$")
end

-- the saved server entry and its account the join used, or nil; the join kept the host and user as typed, trimmed
local function savedEntry(offer)
    for _, server in ipairs(getServerList()) do
        if trim(server:getIp()) == offer.host and server:getPort() == offer.port then
            local accounts = server:getAccounts()
            for i = 0, accounts:size() - 1 do
                local account = accounts:get(i)
                if trim(account:getUserName()) == offer.user then return server, account end
            end
        end
    end
    return nil
end

-- as the main menu's Join opens the server list, so that the Connect screen's Back returns to it
local function rejoin(server, account)
    Dont39AllInOne_dropModSyncRejoin("rejoin")
    local main = MainScreen.instance
    if not main or not main.multiplayer or not main.bottomPanel then
        error("the main menu has no server list (MainScreen.instance.multiplayer)")
    end
    main.bottomPanel:setVisible(false)
    main.multiplayer:setVisible(true, JoypadState.getMainMenuJoypad())
    main.multiplayer:connectToServer(server, account)
end

local function offer()
    if guard.disabled then return end
    -- absent when ZombieBuddy did not load this mod's Java code
    if not Dont39AllInOne_getModSyncRejoin or not Dont39AllInOne_dropModSyncRejoin then return end
    if MainScreen.instance and MainScreen.instance.inGame then return end
    local o = Dont39AllInOne_getModSyncRejoin()
    if not o then return end
    if type(o.host) ~= "string" or type(o.port) ~= "number" or type(o.user) ~= "string" or type(o.name) ~= "string" then
        error("the server to rejoin lacks its host, port, user or name")
    end
    local server, account = savedEntry(o)
    if not server then
        Common.log(PREFIX, o.host .. ":" .. tostring(math.floor(o.port)) .. " with " .. o.user
            .. " is not in the saved servers")
        Dont39AllInOne_dropModSyncRejoin("no saved entry")
        open(nil, getText(KEY .. "RejoinNoEntry", o.name), { { title = getText("UI_Ok"), onClick = function() end,
            accept = true } })
        return
    end
    open(getText(KEY .. "RejoinTitle", o.name), getText(KEY .. "RejoinText"), {
        { title = getText(KEY .. "Rejoin"), onClick = function() rejoin(server, account) end, accept = true },
        { title = getText(KEY .. "NotNow"), onClick = function() Dont39AllInOne_dropModSyncRejoin("not now") end },
    })
end

Events.OnMainMenuEnter.Add(function()
    local ok, err, trace = pcall(offer)
    if not ok then fail("offering to rejoin", err, trace) end
end)
