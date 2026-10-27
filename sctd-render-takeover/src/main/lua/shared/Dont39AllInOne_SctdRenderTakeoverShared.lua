-- Dont39 All-in-One: sctd-render-takeover, the parts its files share: the record it writes under SCTD's transmog key,
-- the look of an item, and the check of the SCTD code it replaces or relies on.

local Appearance = require "Dont39AllInOne_AppearanceOverrideShared"

local Takeover = {}

Takeover.MOD_ID = "ShadowCatTacticsDispatch"
Takeover.MODULE = "Dont39AllInOne_SctdRenderTakeover" -- the look edits clients send

local PREFIX = "[Dont39AllInOne] sctd-render-takeover: "

-- Never throws.
function Takeover.log(message)
    pcall(function() DebugType.Mod:println(PREFIX .. message) end)
end

function Takeover.logError(message)
    pcall(function() DebugType.Mod:error(PREFIX .. message) end)
end

-- Record ----------------------------------------------------------------------------------------------------------

-- SCTD's own key, so its readers see the item as transmogged or hidden. Without baseFullType, srcFullType or
-- baseStats its code never swaps or re-stats the item, and its Restore only clears the record.
local KEY = "SCTD_Transmog"
local FORMAT = 1

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

-- Hidden by this feature, or an SCTD placeholder made before it.
function Takeover.isHidden(item)
    local data = Takeover.ourRecord(item)
    if data then return data.hidden == true end
    return SCTD_Transmog_IsInvisVariant(item:getFullType())
end

-- The look override of an item this feature transmogged, else nil.
function Takeover.lookOverride(item)
    if not Takeover.ourRecord(item) then return nil end
    local override = Appearance.get(item)
    if type(override) == "table" and override.type ~= nil then return override end
    return nil
end

-- Look ------------------------------------------------------------------------------------------------------------

-- The item as it is drawn now, in the appearance override's format.
function Takeover.lookOf(item)
    local clothing = item:getClothingItem()
    local visual = item:getVisual()
    if not clothing or not visual then error(item:getFullType() .. " has no clothing visual") end
    -- the getters given the clothing item pick what is still unset, as the item's next draw would
    visual:getTextureChoice(clothing)
    visual:getBaseTexture(clothing)
    local tint = visual:getTint(clothing)
    return { type = item:getFullType(), textureChoice = visual:getTextureChoice(),
        baseTexture = visual:getBaseTexture(),
        tint = { tint:getRedFloat(), tint:getGreenFloat(), tint:getBlueFloat(), tint:getAlphaFloat() },
        decal = visual:getDecal(clothing), hue = visual:getHue(clothing) }
end

local function copyLook(look)
    local t = look.tint
    return { type = look.type, textureChoice = look.textureChoice, baseTexture = look.baseTexture,
        tint = { t[1], t[2], t[3], t[4] }, decal = look.decal, hue = look.hue }
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
    local edited, changed = copyLook(look), false
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
    local edited = copyLook(look)
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

-- Each block runs from its header line to the first line of the header's indent plus "end". The texts are in the
-- task's research/sctd-blocks/; length and hash come from cut and hash below. global: the live value must be the one
-- from this block. client: the global is in client Lua, which a dedicated server does not load.
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
}

-- Primes below 2^31: every product stays below 2^53, where Kahlua's numbers are exact. string.byte gives UTF-16
-- units in Kahlua, hence base 65536.
local P1, P2, BASE = 2147483647, 2147483629, 65536

function Takeover.hash(text)
    local a, b = 0, 0
    for i = 1, string.len(text) do
        local c = string.byte(text, i)
        a = (a * BASE + c) % P1
        b = (b * BASE + c) % P2
    end
    return a, b
end

-- Returns the block's text and its first and last line numbers.
function Takeover.cut(lines, header, path)
    local first = nil
    for n, line in ipairs(lines) do
        if line == header then
            if first then error(header .. " appears more than once in " .. path) end
            first = n
        end
    end
    if not first then error(header .. " was not found in " .. path) end
    local last = string.match(header, "^(%s*)") .. "end"
    for n = first, #lines do
        if lines[n] == last then
            local block = {}
            for i = first, n do block[#block + 1] = lines[i] end
            return table.concat(block, "\n"), first, n
        end
    end
    error(header .. " in " .. path .. " has no closing '" .. last .. "' line")
end

function Takeover.readLines(path)
    local reader = getModFileReader(Takeover.MOD_ID, path, false)
    if not reader then error(Takeover.MOD_ID .. "'s " .. path .. " was not found") end
    local lines = {}
    local ok, err = pcall(function()
        local line = reader:readLine()
        while line ~= nil do
            lines[#lines + 1] = line
            line = reader:readLine()
        end
    end)
    -- a failing close must not hide the read error
    local closed, closeErr = pcall(function() reader:close() end)
    if not ok then error(err) end
    if not closed then error(closeErr) end
    return lines
end

-- Kahlua prints large whole numbers in exponent form.
local function digits(n)
    if n < 100000 then return tostring(n) end
    local low = n % 100000
    return digits((n - low) / 100000) .. string.sub(tostring(100000 + low), 2)
end
Takeover.digits = digits

local function resolve(path)
    local value = _G
    for part in string.gmatch(path, "[^%.]+") do
        if type(value) ~= "table" then return nil end
        value = value[part]
    end
    return value
end

-- Whether [fn] is defined inside [range] (a block found by check).
function Takeover.isFrom(fn, range)
    if type(fn) ~= "function" then return false end
    local name = string.lower(tostring(getFilenameOfClosure(fn)))
    local file = string.lower(string.match(range.path, "([^/]+)$"))
    local line = getFirstLineOfClosure(fn)
    return string.sub(name, -string.len(file)) == file and line >= range.first and line <= range.last
end

function Takeover.describe(fn)
    if type(fn) ~= "function" then return tostring(fn) end
    return tostring(getFilenameOfClosure(fn)) .. ":" .. tostring(getFirstLineOfClosure(fn))
end

-- Whether SCTD's code is the reviewed version. Returns why there is nothing to do, or nil and the blocks' ranges by
-- name. Throws when anything does not match. [identity]: also check that the live globals are SCTD's, once all of
-- its Lua has loaded.
function Takeover.check(identity)
    local info = getModInfoByID(Takeover.MOD_ID)
    if not info or not isModActive(info) then return Takeover.MOD_ID .. " is not enabled" end
    local problem = Appearance.problem()
    if problem then error("appearance-override: " .. problem) end
    local files, ranges = {}, {}
    for _, block in ipairs(Takeover.BLOCKS) do
        local path = Takeover.FILES[block.file]
        files[path] = files[path] or Takeover.readLines(path)
        local text, first, last = Takeover.cut(files[path], block.header, path)
        local a, b = Takeover.hash(text)
        if string.len(text) ~= block.length or a ~= block.hash[1] or b ~= block.hash[2] then
            error(block.name .. " at " .. path .. ":" .. digits(first) .. " is not the reviewed version (found length "
                .. digits(string.len(text)) .. ", hash " .. digits(a) .. ", " .. digits(b) .. ")")
        end
        local range = { path = path, first = first, last = last }
        if identity and block.global and not (block.client and isServer()) then
            local live = resolve(block.global)
            if type(live) ~= "function" then error(block.global .. " was not found") end
            if not Takeover.isFrom(live, range) then
                error(block.global .. " is not the one at " .. path .. ":" .. digits(first) .. " but from "
                    .. Takeover.describe(live))
            end
        end
        ranges[block.name] = range
    end
    return nil, ranges
end

return Takeover
