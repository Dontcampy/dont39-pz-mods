function SCTD_Transmog_IsInvisVariant(fullType)
    if type(fullType) ~= "string" then return false end
    return fullType:sub(1, #SCTD_INVIS_PREFIX) == SCTD_INVIS_PREFIX
end