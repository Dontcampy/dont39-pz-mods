-- Sync Guard: tells the Java side which container the loot window shows, so it checks it with the server once
-- (R7). The game asks the server only for containers this client never explored, so an explored one that changed
-- unseen (a fire emptied it) is caught only here.

require "ISUI/ISInventoryPage"

local PREFIX = "[Dont39SyncGuard] "
local disabled = false -- after an error in this file; final for the session

-- [trace]: the Lua stack where the error was raised (the game's pcall returns it third).
local function fail(what, err, trace)
    if disabled then return end
    disabled = true
    pcall(function()
        DebugType.Mod:error(PREFIX .. "open checks off after an error while " .. what .. ": " .. tostring(err) .. "\n"
            .. tostring(trace))
    end)
end

-- the loot page's container, once per change; the player's own inventory is checked by the server's frames
local function report(page)
    if page.onCharacter or not Dont39SyncGuard_containerShown then return end
    local pane = page.inventoryPane
    local container = pane and pane.inventory
    if container == page.dont39SyncGuardShown then return end
    page.dont39SyncGuardShown = container
    if container then Dont39SyncGuard_containerShown(container) end
end

local function guarded(page)
    if disabled then return end
    local ok, err, trace = pcall(report, page)
    if not ok then fail("reporting the shown container", err, trace) end
end

if not ISInventoryPage or not ISInventoryPage.selectContainer or not Events.OnRefreshInventoryWindowContainers then
    pcall(function()
        DebugType.Mod:error(PREFIX .. "open checks off: the game's loot window (ISInventoryPage.selectContainer,"
            .. " OnRefreshInventoryWindowContainers) was not found")
    end)
    return
end

-- a click on a container
local vanilla = ISInventoryPage.selectContainer
function ISInventoryPage:selectContainer(button)
    -- outside pcall, so vanilla's errors surface as without this mod
    vanilla(self, button)
    guarded(self)
end

-- the window picked a container itself, e.g. as the player walked up to it
Events.OnRefreshInventoryWindowContainers.Add(function(page, stage)
    if stage == "end" then guarded(page) end
end)
