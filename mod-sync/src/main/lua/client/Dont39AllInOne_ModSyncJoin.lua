-- Dont39 All-in-One: mod sync on the Connect screen. While a join waits for the server's version of its Workshop
-- items, the screen's status line shows what it does, and a dialog asks before downloading from a server that is not
-- remembered or taking Java code from it. After a join stopped for a restart, the screen offers to restart the game,
-- where the Java side can, and to quit it. The Java side holds the join; Dont39AllInOne_getModSyncJoin's keys are
-- listed in JoinStatus.kt.

require "ISUI/ISButton"
require "ISUI/ISTickBox"

local Common = require "Dont39AllInOne_Common"
local Shared = require "Dont39AllInOne_ModSyncShared"

local PREFIX = "[Dont39AllInOne] mod sync join: "
local KEY = Shared.KEY
local POLL_MS = 250 -- the status line follows the download a few times a second
local PAD = Shared.PAD
local WIDTH = 560
local KB = 1024
local MB = 1024 * 1024

local lastPoll = 0
local dialog = nil
local asked = false -- this confirm was shown; it is not shown again before the next one
local quit = nil -- the Quit button, on the Connect screen it was added to
local restart = nil -- the Restart button, likewise
local dismissed = nil -- the restart stop whose screen the player left; its buttons are not shown again
local buttonsFor = nil -- the restart stop the buttons are shown for
local restarted = nil -- the restart stop whose Restart was clicked; it stays disabled for that stop
local screenFor = nil -- "join", or the restart stop's number, once the Connect screen was there for it

-- as text: the Java side's numbers are Lua doubles
local function count(n)
    return tostring(math.floor(n))
end

-- one decimal, without relying on string.format's number support
local function tenths(value, unit)
    local t = math.floor(value * 10 / unit + 0.5)
    return tostring(math.floor(t / 10)) .. "." .. tostring(t % 10)
end

-- most syncs are a few changed Lua files, which "0.0 MB" would hide
local function size(bytes)
    if bytes < KB then return count(bytes) .. " B" end
    if bytes < MB then return tenths(bytes, KB) .. " KB" end
    return tenths(bytes, MB) .. " MB"
end

local function closeDialog()
    if dialog then
        local d = dialog
        dialog = nil
        d:setVisible(false)
        d:removeFromUIManager()
    end
end

local function hideButtons()
    if quit then quit:setVisible(false) end
    if restart then restart:setVisible(false) end
end

local guard = Common.guard(PREFIX, function()
    pcall(closeDialog)
    pcall(hideButtons)
    -- also outside a confirm: a later one would wait for an answer this file can no longer give
    if Dont39AllInOne_answerModSyncJoin then
        pcall(Dont39AllInOne_answerModSyncJoin, "failed")
    end
end)
local fail = guard.fail

local function answer(choice)
    closeDialog()
    Dont39AllInOne_answerModSyncJoin(choice)
end

local function onDownload(d)
    local remember = d.tick:isSelected(1)
    local ok, err, trace = pcall(answer, remember and "remember" or "accept")
    if not ok then fail("taking the answer", err, trace) end
end

local function onCancel()
    local ok, err, trace = pcall(answer, "decline")
    if not ok then fail("taking the answer", err, trace) end
end

-- the "don't ask again" choice, between the text and the buttons
local function addTick(d, y, height)
    local tick = ISTickBox:new(PAD, y, d.width - 2 * PAD, height, "", nil, nil)
    tick:initialise()
    tick:instantiate()
    d:addChild(tick)
    tick:addOption(getText(KEY .. "DontAsk"))
    d.tick = tick
    return tick:getBottom()
end

local function confirmText(status)
    local width = WIDTH - 2 * PAD
    local function text(s) return Common.breakable(Common.plain(s), UIFont.NewSmall, width) end
    local lines = {
        " <H2> " .. Common.breakable(Common.plain(getText(KEY .. "ConfirmTitle")), UIFont.Medium, width),
        " <TEXT> " .. text(getText(KEY .. "ConfirmText", status.server)),
        "",
    }
    for _, item in ipairs(status.items) do
        if item.files > 0 then
            lines[#lines + 1] = text(getText(KEY .. "ConfirmItem", item.name, size(item.bytes), count(item.files)))
        else
            lines[#lines + 1] = text(getText(KEY .. "ConfirmItemNoDownload", item.name))
        end
        if item.java then
            local java = Common.breakable(Common.plain(getText(KEY .. "ConfirmJava")), UIFont.NewSmall, width - PAD)
            lines[#lines + 1] = " <INDENT:" .. PAD .. "> " .. java .. " <INDENT:0> "
        end
    end
    lines[#lines + 1] = ""
    lines[#lines + 1] = text(getText(KEY .. "ConfirmTotal", size(status.bytes), count(status.files)))
    return table.concat(lines, " <LINE> ")
end

local function openDialog(status)
    if type(status.items) ~= "table" or type(status.server) ~= "string" then
        error("the join asks without its items or server")
    end
    dialog = Shared.Dialog:new(WIDTH, confirmText(status), {
        { title = getText(KEY .. "Download"), onClick = onDownload, accept = true },
        { title = getText(KEY .. "Cancel"), onClick = onCancel },
    }, addTick)
    dialog:open()
end

-- the Connect screen's line under the server name, which vanilla also writes the join's states to
local function setLine(text)
    local screen = ConnectToServer and ConnectToServer.instance
    if not screen or not screen:getIsVisible() then return end
    if not screen.connectLabel then error("the Connect screen has no status line (connectLabel)") end
    screen.connectLabel.name = text
end

local function onQuit()
    local ok, err, trace = pcall(function() getCore():quitToDesktop() end)
    if not ok then fail("quitting the game", err, trace) end
end

local function onRestart()
    local ok, err, trace = pcall(function()
        -- for the rest of the stop: a click while the game quits would start a second helper process
        restarted = buttonsFor
        restart:setEnable(false)
        local why = Dont39AllInOne_restartModSyncGame()
        if why then setLine(why) end
    end)
    if not ok then fail("restarting the game", err, trace) end
end

-- [button]: the one shown before, which is made again for another screen
local function addButton(button, screen, back, title, onClick, accept)
    if button and button.parent == screen then return button end
    button = ISButton:new(0, 0, 100, back:getHeight(), title, nil, onClick)
    button:initialise()
    button:instantiate()
    -- before any setEnable, which keeps these colours for the enabled state
    if accept then button:enableAcceptColor() end
    button:setAnchorLeft(true)
    button:setAnchorRight(false)
    button:setAnchorTop(false)
    button:setAnchorBottom(true)
    button:setWidthToTitle(100)
    screen:addChild(button)
    return button
end

-- next to the screen's Back button, which vanilla moves on resize
local function placeButtons(screen, status)
    local back = screen.backBtn
    if not back then error("the Connect screen has no Back button (backBtn)") end
    local x = back:getRight() + PAD
    if type(status.relaunch) ~= "boolean" then error("the restart stop does not say whether the game can restart") end
    if status.relaunch then
        restart = addButton(restart, screen, back, getText(KEY .. "RestartGame"), onRestart, true)
        local enable = restarted ~= status.stop
        if restart.enable ~= enable then restart:setEnable(enable) end
        restart:setX(x)
        restart:setY(back:getY())
        restart:setVisible(true)
        x = restart:getRight() + PAD
    elseif restart then
        restart:setVisible(false)
    end
    quit = addButton(quit, screen, back, getText(KEY .. "QuitGame"), onQuit)
    quit:setX(x)
    quit:setY(back:getY())
    quit:setVisible(true)
end

-- [status]: the restart stop the Java side reports, nil when there is none
local function updateButtons(status)
    local screen = ConnectToServer and ConnectToServer.instance
    local stop = status and status.stop
    local shown = quit ~= nil and quit:getIsVisible()
    if stop and stop ~= dismissed and screen and screen:getIsVisible() and not screen.connecting then
        buttonsFor = stop
        placeButtons(screen, status)
        return
    end
    -- Back or a new connect left the stop's screen
    if shown and stop then dismissed = stop end
    hideButtons()
end

-- The join's Workshop step hides the Connect screen behind ServerWorkshopItemScreen and, once the items are in, only
-- hides that one again: vanilla goes on into the game, while this feature holds the join with no screen, and the
-- screen's OnConnectFailed shows nothing while it is hidden. Shown again once per join, and once per restart stop,
-- so that Back still leaves it.
local function restoreScreen(status)
    if not status then
        screenFor = nil
        return
    end
    local key = status.state == "restart" and status.stop or "join"
    if screenFor == key then return end
    local screen = ConnectToServer and ConnectToServer.instance
    if not screen then return end
    if not screen:getIsVisible() then
        local workshop = ServerWorkshopItemScreen and ServerWorkshopItemScreen.instance
        if workshop and workshop:getIsVisible() then return end
        screen:setVisible(true, JoypadState.getMainMenuJoypad())
        -- the stop's reason was dropped while the screen was hidden
        if status.state == "restart" then
            if type(status.reason) ~= "string" then error("the restart stop has no reason") end
            screen:OnConnectFailed(status.reason)
        end
        Common.log(PREFIX, "showed the Connect screen again, which the Workshop step had left hidden")
    end
    screenFor = key
end

local function update(status)
    local state = status and status.state
    if state ~= "confirm" then
        closeDialog()
        asked = false
    end
    updateButtons(state == "restart" and status or nil)
    if not status then return end
    if state == "preparing" then
        setLine(getText(KEY .. "Preparing"))
    elseif state == "confirm" then
        setLine(getText(KEY .. "Waiting"))
        if not asked then
            asked = true
            openDialog(status)
        end
    elseif state == "download" then
        setLine(getText(KEY .. "Progress", count(status.filesDone), count(status.files), size(status.bytesDone),
            size(status.bytes)))
    elseif state ~= "restart" then -- the stop's reason is on the status line already
        error("unknown join state " .. tostring(state))
    end
end

local function onTick()
    if guard.disabled then return end
    -- absent when ZombieBuddy did not load this mod's Java code: then no join waits for it
    if not Dont39AllInOne_getModSyncJoin or not Dont39AllInOne_answerModSyncJoin then return end
    local ok, err, trace = pcall(function()
        -- every frame: a stop in the next frame needs the screen for its reason
        local status = Dont39AllInOne_getModSyncJoin()
        restoreScreen(status)
        local now = getTimestampMs()
        if now - lastPoll < POLL_MS then return end
        lastPoll = now
        update(status)
    end)
    if not ok then fail("showing the join's state", err, trace) end
end

-- fires on every frame the join holds: MainScreenState.update skips it only on the frame the join ends
Events.OnFETick.Add(onTick)
