function SCTD_Transmog_GetSavedBodyLocation(item)
    if not item or not item:hasModData() then return nil end
    local data = item:getModData().SCTD_Transmog
    if type(data) ~= "table" then return nil end
    local location = SCTD_Transmog_ResolveSlot(data.baseBodyLocation)
    if location then return location end
    -- 旧记录缺少槽位时，从 A 的原类型读取；不使用当前 B 的脚本部位。
    local fullType = data.baseFullType
    if type(fullType) ~= "string" or fullType == "" then return nil end
    if SCTD_Transmog_BaseSlotCache[fullType] then return SCTD_Transmog_BaseSlotCache[fullType] end
    local ok, baseLocation = pcall(function()
        local base = instanceItem(fullType)
        if not base then return nil end
        local loc = base:getBodyLocation()
        if not loc or loc == "" then loc = base:canBeEquipped() end
        return loc and SCTD_Transmog_ResolveSlot(tostring(loc)) or nil
    end)
    if ok and baseLocation then
        SCTD_Transmog_BaseSlotCache[fullType] = baseLocation
        return baseLocation
    end
end