local function onTransmogCommand(module, command, player, args)
    if module ~= SCTD_TRANSMOG_MODULE then return end
    if not player or not args then return end
    if not SCTD_Transmog_Apply or not SCTD_Transmog_Restore then return end

    if command == "apply" then
        local baseId = tonumber(args.baseId)
        local srcId = tonumber(args.srcId)
        if not baseId or not srcId then return end
        -- 胚子必为穿戴中；外观源为背包/包中未穿戴服装
        local baseItem = SCTD_TM_FindWornById(player, baseId)
        local sourceItem = SCTD_TM_FindInvById(player:getInventory(), srcId)
        if baseItem and sourceItem then
            SCTD_Transmog_Apply(player, baseItem, sourceItem, args.ignoreBodyLocation == true, args.invertStats == true)
        end
    elseif command == "restore" then
        local itemId = tonumber(args.itemId)
        if not itemId then return end
        local item = SCTD_TM_FindWornById(player, itemId)
        if not item then item = SCTD_TM_FindInvById(player:getInventory(), itemId) end
        if item then
            SCTD_Transmog_Restore(player, item)
        end
    elseif command == "repairSlots" then
        -- 仅重查发送者当前穿戴记录；忽略请求中的物品、部位等字段。
        if SCTD_Transmog_RepairWornSlots then SCTD_Transmog_RepairWornSlots(player) end
    elseif command == "hideItem" then
        -- 隐藏：把发送者穿戴中的服装转为对应部位透明变体（只按 ID 从实际穿戴列表解析，服务器权威执行）。
        local itemId = tonumber(args.itemId)
        if not itemId then return end
        local item = SCTD_TM_FindWornById(player, itemId)
        if item and SCTD_Transmog_HideItem then SCTD_Transmog_HideItem(player, item) end
    elseif command == "showItem" then
        -- 显示：把发送者穿戴中的透明变体还原回原服装。
        local itemId = tonumber(args.itemId)
        if not itemId then return end
        local item = SCTD_TM_FindWornById(player, itemId)
        if item and SCTD_Transmog_ShowItem then SCTD_Transmog_ShowItem(player, item) end
    end
end