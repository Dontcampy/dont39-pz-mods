local function SCTD_AutoHide_CanAutoHide(player, item)
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