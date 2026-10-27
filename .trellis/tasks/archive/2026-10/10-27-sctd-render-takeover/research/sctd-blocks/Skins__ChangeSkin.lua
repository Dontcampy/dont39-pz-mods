function SCTD_ChangeSkin(player, clothingItem, textureIndex)
    if not player or not clothingItem or textureIndex == nil then return false end
    
    local hidden = hiddenRecordOf(clothingItem)
    if hidden then
        if not setHiddenTexture(hidden, textureIndex) then return false end
        if isClient() then
            sendClientCommand(player, SCTD_MODULE, "changeSkin", {
                itemId = clothingItem:getID(),
                textureIndex = textureIndex
            })
        end
        return true
    end
    
    -- 检查服装是否被自动隐藏
    local itemId = clothingItem:getID()
    local isAutoHidden = false
    local causeTypeName = nil
    
    if LingLi_FAutoSwitchClothing and LingLi_FAutoSwitchClothing.mutexHiddenItems and LingLi_FAutoSwitchClothing.mutexHiddenItems[itemId] then
        isAutoHidden = true
        causeTypeName = LingLi_FAutoSwitchClothing.mutexHiddenItems[itemId]
    end
    if not isAutoHidden and LingLi_MAutoSwitchClothing and LingLi_MAutoSwitchClothing.mutexHiddenItems and LingLi_MAutoSwitchClothing.mutexHiddenItems[itemId] then
        isAutoHidden = true
        causeTypeName = LingLi_MAutoSwitchClothing.mutexHiddenItems[itemId]
    end
    -- 如果自动隐藏已禁用，或服装实际未被隐藏，则不视为自动隐藏
    if isAutoHidden then
        if (SCTD_IsAutoHideDisabled and SCTD_IsAutoHideDisabled(player)) or (SCTD_IsClothingHidden and not SCTD_IsClothingHidden(clothingItem)) then
            isAutoHidden = false
        end
    end
    
    if SCTD_ContentFilter.IsProtected(player, clothingItem) then
        isAutoHidden = false
        if SCTD_ContentFilter.IsHiddenTexture(clothingItem, textureIndex) then return false end
    end
    -- 本地应用皮肤
    local success = applyTextureToClothing(player, clothingItem, textureIndex)
    
    -- 如果服装被自动隐藏，重新应用隐藏状态
    if success and isAutoHidden then
        -- 延迟重新隐藏，等待模型重置完成
        local tickCount = 0
        local function reapplyHide()
            tickCount = tickCount + 1
            if tickCount >= 3 then
                Events.OnTick.Remove(reapplyHide)
                -- 反色情内容：延迟期间开关被打开时，受保护服装不得再被自动隐藏
                if SCTD_ContentFilter.IsProtected(player, clothingItem) then return end
                local visual = clothingItem:getVisual()
                local clothingItemDef = clothingItem:getClothingItem()
                if visual and clothingItemDef then
                    local modData = clothingItem:getModData()
                    -- 查找名为 TM 的透明贴图
                    local textureChoices = clothingItemDef:getTextureChoices()
                    local baseTextures = clothingItemDef:getBaseTextures()
                    local transparentIndex = -1
                    
                    -- 优先在 textureChoices 中查找包含 "TM" 的贴图
                    if textureChoices and textureChoices:size() > 0 then
                        for i = 0, textureChoices:size() - 1 do
                            local choice = textureChoices:get(i)
                            if choice and type(choice) == "string" and string.find(choice, "TM") then
                                transparentIndex = i
                                break
                            end
                        end
                    end
                    
                    -- 如果 textureChoices 没找到，尝试在 baseTextures 中查找
                    if transparentIndex < 0 and baseTextures and baseTextures:size() > 0 then
                        for i = 0, baseTextures:size() - 1 do
                            local choice = baseTextures:get(i)
                            if choice and type(choice) == "string" and string.find(choice, "TM") then
                                transparentIndex = i
                                break
                            end
                        end
                    end
                    
                    if transparentIndex >= 0 then
                        -- 保存新选择的皮肤作为原始贴图索引
                        modData.SCTD_OriginalTextureChoice = textureIndex
                        modData.SCTD_OriginalBaseTexture = textureIndex
                        modData.SCTD_Hidden = true
                        
                        if textureChoices and transparentIndex < textureChoices:size() then
                            visual:setTextureChoice(transparentIndex)
                        end
                        if baseTextures and transparentIndex < baseTextures:size() then
                            visual:setBaseTexture(transparentIndex)
                        end
                        clothingItem:synchWithVisual()
                        player:resetModelNextFrame()
                        
                        -- 多人模式同步
                        if isClient() then
                            local bodyLocation = clothingItem:getBodyLocation()
                            if bodyLocation and bodyLocation ~= "" then
                                sendClothing(player, bodyLocation, clothingItem)
                            end
                            syncVisuals(player)
                        end
                    end
                end
            end
        end
        Events.OnTick.Add(reapplyHide)
    end
    
    -- 多人模式：发送到服务器同步
    if success and isClient() then
        sendClientCommand(player, SCTD_MODULE, "changeSkin", {
            itemId = clothingItem:getID(),
            textureIndex = textureIndex
        })
    end
    if success and SCTD_ContractsBump then SCTD_ContractsBump("c_skin") end
    
    return success
end