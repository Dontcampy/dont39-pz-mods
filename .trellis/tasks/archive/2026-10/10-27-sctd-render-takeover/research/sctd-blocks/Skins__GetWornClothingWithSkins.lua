function SCTD_GetWornClothingWithSkins(player)
    if not player then return {} end
    
    -- 关键：先 Restore() 放回真实穿戴列表，与 SCTD_Transmog_GetWornItems 同标准。
    -- 否则在幻化每帧 swap 窗口内读到的是「显示副本」(instanceItem，modData 为空)，皮肤面板会
    -- 缓存副本引用；点「隐藏」时 SCTD_HideClothing 把 TM 设在副本上，而副本每帧被 Prepare 的
    -- target:copyFrom(真实visual) 重新覆盖、Restore 时又被丢弃，真实物品的 textureChoice 从未改变
    -- → TM 每帧被冲掉，表现为「点隐藏只换了个可见皮肤、没变透明」。Restore 后列表持真实物品，
    -- 隐藏落在真实物品上，下帧副本 copyFrom(真实visual=TM) 即透明。
    pcall(function()
        if SCTD_ClothingAppearance and SCTD_ClothingAppearance.Restore then
            SCTD_ClothingAppearance.Restore()
        end
    end)
    
    local result = {}
    local wornItems = player:getWornItems()
    if not wornItems then return {} end
    
    
    for i = 0, wornItems:size() - 1 do
        local item = wornItems:getItemByIndex(i)
        -- 表情变体（blink 闭眼 / lips 张嘴）是系统临时换上的同体模型，不进入皮肤列表；
        -- 玩家真正穿着的是基准身形 rest，它照常进入列表供选肤色。
        -- 透明变体（隐藏态）按记录的原服装列出：皮肤/颜色写进隐藏记录，显示时随原服装还原。
        local hidden = hiddenRecordOf(item)
        if item and not SCTD_IsBlinkClothing(item:getType()) and not SCTD_IsWoundOverlay(item:getFullType()) and not SCTD_IsBandageOverlay(item:getFullType()) and (hidden or not SCTD_Transmog_IsInvisVariant(item:getFullType())) then
            local itemType = item:getType()
            local choices = nil
            if hidden then
                itemType = hidden.baseFullType:match("^[^%.]+%.(.+)$") or hidden.baseFullType
                choices = SCTD_TextureChoicesFromAsset(hiddenBaseAsset(hidden), itemType)
            else
                choices = SCTD_GetTextureChoicesFromItem(item)
            end

            -- 获取 ClothingItem（用于颜色和模型检查）
            local clothingItemAsset = nil
            local ok_ci, val_ci = pcall(function() return item:getClothingItem() end)
            if hidden then
                clothingItemAsset = hiddenBaseAsset(hidden)
            elseif ok_ci and val_ci then
                clothingItemAsset = val_ci
                -- 检查资产是否就绪
                local ok_ready, isReady = pcall(function() return val_ci:isReady() end)
                if ok_ready and not isReady then
                else
                end
            else
            end
            
            -- 检查是否有皮肤选择（需要多于1个才能切换）
            local hasSkins = choices and #choices > 1
            
            -- 检查是否支持颜色自定义 (AllowRandomTint)
            local hasColorChange = false
            if clothingItemAsset then
                local ok, val = pcall(function() return clothingItemAsset:getAllowRandomTint() end)
                if ok and val == true then hasColorChange = true end
            end
            
            -- 检查是否有 ClothingItemExtra 变体
            local hasExtras = false
            local extraOptions = nil
            local extraTypes = nil
            local ok_eo, val_eo = pcall(function() return item:getClothingItemExtraOption() end)
            if ok_eo and val_eo and not hidden then
                local ok_sz, val_sz = pcall(function() return val_eo:size() end)
                if ok_sz and val_sz and val_sz > 0 then
                    hasExtras = true
                    extraOptions = val_eo
                    local ok_et, val_et = pcall(function() return item:getClothingItemExtra() end)
                    if ok_et and val_et then extraTypes = val_et end
                end
            end
            
            -- 检查 SkinPairs 配对
            local skinPairVariant = not hidden and SCTD_FindSkinPairVariant(itemType) or nil
            local hasSkinPair = skinPairVariant ~= nil
            
            
            -- 只添加有皮肤、有颜色自定义、有Extra变体或有SkinPair的服装
            if hasSkins or hasColorChange or hasExtras or hasSkinPair then
                local currentIndex = 0
                local visual = nil
                local ok_v, val_v = pcall(function() return item:getVisual() end)
                if ok_v and val_v then visual = val_v end
                
                if hidden and clothingItemAsset then
                    local hmOk, hm = pcall(function() return clothingItemAsset:hasModel() end)
                    if hmOk and hm == true then
                        currentIndex = tonumber(hidden.baseTextureChoice) or 0
                    else
                        currentIndex = tonumber(hidden.baseBaseTexture) or 0
                    end
                elseif visual and clothingItemAsset then
                    local hmOk, hm = pcall(function() return clothingItemAsset:hasModel() end)
                    if hmOk and hm == true then
                        local ok, val = pcall(function() return visual:getTextureChoice() end)
                        if ok and val then currentIndex = val end
                    else
                        local ok, val = pcall(function() return visual:getBaseTexture() end)
                        if ok and val then currentIndex = val end
                    end
                end

                local currentChoice = ""
                if choices and currentIndex >= 0 and currentIndex < #choices then
                    currentChoice = choices[currentIndex + 1] or ""
                end
                
                -- 获取当前颜色
                local currentColor = nil
                if hidden then
                    local t = hidden.baseTint
                    if hasColorChange and type(t) == "table" then currentColor = ImmutableColor.new(t.r, t.g, t.b, 1) end
                elseif hasColorChange and visual and clothingItemAsset then
                    local ok, val = pcall(function() return visual:getTint(clothingItemAsset) end)
                    if ok and val then currentColor = val end
                end
                
                -- 获取显示名称
                local displayName = ""
                local ok_dn, val_dn = pcall(function() return item:getDisplayName() end)
                if ok_dn and val_dn then displayName = val_dn end
                
                table.insert(result, {
                    item = item,
                    itemType = itemType,
                    displayName = displayName,
                    choices = choices or {},
                    currentIndex = currentIndex,
                    currentChoice = currentChoice,
                    hasSkins = hasSkins,
                    hasColorChange = hasColorChange,
                    currentColor = currentColor,
                    hasExtras = hasExtras,
                    extraOptions = extraOptions,
                    extraTypes = extraTypes,
                    hasSkinPair = hasSkinPair,
                    skinPairVariant = skinPairVariant,
                })
            end
        end
    end
    
    return result
end