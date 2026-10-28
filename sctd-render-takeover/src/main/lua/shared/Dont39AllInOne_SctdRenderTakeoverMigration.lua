-- Dont39 All-in-One: sctd-render-takeover, the one-way conversion of the items SCTD's own swap made (hidden
-- placeholders, type transmogs, inverted transmogs) back into the real items, keeping the look the player had.
-- Authoritative side only, while the takeover runs.

local Appearance = require "Dont39AllInOne_AppearanceOverrideShared"
local Common = require "Dont39AllInOne_CommonShared"
local Takeover = require "Dont39AllInOne_SctdRenderTakeoverShared"
local Runtime = require "Dont39AllInOne_SctdRenderTakeoverRuntime"

local Migration = {}

local HIDDEN, TRANSMOG, INVERT = "hidden", "transmog", "invert"

-- the migration only; the takeover keeps running
local guard = Common.guard(Takeover.PREFIX .. "migration ")

-- per player whose whole inventory was scanned: { left = ids of the items left as SCTD made them, reported once }. A
-- rejoin is a new object, scanned again. Kahlua has no weak tables: players no longer there are dropped (prune).
local scanned = {}

-- What to find ------------------------------------------------------------------------------------------------------

-- SCTD's record of an item its own swap made, and the kind; nil for anything else, which SCTD's Restore still handles.
local function oldStyle(item, left)
    if left[item:getID()] or not item:hasModData() or Takeover.ourRecord(item) then return nil end
    local data = item:getModData()[Takeover.KEY]
    if type(data) ~= "table" or type(data.baseFullType) ~= "string" or data.baseFullType == "" then return nil end
    local fullType = item:getFullType()
    local hasSource = type(data.srcFullType) == "string" and data.srcFullType ~= ""
    if data.inverted == true then
        if hasSource and fullType == data.baseFullType then return data, INVERT end
        return nil
    end
    if SCTD_Transmog_IsInvisVariant(fullType) then
        if data.hidden == true then return data, HIDDEN end
        return nil
    end
    if hasSource and fullType ~= data.baseFullType then return data, TRANSMOG end
    return nil
end

local function add(found, item, left)
    local data, kind = oldStyle(item, left)
    if data then found[#found + 1] = { item = item, data = data, kind = kind } end
end

-- Outer bags come first: converting one moves its contents into the new bag, where the inner items are then found.
local function collect(container, found, left)
    local items = container:getItems()
    for i = 0, items:size() - 1 do
        local item = items:get(i)
        add(found, item, left)
        if item:IsInventoryContainer() then collect(item:getItemContainer(), found, left) end
    end
    return found
end

local function collectWorn(player, left)
    local found = {}
    local worn = Takeover.realWorn(player)
    for i = 0, worn:size() - 1 do
        local item = worn:getItemByIndex(i)
        if item then add(found, item, left) end
    end
    return found
end

-- Rebuilding ----------------------------------------------------------------------------------------------------------

-- SCTD's CopyState(from, to, true, true) as reviewed: no texture indices, which belong to the item type, and no
-- contents.
local function copyState(from, to)
    local fromVisual, toVisual = from:getVisual(), to:getVisual()
    if fromVisual and toVisual then
        local clothing = from:getClothingItem()
        if not clothing then error(from:getFullType() .. " has a visual but no clothing item") end
        toVisual:setTint(fromVisual:getTint(clothing))
        toVisual:copyDirt(fromVisual)
        toVisual:copyBlood(fromVisual)
        toVisual:copyHoles(fromVisual)
        toVisual:copyPatches(fromVisual)
    end
    to:setColor(from:getColor())
    to:setConditionNoSound(from:getCondition())
    to:setFavorite(from:isFavorite())
    if from:hasModData() then to:copyModData(from:getModData()) end
    if to:IsClothing() and from:IsClothing() then
        from:copyPatchesTo(to)
        to:setWetness(from:getWetness())
    end
    to:synchWithVisual()
end

-- SCTD's applyAuthoritativeTint as reviewed: an item with a custom color is drawn in its own color, which
-- synchWithVisual rebuilds from the color channels.
local function setCustomTint(item, r, g, b)
    item:getVisual():setTint(ImmutableColor.new(r, g, b, 1))
    item:setColorRed(r)
    item:setColorGreen(g)
    item:setColorBlue(b)
    item:setColor(Color.new(r, g, b, 1))
    item:setCustomColor(true)
    item:synchWithVisual()
end

local function isTint(t)
    return type(t) == "table" and type(t.r) == "number" and type(t.g) == "number" and type(t.b) == "number"
end

local function sourceName(data, old)
    if type(data.srcDisplayName) == "string" then return data.srcDisplayName end
    return old:getDisplayName()
end

-- The real item for a placeholder or a type transmog, with its record and override, not yet in any container. Returns
-- nil and why when the item's data does not allow it.
local function rebuild(old, data, kind)
    local item = instanceItem(data.baseFullType)
    if not item then return nil, data.baseFullType .. " does not exist" end
    if not item:getVisual() then return nil, data.baseFullType .. " has no visual" end
    local look = nil
    if kind == TRANSMOG then
        if not old:getClothingItem() then return nil, "it has no clothing item to take the look from" end
        local problem
        look, problem = Takeover.drawableLook(old)
        if not look then return nil, "its look cannot be drawn: " .. problem end
    elseif data.baseTint ~= nil and not isTint(data.baseTint) then
        return nil, "its record's tint is " .. tostring(data.baseTint) .. ", not r, g, b"
    end
    copyState(old, item)
    local md = item:getModData()
    md[Takeover.KEY] = nil
    md.SCTD_TransmogHidden = nil
    md.SCTD_TransmogShown = nil
    local visual = item:getVisual()
    if type(data.baseTextureChoice) == "number" then visual:setTextureChoice(data.baseTextureChoice) end
    if type(data.baseBaseTexture) == "number" then visual:setBaseTexture(data.baseBaseTexture) end
    if kind == HIDDEN then
        -- the placeholder borrowed the real item's icon
        md.customInventoryIcon = data.baseCustomIcon
        local t = data.baseTint
        if t and data.baseCustomColor then
            setCustomTint(item, t.r, t.g, t.b)
        elseif t then
            visual:setTint(ImmutableColor.new(t.r, t.g, t.b, 1))
        end
        -- the recorded name is the item's own in the language of whoever hid it, unless the player renamed it
        if type(data.baseDisplayName) == "string" and data.baseDisplayName ~= item:getDisplayName() then
            item:setName(data.baseDisplayName)
            item:setCustomName(true)
        end
        Takeover.setRecord(item, Takeover.newRecord(true, data.autoHidden == true))
        Appearance.write(item, { hidden = true })
    else
        local fluid, oldFluid = item:getFluidContainer(), old:getFluidContainer()
        if fluid and oldFluid then fluid:copyFluidsFrom(oldFluid) end
        Takeover.setRecord(item, Takeover.newRecord(false, false, sourceName(data, old)))
        Appearance.write(item, look)
    end
    item:synchWithVisual()
    return item
end

-- Where the real item is worn, as SCTD's Restore picks it: the recorded slot, else the item's own.
local function wornLocation(player, item, data)
    if type(data.baseBodyLocation) == "string" and data.baseBodyLocation ~= "" then
        local saved = ItemBodyLocation.get(ResourceLocation.of(data.baseBodyLocation))
        if saved and player:getWornItems():getBodyLocationGroup():getLocation(saved) then return saved end
    end
    local own = item:getBodyLocation()
    if not own and item:IsInventoryContainer() then own = item:canBeEquipped() end
    return own
end

-- A fresh source, as SCTD's Restore returns it: what it consumed was not kept.
local function newSource(data)
    local source = instanceItem(data.srcFullType)
    if not source then return nil end
    if type(data.srcCondition) == "number" then source:setConditionNoSound(data.srcCondition) end
    if data.srcFavorite == true then source:setFavorite(true) end
    return source
end

local function give(player, item)
    local inventory = player:getInventory()
    inventory:AddItem(item)
    sendAddItemToContainer(inventory, item)
end

-- The new item goes in before the old one goes out: an error between them leaves a duplicate, never a lost item.
-- Returns whether the hands changed.
local function swap(player, container, old, item, location)
    local primary = player:getPrimaryHandItem() == old
    local secondary = player:getSecondaryHandItem() == old
    container:AddItem(item)
    sendAddItemToContainer(container, item)
    if location then
        Takeover.takeOff(player, old)
        if item:IsInventoryContainer() then item:setCanBeEquipped(location) end
        Takeover.wear(player, location, item)
    elseif primary or secondary then
        player:removeFromHands(old)
    end
    container:Remove(old)
    sendRemoveItemFromContainer(container, old)
    if location then return primary or secondary end
    if primary then player:setPrimaryHandItem(item) end
    if secondary then player:setSecondaryHandItem(item) end
    return primary or secondary
end

-- Converting ----------------------------------------------------------------------------------------------------------

-- Each returns { kind, worn = worn items changed, hands = the hands changed, invert = the swap still to do }, or nil
-- and why the item was left as it is.

local function convertSwapped(player, old, data, kind)
    local item, problem = rebuild(old, data, kind)
    if not item then return nil, problem end
    local worn = player:getWornItems():contains(old)
    local location = nil
    if worn then
        location = wornLocation(player, item, data)
        if not location then return nil, "it has no body location to be worn at" end
    end
    local source = nil
    if kind == TRANSMOG then
        source = newSource(data)
        if not source then return nil, data.srcFullType .. " does not exist" end
    end
    -- before the contents move: the new item must have a container to go to, or they would go with it
    local container = old:getContainer()
    if not container then error(old:getFullType() .. " " .. Takeover.digits(old:getID()) .. " is in no container") end
    if item:IsInventoryContainer() and old:IsInventoryContainer() then
        item:getItemContainer():takeItemsFrom(old:getItemContainer())
    else
        SCTD_Transmog_EmptyContainer(player, old)
    end
    local hands = swap(player, container, old, item, location)
    if source then give(player, source) end
    return { kind = kind, worn = worn, hands = hands }
end

-- The item is the real one already, with the source's stats only where SCTD's resnap put them this session.
local function convertInverted(player, item, data)
    local source = newSource(data)
    if not source then return nil, data.srcFullType .. " does not exist" end
    local worn = player:getWornItems():contains(item)
    local look = nil
    if not worn then
        if not item:getClothingItem() then return nil, "it has no clothing item to give its look" end
        local problem
        look, problem = Takeover.drawableLook(item)
        if not look then return nil, "its look cannot be drawn: " .. problem end
    end
    Takeover.setRecord(item, nil)
    -- the owner's client drops the record and takes the server's stats
    SCTD_TransmogSync.Notify(player, "restored", item)
    if not worn then
        -- wearing the source later draws this item
        Takeover.setRecord(source, Takeover.newRecord(false, false, item:getDisplayName()))
        Appearance.write(source, look)
        give(player, source)
        return { kind = INVERT }
    end
    give(player, source)
    -- worn: the takeover's invert, after every record is cleared (convertAll)
    return { kind = INVERT, invert = { item = item, source = source } }
end

local function convertItem(player, entry)
    if entry.kind == INVERT then return convertInverted(player, entry.item, entry.data) end
    return convertSwapped(player, entry.item, entry.data, entry.kind)
end

-- [state] collects what changed as it goes, so an error part way still leaves it to finish. [left]: the player's.
local function convertAll(player, found, state, left)
    local counts, inverts = state.counts, {}
    for _, entry in ipairs(found) do
        -- a takeover that stopped put SCTD's functions back
        if Runtime.stopped() then break end
        local item = entry.item
        local result, why = convertItem(player, entry)
        if result then
            counts[result.kind] = counts[result.kind] + 1
            if result.invert then inverts[#inverts + 1] = result.invert end
            state.worn = state.worn or result.worn == true
            state.hands = state.hands or result.hands == true
        else
            left[item:getID()] = true
            counts.left = counts.left + 1
            Takeover.logError("migration left " .. item:getFullType() .. " " .. Takeover.digits(item:getID()) .. " of "
                .. tostring(player:getUsername()) .. " as SCTD made it: " .. why)
        end
    end
    -- Each Apply fires the clothing event, whose single player resnap would give an inverted item still holding its
    -- record the source's stats. Refused, both stay as they are.
    for _, pending in ipairs(inverts) do
        if not Runtime.stopped() and SCTD_Transmog_Apply(player, pending.item, pending.source, true, true) then
            state.worn = true
        else
            counts.kept = counts.kept + 1
        end
    end
end

local function finish(player, state)
    if state.hands then sendEquip(player) end
    if state.worn then
        player:resetModelNextFrame()
        syncVisuals(player)
        -- runs auto-hide on the new items, which the busy flag held off
        triggerEvent("OnClothingUpdated", player)
    end
    Appearance.publish(player)
    local counts = state.counts
    if counts[HIDDEN] + counts[TRANSMOG] + counts[INVERT] + counts.left == 0 then return end
    Takeover.log("converted " .. tostring(player:getUsername()) .. ": " .. counts[HIDDEN] .. " hidden, "
        .. counts[TRANSMOG] .. " transmogs, " .. counts[INVERT] .. " inverts (" .. counts.kept
        .. " left as themselves), " .. counts.left .. " left (see errors)")
end

-- [left]: the player's; [what]: for the error lines.
local function convertPlayer(player, found, left, what)
    if #found == 0 then return end
    local state = { counts = { [HIDDEN] = 0, [TRANSMOG] = 0, [INVERT] = 0, kept = 0, left = 0 },
        worn = false, hands = false }
    local ok, err, trace = pcall(Takeover.whileBusy, player, convertAll, player, found, state, left)
    -- before finish's clothing event, which would try the rest again
    if not ok then guard.fail(what, err, trace) end
    -- items converted before an error still reach the clients as they are on the server
    local finished, finishErr, finishTrace = pcall(finish, player, state)
    if finished then return end
    if ok then Takeover.rethrow(finishErr, finishTrace) end
    Takeover.logError("migration also failed finishing " .. what .. ": " .. tostring(finishErr) .. "\n"
        .. tostring(finishTrace))
end

-- Entry points --------------------------------------------------------------------------------------------------------

local function canRun(player)
    return not guard.disabled and not Runtime.stopped() and not Takeover.isBusy(player)
end

-- A server's packets for a container inside an inventory item need the player's square, which comes some ticks after
-- the player shows in the online list; without it the engine drops them and the client keeps the old item.
local function placed(player)
    return not isServer() or player:getCurrentSquare() ~= nil
end

local function scan(player, what)
    if scanned[player] or not canRun(player) or not placed(player) then return end
    local state = { left = {} }
    scanned[player] = state
    -- puts SCTD's display copies back, so the real worn items are found
    Takeover.realWorn(player)
    convertPlayer(player, collect(player:getInventory(), {}, state.left), state.left, what)
end

-- An old-style item worn after the player's scan: picked up from the ground, a corpse or a container. Not before the
-- scan: on a server the player may not be in the online list yet.
local function scanWorn(player, what)
    local state = scanned[player]
    if not state or not canRun(player) or not placed(player) then return end
    convertPlayer(player, collectWorn(player, state.left), state.left, what)
end

local function guarded(what, fn, player)
    local name = "?"
    pcall(function() name = tostring(player:getUsername()) end)
    what = what .. " of " .. name
    guard.run(what, fn, player, what)
end

-- The players there now: the online list on a server, the local players in single player.
local function present()
    local players = {}
    if isServer() then
        local online = getOnlinePlayers()
        for i = 0, online:size() - 1 do players[#players + 1] = online:get(i) end
    else
        for i = 0, getNumActivePlayers() - 1 do
            local player = getSpecificPlayer(i)
            if player then players[#players + 1] = player end
        end
    end
    return players
end

-- Forgets the players no longer there; returns those there.
local function prune()
    local players, kept = present(), {}
    for _, player in ipairs(players) do kept[player] = scanned[player] end
    scanned = kept
    return players
end

local function scanOnline()
    for _, player in ipairs(prune()) do
        if scanned[player] then
            -- a wear raises no clothing event on a server
            guarded("converting the newly worn items", scanWorn, player)
        else
            guarded("converting the items", scan, player)
        end
        if guard.disabled then return end
    end
end

local function scanLocal()
    for _, player in ipairs(prune()) do
        guarded("converting the items", scan, player)
    end
end

-- Does nothing once the takeover stopped.
function Migration.start()
    if isServer() then
        Common.every(1000, function()
            if not Runtime.stopped() then guard.run("looking for online players to convert", scanOnline) end
        end)
    else
        Events.OnGameStart.Add(function()
            guard.run("looking for local players to convert", scanLocal)
        end)
        Events.OnCreatePlayer.Add(function(_, player)
            if not player or not guard.run("forgetting players no longer there", prune) then return end
            guarded("converting the items", scan, player)
        end)
    end
    Events.OnClothingUpdated.Add(function(character)
        if not guard.disabled and Common.isPlayer(character) then
            guarded("converting the newly worn items", scanWorn, character)
        end
    end)
end

return Migration
