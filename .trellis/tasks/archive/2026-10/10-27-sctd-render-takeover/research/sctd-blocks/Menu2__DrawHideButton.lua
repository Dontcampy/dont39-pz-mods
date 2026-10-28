function SCTD_Transmog_DrawHideButton(list, y, item, player)
    if not item then return list:getWidth() - 20 * (list._sctdTextZoom or 1) end
    local x, by, w, h = SCTD_Transmog_HideBounds(list, y)
    local A = SCTD_ClothingAppearance
    local forbidden = A and A.IsForbidden(player, item)
    -- 透明服幻化隐藏：当前穿戴项若为透明变体即处于「隐藏」态（按钮为「显示」，点击还原回原服装），
    -- 否则为普通服装（按钮为「隐藏」，点击转为对应部位透明变体）。
    local hidden = SCTD_Transmog_IsInvisVariant and SCTD_Transmog_IsInvisVariant(item:getFullType()) or false
    local text = getText(forbidden and "IGUI_SCTD_SkinPanel_Forbidden" or (hidden and "IGUI_SCTD_SkinPanel_Show" or "IGUI_SCTD_SkinPanel_Hide"))
    local mx, my = list:getMouseX(), list:getMouseY()
    local hover = list:isMouseOver() and not list:isMouseOverScrollBar() and mx >= x and mx < x + w and my >= by and my < by + h
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