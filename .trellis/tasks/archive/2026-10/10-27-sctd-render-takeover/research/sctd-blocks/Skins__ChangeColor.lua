function SCTD_ChangeColor(player, clothingItem, r, g, b)
    if not player or not clothingItem then return false end
    
    local hidden = hiddenRecordOf(clothingItem)
    if hidden then
        setHiddenTint(hidden, r, g, b)
        if isClient() then
            sendClientCommand(player, SCTD_MODULE, "changeColor", {
                itemId = clothingItem:getID(),
                r = r,
                g = g,
                b = b
            })
        end
        return true
    end
    
    -- 本地应用颜色
    local success = applyColorToClothing(player, clothingItem, r, g, b)
    
    -- 多人模式：发送到服务器同步
    if success and isClient() then
        sendClientCommand(player, SCTD_MODULE, "changeColor", {
            itemId = clothingItem:getID(),
            r = r,
            g = g,
            b = b
        })
    end
    
    return success
end