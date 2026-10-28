-- Dont39 All-in-One: appearance-override on a multiplayer client: takes every player's overrides from the server, and
-- asks for all of them once in the game.

local Appearance = require "Dont39AllInOne_AppearanceOverrideShared"

-- The server drops a client command until it has bound the player to the connection, which can be after the game
-- started here; so the request repeats until the server's answer ends.
local ASK_EVERY_MS = 2000
local ASK_TRIES = 30

-- local player -> { next = ms, tries = n }, while its answer has not ended
local asking = {}
local inGame = false

local function guarded(what, fn, ...)
    if isClient() then Appearance.run(what, fn, ...) end
end

local function startAsking(player)
    asking[player] = { next = 0, tries = 0 }
end

local function askAgain()
    local now = getTimestampMs()
    for player, state in pairs(asking) do
        if now >= state.next then
            if state.tries >= ASK_TRIES then
                asking = {}
                error("the server did not answer " .. ASK_TRIES .. " requests for every player's overrides")
            end
            state.tries = state.tries + 1
            state.next = now + ASK_EVERY_MS
            sendClientCommand(player, Appearance.MODULE, "snapshot", {})
        end
    end
end

-- sets that arrived during the load are in the answer, with their revisions
Events.OnGameStart.Add(function()
    guarded("asking the server for every player's overrides", function()
        Dont39AllInOne_clearAppearanceOverrideSets()
        inGame = true
        startAsking(getPlayer())
    end)
end)

-- a split-screen player who joined later
Events.OnCreatePlayer.Add(function(_, player)
    if inGame then guarded("asking the server for a new local player's overrides", startAsking, player) end
end)

Events.OnTick.Add(function()
    for _ in pairs(asking) do
        -- a sync stopped by another error asks no more
        if Appearance.problem() then
            asking = {}
            return
        end
        guarded("asking the server for every player's overrides", askAgain)
        return
    end
end)

Events.OnServerCommand.Add(function(module, command, args)
    if module ~= Appearance.MODULE then return end
    if command == "set" then
        guarded("taking a player's overrides from the server", Dont39AllInOne_putAppearanceOverrideSet, args.user,
            args.revision, args.entries)
    elseif command == "snapshotDone" then
        -- one answer serves every local player: it has all players' sets
        asking = {}
    end
end)

local problem = Appearance.problem()
Appearance.log("client part " .. (problem and ("idle: " .. problem) or "ready"))
