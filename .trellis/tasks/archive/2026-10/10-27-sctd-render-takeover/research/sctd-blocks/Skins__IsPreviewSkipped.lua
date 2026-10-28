function SCTD_IsPreviewSkipped(player, item)
    if not item then return true end
    local fullType = item:getFullType()
    if SCTD_IsWoundOverlay(fullType) then return true end
    if SCTD_IsBandageOverlay(fullType) and (SCTD_ClothingAppearance.IsBandageHidden(player) or SCTD_WearsBodyForm(player)) then return true end
    -- 透明变体（隐藏后的服装）在三个预览(3D角色/头发/3D服装)里同样跳过，与游戏内实际显示一致。
    if SCTD_Transmog_IsInvisVariant(fullType) then return true end
    return false
end