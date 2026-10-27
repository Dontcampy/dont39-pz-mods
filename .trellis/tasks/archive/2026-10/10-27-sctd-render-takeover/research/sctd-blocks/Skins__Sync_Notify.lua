function SCTD_TransmogSync.Notify(player, command, item, stats)
    if not isServer() then return end
    SCTD_TransmogSync.serial = SCTD_TransmogSync.serial + 1
    sendServerCommand(player, SCTD_TRANSMOG_MODULE, command, {
        playerId = player:getOnlineID(), itemId = item:getID(), fullType = item:getFullType(),
        revision = SCTD_TransmogSync.serial,
        record = SCTD_Transmog_GetData(item) or false,
        stats = stats or SCTD_Transmog_SnapshotStats(item),
    })
end