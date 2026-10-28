function SCTD_Transmog_ShowItem(player, item)
    if not player or not item then return false end
    if isClient() then
        local itemId = nil
        pcall(function() itemId = item:getID() end)
        if itemId then
            sendClientCommand(player, SCTD_TRANSMOG_MODULE, "showItem", { itemId = itemId })
        end
        return false
    end
    local exempt = SCTD_WearsBodyForm(player)
    SCTD_AutoHide_SetBusy(player, true)
    local ok, res, restored = pcall(SCTD_Transmog_Restore, player, item, nil)
    if ok and res and restored then
        pcall(function() restored:getModData().SCTD_TransmogShown = exempt or nil end)
    end
    SCTD_AutoHide_SetBusy(player, false)
    if ok then return res, restored end
    return false
end