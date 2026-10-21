-- Dont39 All-in-One: mod sync's notice in game. When the session runs a mod of a server's Workshop item without the
-- server's Java code (the game did not load it, or another jar serves it), the player is told once in game. The Java
-- side decides; Dont39AllInOne_takeModSyncNotice gives its text once.

local Common = require "Dont39AllInOne_Common"
local Shared = require "Dont39AllInOne_ModSyncShared"

local PREFIX = "[Dont39AllInOne] mod sync notice: "
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

local function onOk()
    local ok, err, trace = pcall(closeDialog)
    if not ok then fail("closing the notice", err, trace) end
end

local function show()
    if guard.disabled or not isClient() then return end
    -- absent when ZombieBuddy did not load this mod's Java code
    if not Dont39AllInOne_takeModSyncNotice then return end
    local text = Dont39AllInOne_takeModSyncNotice()
    if not text then return end
    local width = WIDTH - 2 * PAD
    local body = " <H2> " .. Common.breakable(Common.plain(getText(KEY .. "NoticeTitle")), UIFont.Medium, width)
        .. " <LINE> <TEXT> " .. Common.lines(text, width)
    dialog = Shared.Dialog:new(WIDTH, body, { { title = getText("UI_Ok"), onClick = onOk, accept = true } })
    dialog:open()
end

-- the player exists once the game starts, also after a join
Events.OnGameStart.Add(function()
    local ok, err, trace = pcall(show)
    if not ok then fail("showing the notice", err, trace) end
end)
