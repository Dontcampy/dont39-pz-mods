-- Dont39 All-in-One: sctd-render-takeover, the parts its files share: the record it writes under SCTD's transmog key,
-- the busy flag, worn items and looks, and the check of the SCTD code it replaces or relies on.

local Appearance = require "Dont39AllInOne_AppearanceOverrideShared"
local Common = require "Dont39AllInOne_CommonShared"
local SourceGate = require "Dont39AllInOne_SctdRenderTakeoverSourceGate"

local Takeover = {}

Takeover.MOD_ID = "ShadowCatTacticsDispatch"
Takeover.MODULE = "Dont39AllInOne_SctdRenderTakeover" -- the look edits clients send
Takeover.PREFIX = "[Dont39AllInOne] sctd-render-takeover: "

function Takeover.log(message)
    Common.log(Takeover.PREFIX, message)
end

function Takeover.logError(message)
    Common.logError(Takeover.PREFIX, message)
end

Takeover.digits = SourceGate.digits

-- An error caught to undo something, raised again with the stack where it was first raised.
function Takeover.rethrow(err, trace)
    error(tostring(err) .. "\n" .. tostring(trace))
end

-- Record ----------------------------------------------------------------------------------------------------------

-- SCTD's own key, so its readers see the item as transmogged or hidden. Without baseFullType, srcFullType or
-- baseStats its code never swaps or re-stats the item, and its Restore only clears the record.
local KEY = "SCTD_Transmog"
local FORMAT = 1
Takeover.KEY = KEY

function Takeover.newRecord(hidden, autoHidden, srcDisplayName)
    return { Dont39AllInOne = FORMAT, hidden = hidden or nil, autoHidden = autoHidden or nil,
        srcDisplayName = srcDisplayName }
end

-- The item's record when this feature wrote it, else nil.
function Takeover.ourRecord(item)
    if not item:hasModData() then return nil end
    local data = item:getModData()[KEY]
    if type(data) == "table" and data.Dont39AllInOne == FORMAT then return data end
    return nil
end

-- [record] nil clears it
function Takeover.setRecord(item, record)
    item:getModData()[KEY] = record
end

-- The look override of an item this feature transmogged, else nil.
function Takeover.lookOverride(item)
    if not Takeover.ourRecord(item) then return nil end
    local override = Appearance.get(item)
    if type(override) == "table" and override.type ~= nil then return override end
    return nil
end

-- Busy ------------------------------------------------------------------------------------------------------------

-- players for whom auto-hide or the migration runs or SCTD swaps an item: auto-hide must not run inside any of them.
-- whileBusy puts each entry back, so the table holds no player after the call.
local busy = {}

function Takeover.isBusy(player)
    return busy[player] == true
end

-- [fn](...) with [player] marked busy; the mark is put back as it was, also when [fn] throws.
function Takeover.whileBusy(player, fn, ...)
    local was = busy[player]
    busy[player] = true
    local ok, r1, r2 = pcall(fn, ...)
    busy[player] = was
    if not ok then Takeover.rethrow(r1, r2) end
    return r1, r2
end

-- Worn items ------------------------------------------------------------------------------------------------------

function Takeover.realWorn(player)
    -- SCTD swaps the worn list for display copies while it draws
    if SCTD_ClothingAppearance and SCTD_ClothingAppearance.Restore then SCTD_ClothingAppearance.Restore() end
    return player:getWornItems()
end

-- Off the body; the item stays in its container.
function Takeover.takeOff(player, item)
    player:removeFromHands(item)
    player:removeWornItem(item, false)
    -- removeWornItem leaves an item in a multi-item slot (belt) worn
    local worn = player:getWornItems()
    if worn:contains(item) then worn:remove(item) end
end

-- [item] is already in the player's inventory. The clothing packet carries the whole worn list.
function Takeover.wear(player, location, item)
    player:removeFromHands(item)
    player:setWornItem(location, item, false)
    sendClothing(player, location, item)
end

-- Look ------------------------------------------------------------------------------------------------------------

-- [item]'s look as it is drawn now, or nil and why the appearance override cannot draw it: stored data, not a
-- mismatch.
function Takeover.drawableLook(item)
    local look = Appearance.lookOf(item)
    local problem = Dont39AllInOne_checkAppearanceOverride(look)
    if problem then return nil, problem end
    return look
end

function Takeover.clothingAsset(fullType)
    local script = ScriptManager.instance:FindItem(fullType)
    local asset = script and script:getClothingItemAsset()
    if not asset then error(fullType .. " has no clothing item") end
    return asset
end

-- The look with SCTD's skin index applied as its skin panel applies it, or nil when the index fits neither list.
function Takeover.withTexture(look, index)
    local asset = Takeover.clothingAsset(look.type)
    local edited, changed = Appearance.copy(look), false
    if index < asset:getTextureChoices():size() then
        edited.textureChoice = index
        changed = true
    end
    if index < asset:getBaseTextures():size() then
        edited.baseTexture = index
        changed = true
    end
    return changed and edited or nil
end

function Takeover.withTint(look, r, g, b)
    local edited = Appearance.copy(look)
    edited.tint = { r, g, b, 1 }
    return edited
end

-- Check of SCTD's code ----------------------------------------------------------------------------------------------

-- relative to the mod's folder; the game also looks in its common folder
Takeover.FILES = {
    Skins = "media/lua/shared/LingLi_SCTD_Skins.lua",
    Srv = "media/lua/server/SCTD_Craft_Server.lua",
    Menu = "media/lua/client/LingLiandLightja_SCTD_Menu.lua",
    Menu2 = "media/lua/client/LingLi_SCTD_Menu_2.lua",
}

-- The reviewed texts are in the archived tasks' research/sctd-blocks/. Format: SourceGate.new.
Takeover.BLOCKS = {
    { name = "HideItem_Server", file = "Skins", global = "SCTD_Transmog_HideItem_Server",
        header = "function SCTD_Transmog_HideItem_Server(player, item, opts)",
        length = 4444, hash = { 939850532, 2047066782 } },
    { name = "Apply", file = "Skins", global = "SCTD_Transmog_Apply",
        header = "function SCTD_Transmog_Apply(player, baseItem, sourceItem, ignoreBodyLocation, invertStats)",
        length = 6268, hash = { 1799431547, 1086661073 } },
    { name = "Restore", file = "Skins", global = "SCTD_Transmog_Restore",
        header = "function SCTD_Transmog_Restore(player, item, opts)",
        length = 6343, hash = { 1369967614, 1731642324 } },
    { name = "AutoHide_Reconcile", file = "Skins", global = "SCTD_AutoHide_Reconcile",
        header = "function SCTD_AutoHide_Reconcile(player)",
        length = 2123, hash = { 1251864049, 673404584 } },
    { name = "AutoHide_CanAutoHide", file = "Skins",
        header = "local function SCTD_AutoHide_CanAutoHide(player, item)",
        length = 844, hash = { 345461405, 1510726122 } },
    { name = "IsPreviewSkipped", file = "Skins", global = "SCTD_IsPreviewSkipped",
        header = "function SCTD_IsPreviewSkipped(player, item)",
        length = 467, hash = { 724927807, 1268935629 } },
    { name = "GetWornClothingWithSkins", file = "Skins", global = "SCTD_GetWornClothingWithSkins",
        header = "function SCTD_GetWornClothingWithSkins(player)",
        length = 6733, hash = { 1475454722, 1812788617 } },
    { name = "ChangeSkin", file = "Skins", global = "SCTD_ChangeSkin",
        header = "function SCTD_ChangeSkin(player, clothingItem, textureIndex)",
        length = 5502, hash = { 857978317, 1760956822 } },
    { name = "ChangeColor", file = "Skins", global = "SCTD_ChangeColor",
        header = "function SCTD_ChangeColor(player, clothingItem, r, g, b)",
        length = 854, hash = { 1148573922, 869766575 } },
    { name = "HideItem", file = "Skins", global = "SCTD_Transmog_HideItem",
        header = "function SCTD_Transmog_HideItem(player, item)",
        length = 580, hash = { 1015370992, 1319428102 } },
    { name = "ShowItem", file = "Skins", global = "SCTD_Transmog_ShowItem",
        header = "function SCTD_Transmog_ShowItem(player, item)",
        length = 753, hash = { 1224933933, 472652072 } },
    { name = "Sync_Notify", file = "Skins", global = "SCTD_TransmogSync.Notify",
        header = "function SCTD_TransmogSync.Notify(player, command, item, stats)",
        length = 490, hash = { 856336182, 1746590533 } },
    { name = "Sync_FlushPlayer", file = "Skins", global = "SCTD_TransmogSync.FlushPlayer",
        header = "function SCTD_TransmogSync.FlushPlayer(player)",
        length = 1398, hash = { 1369020312, 1036445459 } },
    { name = "IsTransmogged", file = "Skins", global = "SCTD_Transmog_IsTransmogged",
        header = "function SCTD_Transmog_IsTransmogged(item)",
        length = 231, hash = { 173826333, 698899928 } },
    { name = "GetSavedBodyLocation", file = "Skins", global = "SCTD_Transmog_GetSavedBodyLocation",
        header = "function SCTD_Transmog_GetSavedBodyLocation(item)",
        length = 1025, hash = { 1829717677, 265823001 } },
    { name = "ResnapContainer", file = "Skins",
        header = "local function SCTD_Transmog_ResnapContainer(container)",
        length = 904, hash = { 970013252, 1177894253 } },
    { name = "RepairWornSlots", file = "Skins", global = "SCTD_Transmog_RepairWornSlots",
        header = "function SCTD_Transmog_RepairWornSlots(player)",
        length = 2555, hash = { 1875794251, 672034853 } },
    { name = "onTransmogCommand", file = "Srv",
        header = "local function onTransmogCommand(module, command, player, args)",
        length = 1914, hash = { 1576289321, 1107265146 } },
    { name = "MatchesBaseFilter", file = "Menu2", global = "SCTD_Transmog_MatchesBaseFilter", client = true,
        header = "function SCTD_Transmog_MatchesBaseFilter(panel, item)",
        length = 525, hash = { 432598873, 1819549233 } },
    { name = "DrawHideButton", file = "Menu2", global = "SCTD_Transmog_DrawHideButton", client = true,
        header = "function SCTD_Transmog_DrawHideButton(list, y, item, player)",
        length = 1537, hash = { 137218000, 529737766 } },
    { name = "ClickHide", file = "Menu2", global = "SCTD_Transmog_ClickHide", client = true,
        header = "function SCTD_Transmog_ClickHide(panel, list, row, x, y)",
        length = 902, hash = { 266774575, 667409843 } },
    { name = "Panel_createChildren", file = "Menu", global = "SCTD_TransmogPanel.createChildren", client = true,
        header = "function SCTD_TransmogPanel:createChildren()",
        length = 9508, hash = { 1984729657, 2143294369 } },
    -- inside createChildren; checked on each new panel
    { name = "Panel_baseRowDraw", file = "Menu",
        header = "    self.baseList.doDrawItem = function(listSelf, y, item, alt)",
        length = 2107, hash = { 554748814, 1948706242 } },
    -- the migration copies these or mirrors their item moves
    { name = "CopyState", file = "Skins",
        header = "local function SCTD_Transmog_CopyState(fromItem, toItem, skipTextureIndices, skipContainerContents)",
        length = 2840, hash = { 169308000, 1121978666 } },
    { name = "ApplyAuthoritativeTint", file = "Skins",
        header = "local function applyAuthoritativeTint(item, r, g, b)",
        length = 764, hash = { 710792078, 1543788658 } },
    { name = "EmptyContainer", file = "Skins", global = "SCTD_Transmog_EmptyContainer",
        header = "function SCTD_Transmog_EmptyContainer(player, item)",
        length = 555, hash = { 213486501, 1339205753 } },
    { name = "IsInvisVariant", file = "Skins", global = "SCTD_Transmog_IsInvisVariant",
        header = "function SCTD_Transmog_IsInvisVariant(fullType)",
        length = 175, hash = { 196783585, 603382642 } },
    { name = "RemoveWorn", file = "Skins",
        header = "local function SCTD_Transmog_RemoveWorn(player, item)",
        length = 360, hash = { 1152657041, 471707537 } },
    { name = "WearNoSync", file = "Skins",
        header = "local function SCTD_Transmog_WearNoSync(player, item, bodyLocation)",
        length = 293, hash = { 1523763947, 195829436 } },
}

-- Lengths and hashes are over Kahlua's strings: BlockHashes.java (archived task 10-27-sctd-render-takeover,
-- research/sctd-blocks/) runs this gate in the game's Kahlua to give them.
Takeover.gate = SourceGate.new(Takeover.MOD_ID, Takeover.FILES, Takeover.BLOCKS)

-- Whether SCTD's code is the reviewed version. Returns why there is nothing to do, or nil and the blocks' ranges by
-- name. Throws when anything does not match. [identity]: also check that the live globals are SCTD's, once all of
-- its Lua has loaded.
function Takeover.check(identity)
    local info = getModInfoByID(Takeover.MOD_ID)
    if not info or not isModActive(info) then return Takeover.MOD_ID .. " is not enabled" end
    local problem = Appearance.problem()
    if problem then error("appearance-override: " .. problem) end
    return nil, Takeover.gate.check(identity)
end

return Takeover
