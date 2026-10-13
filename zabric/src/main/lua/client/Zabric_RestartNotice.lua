-- Zabric: tells the player which mods take effect only after a restart.

require "ISUI/ISModalDialog"

local PREFIX = "[Zabric] restart notice: "
local TEXT = "Restart the game once so that these mods take effect:"

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
    local w, h = 450, 130 + 20 * #list
    local core = getCore()
    local modal = ISModalDialog:new((core:getScreenWidth() - w) / 2, (core:getScreenHeight() - h) / 2, w, h, text,
        false, nil, nil)
    modal:initialise()
    modal:setAlwaysOnTop(true)
    modal:addToUIManager()
end

if mods ~= nil then
    Events.OnMainMenuEnter.Add(show)
end
