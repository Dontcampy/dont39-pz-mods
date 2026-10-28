-- Dont39 All-in-One: sctd-render-takeover where items are decided (server, single player): SCTD's hide, transmog,
-- restore and auto-hide keep the worn item and change only its drawn look.

local Appearance = require "Dont39AllInOne_AppearanceOverrideShared"
local Takeover = require "Dont39AllInOne_SctdRenderTakeoverShared"
local Runtime = require "Dont39AllInOne_SctdRenderTakeoverRuntime"

local Authoritative = {}

local realWorn = Takeover.realWorn

-- [override] on [item]; a batch (SCTD's deferSync) publishes once at its end.
local function putOverride(player, item, override, opts)
    Appearance.put(player, item, override)
    if not (opts and opts.deferSync) then Appearance.publish(player) end
end

local function hideItemServer(player, item, opts)
    if isClient() or not player or not item then return false end
    opts = opts or {}
    local worn = realWorn(player)
    if not worn or not worn:contains(item) then return false end
    if SCTD_ClothingAppearance.IsForbidden(player, item) then return false end
    if SCTD_ContentFilter.IsProtected(player, item) then return false end
    if SCTD_Transmog_IsTransmogged(item) then return false end
    local fullType = item:getFullType()
    if SCTD_Transmog_IsInvisVariant(fullType) then return false end
    if SCTD_IsWoundOverlay(fullType) or SCTD_IsBandageOverlay(fullType) then return false end
    if not item:getClothingItem() then return false end
    local md = item:getModData()
    md.SCTD_TransmogHidden = nil
    md.SCTD_TransmogShown = nil
    -- before the override, whose mod data sync then carries both
    Takeover.setRecord(item, Takeover.newRecord(true, opts.autoHidden))
    putOverride(player, item, { hidden = true }, opts)
    SCTD_TransmogSync.Notify(player, "applied", item)
    return true, item
end

-- [from]'s look for [target], or nil with an error line when the appearance override cannot draw it.
local function lookFor(target, from)
    local look, problem = Takeover.drawableLook(from)
    if look then return look end
    Takeover.logError(target:getFullType() .. " is not drawn as " .. from:getFullType() .. ": " .. problem)
    return nil
end

local function isSource(player, base, source, ignoreBodyLocation)
    for _, candidate in ipairs(SCTD_Transmog_GetSources(player, base, ignoreBodyLocation)) do
        if candidate == source then return true end
    end
    return false
end

-- B is worn in its own slot in A's place and drawn as A; A stays in the inventory.
local function invert(player, base, source, worn)
    local look = lookFor(source, base)
    if not look then return false end
    local location = SCTD_Transmog_GetBodyLocation(source)
    if not location or location == "" then return false end
    for i = 0, worn:size() - 1 do
        if worn:getItemByIndex(i) ~= base and SCTD_ContentFilter.Conflicts(location, worn:get(i):getLocation()) then
            return false
        end
    end
    if not SCTD_ContentFilter.CanWear(player, source, base, location) then return false end
    local inventory = player:getInventory()
    local container = source:getContainer()
    if container ~= inventory then
        container:Remove(source)
        sendRemoveItemFromContainer(container, source)
        inventory:AddItem(source)
        sendAddItemToContainer(inventory, source)
    end
    -- before the swap: its clothing event runs auto-hide, which leaves transmogged items alone
    Takeover.setRecord(source, Takeover.newRecord(false, false, base:getDisplayName()))
    Takeover.takeOff(player, base)
    Takeover.wear(player, location, source)
    sendEquip(player)
    Appearance.setLook(player, source, look)
    player:resetModelNextFrame()
    triggerEvent("OnClothingUpdated", player)
    syncVisuals(player)
    SCTD_TransmogSync.Notify(player, "applied", source)
    return true, source
end

local function apply(player, base, source, ignoreBodyLocation, invertStats)
    if not player or not base or not source then return false end
    local worn = realWorn(player)
    if SCTD_ClothingAppearance.IsForbidden(player, base) or SCTD_ClothingAppearance.IsForbidden(player, source) then
        return false
    end
    if SCTD_Transmog_IsTransmogged(base) then return false end
    if not worn or not worn:contains(base) then return false end
    if not isSource(player, base, source, ignoreBodyLocation) then return false end
    local location = worn:getLocation(base) or SCTD_Transmog_GetBodyLocation(base)
    if not location or location == "" then return false end
    if base:getFullType() == source:getFullType() or not source:getClothingItem() then return false end
    if invertStats == true then return invert(player, base, source, worn) end
    local look = lookFor(base, source)
    if not look then return false end
    if not SCTD_ContentFilter.CanWear(player, source, base, location) then return false end
    Takeover.setRecord(base, Takeover.newRecord(false, false, source:getDisplayName()))
    Appearance.setLook(player, base, look)
    SCTD_TransmogSync.Notify(player, "applied", base)
    return true, base
end

-- Restore ---------------------------------------------------------------------------------------------------------

-- SCTD's Restore, and whether the takeover's replaces it. The wrapper stays installed when the takeover stops: it then
-- calls SCTD's own Restore.
local sctdRestore = nil
local takenOver = false
local wrapperStopped = false

local function restoreOurs(player, item, opts)
    local worn = realWorn(player)
    if not worn or not worn:contains(item) then return false end
    Takeover.setRecord(item, nil)
    putOverride(player, item, nil, opts)
    SCTD_TransmogSync.Notify(player, "restored", item)
    return true, item
end

-- SCTD's own records (placeholders, type transmogs) go to its Restore, which swaps items; SCTD's error goes on as
-- without this feature.
local function restoreSctd(player, item, opts)
    if not player then return sctdRestore(player, item, opts) end
    return Takeover.whileBusy(player, sctdRestore, player, item, opts)
end

local function isOurs(player, item)
    return player ~= nil and item ~= nil and Takeover.ourRecord(item) ~= nil
end

local function restore(player, item, opts)
    local ok, ours, trace = pcall(isOurs, player, item)
    if not ok then
        Runtime.fail("restoring an item", ours, trace)
        return false
    end
    if not ours then return restoreSctd(player, item, opts) end
    local done, r1, r2 = pcall(restoreOurs, player, item, opts)
    if done then return r1, r2 end
    Runtime.fail("restoring " .. tostring(item:getFullType()), r1, r2)
    return false
end

-- The wrapper's own error also ends its part.
local function failWrapper(what, err, trace)
    wrapperStopped = true
    if not Runtime.failed() then return Runtime.fail(what, err, trace) end
    Takeover.logError("stopped clearing the looks of items SCTD restores after an error while " .. what .. ": "
        .. tostring(err) .. "\n" .. tostring(trace))
end

local function hasOurLook(item)
    return item ~= nil and Takeover.ourRecord(item) ~= nil and Appearance.get(item) ~= nil
end

local function clearLeftLook(player, item, opts)
    if not Takeover.ourRecord(item) and Appearance.get(item) ~= nil then putOverride(player, item, nil, opts) end
end

-- When SCTD's own Restore clears this feature's record (the takeover stopped), the look goes with it, so an item
-- never stays hidden with no way to show it.
local function restoreWrapper(player, item, opts)
    local watched = false
    if not wrapperStopped then
        local ok, result, trace = pcall(hasOurLook, item)
        if ok then watched = result else failWrapper("checking an item before it is restored", result, trace) end
    end
    local impl = (takenOver and not Runtime.stopped()) and restore or sctdRestore
    local done, restored = impl(player, item, opts)
    if watched and done and not wrapperStopped then
        local ok, err, trace = pcall(clearLeftLook, player, item, opts)
        if not ok then failWrapper("clearing the look of an item SCTD restored", err, trace) end
    end
    return done, restored
end

-- Wraps SCTD's Restore, also when the check failed: items this feature changed in an earlier session must stay
-- restorable.
function Authoritative.wrapRestore()
    if isClient() or type(SCTD_Transmog_Restore) ~= "function" or Appearance.problem() then return end
    sctdRestore = SCTD_Transmog_Restore
    SCTD_Transmog_Restore = restoreWrapper
end

-- Auto-hide -------------------------------------------------------------------------------------------------------

-- SCTD's local SCTD_AutoHide_CanAutoHide, as reviewed.
local function canAutoHide(player, item)
    if not item then return false end
    local fullType = nil
    pcall(function() fullType = item:getFullType() end)
    if not fullType then return false end
    if SCTD_Transmog_IsInvisVariant(fullType) then return false end
    if SCTD_IsWoundOverlay(fullType) or SCTD_IsBandageOverlay(fullType) then return false end
    if not SCTD_IsVanillaClothingType(fullType) then return false end
    if SCTD_Transmog_IsTransmogged(item) then return false end
    local clothingItem = nil
    pcall(function() clothingItem = item:getClothingItem() end)
    if not clothingItem then return false end
    if SCTD_ClothingAppearance.IsForbidden(player, item) then return false end
    if item:hasModData() and item:getModData().SCTD_TransmogShown == true then return false end
    return true
end

-- Each step goes through SCTD's global, as SCTD's own auto-hide does, deferred: the set is published once at the end.
local function reconcileWorn(player)
    if player:isDead() then return end
    local shouldHide = SCTD_WearsBodyForm(player) and SCTD_ClothingAppearance.IsAutoHideNonSct(player)
    local worn = player:getWornItems()
    if not worn then return end
    local targets, changed, swapped = {}, false, false
    if shouldHide then
        for i = 0, worn:size() - 1 do
            local item = worn:getItemByIndex(i)
            if canAutoHide(player, item) then targets[#targets + 1] = item end
        end
        for _, item in ipairs(targets) do
            -- a failed step put SCTD's own functions back, which would swap the rest
            if Runtime.stopped() then break end
            if SCTD_Transmog_HideItem_Server(player, item, { autoHidden = true, deferSync = true }) then
                changed = true
            end
        end
    else
        -- ours, and SCTD's placeholders from before
        for i = 0, worn:size() - 1 do
            local item = worn:getItemByIndex(i)
            local data = item and SCTD_Transmog_GetData(item)
            if data and data.autoHidden then targets[#targets + 1] = item end
        end
        for _, item in ipairs(targets) do
            if Runtime.stopped() then break end
            local placeholder = SCTD_Transmog_IsInvisVariant(item:getFullType())
            if SCTD_Transmog_Restore(player, item, { deferSync = true }) then
                changed = true
                if placeholder then swapped = true end
            end
        end
        if swapped then SCTD_Transmog_BatchSync(player) end
    end
    if changed then Appearance.publish(player) end
end

local function reconcile(player)
    if isClient() or not player or Takeover.isBusy(player) then return end
    Takeover.whileBusy(player, reconcileWorn, player)
end

-- Look edits --------------------------------------------------------------------------------------------------------

local function wornById(player, id)
    local worn = player:getWornItems()
    for i = 0, worn:size() - 1 do
        local item = worn:getItemByIndex(i)
        if item and item:getID() == id then return item end
    end
    return nil
end

local function isIndex(n)
    return type(n) == "number" and n >= 0 and n == math.floor(n)
end

local function isShare(n)
    return type(n) == "number" and n >= 0 and n <= 1
end

-- A client's skin panel edit of a transmogged item's look. An item no longer worn or transmogged is an edit that
-- crossed a change, not an error.
local function editLook(player, command, args)
    local function reject(why)
        Takeover.logError("ignored a look edit from " .. tostring(player:getUsername()) .. ": " .. why)
    end
    if type(args) ~= "table" or type(args.itemId) ~= "number" then return reject("no item id") end
    local item = wornById(player, args.itemId)
    local look = item and Takeover.lookOverride(item)
    if not look then return end
    local edited
    if command == "lookSkin" then
        if not isIndex(args.textureIndex) then return reject("texture index " .. tostring(args.textureIndex)) end
        edited = Takeover.withTexture(look, args.textureIndex)
    else
        if not (isShare(args.r) and isShare(args.g) and isShare(args.b)) then
            return reject("color " .. tostring(args.r) .. ", " .. tostring(args.g) .. ", " .. tostring(args.b))
        end
        edited = Takeover.withTint(look, args.r, args.g, args.b)
    end
    if edited then Appearance.setLook(player, item, edited) end
end

local function onClientCommand(module, command, player, args)
    if module ~= Takeover.MODULE or Runtime.stopped() or (command ~= "lookSkin" and command ~= "lookColor") then
        return
    end
    local ok, err, trace = pcall(editLook, player, command, args)
    if not ok then Runtime.fail("taking a look edit from a client", err, trace) end
end

function Authoritative.install()
    if not sctdRestore then error("SCTD_Transmog_Restore is not wrapped") end
    Runtime.replace(_G, "SCTD_Transmog_HideItem_Server",
        Runtime.guard("hiding an item", hideItemServer, SCTD_Transmog_HideItem_Server, Runtime.refuse))
    Runtime.replace(_G, "SCTD_Transmog_Apply",
        Runtime.guard("transmogging an item", apply, SCTD_Transmog_Apply, Runtime.refuse))
    Runtime.replace(_G, "SCTD_AutoHide_Reconcile", Runtime.guard("hiding or showing clothing for a body form",
        reconcile, SCTD_AutoHide_Reconcile, function() end))
    takenOver = true
    if isServer() then Events.OnClientCommand.Add(onClientCommand) end
end

return Authoritative
