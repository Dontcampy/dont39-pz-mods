function SCTD_Transmog_HideItem(player, item)
    if not player or not item then return false end
    if isClient() then
        local itemId = nil
        pcall(function() itemId = item:getID() end)
        if itemId then
            sendClientCommand(player, SCTD_TRANSMOG_MODULE, "hideItem", { itemId = itemId })
        end
        return false
    end
    SCTD_AutoHide_SetBusy(player, true)
    local ok, res, hidden = pcall(SCTD_Transmog_HideItem_Server, player, item, nil)
    SCTD_AutoHide_SetBusy(player, false)
    if ok then return res, hidden end
    return false
end