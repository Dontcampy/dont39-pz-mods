-- Dont39 All-in-One: appearance-override, its Lua API. Draws a worn item hidden or as another item type, on every
-- client, while the item itself does not change. Call it where items are decided: on the server, or in single player.
--
--   local Appearance = require "Dont39AllInOne_AppearanceOverrideShared"
--   Appearance.setHidden(player, item)
--   Appearance.setLook(player, item, { type = "Base.Jacket_Black", textureChoice = 0, baseTexture = -1,
--       tint = { 1, 1, 1, 1 }, decal = nil, hue = nil })
--   Appearance.clear(player, item)
--
-- A look gives every value the game would otherwise pick at random: textureChoice and baseTexture are indices into
-- the type's clothing textures (-1 when it has none), tint is r, g, b, a from 0 to 1, decal is required when the type
-- draws one, hue is -1 to 1 (nil is 0). Bad input is an error. The override stays in the item's mod data under
-- Appearance.KEY, so it is saved and travels with the item; it applies while the item is worn.

local Appearance = {}

Appearance.KEY = "Dont39AllInOne_Appearance" -- Override.kt reads the value
Appearance.MODULE = "Dont39AllInOne_AppearanceOverride" -- the commands between server and clients

local PREFIX = "[Dont39AllInOne] appearance-override sync: "

-- per player key: { revision = n, signature = s, entries = t }, the set last sent
local sent = {}
-- bad overrides already reported, by player key, type and reason
local reported = {}
local stopped = nil

-- For the sync's status lines; never throws.
function Appearance.log(message)
    pcall(function() DebugType.Mod:println(PREFIX .. message) end)
end

local function logError(message)
    pcall(function() DebugType.Mod:error(PREFIX .. message) end)
end

-- Ends sending for the session: a set half sent must not be built on.
function Appearance.fail(what, err, trace)
    if stopped then return end
    stopped = "it stopped after an error while " .. what .. " (details in console.txt)"
    logError("disabled after an error while " .. what .. ": " .. tostring(err) .. "\n" .. tostring(trace))
end

-- Why the API cannot be used now, or nil.
function Appearance.problem()
    if stopped then return stopped end
    if not Dont39AllInOne_getAppearanceOverrideProblem then return "its Java side is not loaded" end
    -- disabled Java would make every stored override look invalid
    return Dont39AllInOne_getAppearanceOverrideProblem()
end

-- A new game: the sets start again from revision 1, as the clients' copies do.
function Appearance.startSession()
    sent, reported = {}, {}
end

-- In multiplayer the user name the clients key the set by; in single player the local player's slot.
local function playerKey(player)
    if isServer() then return player:getUsername() end
    return "#" .. tostring(player:getPlayerNum())
end

-- The item's override, or nil. Do not change the table: the set API writes a new one.
function Appearance.get(item)
    if not item:hasModData() then return nil end
    return item:getModData()[Appearance.KEY]
end

local function describe(override)
    if override.hidden then return "hidden" end
    local t = override.tint
    return table.concat({ override.type, tostring(override.textureChoice), tostring(override.baseTexture),
        tostring(t[1]), tostring(t[2]), tostring(t[3]), tostring(t[4]), tostring(override.decal),
        tostring(override.hue) }, ",")
end

-- The worn items' overrides by item type, and a text that differs whenever they do.
local function wornSet(player, key)
    local worn = player:getWornItems()
    local entries, parts = {}, {}
    for i = 0, worn:size() - 1 do
        local item = worn:get(i):getItem()
        local override = item and Appearance.get(item)
        local fullType = override and item:getFullType()
        -- the clients draw by type: the first worn item of a type gives its look
        if override and entries[fullType] == nil then
            -- a non-table fails Kahlua's argument conversion, not the check
            local problem = type(override) ~= "table" and ("the override is " .. tostring(override) .. ", not a table")
                or Dont39AllInOne_checkAppearanceOverride(override)
            if problem then
                -- stored data: the item is drawn as itself, once reported
                local id = key .. "|" .. fullType .. "|" .. problem
                if not reported[id] then
                    reported[id] = true
                    logError(fullType .. " worn by " .. key .. " is drawn as itself: its override is not valid: "
                        .. problem)
                end
            else
                entries[fullType] = override
                parts[#parts + 1] = fullType .. "=" .. describe(override)
            end
        end
    end
    table.sort(parts)
    return entries, table.concat(parts, ";")
end

local function send(player, key, set)
    if isServer() then
        sendServerCommand(Appearance.MODULE, "set", { user = key, revision = set.revision, entries = set.entries })
    else
        Dont39AllInOne_putLocalAppearanceOverrideSet(player, set.revision, set.entries)
    end
end

local function publish(player)
    local key = playerKey(player)
    local entries, signature = wornSet(player, key)
    local last = sent[key]
    -- a player who never had an override needs no message
    if (last and last.signature == signature) or (not last and signature == "") then return end
    local set = { revision = (last and last.revision or 0) + 1, signature = signature, entries = entries }
    sent[key] = set
    send(player, key, set)
end

-- Sends [player]'s set to every client when it changed since the last; the API does it after each change, and the
-- server Lua when clothes change.
function Appearance.publish(player)
    local problem = Appearance.problem()
    if problem then error("appearance-override: " .. problem, 2) end
    local ok, err, trace = pcall(publish, player)
    if not ok then
        Appearance.fail("sending " .. tostring(playerKey(player)) .. "'s overrides", err, trace)
        error("appearance-override: " .. stopped, 2)
    end
end

-- Server: every online player's last set, to [player] after it joined.
function Appearance.sendAll(player)
    local players = getOnlinePlayers()
    for i = 0, players:size() - 1 do
        local other = players:get(i)
        Appearance.publish(other)
        local set = sent[playerKey(other)]
        if set then
            sendServerCommand(player, Appearance.MODULE, "set",
                { user = playerKey(other), revision = set.revision, entries = set.entries })
        end
    end
    -- the client asks again until this arrives
    sendServerCommand(player, Appearance.MODULE, "snapshotDone", {})
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
    if not instanceof(item, "InventoryItem") then
        error("appearance-override: " .. tostring(item) .. " is not an item", 3)
    end
end

-- [override] nil clears it
local function store(player, item, override)
    if override ~= nil or item:hasModData() then
        item:getModData()[Appearance.KEY] = override
        -- the owner's copy of the item, for code that reads Appearance.get there
        if isServer() then syncItemModData(player, item) end
    end
    Appearance.publish(player)
end

function Appearance.setHidden(player, item)
    checkCaller(player, item)
    store(player, item, { hidden = true })
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
    local problem = Dont39AllInOne_checkAppearanceOverride(look)
    if problem then error("appearance-override: the look for " .. item:getFullType() .. ": " .. problem, 2) end
    -- a copy, so the caller's table can change without changing the item
    store(player, item, { type = look.type, textureChoice = look.textureChoice, baseTexture = look.baseTexture,
        tint = { look.tint[1], look.tint[2], look.tint[3], look.tint[4] }, decal = look.decal, hue = look.hue })
end

function Appearance.clear(player, item)
    checkCaller(player, item)
    store(player, item, nil)
end

return Appearance
