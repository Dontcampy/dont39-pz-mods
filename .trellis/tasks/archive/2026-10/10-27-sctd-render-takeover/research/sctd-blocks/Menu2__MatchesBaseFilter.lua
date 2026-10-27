function SCTD_Transmog_MatchesBaseFilter(panel, item)
    if panel.baseFilter == "transmogged" then
        -- 透明变体（隐藏态）归「隐藏」筛选，不计入「已幻化」。
        if SCTD_Transmog_IsInvisVariant and SCTD_Transmog_IsInvisVariant(item:getFullType()) then return false end
        return SCTD_Transmog_IsTransmogged and SCTD_Transmog_IsTransmogged(item) or false
    end
    if panel.baseFilter == "hidden" then
        return SCTD_Transmog_IsInvisVariant and SCTD_Transmog_IsInvisVariant(item:getFullType()) or false
    end
    return true
end