function SCTD_AutoHide_Reconcile(player)
    if isClient() or not player or SCTD_AutoHide_IsBusy(player) then return end
    SCTD_AutoHide_SetBusy(player, true)
    -- 全程 pcall 包裹：本函数由 OnClothingUpdated 等事件回调触发，常嵌在调用方（如槽位修复）
    -- 的 pcall 窗口内，任何异常都不得外泄，否则会中断调用方后续的穿戴广播。
    pcall(function()
        if player:isDead() then return end
        local shouldHide = SCTD_WearsBodyForm(player) and SCTD_ClothingAppearance.IsAutoHideNonSct(player)
        local worn = player:getWornItems()
        if not worn then return end
        local targets, changed = {}, 0
        -- 先把覆盖不对的存量隐藏项换成正确变体（仅查表，已正确时不动）。
        for i = 0, worn:size() - 1 do
            local item = worn:getItemByIndex(i)
            if item and SCTD_Transmog_IsInvisVariant(item:getFullType()) then targets[#targets + 1] = item end
        end
        for _, item in ipairs(targets) do
            if SCTD_Transmog_RefitInvis_Server(player, item, true) then changed = changed + 1 end
        end
        targets = {}
        if shouldHide then
            for i = 0, worn:size() - 1 do
                local item = worn:getItemByIndex(i)
                if SCTD_AutoHide_CanAutoHide(player, item) then targets[#targets + 1] = item end
            end
            for _, item in ipairs(targets) do
                if SCTD_Transmog_HideItem_Server(player, item, { autoHidden = true, deferSync = true }) then
                    changed = changed + 1
                end
            end
        else
            for i = 0, worn:size() - 1 do
                local item = worn:getItemByIndex(i)
                if item and SCTD_Transmog_IsInvisVariant(item:getFullType()) then
                    local data = SCTD_Transmog_GetData(item)
                    if data and data.autoHidden then targets[#targets + 1] = item end
                end
            end
            for _, item in ipairs(targets) do
                if SCTD_Transmog_Restore(player, item, { deferSync = true }) then changed = changed + 1 end
            end
        end
        if changed > 0 then SCTD_Transmog_BatchSync(player) end
    end)
    SCTD_AutoHide_SetBusy(player, false)
end