-- Dont39 All-in-One: sctd-render-takeover in SCTD's panels (multiplayer client, single player): items this feature
-- hides show as hidden, and its transmogged items' skins and colors edit the look.

local Appearance = require "Dont39AllInOne_AppearanceOverrideShared"
local SourceGate = require "Dont39AllInOne_SctdRenderTakeoverSourceGate"
local Takeover = require "Dont39AllInOne_SctdRenderTakeoverShared"
local Runtime = require "Dont39AllInOne_SctdRenderTakeoverRuntime"

local Ui = {}

local ranges = nil -- the checked blocks of SCTD's code, by name

-- Hidden state -------------------------------------------------------------------------------------------------------

-- SCTD's panel tells a hidden item by its placeholder type. For the item a call is about, the check answers true when
-- this feature hides it; anything else gets SCTD's own answer.
local function hiddenTypeOf(item)
    local data = item and Takeover.ourRecord(item)
    if data and data.hidden then return item:getFullType() end
    return nil
end

-- [fn](...) with SCTD's placeholder check answering true for [fullType]; put back afterwards, also when [fn] throws,
-- unless something else replaced it in the meantime. A wrapper another mod built on it then keeps only SCTD's answer.
local function asHidden(fullType, fn, a, b, c, d, e)
    local sctd = SCTD_Transmog_IsInvisVariant
    local during = true
    local answer = function(other)
        if during and other == fullType then return true end
        return sctd(other)
    end
    SCTD_Transmog_IsInvisVariant = answer
    local ok, r1, r2 = pcall(fn, a, b, c, d, e)
    during = false
    if SCTD_Transmog_IsInvisVariant == answer then SCTD_Transmog_IsInvisVariant = sctd end
    if not ok then Takeover.rethrow(r1, r2) end
    return r1, r2
end

local function hiddenTypeIn(itemOf, a, b, c, d, e)
    return hiddenTypeOf(itemOf(a, b, c, d, e))
end

-- SCTD's [original], with the hidden state of the item [itemOf](...) picks; SCTD's own errors go on as without this
-- feature. An error finding the item stops the takeover, and [onError] answers that call.
local function hiddenAware(what, original, itemOf, onError)
    return function(a, b, c, d, e)
        if Runtime.stopped() then return original(a, b, c, d, e) end
        local ok, fullType, trace = pcall(hiddenTypeIn, itemOf, a, b, c, d, e)
        if not ok then
            Runtime.fail(what, fullType, trace)
            return onError(a, b, c, d, e)
        end
        if not fullType then return original(a, b, c, d, e) end
        return asHidden(fullType, original, a, b, c, d, e)
    end
end

local function filterItem(_, item)
    return item
end

local function buttonItem(_, _, item)
    return item
end

local function clickedItem(_, list, row)
    return list.items[row] and list.items[row].item
end

local function rowItem(_, _, item)
    return item and item.item
end

-- The row drawing is a closure SCTD builds in createChildren, so it is wrapped on each new panel.
local function wrapBaseRows(panel)
    local list = panel.baseList
    local sctdDraw = list.doDrawItem
    if not SourceGate.isFrom(sctdDraw, ranges.Panel_baseRowDraw) then
        error("the transmog panel's row drawing is not SCTD's reviewed one but from " .. SourceGate.describe(sctdDraw))
    end
    list.doDrawItem = hiddenAware("drawing a row of SCTD's transmog panel", sctdDraw, rowItem, sctdDraw)
end

local function panelChildren(original)
    return function(self)
        original(self)
        if Runtime.stopped() then return end
        local ok, err, trace = pcall(wrapBaseRows, self)
        if not ok then Runtime.fail("taking over SCTD's transmog panel rows", err, trace) end
    end
end

local function previewSkipped(player, item)
    local data = item and Takeover.ourRecord(item)
    if data and data.hidden then return true, true end
    return false
end

-- Skin panel ----------------------------------------------------------------------------------------------------------

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
        if Runtime.stopped() or not player then return list end
        local ok, result, trace = pcall(withLookEntries, player, list)
        if ok then return result end
        Runtime.fail("listing transmogged items' looks for SCTD's skin panel", result, trace)
        return list
    end
end

-- A multiplayer client edits its copy of the look, which the panel reads back at once, and the server's; the
-- server's copy then replaces this one.
local function sendLook(player, item, edited, command, args)
    if isClient() then
        Appearance.write(item, edited)
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

-- [checked]: the blocks' ranges by name, from the gate.
function Ui.install(checked)
    ranges = checked
    Runtime.replace(_G, "SCTD_Transmog_MatchesBaseFilter", hiddenAware("filtering SCTD's transmog panel",
        SCTD_Transmog_MatchesBaseFilter, filterItem, SCTD_Transmog_MatchesBaseFilter))
    Runtime.replace(_G, "SCTD_Transmog_DrawHideButton", hiddenAware("drawing SCTD's hide button",
        SCTD_Transmog_DrawHideButton, buttonItem, SCTD_Transmog_DrawHideButton))
    Runtime.replace(_G, "SCTD_Transmog_ClickHide", hiddenAware("clicking SCTD's hide button",
        SCTD_Transmog_ClickHide, clickedItem, Runtime.refuse))
    Runtime.replace(_G, "SCTD_IsPreviewSkipped", Runtime.takeOrPass("checking an item for SCTD's previews",
        previewSkipped, SCTD_IsPreviewSkipped, SCTD_IsPreviewSkipped))
    Runtime.replace(_G, "SCTD_GetWornClothingWithSkins", wornClothingWithSkins(SCTD_GetWornClothingWithSkins))
    Runtime.replace(_G, "SCTD_ChangeSkin", Runtime.takeOrPass("changing a look's skin", changeSkin, SCTD_ChangeSkin,
        Runtime.refuse))
    Runtime.replace(_G, "SCTD_ChangeColor", Runtime.takeOrPass("changing a look's color", changeColor,
        SCTD_ChangeColor, Runtime.refuse))
    Runtime.replace(SCTD_TransmogPanel, "createChildren", panelChildren(SCTD_TransmogPanel.createChildren))
end

return Ui
