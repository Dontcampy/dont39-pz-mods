-- Dont39 All-in-One: tunnel. What the join check, the add/edit server dialog and the server list share, mainly the
-- store of each server address's tunnel, which the Java side writes and reads when the game connects.

local Shared = {}

Shared.GAP = 10 -- ISMPEditServer's UI_BORDER_SPACING
Shared.MAX_PORT = 65535
Shared.MODE_TEXT = { off = "Off", tcp = "Tcp", kcp = "Kcp" } -- UI_Dont39AllInOne_Tunnel_<text>, _<text>Tooltip
-- in Zomboid/Lua, written by TunnelStore.kt; the released TCP-only file is read while the new one does not exist
Shared.STORE = "Dont39AllInOne_Tunnel.txt"
local RELEASED_STORE = "Dont39AllInOne_TcpTunnel.txt"

-- Bumped when the dialog stored a tunnel, so a part that keeps what it read from the store reads it again.
Shared.storeVersion = 0

function Shared.storeChanged()
    Shared.storeVersion = Shared.storeVersion + 1
end

function Shared.trim(text)
    return string.match(text, "^%s*(.-)%s*$")
end

-- as text: the Java side would get Lua numbers as doubles
function Shared.portText(number)
    return tostring(math.floor(number))
end

-- The file's lines, nil when it does not exist.
local function readLines(name)
    local reader = getFileReader(name, false)
    if not reader then return nil end
    local lines = {}
    local ok, err = pcall(function()
        local line = reader:readLine()
        while line do
            lines[#lines + 1] = line
            line = reader:readLine()
        end
    end)
    reader:close()
    if not ok then error("reading Zomboid/Lua/" .. name .. ": " .. tostring(err)) end
    return lines
end

-- A port as stored, nil when it is none.
local function storedPort(text)
    local number = text and tonumber(text)
    if not number or number > Shared.MAX_PORT then return nil end
    return number
end

-- Every stored address ("<host>:<game port>") with its mode and both ports (0 when never set); nil when there is no
-- store. Parsed as TunnelStore.kt does; change both together. The fixtures in tunnel/src/test/resources/store/ pin
-- the format.
function Shared.readStore()
    local name, released = Shared.STORE, false
    local lines = readLines(Shared.STORE)
    if not lines then
        name, released = RELEASED_STORE, true
        lines = readLines(RELEASED_STORE)
        if not lines then return nil end
    end
    local entries = {}
    for i, line in ipairs(lines) do
        if not string.match(line, "^%s*$") then
            local entryKey, value = string.match(line, "^(.*)=([^=]*)$")
            local mode, tcp, kcp = nil, nil, nil
            if entryKey then
                entryKey, value = Shared.trim(entryKey), Shared.trim(value)
                if released then
                    tcp = storedPort(string.match(value, "^%d+$"))
                    mode, kcp = tcp and tcp ~= 0 and "tcp", 0
                else
                    local tcpText, kcpText
                    mode, tcpText, kcpText = string.match(value, "^(%a+)%s*,%s*(%d+)%s*,%s*(%d+)$")
                    tcp, kcp = storedPort(tcpText), storedPort(kcpText)
                end
            end
            local chosen = (mode == "tcp" and tcp) or (mode == "kcp" and kcp) or nil
            if not entryKey or entryKey == "" or not tcp or not kcp
                or (mode ~= "off" and mode ~= "tcp" and mode ~= "kcp") or chosen == 0 then
                error("Zomboid/Lua/" .. name .. " line " .. i .. " is not a tunnel entry: " .. line)
            end
            -- the last line of an address wins, as in the Java side's map
            entries[entryKey] = { mode = mode, tcp = tcp, kcp = kcp }
        end
    end
    return entries
end

return Shared
