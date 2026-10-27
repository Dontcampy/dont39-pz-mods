function SCTD_Transmog_IsTransmogged(item)
    if not item then return false end
    local ok, md = pcall(function() return item:getModData() end)
    if not ok or not md then return false end
    return md.SCTD_Transmog ~= nil
end