function SCTD_Transmog_EmptyContainer(player, item)
    if not item or not item:IsInventoryContainer() then return end
    local container = item:getItemContainer()
    local inventory = player:getInventory()
    if not container or not inventory then return end
    local items = container:getItems()
    for i = items:size() - 1, 0, -1 do
        local child = items:get(i)
        container:Remove(child)
        sendRemoveItemFromContainer(container, child)
        inventory:AddItem(child)
        sendAddItemToContainer(inventory, child)
    end
end