-- Zabric: tells the player which mods take effect only after a restart, and when the game could not restart itself.

require "ISUI/ISModalDialog"

local PREFIX = "[Zabric] restart notice: "
local TEXT = "Restart the game once so that these mods take effect:"
local RELAUNCH_TEXT = "The game quit to restart itself last time, but could not start again:"
local WIDTH = 450
local WRAP_WIDTH = 600

-- Never throws.
local function log(message)
    pcall(function() DebugType.Mod:println(PREFIX .. message) end)
end

-- absent when ZombieBuddy did not load the mod's Java code
local function restartMods()
    return Zabric_restartMods and Zabric_restartMods()
end

local mods = restartMods()
if mods == nil then
    log("the mod's Java code is not loaded")
elseif #mods == 0 then
    log("not needed")
else
    log("pending: " .. table.concat(mods, ", "))
end

local function dialog(text, lines)
    local h = 110 + 20 * lines
    local core = getCore()
    local modal = ISModalDialog:new((core:getScreenWidth() - WIDTH) / 2, (core:getScreenHeight() - h) / 2, WIDTH, h,
        text, false, nil, nil)
    modal:initialise()
    modal:setAlwaysOnTop(true)
    modal:addToUIManager()
end

-- how many were listed when the dialog last showed; mods can join later in the session
local shown = 0

local function show()
    local list = restartMods()
    if #list <= shown then
        return
    end
    shown = #list
    local text = TEXT
    for _, name in ipairs(list) do
        text = text .. "\n" .. name
    end
    dialog(text, 1 + #list)
end

-- ISModalDialog widens itself to its longest line, so a long reason is broken at spaces
local function wrap(text)
    local tm = getTextManager()
    local lines = {}
    for paragraph in string.gmatch(text .. "\n", "([^\n]*)\n") do
        local line = ""
        for word in string.gmatch(paragraph, "%S+") do
            local longer = line == "" and word or line .. " " .. word
            if line ~= "" and tm:MeasureStringX(UIFont.Small, longer) > WRAP_WIDTH then
                table.insert(lines, line)
                line = word
            else
                line = longer
            end
        end
        table.insert(lines, line)
    end
    return lines
end

-- only a launch that quit to restart writes the record, so one look per session is enough
local relaunchChecked = false

local function showRelaunchFailure()
    if relaunchChecked then
        return
    end
    relaunchChecked = true
    local ok, reason = pcall(Zabric_relaunchFailure)
    if not ok then
        log("cannot read whether the last restart failed: " .. tostring(reason))
        return
    end
    if reason == nil then
        return
    end
    log("the last restart failed: " .. reason)
    local lines = wrap(reason)
    dialog(RELAUNCH_TEXT .. "\n" .. table.concat(lines, "\n"), 1 + #lines)
end

if mods ~= nil then
    Events.OnMainMenuEnter.Add(show)
    Events.OnMainMenuEnter.Add(showRelaunchFailure)
end
