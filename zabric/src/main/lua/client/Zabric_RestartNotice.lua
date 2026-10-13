-- Zabric: tells the player to restart once after the mod registered itself with ZombieBuddy.

require "ISUI/ISModalDialog"

local PREFIX = "[Zabric] restart notice: "
local TEXT = "Zabric has registered itself with ZombieBuddy.\n"
    .. "Restart the game once so that the Dont39 All-in-One fixes that need it can switch on."

-- Never throws.
local function log(message)
    pcall(function() DebugType.Mod:println(PREFIX .. message) end)
end

-- absent when ZombieBuddy did not load the mod's Java code
local needed = Zabric_restartNeeded and Zabric_restartNeeded()
if Zabric_restartNeeded == nil then
    log("the mod's Java code is not loaded")
else
    log(needed and "pending" or "not needed")
end

local function show()
    Events.OnMainMenuEnter.Remove(show)
    local w, h = 450, 150
    local core = getCore()
    local modal = ISModalDialog:new((core:getScreenWidth() - w) / 2, (core:getScreenHeight() - h) / 2, w, h, TEXT,
        false, nil, nil)
    modal:initialise()
    modal:setAlwaysOnTop(true)
    modal:addToUIManager()
end

if needed then
    Events.OnMainMenuEnter.Add(show)
end
