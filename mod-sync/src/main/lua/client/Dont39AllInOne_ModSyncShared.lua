-- Dont39 All-in-One: mod sync. What the join screen and the rejoin offer share: text for the rich text panel.

local Shared = {}

Shared.KEY = "UI_Dont39AllInOne_ModSync_"
Shared.PAD = 10

-- a line never starts with these: the break moves one character earlier
local CLOSING = "，。、；：？！）」』》"

local function closing(c)
    return string.find(CLOSING, c, 1, true) ~= nil
end

-- the rich text panel breaks lines only at spaces, and Chinese text has none: split longer runs to the width
function Shared.breakable(text, font, width)
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
function Shared.plain(text)
    return (string.gsub(string.gsub(tostring(text), "<", "("), ">", ")"))
end

-- [text] as rich text lines that fit [width] in the panel's small font
function Shared.lines(text, width)
    local lines = {}
    for line in string.gmatch(tostring(text) .. "\n", "(.-)\n") do
        lines[#lines + 1] = Shared.breakable(Shared.plain(line), UIFont.NewSmall, width)
    end
    return table.concat(lines, " <LINE> ")
end

return Shared
