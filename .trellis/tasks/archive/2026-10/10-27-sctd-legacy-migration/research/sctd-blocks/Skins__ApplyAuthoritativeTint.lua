local function applyAuthoritativeTint(item, r, g, b)
    if not item then return end
    r = tonumber(r) or 1; g = tonumber(g) or 1; b = tonumber(b) or 1
    pcall(function()
        local v = item:getVisual()
        if v and v.setTint and ImmutableColor and ImmutableColor.new then
            v:setTint(ImmutableColor.new(r, g, b, 1))
        end
        if item.setColorRed then item:setColorRed(r) end
        if item.setColorGreen then item:setColorGreen(g) end
        if item.setColorBlue then item:setColorBlue(b) end
        if item.setColor and Color and Color.new then item:setColor(Color.new(r, g, b, 1)) end
        if item.setCustomColor then item:setCustomColor(true) end
        if item.synchWithVisual then item:synchWithVisual() end
    end)
end