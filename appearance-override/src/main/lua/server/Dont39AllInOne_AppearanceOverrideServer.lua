-- Dont39 All-in-One: appearance-override on the server, and in single player: sends a player's overrides to the
-- clients when the player's clothes change, and every player's to a client that joined.

-- multiplayer clients load server files too
if isClient() then return end

local Appearance = require "Dont39AllInOne_AppearanceOverrideShared"

local function guarded(what, fn, arg)
    -- a stopped sync was reported once
    if Appearance.problem() then return end
    local ok, err, trace = pcall(fn, arg)
    if not ok then Appearance.fail(what, err, trace) end
end

-- single player: the Java side's sets start again with the game, as the revisions here do
local function publishLocal()
    for i = 0, getNumActivePlayers() - 1 do
        local player = getSpecificPlayer(i)
        if player then Appearance.publish(player) end
    end
end

Appearance.startSession()
local problem = Appearance.problem()
if problem then
    Appearance.log("server part idle: " .. problem)
    return
end
if not isServer() then
    Dont39AllInOne_clearAppearanceOverrideSets()
end

-- also on the server when another player's packet changed the clothes
Events.OnClothingUpdated.Add(function(character)
    -- an animal is an IsoPlayer whose user name may be a player's
    if instanceof(character, "IsoPlayer") and not instanceof(character, "IsoAnimal") then
        guarded("sending a player's overrides after a clothing change", Appearance.publish, character)
    end
end)

-- The server fills a joined player's worn list from the client's packets without that event, so the worn items are
-- also checked once a second; publish sends only a set that changed.
local function publishOnline()
    local players = getOnlinePlayers()
    for i = 0, players:size() - 1 do
        Appearance.publish(players:get(i))
    end
end

if isServer() then
    local nextCheck = 0
    Events.OnTick.Add(function()
        local now = getTimestampMs()
        if now < nextCheck then return end
        nextCheck = now + 1000
        guarded("sending the online players' overrides", publishOnline)
    end)
end

Events.OnClientCommand.Add(function(module, command, player, args)
    if module == Appearance.MODULE and command == "snapshot" then
        guarded("sending every player's overrides to a client", Appearance.sendAll, player)
    end
end)

Events.OnGameStart.Add(function()
    if not isServer() then guarded("sending the local players' overrides", publishLocal) end
end)

Events.OnCreatePlayer.Add(function(_, player)
    if not isServer() then guarded("sending a local player's overrides", Appearance.publish, player) end
end)

Appearance.log("server part ready (" .. (isServer() and "server" or "single player") .. ")")
