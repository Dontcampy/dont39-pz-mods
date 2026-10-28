local function SCTD_Transmog_CopyState(fromItem, toItem, skipTextureIndices, skipContainerContents)
    local fromVisual, toVisual = nil, nil
    pcall(function() fromVisual = fromItem:getVisual() end)
    pcall(function() toVisual = toItem:getVisual() end)
    if fromVisual and toVisual then
        pcall(function() toVisual:setTint(fromVisual:getTint(fromItem:getClothingItem())) end)
        if not skipTextureIndices then
            -- 目标服装的贴图列表可能比来源短：张嘴 Lips 只有 1 张贴图、眨眼 Blink 有睁/闭 2 张。
            -- 直接照搬来源索引会越界——切换瞬间若恰逢闭眼（Blink 索引 1），Lips 会被设成不存在的
            -- 索引 1，身体贴图渲染异常（约 5% 概率，咀嚼时反复切换会更频繁地闪）。
            -- 按目标列表长度钳制：装得下就原样保留（眨眼无缝接续），装不下就退回第 0 张。
            local toAsset = nil
            pcall(function() toAsset = toItem:getClothingItem() end)
            local choiceCount, baseCount = 0, 0
            if toAsset then
                pcall(function() local c = toAsset:getTextureChoices() if c then choiceCount = c:size() end end)
                pcall(function() local b = toAsset:getBaseTextures() if b then baseCount = b:size() end end)
            end
            pcall(function()
                local bt = fromVisual:getBaseTexture()
                if type(bt) == "number" and baseCount > 0 then
                    if bt < 0 or bt >= baseCount then bt = 0 end
                    toVisual:setBaseTexture(bt)
                end
            end)
            pcall(function()
                local tc = fromVisual:getTextureChoice()
                if type(tc) == "number" and choiceCount > 0 then
                    if tc < 0 or tc >= choiceCount then tc = 0 end
                    toVisual:setTextureChoice(tc)
                end
            end)
            pcall(function() toVisual:setDecal(fromVisual:getDecal(fromItem:getClothingItem())) end)
        end
        pcall(function() toVisual:copyDirt(fromVisual) end)
        pcall(function() toVisual:copyBlood(fromVisual) end)
        pcall(function() toVisual:copyHoles(fromVisual) end)
        pcall(function() toVisual:copyPatches(fromVisual) end)
    end
    pcall(function() toItem:setColor(fromItem:getColor()) end)
    pcall(function() toItem:setConditionNoSound(fromItem:getCondition()) end)
    pcall(function() toItem:setFavorite(fromItem:isFavorite()) end)
    if fromItem:hasModData() then
        pcall(function() toItem:copyModData(fromItem:getModData()) end)
    end
    pcall(function()
        if not skipContainerContents and toItem:IsInventoryContainer() and fromItem:IsInventoryContainer() then
            toItem:getItemContainer():takeItemsFrom(fromItem:getItemContainer())
        end
    end)
    pcall(function()
        if toItem:IsClothing() and fromItem:IsClothing() then
            fromItem:copyPatchesTo(toItem)
            toItem:setWetness(fromItem:getWetness())
        end
    end)
    toItem:synchWithVisual()
end