-- Dont39 All-in-One: appearance-override, its Lua API. Draws a worn item hidden or as another item type, on every
-- client, while the item itself does not change. Call it where items are decided: on the server, or in single player.
--
--   local Appearance = require "Dont39AllInOne_AppearanceOverrideShared"
--   Appearance.setHidden(player, item)
--   Appearance.setLook(player, item, Appearance.lookOf(other))
--   Appearance.setLook(player, item, { type = "Base.Jacket_Black", textureChoice = 0, baseTexture = -1,
--       tint = { 1, 1, 1, 1 }, decal = nil, hue = nil })
--   Appearance.clear(player, item)
--
-- Several items at once: Appearance.put for each, then one Appearance.publish(player), so the clients get one set.
--
-- A look gives every value the game would otherwise pick at random: textureChoice and baseTexture are indices into
-- the type's clothing textures (-1 when it has none), tint is r, g, b, a from 0 to 1, decal is required when the type
-- draws one, hue is -1 to 1 (nil is 0). Bad input is an error. The override stays in the item's mod data under
-- Appearance.KEY, so it is saved and travels with the item; it applies while the item is worn.

local Common = require "Dont39AllInOne_CommonShared"

local Appearance = {}

Appearance.KEY = "Dont39AllInOne_Appearance" -- Override.kt reads the value
Appearance.MODULE = "Dont39AllInOne_AppearanceOverride" -- the commands between server and clients

local PREFIX = "[Dont39AllInOne] appearance-override sync: "

-- Stops the sync for the session: a set half sent must not be built on.
local guard = Common.guard(PREFIX)

-- per player key: { revision = n, signature = s, entries = t }, the set last sent
local sent = {}
-- per player key: { [override table] = what read() said }, for the tables worn at the last publish; the API stores a
-- new table on every change, so a table already read reads the same
local readByPlayer = {}
-- bad overrides already reported, by player key, type and reason
local reported = {}

-- For the sync's status lines; never throws.
function Appearance.log(message)
    Common.log(PREFIX, message)
end

-- Why the API cannot be used now, or nil.
function Appearance.problem()
    if guard.disabled then return guard.reason end
    if not Dont39AllInOne_getAppearanceOverrideProblem then return "its Java side is not loaded" end
    -- disabled Java would make every stored override look invalid
    return Dont39AllInOne_getAppearanceOverrideProblem()
end

-- For the sync's event handlers: [fn](...) while the API can be used; an error stops the sync.
function Appearance.run(what, fn, ...)
    -- a stopped sync was reported once
    if Appearance.problem() then return end
    guard.run(what, fn, ...)
end

-- A new game: the sets start again from revision 1, as the clients' copies do.
function Appearance.startSession()
    sent, readByPlayer, reported = {}, {}, {}
end

-- In multiplayer the user name the clients key the set by; in single player the local player's slot.
local function playerKey(player)
    if isServer() then return player:getUsername() end
    return "#" .. tostring(player:getPlayerNum())
end

-- The item's override, or nil. Do not change the table: the API stores a new one.
function Appearance.get(item)
    if not item:hasModData() then return nil end
    return item:getModData()[Appearance.KEY]
end

-- { override = the copy to store, text = the same exactly when two overrides draw the same } when [value] can be
-- stored and drawn as an override, else { problem = why not }.
local function read(value)
    -- a non-table fails Kahlua's argument conversion, not the check
    if type(value) ~= "table" then return { problem = "the override is " .. tostring(value) .. ", not a table" } end
    if not Dont39AllInOne_readAppearanceOverride then return { problem = "its Java side is not loaded" } end
    return Dont39AllInOne_readAppearanceOverride(value)
end

-- A copy of [override] (a look or { hidden = true }) the caller may change, e.g. to store an edited look.
function Appearance.copy(override)
    local copy = {}
    for k, v in pairs(override) do
        copy[k] = type(v) == "table" and Appearance.copy(v) or v
    end
    return copy
end

-- [item] as it is drawn now, as a look for setLook or put. The item is clothing with a visual.
function Appearance.lookOf(item)
    if not instanceof(item, "InventoryItem") then
        error("appearance-override: " .. tostring(item) .. " is not an item", 2)
    end
    if not item:getClothingItem() or not item:getVisual() then
        error("appearance-override: " .. item:getFullType() .. " has no clothing visual", 2)
    end
    local look = Dont39AllInOne_readAppearanceOverrideLook and Dont39AllInOne_readAppearanceOverrideLook(item)
    if not look then error("appearance-override: " .. tostring(Appearance.problem()), 2) end
    return look
end

-- The worn items' overrides by item type, and a text that differs whenever they do.
local function wornSet(player, key)
    local worn = player:getWornItems()
    local last, now = readByPlayer[key] or {}, {}
    local entries, parts = {}, {}
    for i = 0, worn:size() - 1 do
        local item = worn:get(i):getItem()
        local override = item and Appearance.get(item)
        local fullType = override and item:getFullType()
        -- the clients draw by type: the first worn item of a type gives its look
        if override and entries[fullType] == nil then
            local result = now[override] or last[override] or read(override)
            now[override] = result
            if result.problem then
                -- stored data: the item is drawn as itself, once reported
                local id = key .. "|" .. fullType .. "|" .. result.problem
                if not reported[id] then
                    reported[id] = true
                    Common.logError(PREFIX, fullType .. " worn by " .. key
                        .. " is drawn as itself: its override is not valid: " .. result.problem)
                end
            else
                entries[fullType] = override
                parts[#parts + 1] = fullType .. "=" .. result.text
            end
        end
    end
    readByPlayer[key] = now
    table.sort(parts)
    return entries, table.concat(parts, ";")
end

local function setMessage(key, set)
    return { user = key, revision = set.revision, entries = set.entries }
end

local function publish(player)
    local key = playerKey(player)
    local entries, signature = wornSet(player, key)
    local last = sent[key]
    -- a player who never had an override needs no message
    if (last and last.signature == signature) or (not last and signature == "") then return end
    local set = { revision = (last and last.revision or 0) + 1, signature = signature, entries = entries }
    sent[key] = set
    if isServer() then
        sendServerCommand(Appearance.MODULE, "set", setMessage(key, set))
    else
        Dont39AllInOne_putLocalAppearanceOverrideSet(player, set.revision, set.entries)
    end
end

-- Sends [player]'s set to every client when it changed since the last; setHidden, setLook and clear do it after each
-- change, and the server Lua when clothes change.
function Appearance.publish(player)
    local problem = Appearance.problem()
    if problem then error("appearance-override: " .. problem, 2) end
    local ok, err, trace = pcall(publish, player)
    if not ok then
        guard.fail("sending " .. tostring(playerKey(player)) .. "'s overrides", err, trace)
        error("appearance-override: " .. guard.reason, 2)
    end
end

-- Server: publishes every online player's set, and forgets what was read for players who left.
function Appearance.publishOnline()
    local players = getOnlinePlayers()
    local online = {}
    for i = 0, players:size() - 1 do
        local player = players:get(i)
        online[playerKey(player)] = true
        Appearance.publish(player)
    end
    -- the sent revisions stay: a player who joins again must replace the set the clients still hold
    for key in pairs(readByPlayer) do
        if not online[key] then readByPlayer[key] = nil end
    end
end

-- Server: every online player's last set, to [player] after it joined.
function Appearance.sendAll(player)
    local players = getOnlinePlayers()
    for i = 0, players:size() - 1 do
        local other = players:get(i)
        Appearance.publish(other)
        local set = sent[playerKey(other)]
        if set then sendServerCommand(player, Appearance.MODULE, "set", setMessage(playerKey(other), set)) end
    end
    -- the client asks again until this arrives
    sendServerCommand(player, Appearance.MODULE, "snapshotDone", {})
end

-- Errors raised here name the API function's caller.

-- [level]: 1 when the API function calls it, 2 through checkCaller
local function checkItem(item, level)
    if not instanceof(item, "InventoryItem") then
        error("appearance-override: " .. tostring(item) .. " is not an item", level + 2)
    end
end

local function checkCaller(player, item)
    if isClient() then
        error("appearance-override: call it on the server; a client's change would not reach the others", 3)
    end
    local problem = Appearance.problem()
    if problem then error("appearance-override: " .. problem, 3) end
    if not instanceof(player, "IsoPlayer") then
        error("appearance-override: " .. tostring(player) .. " is not a player", 3)
    end
    checkItem(item, 2)
end

-- The stored copy of [override] (nil clears it) into the item's mod data; returns why not, or nil.
local function store(item, override)
    if override == nil then
        if item:hasModData() then item:getModData()[Appearance.KEY] = nil end
        return nil
    end
    local result = read(override)
    if result.problem then return "the override for " .. item:getFullType() .. ": " .. result.problem end
    item:getModData()[Appearance.KEY] = result.override
    return nil
end

local function storeSynced(player, item, override)
    if override == nil and not item:hasModData() then return nil end
    local problem = store(item, override)
    -- for code that reads Appearance.get on the owner's client
    if not problem and isServer() then syncItemModData(player, item) end
    return problem
end

-- [override] (nil, { hidden = true } or a look) on [item], checked; the owner's copy of the item gets it, the
-- clients' drawing at the next Appearance.publish(player).
function Appearance.put(player, item, override)
    checkCaller(player, item)
    local problem = storeSynced(player, item, override)
    if problem then error("appearance-override: " .. problem, 2) end
end

-- [override] (nil, { hidden = true } or a look) into the item's mod data, checked, sending nothing. For an item not
-- sent yet, whose add packet carries its mod data; and on a multiplayer client for its own copy of an item until the
-- server's arrives (players are drawn from the sets the server sends, not from that copy).
function Appearance.write(item, override)
    checkItem(item, 1)
    local problem = store(item, override)
    if problem then error("appearance-override: " .. problem, 2) end
end

function Appearance.setHidden(player, item)
    checkCaller(player, item)
    local problem = storeSynced(player, item, { hidden = true })
    if problem then error("appearance-override: " .. problem, 2) end
    Appearance.publish(player)
end

function Appearance.setLook(player, item, look)
    checkCaller(player, item)
    if type(look) ~= "table" then
        error("appearance-override: the look for " .. item:getFullType() .. " is " .. tostring(look)
            .. ", not a table", 2)
    end
    if look.hidden ~= nil then
        error("appearance-override: a look has no hidden key; use setHidden", 2)
    end
    local problem = storeSynced(player, item, look)
    if problem then error("appearance-override: " .. problem, 2) end
    Appearance.publish(player)
end

function Appearance.clear(player, item)
    checkCaller(player, item)
    storeSynced(player, item, nil)
    Appearance.publish(player)
end

return Appearance
