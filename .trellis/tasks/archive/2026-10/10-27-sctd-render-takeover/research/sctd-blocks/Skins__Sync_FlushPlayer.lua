function SCTD_TransmogSync.FlushPlayer(player)
    local state = SCTD_TransmogSync.players[player]
    if not state or state.busy then return end
    state.busy = true
    local ok, err = pcall(function()
        SCTD_TransmogSync.ReconcileWorn(player)
        for id, entry in pairs(state.pending) do
            local data = entry.data
            local item = SCTD_TransmogSync.FindItem(player:getInventory(), id)
            if item and item:getFullType() == data.fullType then
                -- false 是服务器明确清除记录的标记，不能用缺字段代替。
                item:getModData().SCTD_Transmog = data.record ~= false and data.record or nil
                SCTD_Transmog_ApplyStats(item, data.stats)
                SCTD_Transmog_RefreshSlot(item)
                state.pending[id] = nil
                SCTD_TransmogSync.ReconcileWorn(player)
                player:resetModelNextFrame()
                if SCTD_TransmogPanel then
                    local panel = SCTD_TransmogPanel.instance
                    if panel and panel.player == player then panel._sctdSelectId = id end
                    SCTD_TransmogPanel.dirty = true
                end
            elseif getTimestampMs() - entry.time > 30000 then
                -- 已被后续操作消耗/转移的物品不补造、不复活；保留 revision 拒绝旧包。
                state.pending[id] = nil
            end
        end
    end)
    state.busy = false
    if not ok then error(err) end
end