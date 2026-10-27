local function SCTD_Transmog_ResnapContainer(container)
    if not container then return end
    local ok, items = pcall(function() return container:getItems() end)
    if not ok or not items then return end
    for i = 0, items:size() - 1 do
        local item = items:get(i)
        if item then
            local data = SCTD_Transmog_GetData(item)
            if data then
                if data.baseStats then SCTD_Transmog_ApplyStats(item, data.baseStats, true) end
                SCTD_Transmog_RefreshSlot(item)
            end
            local okI, isCont = pcall(function() return item:IsInventoryContainer() end)
            if okI and isCont then
                local okIC, inner = pcall(function() return item:getItemContainer() end)
                if okIC and inner then
                    SCTD_Transmog_ResnapContainer(inner)
                end
            end
        end
    end
end