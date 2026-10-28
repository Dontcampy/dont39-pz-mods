local function SCTD_Transmog_WearNoSync(player, item, bodyLocation)
    player:getInventory():AddItem(item)
    sendAddItemToContainer(player:getInventory(), item)
    pcall(function()
        player:setWornItem(bodyLocation, item)
        sendClothing(player, bodyLocation, item)
    end)
end