-- Dont39 All-in-One: mod sync's rejoin offer. After a join stopped for a restart to use a server's Java code, the main
-- menu of a later launch offers to join that server again, through the vanilla join with its saved entry and account.
-- The Java side keeps the server; Dont39AllInOne_getModSyncRejoin's keys are listed in RejoinOffer.kt.

local Common = require "Dont39AllInOne_Common"
local Shared = require "Dont39AllInOne_ModSyncShared"

local PREFIX = "[Dont39AllInOne] mod sync rejoin: "
local KEY = Shared.KEY
local PAD = Shared.PAD
local WIDTH = 460

local dialog = nil
local waiting = false -- the Java side still decides whether to offer; asked again every frame

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

-- closed first: the answer may open the server list
local function answered(onClick)
    return function()
        closeDialog()
        local ok, err, trace = pcall(onClick)
        if not ok then fail("taking the answer", err, trace) end
    end
end

-- [buttons]: { title, onClick, accept } each, side by side
local function open(title, text, buttons)
    local width = WIDTH - 2 * PAD
    local head = " <TEXT> "
    if title then
        head = " <H2> " .. Common.breakable(Common.plain(title), UIFont.Medium, width) .. " <LINE> <TEXT> "
    end
    local body = head .. Common.lines(text, width)
    local row = {}
    for i, b in ipairs(buttons) do
        row[i] = { title = b.title, onClick = answered(b.onClick), accept = b.accept }
    end
    dialog = Shared.Dialog:new(WIDTH, body, row)
    dialog:open()
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
    waiting = false
    if guard.disabled then return end
    -- absent when ZombieBuddy did not load this mod's Java code
    if not Dont39AllInOne_getModSyncRejoin or not Dont39AllInOne_dropModSyncRejoin then return end
    if MainScreen.instance and MainScreen.instance.inGame then return end
    local o = Dont39AllInOne_getModSyncRejoin()
    if not o then return end
    -- a +connect host name is still resolved, off the game thread
    if o.pending then
        waiting = true
        return
    end
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

local function tryOffer()
    local ok, err, trace = pcall(offer)
    if not ok then fail("offering to rejoin", err, trace) end
end

Events.OnMainMenuEnter.Add(tryOffer)

Events.OnFETick.Add(function()
    if waiting then tryOffer() end
end)
