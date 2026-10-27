-- Dont39 All-in-One: sctd-render-takeover. Shadow Cat Tactics: Dispatch hides and transmogs worn clothing by swapping
-- the item for another; this replaces those steps so the worn item stays and only its drawn look changes, through
-- appearance-override. Under server/ because that folder loads last on every side, after all of SCTD's Lua.

local Appearance = require "Dont39AllInOne_AppearanceOverrideShared"
local Takeover = require "Dont39AllInOne_SctdRenderTakeoverShared"

local stopped = false -- the takeover; final for the session
local failed = false -- a failure was reported
local ranges = nil -- the checked blocks of SCTD's code, by name
local replaced = {} -- { holder, key, original } of each replaced value

-- The Restore wrapper stays installed when the takeover stops: it then calls SCTD's own Restore.
local sctdRestore = nil
local restoreImpl = nil
local wrapperStopped = false

-- per player, while auto-hide runs or SCTD swaps an item: auto-hide must not run inside either
local busy = setmetatable({}, { __mode = "k" })

local function putBack()
    restoreImpl = sctdRestore
    for i = #replaced, 1, -1 do
        local entry = replaced[i]
        entry.holder[entry.key] = entry.original
    end
    replaced = {}
end

-- Stops the takeover; SCTD runs as shipped. The Restore wrapper keeps its part: items changed before stay restorable.
local function fail(what, err, trace)
    stopped = true
    putBack()
    if failed then return end
    failed = true
    Takeover.logError("disabled after an error while " .. what .. ": " .. tostring(err) .. "\n" .. tostring(trace))
end

-- The Restore wrapper's own error also ends its part.
local function failWrapper(what, err, trace)
    wrapperStopped = true
    if not failed then return fail(what, err, trace) end
    Takeover.logError("stopped clearing the looks of items SCTD restores after an error while " .. what .. ": "
        .. tostring(err) .. "\n" .. tostring(trace))
end

local function replace(holder, key, value)
    replaced[#replaced + 1] = { holder = holder, key = key, original = holder[key] }
    holder[key] = value
end

-- A replacement whose first error stops the takeover; [onError] answers that call. Once stopped, SCTD's [original]
-- answers callers that kept a reference.
local function guard(what, fn, original, onError)
    return function(a, b, c, d, e)
        if stopped then return original(a, b, c, d, e) end
        local ok, r1, r2 = pcall(fn, a, b, c, d, e)
        if ok then return r1, r2 end
        fail(what, r1, r2)
        return onError(a, b, c, d, e)
    end
end

-- [fn] returns whether the call is this feature's, and its result; any other call goes to SCTD's [original], outside
-- this feature's error handling.
local function takeOrPass(what, fn, original, onError)
    return function(a, b, c, d, e)
        if not stopped then
            local ok, handled, result = pcall(fn, a, b, c, d, e)
            if not ok then
                fail(what, handled, result)
                return onError(a, b, c, d, e)
            end
            if handled then return result end
        end
        return original(a, b, c, d, e)
    end
end

local function refuse()
    return false
end

-- Authoritative part: server and single player -----------------------------------------------------------------------

local function realWorn(player)
    -- SCTD swaps the worn list for display copies while it draws
    if SCTD_ClothingAppearance and SCTD_ClothingAppearance.Restore then SCTD_ClothingAppearance.Restore() end
    return player:getWornItems()
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
    Appearance.setHidden(player, item)
    SCTD_TransmogSync.Notify(player, "applied", item)
    return true, item
end

-- [from]'s look for [target], or nil with an error line when the appearance override cannot draw it.
local function lookFor(target, from)
    local look = Takeover.lookOf(from)
    local problem = Dont39AllInOne_checkAppearanceOverride(look)
    if not problem then return look end
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
    player:removeFromHands(base)
    player:removeWornItem(base, false)
    -- removeWornItem leaves an item in a multi-item slot (belt) worn
    if worn:contains(base) then worn:remove(base) end
    player:removeFromHands(source)
    player:setWornItem(location, source, false)
    sendClothing(player, location, source)
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

local function restoreOurs(player, item)
    local worn = realWorn(player)
    if not worn or not worn:contains(item) then return false end
    Takeover.setRecord(item, nil)
    Appearance.clear(player, item)
    SCTD_TransmogSync.Notify(player, "restored", item)
    return true, item
end

-- SCTD's own records (placeholders, type transmogs) go to its Restore, which swaps items.
local function restoreSctd(player, item, opts)
    if not player then return sctdRestore(player, item, opts) end
    local was = busy[player]
    busy[player] = true
    -- pcall only to clear the flag; SCTD's error goes on as without this feature
    local ok, done, restored = pcall(sctdRestore, player, item, opts)
    busy[player] = was
    if not ok then error(done) end
    return done, restored
end

local function isOurs(player, item)
    return player ~= nil and item ~= nil and Takeover.ourRecord(item) ~= nil
end

local function restore(player, item, opts)
    local ok, ours, trace = pcall(isOurs, player, item)
    if not ok then
        fail("restoring an item", ours, trace)
        return false
    end
    if not ours then return restoreSctd(player, item, opts) end
    local done, r1, r2 = pcall(restoreOurs, player, item)
    if done then return r1, r2 end
    fail("restoring " .. tostring(item:getFullType()), r1, r2)
    return false
end

local function hasOurLook(item)
    return item ~= nil and Takeover.ourRecord(item) ~= nil and Appearance.get(item) ~= nil
end

local function clearLeftLook(player, item)
    if not Takeover.ourRecord(item) and Appearance.get(item) ~= nil then Appearance.clear(player, item) end
end

-- When SCTD's own Restore clears this feature's record (the takeover stopped), the look goes with it, so an item
-- never stays hidden with no way to show it.
local function restoreWrapper(player, item, opts)
    local watched = false
    if not wrapperStopped then
        local ok, result, trace = pcall(hasOurLook, item)
        if ok then watched = result else failWrapper("checking an item before it is restored", result, trace) end
    end
    local done, restored = restoreImpl(player, item, opts)
    if watched and done and not wrapperStopped then
        local ok, err, trace = pcall(clearLeftLook, player, item)
        if not ok then failWrapper("clearing the look of an item SCTD restored", err, trace) end
    end
    return done, restored
end

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

local function reconcileWorn(player)
    if player:isDead() then return end
    local shouldHide = SCTD_WearsBodyForm(player) and SCTD_ClothingAppearance.IsAutoHideNonSct(player)
    local worn = player:getWornItems()
    if not worn then return end
    local targets = {}
    if shouldHide then
        for i = 0, worn:size() - 1 do
            local item = worn:getItemByIndex(i)
            if canAutoHide(player, item) then targets[#targets + 1] = item end
        end
        for _, item in ipairs(targets) do
            -- a failed step put SCTD's own functions back, which would swap the rest
            if stopped then return end
            SCTD_Transmog_HideItem_Server(player, item, { autoHidden = true, deferSync = true })
        end
        return
    end
    -- ours, and SCTD's placeholders from before
    for i = 0, worn:size() - 1 do
        local item = worn:getItemByIndex(i)
        local data = item and SCTD_Transmog_GetData(item)
        if data and data.autoHidden then targets[#targets + 1] = item end
    end
    local swapped = false
    for _, item in ipairs(targets) do
        if stopped then break end
        local placeholder = SCTD_Transmog_IsInvisVariant(item:getFullType())
        if SCTD_Transmog_Restore(player, item, { deferSync = true }) and placeholder then swapped = true end
    end
    if swapped then SCTD_Transmog_BatchSync(player) end
end

local function reconcile(player)
    if isClient() or not player or busy[player] then return end
    busy[player] = true
    reconcileWorn(player)
    busy[player] = nil
end

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
    if module ~= Takeover.MODULE or stopped or (command ~= "lookSkin" and command ~= "lookColor") then return end
    local ok, err, trace = pcall(editLook, player, command, args)
    if not ok then fail("taking a look edit from a client", err, trace) end
end

local function installAuthoritative()
    if not sctdRestore then error("SCTD_Transmog_Restore is not wrapped") end
    replace(_G, "SCTD_Transmog_HideItem_Server",
        guard("hiding an item", hideItemServer, SCTD_Transmog_HideItem_Server, refuse))
    replace(_G, "SCTD_Transmog_Apply", guard("transmogging an item", apply, SCTD_Transmog_Apply, refuse))
    replace(_G, "SCTD_AutoHide_Reconcile", guard("hiding or showing clothing for a body form", reconcile,
        SCTD_AutoHide_Reconcile, function() end))
    restoreImpl = restore
    if isServer() then Events.OnClientCommand.Add(onClientCommand) end
end

-- UI part: multiplayer client and single player ---------------------------------------------------------------------

-- SCTD's panel functions follow, with this feature's hidden state for SCTD's placeholder type check.

local function matchesBaseFilter(panel, item)
    if panel.baseFilter == "transmogged" then
        return SCTD_Transmog_IsTransmogged(item) and not Takeover.isHidden(item)
    end
    if panel.baseFilter == "hidden" then return Takeover.isHidden(item) end
    return true
end

local function drawHideButton(list, y, item, player)
    if not item then return list:getWidth() - 20 * (list._sctdTextZoom or 1) end
    local x, by, w, h = SCTD_Transmog_HideBounds(list, y)
    local A = SCTD_ClothingAppearance
    local forbidden = A and A.IsForbidden(player, item)
    local hidden = Takeover.isHidden(item)
    local text = getText(forbidden and "IGUI_SCTD_SkinPanel_Forbidden"
        or (hidden and "IGUI_SCTD_SkinPanel_Show" or "IGUI_SCTD_SkinPanel_Hide"))
    local mx, my = list:getMouseX(), list:getMouseY()
    local hover = list:isMouseOver() and not list:isMouseOverScrollBar() and mx >= x and mx < x + w and my >= by
        and my < by + h
    local texture = SCTD_UITheme.Texture("media/ui_images/04.png")
    if texture then list:drawTextureScaled(texture, x, by, w, h, hover and 1 or 0.85, 1, 1, 1) end
    if hover and not forbidden then list:drawRect(x, by, w, h, 0.25, 0.2, 0.75, 0.8) end
    SCTD_UITheme.StatusBorder(list, x, by, w, h, 1, forbidden and 0.4 or (hidden and 1 or 0.6),
        forbidden and 0.4 or (hidden and 0.8 or 0.3), forbidden and 0.4 or 0.2)
    local tm, scale = getTextManager(), list._sctdTextZoom or 1
    list:drawText(text, x + (w - tm:MeasureStringX(UIFont.Small, text) * scale) / 2,
        by + (h - tm:MeasureStringY(UIFont.Small, text) * scale) / 2, 0.95, 0.95, 0.95, 1, UIFont.Small)
    return x - 6 * scale
end

local function clickHide(panel, list, row, x, y)
    if list:isMouseOverScrollBar() then return false end
    local item = list.items[row] and list.items[row].item
    if not item then return false end
    local bx, by, w, h = SCTD_Transmog_HideBounds(list, list:topOfItem(row))
    if x < bx or x >= bx + w or y < by or y >= by + h then return false end
    local A = SCTD_ClothingAppearance
    if A and A.IsForbidden(panel.player, item) then return true end
    playSCTDSound(SCTD_SOUND_CLICK)
    if Takeover.isHidden(item) then
        if SCTD_Transmog_ShowItem then SCTD_Transmog_ShowItem(panel.player, item) end
    else
        if SCTD_Transmog_HideItem then SCTD_Transmog_HideItem(panel.player, item) end
    end
    return true
end

-- SCTD's row of the panel's worn list; a hidden item is not marked transmogged.
local function drawBaseRow(listSelf, y, item, panel, fontHgt)
    if (y + listSelf:getYScroll() + listSelf.itemheight < 0) or (y + listSelf:getYScroll() >= listSelf.height) then
        return y + listSelf.itemheight
    end
    local wornItem = item.item
    local name = item.text or "???"
    local transmogged = wornItem and SCTD_Transmog_IsTransmogged(wornItem) and not Takeover.isHidden(wornItem)
    if transmogged then
        name = name .. " " .. getText("IGUI_SCTD_Transmog_Mark")
    end
    local uiScale = listSelf._sctdTextZoom or 1
    if listSelf.tex03 then
        listSelf:drawTextureScaled(listSelf.tex03, 0, y, listSelf:getWidth(), listSelf.itemheight, 0.8, 1, 1, 1)
    end
    if listSelf.selected == item.index then
        listSelf:drawRect(0, y, listSelf:getWidth(), listSelf.itemheight, 0.35, 0.5, 0.12, 0.12)
        listSelf:drawRectBorder(0, y, listSelf:getWidth(), listSelf.itemheight, 1, 0.9, 0.7, 0.3)
    elseif listSelf.mouseoverselected == item.index and listSelf:isMouseOver()
        and not listSelf:isMouseOverScrollBar() then
        listSelf:drawRect(0, y, listSelf:getWidth(), listSelf.itemheight, 0.25, 0.2, 0.75, 0.8)
        listSelf:drawRectBorder(0, y, listSelf:getWidth(), listSelf.itemheight, 0.8, 0.3, 0.9, 0.9)
    end
    local tr, tg, tb = 0.95, 0.95, 0.95
    if transmogged then tr, tg, tb = 0.4, 0.95, 0.85 end
    local textX = SCTD_DrawClothingListIcon(listSelf, y, wornItem)
    local textRight = SCTD_Transmog_DrawHideButton(listSelf, y, wornItem, panel.player)
    name = SCTD_ClothingListFitText(listSelf, name, textRight - textX)
    listSelf:drawText(name, textX, y + (listSelf.itemheight - fontHgt * uiScale) / 2, tr, tg, tb, 1, UIFont.Medium)
    return y + listSelf.itemheight
end

-- The row drawing is a closure SCTD builds in createChildren, so it is replaced on each new panel.
local function replaceBaseRows(panel)
    local list = panel.baseList
    local sctdDraw = list.doDrawItem
    if not Takeover.isFrom(sctdDraw, ranges.Panel_baseRowDraw) then
        error("the transmog panel's row drawing is not SCTD's reviewed one but from " .. Takeover.describe(sctdDraw))
    end
    local fontHgt = getTextManager():MeasureStringY(UIFont.Medium, "A")
    list.doDrawItem = function(listSelf, y, item, alt)
        if stopped then return sctdDraw(listSelf, y, item, alt) end
        local ok, result, trace = pcall(drawBaseRow, listSelf, y, item, panel, fontHgt)
        if ok then return result end
        fail("drawing a row of SCTD's transmog panel", result, trace)
        return sctdDraw(listSelf, y, item, alt)
    end
end

local function panelChildren(original)
    return function(self)
        original(self)
        if stopped then return end
        local ok, err, trace = pcall(replaceBaseRows, self)
        if not ok then fail("taking over SCTD's transmog panel rows", err, trace) end
    end
end

local function previewSkipped(player, item)
    local data = item and Takeover.ourRecord(item)
    if data and data.hidden then return true, true end
    return false
end

-- A transmogged item's entry in SCTD's skin panel lists the look's skins and color, or nil when it has neither.
local function skinEntry(item, look)
    local asset = Takeover.clothingAsset(look.type)
    local itemType = string.match(look.type, "^[^%.]+%.(.+)$") or look.type
    local choices = SCTD_TextureChoicesFromAsset(asset, itemType)
    local hasSkins = choices ~= nil and #choices > 1
    local hasColorChange = asset:getAllowRandomTint() == true
    if not hasSkins and not hasColorChange then return nil end
    local index = asset:hasModel() and look.textureChoice or look.baseTexture
    local currentChoice = ""
    if choices and index >= 0 and index < #choices then currentChoice = choices[index + 1] or "" end
    local t = look.tint
    return {
        item = item,
        itemType = itemType,
        displayName = item:getDisplayName(),
        choices = choices or {},
        currentIndex = index,
        currentChoice = currentChoice,
        hasSkins = hasSkins,
        hasColorChange = hasColorChange,
        currentColor = hasColorChange and ImmutableColor.new(t[1], t[2], t[3], 1) or nil,
        hasExtras = false,
        hasSkinPair = false,
    }
end

-- Hidden items stay listed as themselves: their skin is the item's own, shown again later.
local function withLookEntries(player, list)
    local worn = player:getWornItems()
    local looks = {}
    for i = 0, worn:size() - 1 do
        local item = worn:getItemByIndex(i)
        local look = item and Takeover.lookOverride(item)
        if look then looks[item] = look end
    end
    local result, listed = {}, {}
    for _, entry in ipairs(list) do
        local look = looks[entry.item]
        if look then
            listed[entry.item] = true
            local replacement = skinEntry(entry.item, look)
            if replacement then result[#result + 1] = replacement end
        else
            result[#result + 1] = entry
        end
    end
    for i = 0, worn:size() - 1 do
        local item = worn:getItemByIndex(i)
        if item and looks[item] and not listed[item] then
            local entry = skinEntry(item, looks[item])
            if entry then result[#result + 1] = entry end
        end
    end
    return result
end

local function wornClothingWithSkins(original)
    return function(player)
        local list = original(player)
        if stopped or not player then return list end
        local ok, result, trace = pcall(withLookEntries, player, list)
        if ok then return result end
        fail("listing transmogged items' looks for SCTD's skin panel", result, trace)
        return list
    end
end

-- A multiplayer client edits its copy of the look, which the panel reads back at once, and the server's; the
-- server's copy then replaces this one.
local function sendLook(player, item, edited, command, args)
    if isClient() then
        item:getModData()[Appearance.KEY] = edited
        sendClientCommand(player, Takeover.MODULE, command, args)
    else
        Appearance.setLook(player, item, edited)
    end
end

local function changeSkin(player, item, index)
    local look = player and item and type(index) == "number" and Takeover.lookOverride(item)
    if not look then return false end
    local edited = index >= 0 and Takeover.withTexture(look, index) or nil
    if edited then sendLook(player, item, edited, "lookSkin", { itemId = item:getID(), textureIndex = index }) end
    return true, edited ~= nil
end

local function changeColor(player, item, r, g, b)
    local look = player and item and Takeover.lookOverride(item)
    if not look then return false end
    sendLook(player, item, Takeover.withTint(look, r, g, b), "lookColor",
        { itemId = item:getID(), r = r, g = g, b = b })
    return true, true
end

local function installUi()
    replace(_G, "SCTD_Transmog_MatchesBaseFilter", guard("filtering SCTD's transmog panel", matchesBaseFilter,
        SCTD_Transmog_MatchesBaseFilter, SCTD_Transmog_MatchesBaseFilter))
    replace(_G, "SCTD_Transmog_DrawHideButton", guard("drawing SCTD's hide button", drawHideButton,
        SCTD_Transmog_DrawHideButton, SCTD_Transmog_DrawHideButton))
    replace(_G, "SCTD_Transmog_ClickHide", guard("clicking SCTD's hide button", clickHide, SCTD_Transmog_ClickHide,
        refuse))
    replace(_G, "SCTD_IsPreviewSkipped", takeOrPass("checking an item for SCTD's previews", previewSkipped,
        SCTD_IsPreviewSkipped, SCTD_IsPreviewSkipped))
    replace(_G, "SCTD_GetWornClothingWithSkins", wornClothingWithSkins(SCTD_GetWornClothingWithSkins))
    replace(_G, "SCTD_ChangeSkin", takeOrPass("changing a look's skin", changeSkin, SCTD_ChangeSkin, refuse))
    replace(_G, "SCTD_ChangeColor", takeOrPass("changing a look's color", changeColor, SCTD_ChangeColor, refuse))
    replace(SCTD_TransmogPanel, "createChildren", panelChildren(SCTD_TransmogPanel.createChildren))
end

-- Gate and install ----------------------------------------------------------------------------------------------------

local function sides()
    if isServer() then return "server" end
    if isClient() then return "client" end
    return "single player"
end

local function install()
    if not isClient() then installAuthoritative() end
    if not isServer() then installUi() end
end

local checked, why, found = pcall(Takeover.check, true)
if not checked then
    stopped = true
    Takeover.logError("disabled after an error while checking SCTD's code: " .. tostring(why) .. "\n"
        .. tostring(found))
elseif why then
    stopped = true
    Takeover.log("idle: " .. why)
end

-- Also when the check failed: items this feature changed in an earlier session must stay restorable.
if not isClient() and type(SCTD_Transmog_Restore) == "function" and not Appearance.problem() then
    sctdRestore = SCTD_Transmog_Restore
    restoreImpl = sctdRestore
    SCTD_Transmog_Restore = restoreWrapper
end

if not stopped then
    ranges = found
    local ok, err, trace = pcall(install)
    if ok then
        Takeover.log("ready (" .. sides() .. ")")
    else
        fail("taking over SCTD's hide and transmog", err, trace)
    end
end
