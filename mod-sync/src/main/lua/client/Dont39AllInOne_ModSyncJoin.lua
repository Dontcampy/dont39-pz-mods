-- Dont39 All-in-One: mod sync on the Connect screen. While a join waits for the server's version of its Workshop
-- items, the screen's status line shows what it does, and a dialog asks before downloading from a server that is not
-- remembered. The Java side holds the join; Dont39AllInOne_getModSyncJoin's keys are listed in JoinStatus.kt.

require "ISUI/ISPanel"
require "ISUI/ISButton"
require "ISUI/ISRichTextPanel"
require "ISUI/ISTickBox"

local PREFIX = "[Dont39AllInOne] mod sync join: "
local KEY = "UI_Dont39AllInOne_ModSync_"
local POLL_MS = 250 -- the status line follows the download a few times a second
local PAD = 10
local WIDTH = 560
local KB = 1024
local MB = 1024 * 1024

local disabled = false -- after an error here; final for the session
local lastPoll = 0
local dialog = nil
local asked = false -- this confirm was shown; it is not shown again before the next one

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

-- the rich text panel breaks lines only at spaces, and Chinese text has none: split longer runs to the width
local function breakable(text, font, width)
    local tm = getTextManager()
    local words = {}
    for word in string.gmatch(text, "%S+") do
        if tm:MeasureStringX(font, word) <= width then
            words[#words + 1] = word
        else
            local piece = ""
            for i = 1, string.len(word) do
                local c = string.sub(word, i, i)
                if piece ~= "" and tm:MeasureStringX(font, piece .. c) > width then
                    words[#words + 1] = piece
                    piece = c
                else
                    piece = piece .. c
                end
            end
            words[#words + 1] = piece
        end
    end
    return table.concat(words, " ")
end

-- the rich text panel reads " <...> " as a tag
local function plain(text)
    return (string.gsub(string.gsub(tostring(text), "<", "("), ">", ")"))
end

local function closeDialog()
    if dialog then
        local d = dialog
        dialog = nil
        d:setVisible(false)
        d:removeFromUIManager()
    end
end

-- [trace]: the Lua stack where the error was raised (the game's pcall returns it third).
local function fail(what, err, trace)
    if disabled then return end
    disabled = true
    pcall(function()
        DebugType.Mod:error(PREFIX .. "disabled after an error while " .. what .. ": " .. tostring(err) .. "\n"
            .. tostring(trace))
    end)
    pcall(closeDialog)
    -- also outside a confirm: a later one would wait for an answer this file can no longer give
    if Dont39AllInOne_answerModSyncJoin then
        pcall(Dont39AllInOne_answerModSyncJoin, "failed")
    end
end

local function answer(choice)
    closeDialog()
    Dont39AllInOne_answerModSyncJoin(choice)
end

local Dialog = ISPanel:derive("Dont39AllInOne_ModSyncDialog")

function Dialog:createChildren()
    ISPanel.createChildren(self)
    local font = UIFont.Small
    local buttonHeight = math.max(25, getTextManager():getFontHeight(font) + 6)

    local text = ISRichTextPanel:new(PAD, PAD, self.width - 2 * PAD, 10)
    text:initialise()
    text:instantiate()
    text:noBackground()
    text:setMargins(0, 0, 0, 0)
    self:addChild(text)
    text.text = self.body
    text:paginate()

    local tick = ISTickBox:new(PAD, text:getBottom() + PAD, self.width - 2 * PAD, buttonHeight, "", nil, nil)
    tick:initialise()
    tick:instantiate()
    self:addChild(tick)
    tick:addOption(getText(KEY .. "DontAsk"))
    self.tick = tick

    local y = tick:getBottom() + PAD * 2
    local buttonWidth = (self.width - 3 * PAD) / 2
    local download = ISButton:new(PAD, y, buttonWidth, buttonHeight, getText(KEY .. "Download"), self, Dialog.onDownload)
    download:initialise()
    download:instantiate()
    download:enableAcceptColor()
    self:addChild(download)
    local cancel = ISButton:new(PAD * 2 + buttonWidth, y, buttonWidth, buttonHeight, getText(KEY .. "Cancel"), self,
        Dialog.onCancel)
    cancel:initialise()
    cancel:instantiate()
    cancel:enableCancelColor()
    self:addChild(cancel)

    self:setHeight(y + buttonHeight + PAD)
    self:setY((getCore():getScreenHeight() - self:getHeight()) / 2)
end

function Dialog:onDownload()
    local remember = self.tick:isSelected(1)
    local ok, err, trace = pcall(answer, remember and "remember" or "accept")
    if not ok then fail("taking the answer", err, trace) end
end

function Dialog:onCancel()
    local ok, err, trace = pcall(answer, "decline")
    if not ok then fail("taking the answer", err, trace) end
end

function Dialog:new(body)
    local x = (getCore():getScreenWidth() - WIDTH) / 2
    local o = ISPanel:new(x, 0, WIDTH, 100)
    setmetatable(o, self)
    self.__index = self
    o.body = body
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0.9 }
    o.borderColor = { r = 0.4, g = 0.4, b = 0.4, a = 1 }
    o.moveWithMouse = true
    return o
end

local function confirmText(status)
    local width = WIDTH - 2 * PAD
    local function text(s) return breakable(plain(s), UIFont.NewSmall, width) end
    local lines = {
        " <H2> " .. breakable(plain(getText(KEY .. "ConfirmTitle")), UIFont.Medium, width),
        " <TEXT> " .. text(getText(KEY .. "ConfirmText", status.server)),
        "",
    }
    for _, item in ipairs(status.items) do
        lines[#lines + 1] = text(getText(KEY .. "ConfirmItem", item.name, size(item.bytes), count(item.files)))
    end
    lines[#lines + 1] = ""
    lines[#lines + 1] = text(getText(KEY .. "ConfirmTotal", size(status.bytes), count(status.files)))
    return table.concat(lines, " <LINE> ")
end

local function openDialog(status)
    if type(status.items) ~= "table" or type(status.server) ~= "string" then
        error("the join asks without its items or server")
    end
    dialog = Dialog:new(confirmText(status))
    dialog:initialise()
    dialog:addToUIManager()
    dialog:setAlwaysOnTop(true)
end

-- the Connect screen's line under the server name, which vanilla also writes the join's states to
local function setLine(text)
    local screen = ConnectToServer and ConnectToServer.instance
    if not screen or not screen:getIsVisible() then return end
    if not screen.connectLabel then error("the Connect screen has no status line (connectLabel)") end
    screen.connectLabel.name = text
end

local function update()
    local status = Dont39AllInOne_getModSyncJoin()
    local state = status and status.state
    if state ~= "confirm" then
        closeDialog()
        asked = false
    end
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
    else
        error("unknown join state " .. tostring(state))
    end
end

local function onTick()
    if disabled then return end
    -- absent when ZombieBuddy did not load this mod's Java code: then no join waits for it
    if not Dont39AllInOne_getModSyncJoin or not Dont39AllInOne_answerModSyncJoin then return end
    local now = getTimestampMs()
    if now - lastPoll < POLL_MS then return end
    lastPoll = now
    local ok, err, trace = pcall(update)
    if not ok then fail("showing the join's state", err, trace) end
end

-- fires on every frame the join holds: MainScreenState.update skips it only on the frame the join ends
Events.OnFETick.Add(onTick)
