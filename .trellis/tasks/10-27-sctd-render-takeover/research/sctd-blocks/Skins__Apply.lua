function SCTD_Transmog_Apply(player, baseItem, sourceItem, ignoreBodyLocation, invertStats)
    if not player or not baseItem or not sourceItem then return false end
    if SCTD_ClothingAppearance and SCTD_ClothingAppearance.Restore then SCTD_ClothingAppearance.Restore() end
    if SCTD_ClothingAppearance.IsForbidden(player, baseItem)
        or SCTD_ClothingAppearance.IsForbidden(player, sourceItem) then return false end
    -- 已幻化的胚子须先还原再幻化（还原会替换物品对象，不能连续转换）
    if SCTD_Transmog_IsTransmogged(baseItem) then return false end
    -- 客户端和权威端复用同一套资格校验，跨部位仅解除部位限制。
    local worn = player:getWornItems()
    if not worn or not worn:contains(baseItem) then return false end
    local validSource = false
    for _, candidate in ipairs(SCTD_Transmog_GetSources(player, baseItem, ignoreBodyLocation)) do
        if candidate == sourceItem then validSource = true; break end
    end
    if not validSource then return false end

    -- MP 纯客户端：绝不在本地改物品。客户端的 sendClothing/sendAddItemToContainer/
    -- sendRemoveItemFromContainer/syncVisuals 都是【服务器专用空实现】，本地转换服务器完全不知情，
    -- 导致：脱不下（服务器 ISUnequipAction 找不到该物品 -> isTwoHandWeapon 空指针）、
    -- 重进/丧尸化后消失（服务器从未持有幻化物品）、菜单选中被反复刷掉（服务器回同步覆盖本地）。
    -- 正确做法：发命令让服务器按 ID 解析物品并权威执行同一套转换，再由服务器广播回所有客户端。
    if isClient() then
        local baseId, srcId = nil, nil
        pcall(function() baseId = baseItem:getID() end)
        pcall(function() srcId = sourceItem:getID() end)
        if baseId and srcId then
            sendClientCommand(player, SCTD_TRANSMOG_MODULE, "apply", {
                baseId = baseId, srcId = srcId, ignoreBodyLocation = ignoreBodyLocation == true,
                invertStats = invertStats == true,
            })
        end
        return false
    end

    local baseFullType, srcFullType, baseBodyLoc = nil, nil, nil
    pcall(function() baseFullType = baseItem:getFullType() end)
    pcall(function() srcFullType = sourceItem:getFullType() end)
    pcall(function() baseBodyLoc = worn:getLocation(baseItem) end)
    if not baseBodyLoc then baseBodyLoc = SCTD_Transmog_GetBodyLocation(baseItem) end
    if not baseFullType or not srcFullType or not baseBodyLoc or baseBodyLoc == "" then return false end
    if baseFullType == srcFullType then return false end

    -- 反转属性模式：不做类型转换（外观保持胚子 A），把外观源 B 的属性快照回填到 A 上并消耗 B；
    -- baseStats 存 B 的属性（供存档重载/客户端回填），origStats 存 A 原属性（供还原回写）。
    if invertStats == true then
        local srcStats = SCTD_Transmog_SnapshotStats(sourceItem)
        local origStats = SCTD_Transmog_SnapshotStats(baseItem)
        local data = {
            baseFullType = baseFullType,
            srcFullType = srcFullType,
            baseBodyLocation = tostring(baseBodyLoc),
            baseStats = srcStats,
            origStats = origStats,
            inverted = true,
        }
        pcall(function() data.srcDisplayName = sourceItem:getDisplayName() end)
        pcall(function() data.srcCondition = sourceItem:getCondition() end)
        pcall(function() data.srcFavorite = sourceItem:isFavorite() end)
        pcall(function()
            local bv = baseItem:getVisual()
            if bv then
                data.baseTextureChoice = bv:getTextureChoice()
                data.baseBaseTexture = bv:getBaseTexture()
            end
        end)
        SCTD_Transmog_EmptyContainer(player, sourceItem)
        local srcContainer = sourceItem:getContainer()
        if srcContainer then
            srcContainer:Remove(sourceItem)
            sendRemoveItemFromContainer(srcContainer, sourceItem)
        end
        SCTD_Transmog_ApplyStats(baseItem, srcStats)
        baseItem:getModData().SCTD_Transmog = data
        SCTD_Transmog_RefreshSlot(baseItem)
        pcall(function() sendClothing(player, baseBodyLoc, baseItem) end)
        pcall(function() player:resetModelNextFrame() end)
        triggerEvent("OnClothingUpdated", player)
        syncVisuals(player)
        SCTD_TransmogSync.Notify(player, "applied", baseItem)
        return true, baseItem
    end

    -- 记录还原所需数据（在移除物品前读取）
    local data = {
        baseFullType = baseFullType,
        srcFullType = srcFullType,
        baseBodyLocation = tostring(baseBodyLoc),
    }
    pcall(function() data.srcDisplayName = sourceItem:getDisplayName() end)
    pcall(function() data.srcCondition = sourceItem:getCondition() end)
    pcall(function() data.srcFavorite = sourceItem:isFavorite() end)
    -- 记录胚子的皮肤纹理选择（皮肤系统），还原时恢复
    pcall(function()
        local bv = baseItem:getVisual()
        if bv then
            data.baseTextureChoice = bv:getTextureChoice()
            data.baseBaseTexture = bv:getBaseTexture()
        end
    end)
    -- 快照胚子的服装属性（防御/保温/速度等实例字段）
    local baseStats = SCTD_Transmog_SnapshotStats(baseItem)
    data.baseStats = baseStats

    -- 创建外观源类型的新物品，复制胚子的实例状态（跳过纹理索引，外观保持源服装原始外观）
    -- 新物品的属性被初始化为外观源的值，必须回填胚子的属性
    local ok, newItem = pcall(function()
        local ni = instanceItem(srcFullType)
        if not ni then return nil end
        SCTD_Transmog_CopyState(baseItem, ni, true, true)
        SCTD_Transmog_ApplyStats(ni, baseStats)
        return ni
    end)
    if not ok or not newItem then
        return false
    end
    if not SCTD_ContentFilter.CanWear(player, newItem, baseItem, baseBodyLoc) then return false end
    -- 新物品无法承载容器内容时先移出，外观源容器被消耗前也须清空。
    if not newItem:IsInventoryContainer() then SCTD_Transmog_EmptyContainer(player, baseItem) end
    SCTD_Transmog_EmptyContainer(player, sourceItem)
    -- 移除胚子（穿戴中）与外观源（背包/包中）
    -- 外观源可能在胚子容器内，须在移除父容器前发出源物品的移除包。
    local container = sourceItem:getContainer()
    if container then
        container:Remove(sourceItem)
        sendRemoveItemFromContainer(container, sourceItem)
    end
    -- 创建和资格检查成功后才迁移内容，避免创建失败提前清空原包。
    if newItem:IsInventoryContainer() and baseItem:IsInventoryContainer() then
        newItem:getItemContainer():takeItemsFrom(baseItem:getItemContainer())
    end
    SCTD_Transmog_RemoveWorn(player, baseItem)

    -- 穿上转换后的物品并写入幻化记录
    -- 在物品加入容器和穿戴广播之前写入，确保客户端收到完整还原记录与属性快照。
    newItem:getModData().SCTD_Transmog = data
    SCTD_Transmog_RefreshSlot(newItem)
    SCTD_Transmog_WearAndSync(player, newItem, baseBodyLoc)
    -- 服务器端：通知穿戴者客户端重新回填本地实例属性（防御/保温等不随网络同步）并重选中新物品
    SCTD_TransmogSync.Notify(player, "applied", newItem)
    return true, newItem
end