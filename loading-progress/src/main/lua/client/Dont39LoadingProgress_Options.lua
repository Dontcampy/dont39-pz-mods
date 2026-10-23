-- Loading Progress: its two switches on the mod options page. The Java side reads the same file once per launch,
-- before any Lua runs, so a change applies from the next launch of the game.

local Switches = require "Dont39LoadingProgress_Switches"

Switches.page({
    id = "Dont39LoadingProgress",
    name = "Loading Progress",
    -- in the cache folder's Lua/, where progress/Settings.java reads it
    file = "Dont39LoadingProgress.ini",
    -- progress/Settings.java's Switch, in its order (SettingsTest checks)
    switches = {
        {
            key = "display",
            text = "Display",
            description = "show the progress bar and the log of what loads",
        },
        {
            key = "report",
            text = "Report",
            description = "after each load, write a timing report to Lua/Dont39LoadingProgress/ in this folder",
        },
    },
})
