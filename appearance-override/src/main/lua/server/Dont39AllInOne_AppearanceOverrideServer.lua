-- Dont39 All-in-One: appearance-override on the server, and in single player: sends a player's overrides to the
-- clients when the player's clothes change, and every player's to a client that joined.

-- multiplayer clients load server files too
if isClient() then return end

local Appearance = require "Dont39AllInOne_AppearanceOverrideShared"
local Common = require "Dont39AllInOne_CommonShared"

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
    if Common.isPlayer(character) then
        Appearance.run("sending a player's overrides after a clothing change", Appearance.publish, character)
    end
end)

-- The server fills a joined player's worn list from the client's packets without that event, so the worn items are
-- also checked once a second; publish sends only a set that changed.
if isServer() then
    Common.every(1000, function()
        Appearance.run("sending the online players' overrides", Appearance.publishOnline)
    end)
end

Events.OnClientCommand.Add(function(module, command, player, args)
    if module == Appearance.MODULE and command == "snapshot" then
        Appearance.run("sending every player's overrides to a client", Appearance.sendAll, player)
    end
end)

Events.OnGameStart.Add(function()
    if not isServer() then Appearance.run("sending the local players' overrides", publishLocal) end
end)

Events.OnCreatePlayer.Add(function(_, player)
    if not isServer() then Appearance.run("sending a local player's overrides", Appearance.publish, player) end
end)

Appearance.log("server part ready (" .. (isServer() and "server" or "single player") .. ")")
