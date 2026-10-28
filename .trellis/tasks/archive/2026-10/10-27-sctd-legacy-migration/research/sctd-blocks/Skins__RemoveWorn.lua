local function SCTD_Transmog_RemoveWorn(player, item)
    pcall(function()
        player:removeFromHands(item)
        player:removeWornItem(item, false)
        _sctd_removeWornByRef(player, item)  -- multi-item 部位(belt/beltextra)必须按引用摘除
        player:getInventory():Remove(item)
        sendRemoveItemFromContainer(player:getInventory(), item)
    end)
end