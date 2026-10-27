function SCTD_Transmog_HideItem_Server(player, item, opts)
    if isClient() or not player or not item then return false end
    opts = opts or {}
    if SCTD_ClothingAppearance and SCTD_ClothingAppearance.Restore then SCTD_ClothingAppearance.Restore() end
    local worn = player:getWornItems()
    if not worn or not worn:contains(item) then return false end
    -- 资格校验：禁止名单/受保护内容/已幻化/透明变体本身/伤口绷带覆盖层/非服装 一律不隐藏。
    if SCTD_ClothingAppearance.IsForbidden(player, item) then return false end
    if SCTD_ContentFilter.IsProtected(player, item) then return false end
    if SCTD_Transmog_IsTransmogged(item) then return false end
    local fullType = nil
    pcall(function() fullType = item:getFullType() end)
    if not fullType then return false end
    if SCTD_Transmog_IsInvisVariant(fullType) then return false end
    if SCTD_IsWoundOverlay(fullType) or SCTD_IsBandageOverlay(fullType) then return false end
    local clothingItem = nil
    pcall(function() clothingItem = item:getClothingItem() end)
    if not clothingItem then return false end

    -- 还原所需数据（在移除物品前读取）
    local baseBodyLoc = worn:getLocation(item)
    if not baseBodyLoc then baseBodyLoc = SCTD_Transmog_GetBodyLocation(item) end
    if not baseBodyLoc then return false end
    local locString = tostring(baseBodyLoc)
    if locString == "" then return false end
    local scriptItem = nil
    pcall(function() scriptItem = item:getScriptItem() end)
    local isBag = item:IsInventoryContainer()
    local invisType, fallbackType = SCTD_Transmog_InvisTypeFor(fullType, scriptItem, locString, isBag)
    if not invisType then return false end
    local baseStats = SCTD_Transmog_SnapshotStats(item)
    local data = {
        baseFullType = fullType,
        baseBodyLocation = locString,
        baseStats = baseStats,
        hidden = true,
        autoHidden = opts.autoHidden or nil,
        -- srcFullType 缺省为 nil：透明变体非外观源，还原时不返还任何物品
    }
    pcall(function() data.baseDisplayName = item:getDisplayName() end)
    pcall(function()
        local bv = item:getVisual()
        if bv then
            data.baseTextureChoice = bv:getTextureChoice()
            data.baseBaseTexture = bv:getBaseTexture()
            -- 透明变体不允许随机染色，getTint 会把色调归白；记录 A 的色调供还原/皮肤界面沿用。
            local tint = bv:getTint(clothingItem)
            data.baseTint = {r = tint:getRedFloat(), g = tint:getGreenFloat(), b = tint:getBlueFloat()}
            data.baseCustomColor = item:isCustomColor() or nil
        end
    end)
    -- 图标：变体沿用 A 的当前图标；记录 A 原有的自定义图标（通常为空）供还原时原样写回。
    local iconPath = SCTD_Transmog_IconPathFor(item)
    pcall(function()
        local custom = item:hasModData() and item:getModData().customInventoryIcon or nil
        if type(custom) == "string" and custom ~= "" then data.baseCustomIcon = custom end
    end)

    -- 创建对应部位的透明变体，复制实例状态（跳过纹理索引/容器内容），回填 A 的属性快照。
    -- 签名变体缺失时退回无覆盖的部位变体；两者都缺失（instanceItem 返回 nil）则拒绝隐藏，A 保持可见。
    local ok, inv = pcall(function()
        local ni = instanceItem(invisType)
        if not ni and fallbackType and fallbackType ~= invisType then ni = instanceItem(fallbackType) end
        if not ni then return nil end
        SCTD_Transmog_CopyState(item, ni, true, true)
        SCTD_Transmog_ApplyStats(ni, baseStats)
        return ni
    end)
    if not ok or not inv then return false end
    if not SCTD_ContentFilter.CanWear(player, inv, item, baseBodyLoc) then return false end

    -- 容器类服装：内容物随包迁入透明容器变体；变体不是容器时才倒进玩家背包。
    if isBag and inv:IsInventoryContainer() then
        inv:getItemContainer():takeItemsFrom(item:getItemContainer())
    else
        SCTD_Transmog_EmptyContainer(player, item)
    end

    -- CopyState 会把 A 的 modData 一并复制过来，先清除遗留的隐藏/显示/TM 标记，再写入幻化记录。
    local invMD = inv:getModData()
    invMD.SCTD_TransmogHidden = nil
    invMD.SCTD_TransmogShown = nil
    invMD.SCTD_Hidden = nil
    invMD.SCTD_Transmog = data
    -- 变体在装备栏/提示里显示 A 的原名与图标，避免暴露内部透明服类型。
    -- 图标走原版 getTexture() 的 customInventoryIcon 分支，随物品 modData 序列化，存档与多人均一致。
    if data.baseDisplayName then pcall(function() inv:setName(data.baseDisplayName) end) end
    invMD.customInventoryIcon = iconPath

    SCTD_Transmog_RefreshSlot(inv)
    SCTD_Transmog_RemoveWorn(player, item)
    if opts.deferSync then
        SCTD_Transmog_WearNoSync(player, inv, baseBodyLoc)
    else
        SCTD_Transmog_WearAndSync(player, inv, baseBodyLoc)
    end
    SCTD_TransmogSync.Notify(player, "applied", inv)
    return true, inv
end