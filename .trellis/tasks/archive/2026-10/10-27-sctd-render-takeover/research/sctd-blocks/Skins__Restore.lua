function SCTD_Transmog_Restore(player, item, opts)
    if not player or not item then return false end
    if SCTD_ClothingAppearance and SCTD_ClothingAppearance.Restore then SCTD_ClothingAppearance.Restore() end
    -- 与面板保持一致，只还原本人当前穿戴的物品；失效或重复请求不得生成返还物。
    local worn = player:getWornItems()
    if not worn or not worn:contains(item) then return false end
    local okMD, md = pcall(function() return item:getModData() end)
    if not okMD or not md then return false end
    local data = md.SCTD_Transmog
    if not data then return false end

    -- MP 纯客户端：发命令让服务器权威还原（理由同 SCTD_Transmog_Apply）
    if isClient() then
        local itemId = nil
        pcall(function() itemId = item:getID() end)
        if itemId then
            sendClientCommand(player, SCTD_TRANSMOG_MODULE, "restore", { itemId = itemId })
        end
        return false
    end

    -- 反转属性记录的还原：类型从未转换，原地回写胚子原属性、清记录并返还外观源。
    if data.inverted == true then
        if data.origStats then SCTD_Transmog_ApplyStats(item, data.origStats) end
        md.SCTD_Transmog = nil
        SCTD_Transmog_RefreshSlot(item)
        local curLoc = nil
        pcall(function() curLoc = worn:getLocation(item) end)
        if curLoc then pcall(function() sendClothing(player, curLoc, item) end) end
        pcall(function() player:resetModelNextFrame() end)
        triggerEvent("OnClothingUpdated", player)
        syncVisuals(player)
        if data.srcFullType and data.srcFullType ~= "" then
            pcall(function()
                local srcItem = instanceItem(data.srcFullType)
                if srcItem then
                    if data.srcCondition then pcall(function() srcItem:setConditionNoSound(data.srcCondition) end) end
                    if data.srcFavorite then pcall(function() srcItem:setFavorite(true) end) end
                    local inv = player:getInventory()
                    if inv then
                        inv:AddItem(srcItem)
                        sendAddItemToContainer(inv, srcItem)
                    end
                end
            end)
        end
        SCTD_TransmogSync.Notify(player, "restored", item)
        return true, item
    end

    -- 兼容旧版（视觉替换法）残留的幻化记录：胚子类型从未被转换，
    -- 只需清除标记、返还被消耗的外观源并刷新模型
    if not data.baseFullType or data.baseFullType == "" then
        md.SCTD_Transmog = nil
        if data.srcFullType and data.srcFullType ~= "" then
            pcall(function()
                local srcItem = instanceItem(data.srcFullType)
                if srcItem then
                    if data.srcCondition then pcall(function() srcItem:setConditionNoSound(data.srcCondition) end) end
                    local inv = player:getInventory()
                    if inv then
                        inv:AddItem(srcItem)
                        sendAddItemToContainer(inv, srcItem)
                    end
                end
            end)
        end
        pcall(function() player:resetModelNextFrame() end)
        triggerEvent("OnClothingUpdated", player)
        syncVisuals(player)
        SCTD_TransmogSync.Notify(player, "restored", item)
        return true, item
    end

    -- 创建胚子原类型的新物品，并复制当前实例状态（跳过纹理索引）
    local ok, restored = pcall(function()
        local ni = instanceItem(data.baseFullType)
        if not ni then return nil end
        SCTD_Transmog_CopyState(item, ni, true, true)
        return ni
    end)
    if not ok or not restored then return false end
    -- 跨部位幻化后当前物品的脚本部位是外观源部位，还原必须使用胚子原部位。
    local bodyLoc = SCTD_Transmog_GetBodyLocation(restored)
    if data.baseBodyLocation then
        pcall(function()
            local savedLocation = ItemBodyLocation.get(ResourceLocation.of(data.baseBodyLocation))
            if savedLocation and player:getWornItems():getBodyLocationGroup():getLocation(savedLocation) then
                bodyLoc = savedLocation
            end
        end)
    end
    if not bodyLoc or bodyLoc == "" then return false end
    -- copyModData 会把幻化记录一并复制，必须清除
    restored:getModData().SCTD_Transmog = nil
    -- 透明变体借用的图标不带回原服装：写回 A 原有的自定义图标（无则清空，走脚本图标）。
    if data.hidden then restored:getModData().customInventoryIcon = data.baseCustomIcon end
    if restored:IsInventoryContainer() then restored:setCanBeEquipped(bodyLoc) end
    -- 恢复胚子原来的皮肤纹理选择（皮肤系统）
    pcall(function()
        local rv = restored:getVisual()
        if rv then
            if data.baseTextureChoice then rv:setTextureChoice(data.baseTextureChoice) end
            if data.baseBaseTexture then rv:setBaseTexture(data.baseBaseTexture) end
        end
        local t = data.hidden and data.baseTint
        if type(t) == "table" then
            if data.baseCustomColor then
                applyAuthoritativeTint(restored, t.r, t.g, t.b)
            elseif rv then
                rv:setTint(ImmutableColor.new(t.r, t.g, t.b, 1))
            end
        end
        restored:synchWithVisual()
    end)
    -- 回填胚子的服装属性快照（新实例的属性是脚本默认值）
    if data.baseStats then
        SCTD_Transmog_ApplyStats(restored, data.baseStats)
    end

    if restored:IsInventoryContainer() and item:IsInventoryContainer() then
        restored:getItemContainer():takeItemsFrom(item:getItemContainer())
    elseif not restored:IsInventoryContainer() then
        SCTD_Transmog_EmptyContainer(player, item)
    end
    -- 移除当前已幻化物品，穿上还原后的胚子
    SCTD_Transmog_RemoveWorn(player, item)
    -- deferSync：批处理（自动隐藏对账）逐件还原时跳过模型重建/衣物事件/视觉同步，交 BatchSync 统一收尾。
    if opts and opts.deferSync then
        SCTD_Transmog_WearNoSync(player, restored, bodyLoc)
    else
        SCTD_Transmog_WearAndSync(player, restored, bodyLoc)
    end

    -- 返还外观源到背包（保持原耐久与喜爱标记）
    if data.srcFullType and data.srcFullType ~= "" then
        pcall(function()
            local srcItem = instanceItem(data.srcFullType)
            if srcItem then
                if data.srcCondition then pcall(function() srcItem:setConditionNoSound(data.srcCondition) end) end
                if data.srcFavorite then pcall(function() srcItem:setFavorite(true) end) end
                local inv = player:getInventory()
                if inv then
                    inv:AddItem(srcItem)
                    sendAddItemToContainer(inv, srcItem)
                end
            end
        end)
    end
    -- 服务器端：通知穿戴者客户端重新回填本地实例属性并重选中新物品
    SCTD_TransmogSync.Notify(player, "restored", restored)
    return true, restored
end