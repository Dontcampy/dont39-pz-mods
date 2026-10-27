function SCTD_TransmogPanel:createChildren()
    ISCollapsableWindow.createChildren(self)
    local tm = getTextManager()
    local titleBarHgt = self:titleBarHeight()
    local fontHgt = tm:MeasureStringY(UIFont.Medium, "A")
    local btnH = 24
    local colW = (self.width - 30) / 2
    local listY = titleBarHgt + 64
    local listH = self.height - listY - btnH - 46
    local panelSelf = self

    -- 左列标题：胚子（身上穿着的服装）
    self.baseHeader = ISLabel:new(10, titleBarHgt + 42, 20, getText("IGUI_SCTD_Transmog_BaseHeader"), 0.9, 0.75, 0.3, 1, UIFont.Medium, false)
    self.baseHeader._sctdKeepEmbeddedNavigation = true
    self.baseHeader:initialise()
    self.baseHeader:instantiate()
    self:addChild(self.baseHeader)

    -- 右列标题：外观源（背包中同部位服装）
    self.sourceHeader = ISLabel:new(20 + colW, titleBarHgt + 42, 20, getText("IGUI_SCTD_Transmog_SourceHeader"), 0.9, 0.75, 0.3, 1, UIFont.Medium, false)
    self.sourceHeader._sctdKeepEmbeddedNavigation = true
    self.sourceHeader:initialise()
    self.sourceHeader:instantiate()
    self:addChild(self.sourceHeader)

    -- 左列表：胚子
    self.baseList = ISScrollingListBox:new(10, listY, colW, listH)
    self.baseList:initialise()
    self.baseList:instantiate()
    SCTD_UITheme.StyleScrollBar(self.baseList.vscroll)
    self.baseList.backgroundColor = {r=0.1, g=0.1, b=0.1, a=0.8}
    self.baseList.borderColor = {r=0.6, g=0.1, b=0.1, a=1}
    self.baseList.itemheight = SCTD_ClothingListRowHeight()
    self.baseList.selected = 0
    self.baseList.font = UIFont.Medium
    self.baseList.tex03 = SCTD_UITheme.Texture("media/ui_images/03.png")  -- 列表项背景图层
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
    self.baseList.onMouseWheel = function(listSelf, del)
        -- del<0 向上滚，del>0 向下滚（往下滑）
        listSelf.smoothScrollTargetY = nil
        local maxScroll = math.max(0, listSelf:getScrollHeight() - listSelf.height)
        local newScroll = listSelf:getYScroll() - del * listSelf.itemheight
        listSelf:setYScroll(math.max(-maxScroll, math.min(0, newScroll)))
        return true
    end
    self.baseList.onMouseDown = function(listSelf, x, y)
        if listSelf:isMouseOverScrollBar() then return end
        local row = listSelf:rowAt(x, y)
        if row > 0 and row <= #listSelf.items and listSelf.items[row].item then
            if SCTD_Transmog_ClickHide(panelSelf, listSelf, row, x, y) then return true end
            listSelf.selected = row
            playSCTDSound(SCTD_SOUND_CLICK)
            panelSelf._sctdSelectId = nil
            panelSelf.selectedBase = listSelf.items[row].item
            panelSelf:refreshSources()
            panelSelf:updateInfo()
        end
    end
    self:addChild(self.baseList)

    -- 右列表：外观源
    self.sourceList = ISScrollingListBox:new(20 + colW, listY, colW, listH)
    self.sourceList:initialise()
    self.sourceList:instantiate()
    SCTD_UITheme.StyleScrollBar(self.sourceList.vscroll)
    self.sourceList.backgroundColor = {r=0.1, g=0.1, b=0.1, a=0.8}
    self.sourceList.borderColor = {r=0.6, g=0.1, b=0.1, a=1}
    self.sourceList.itemheight = SCTD_ClothingListRowHeight()
    self.sourceList.selected = 0
    self.sourceList.font = UIFont.Medium
    self.sourceList.tex03 = SCTD_UITheme.Texture("media/ui_images/03.png")  -- 列表项背景图层
    self.sourceList.doDrawItem = function(listSelf, y, item, alt)
        -- 可见性剔除（y 为内容坐标，参照原版 doDrawItem）
        if (y + listSelf:getYScroll() + listSelf.itemheight < 0) or (y + listSelf:getYScroll() >= listSelf.height) then
            return y + listSelf.itemheight
        end
        local srcItem = item.item
        local name = item.text or "???"
        local condText = ""
        if srcItem then
            local okC, cond = pcall(function() return srcItem:getCondition() end)
            local okM, condMax = pcall(function() return srcItem:getConditionMax() end)
            if okC and cond and okM and condMax and condMax > 0 then
                condText = " (" .. tostring(cond) .. "/" .. tostring(condMax) .. ")"
            end
        end
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
        local textX = SCTD_DrawClothingListIcon(listSelf, y, srcItem)
        local uiScale = listSelf._sctdTextZoom or 1
        local text = SCTD_ClothingListFitText(listSelf, name .. condText, listSelf:getWidth() - textX - 21 * uiScale)
        listSelf:drawText(text, textX, y + (listSelf.itemheight - fontHgt * uiScale) / 2, 0.86, 0.84, 0.80, 1, UIFont.Medium)
        return y + listSelf.itemheight
    end
    self.sourceList.onMouseWheel = function(listSelf, del)
        -- del<0 向上滚，del>0 向下滚（往下滑）
        listSelf.smoothScrollTargetY = nil
        local maxScroll = math.max(0, listSelf:getScrollHeight() - listSelf.height)
        local newScroll = listSelf:getYScroll() - del * listSelf.itemheight
        listSelf:setYScroll(math.max(-maxScroll, math.min(0, newScroll)))
        return true
    end
    self.sourceList.onMouseDown = function(listSelf, x, y)
        local row = listSelf:rowAt(x, y)
        if row > 0 and row <= #listSelf.items and listSelf.items[row].item then
            listSelf.selected = row
            playSCTDSound(SCTD_SOUND_CLICK)
            panelSelf.selectedSource = listSelf.items[row].item
            panelSelf:updateInfo()
        end
    end
    self:addChild(self.sourceList)

    -- 信息栏（显示选中胚子的当前外观）
    self.infoLabel = ISLabel:new(10, self.height - btnH - 36, 20, "", 0.85, 0.85, 0.85, 1, UIFont.Medium, false)
    self.infoLabel:initialise()
    self.infoLabel:instantiate()
    self:addChild(self.infoLabel)

    -- 应用幻化 / 还原外观 按钮
    local applyText = getText("IGUI_SCTD_Transmog_Apply")
    local restoreText = getText("IGUI_SCTD_Transmog_Restore")
    local applyW = math.max(100, tm:MeasureStringX(UIFont.Medium, applyText) + 24)
    local restoreW = math.max(100, tm:MeasureStringX(UIFont.Medium, restoreText) + 24)
    self.applyBtn = SCTD_ImageButton:new(10, self.height - btnH - 8, applyW, btnH, applyText, self, function()
        panelSelf:onApplyClick()
    end)
    self.applyBtn:initialise()
    self.applyBtn:instantiate()
    self.applyBtn.font = UIFont.Medium
    self.applyBtn.borderColor = {r=0.3, g=0.8, b=0.3, a=1}
    self:addChild(self.applyBtn)

    self.restoreBtn = SCTD_ImageButton:new(10 + applyW + 10, self.height - btnH - 8, restoreW, btnH, restoreText, self, function()
        panelSelf:onRestoreClick()
    end)
    self.restoreBtn:initialise()
    self.restoreBtn:instantiate()
    self.restoreBtn.font = UIFont.Medium
    self.restoreBtn.borderColor = {r=0.9, g=0.6, b=0.2, a=1}
    self:addChild(self.restoreBtn)
    SCTD_Transmog_CreateBaseFilters(self)
    SCTD_Transmog_CreateFilterButton(self)

    self:refreshBases()
end