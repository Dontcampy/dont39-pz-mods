    self.baseList.doDrawItem = function(listSelf, y, item, alt)
        -- 可见性剔除（y 为内容坐标，参照原版 doDrawItem）
        if (y + listSelf:getYScroll() + listSelf.itemheight < 0) or (y + listSelf:getYScroll() >= listSelf.height) then
            return y + listSelf.itemheight
        end
        local wornItem = item.item
        local name = item.text or "???"
        -- 隐藏按键生成的透明变体同样带幻化记录，但属于「隐藏」态，不标「已幻化」。
        local transmogged = wornItem and SCTD_Transmog_IsTransmogged and SCTD_Transmog_IsTransmogged(wornItem)
            and not (SCTD_Transmog_IsInvisVariant and SCTD_Transmog_IsInvisVariant(wornItem:getFullType()))
        if transmogged then
            name = name .. " " .. getText("IGUI_SCTD_Transmog_Mark")
        end
        local uiScale = listSelf._sctdTextZoom or 1
        -- 普通图层
        if listSelf.tex03 then
            listSelf:drawTextureScaled(listSelf.tex03, 0, y, listSelf:getWidth(), listSelf.itemheight, 0.8, 1, 1, 1)
        end
        -- 点中效果
        if listSelf.selected == item.index then
            listSelf:drawRect(0, y, listSelf:getWidth(), listSelf.itemheight, 0.35, 0.5, 0.12, 0.12)
            listSelf:drawRectBorder(0, y, listSelf:getWidth(), listSelf.itemheight, 1, 0.9, 0.7, 0.3)
        -- 悬停效果（青色科技风）
        elseif listSelf.mouseoverselected == item.index and listSelf:isMouseOver() and not listSelf:isMouseOverScrollBar() then
            listSelf:drawRect(0, y, listSelf:getWidth(), listSelf.itemheight, 0.25, 0.2, 0.75, 0.8)
            listSelf:drawRectBorder(0, y, listSelf:getWidth(), listSelf.itemheight, 0.8, 0.3, 0.9, 0.9)
        end
        local tr, tg, tb = 0.95, 0.95, 0.95
        if transmogged then tr, tg, tb = 0.4, 0.95, 0.85 end
        local textX = SCTD_DrawClothingListIcon(listSelf, y, wornItem)
        local textRight = SCTD_Transmog_DrawHideButton(listSelf, y, wornItem, panelSelf.player)
        name = SCTD_ClothingListFitText(listSelf, name, textRight - textX)
        listSelf:drawText(name, textX, y + (listSelf.itemheight - fontHgt * uiScale) / 2, tr, tg, tb, 1, UIFont.Medium)
        return y + listSelf.itemheight
    end