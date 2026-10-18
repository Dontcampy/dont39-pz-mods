-- Dont39 All-in-One: tunnel join check. Refuses a join to an address with a tunnel chosen when the Java side cannot
-- carry it, instead of letting it go over UDP.

local Shared = require "Dont39AllInOne_TunnelShared"

local PREFIX = "[Dont39AllInOne] tunnel join check: "

-- Why the join to [ip]:[port] must not go on, translated; nil to let it.
local function refusal(ip, port)
    if type(ip) ~= "string" or port == nil then return nil end
    local number = tonumber(type(port) == "string" and Shared.trim(port) or port)
    -- not a port GameClient.doConnect parses, or a Steam ID join; neither has an entry
    if not number or number ~= math.floor(number) or (number == 0 and getSteamModeActive()) then return nil end
    local entries = Shared.readStore()
    local entry = entries and entries[Shared.trim(ip) .. ":" .. Shared.portText(number)]
    if not entry or entry.mode == "off" then return nil end
    if not Dont39AllInOne_getTunnelProblem then
        return getText("UI_Dont39AllInOne_Tunnel_Failed_NoJava")
    end
    local problem = Dont39AllInOne_getTunnelProblem()
    if problem then
        return getText("UI_Dont39AllInOne_Tunnel_Failed_Inactive", problem)
    end
    return nil
end

-- every dedicated-server join from vanilla Lua goes through this global
local vanillaServerConnect = serverConnect
if not vanillaServerConnect then
    error(PREFIX .. "the game's serverConnect function was not found")
end

serverConnect = function(userName, password, ip, localIP, port, ...)
    local ok, refused, trace = pcall(refusal, ip, port)
    if not ok then
        -- fail closed: letting the join through would be the UDP fallback the player did not choose
        pcall(function()
            DebugType.Mod:error(PREFIX .. "refused a join after an error: " .. tostring(refused) .. "\n"
                .. tostring(trace))
        end)
        refused = getText("UI_Dont39AllInOne_Tunnel_Failed_Check", tostring(refused))
    end
    if refused then
        Shared.log(PREFIX, "refused the join to " .. tostring(ip) .. ":" .. tostring(port) .. ": " .. refused)
        -- as vanilla's unknown host: the join screen shows the text and a Back button
        triggerEvent("OnConnectFailed", refused)
        return
    end
    return vanillaServerConnect(userName, password, ip, localIP, port, ...)
end
