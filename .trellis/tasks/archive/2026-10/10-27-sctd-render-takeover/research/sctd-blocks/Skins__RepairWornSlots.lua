function SCTD_Transmog_RepairWornSlots(player)
    if not player then return false end
    local state = SCTD_Transmog_SlotRepairState[player]
    if not state then state = {}; SCTD_Transmog_SlotRepairState[player] = state end
    if state.busy then return false end
    local worn, inv = player:getWornItems(), player:getInventory()
    if not worn or not inv then return false end
    local group, pending, signature = worn:getBodyLocationGroup(), {}, {}
    for i = 0, worn:size() - 1 do
        local item = worn:getItemByIndex(i)
        local location = SCTD_Transmog_GetSavedBodyLocation(item)
        if location and group:getLocation(location) and worn:getLocation(item) ~= location and inv:contains(item) then
            pending[#pending + 1] = { item = item, location = location }
            signature[#signature + 1] = tostring(item:getID()) .. ":" .. tostring(worn:getLocation(item)) .. ":" .. tostring(location)
        end
    end
    if #pending == 0 then state.request = nil; return false end
    if isClient() then
        -- 客户端只申请重查，服务器自行读取记录，绝不接收客户端指定的槽位。
        local key, now = table.concat(signature, "|"), getTimestampMs()
        if state.request ~= key or now - (state.time or 0) >= 2000 then
            state.request, state.time = key, now
            sendClientCommand(player, SCTD_TRANSMOG_MODULE, "repairSlots", {})
        end
        return false
    end
    state.busy = true
    local ok, err = pcall(function()
        -- 先移出全部错槽记录，再按 A 槽重新安放，避免两个幻化物品互相挤掉。
        for _, entry in ipairs(pending) do worn:remove(entry.item) end
        for _, entry in ipairs(pending) do
            local blocked = false
            for i = 0, worn:size() - 1 do
                local otherLoc = worn:getLocation(worn:getItemByIndex(i))
                if (otherLoc == entry.location and not group:isMultiItem(entry.location))
                    or group:isExclusive(entry.location, otherLoc) then blocked = true; break end
            end
            -- A 槽已占用时仅脱下错槽的幻化物品，留在库存，不影响原有穿戴。
            SCTD_Transmog_RefreshSlot(entry.item)
            if not blocked then player:setWornItem(entry.location, entry.item, false) end
        end
        -- 直接按物品移除支持多物品槽；同步重建原版的衣物覆盖缓存。
        local wetness = player:getClothingWetness()
        if wetness then wetness:calculateExposedItems() end
        player:onWornItemsChanged()
        player:resetModelNextFrame()
        sendClothing(player, nil, nil)
        syncVisuals(player)
        triggerEvent("OnClothingUpdated", player)
    end)
    state.busy = false
    return ok
end