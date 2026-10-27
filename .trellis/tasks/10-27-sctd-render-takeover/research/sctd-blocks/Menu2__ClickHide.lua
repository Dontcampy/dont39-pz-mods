function SCTD_Transmog_ClickHide(panel, list, row, x, y)
    if list:isMouseOverScrollBar() then return false end
    local item = list.items[row] and list.items[row].item
    if not item then return false end
    local bx, by, w, h = SCTD_Transmog_HideBounds(list, list:topOfItem(row))
    if x < bx or x >= bx + w or y < by or y >= by + h then return false end
    local A = SCTD_ClothingAppearance
    if A and A.IsForbidden(panel.player, item) then return true end
    playSCTDSound(SCTD_SOUND_CLICK)
    -- 透明服幻化隐藏：透明变体→显示（还原回原服装）；普通服装→隐藏（转为对应部位透明变体）。
    -- 客户端只发命令，服务器权威转换并广播，面板经完成通知自动刷新与重选中。
    if SCTD_Transmog_IsInvisVariant and SCTD_Transmog_IsInvisVariant(item:getFullType()) then
        if SCTD_Transmog_ShowItem then SCTD_Transmog_ShowItem(panel.player, item) end
    else
        if SCTD_Transmog_HideItem then SCTD_Transmog_HideItem(panel.player, item) end
    end
    return true
end