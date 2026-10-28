-- Dont39 All-in-One: what the features' client Lua files share: the shared module's helpers (log, guard) and the
-- client's own.

local Common = {}
for name, fn in pairs(require "Dont39AllInOne_CommonShared") do
    Common[name] = fn
end

-- a line never starts with these: the break moves one character earlier. As code points: the game does not read
-- Lua source as UTF-8, so a literal never equals the translations' characters
local CLOSING = {
    [0xFF0C] = true, [0x3002] = true, [0x3001] = true, [0xFF1B] = true, [0xFF1A] = true, [0xFF1F] = true, -- ，。、；：？
    [0xFF01] = true, [0xFF09] = true, [0x300D] = true, [0x300F] = true, [0x300B] = true, -- ！）」』》
}

local function closing(c)
    return CLOSING[string.byte(c)] == true
end

-- the rich text panel breaks lines only at spaces, and Chinese text has none: split longer runs to the width
function Common.breakable(text, font, width)
    local tm = getTextManager()
    local words = {}
    for word in string.gmatch(text, "%S+") do
        if tm:MeasureStringX(font, word) <= width then
            words[#words + 1] = word
        else
            local piece = ""
            for i = 1, string.len(word) do
                local c = string.sub(word, i, i)
                if piece ~= "" and tm:MeasureStringX(font, piece .. c) > width then
                    local n = string.len(piece)
                    if closing(c) and n > 1 then
                        words[#words + 1] = string.sub(piece, 1, n - 1)
                        piece = string.sub(piece, n, n) .. c
                    else
                        words[#words + 1] = piece
                        piece = c
                    end
                else
                    piece = piece .. c
                end
            end
            words[#words + 1] = piece
        end
    end
    return table.concat(words, " ")
end

-- the rich text panel reads " <...> " as a tag
function Common.plain(text)
    return (string.gsub(string.gsub(tostring(text), "<", "("), ">", ")"))
end

-- [text] as rich text lines that fit [width] in the panel's small font
function Common.lines(text, width)
    local lines = {}
    for line in string.gmatch(tostring(text) .. "\n", "(.-)\n") do
        lines[#lines + 1] = Common.breakable(Common.plain(line), UIFont.NewSmall, width)
    end
    return table.concat(lines, " <LINE> ")
end

return Common
