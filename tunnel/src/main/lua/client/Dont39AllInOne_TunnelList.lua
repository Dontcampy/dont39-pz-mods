-- Dont39 All-in-One: tunnel server list. Shows the tunnel of each saved server that has one, after its address.

require "OptionScreens/MultiplayerUI"

local Common = require "Dont39AllInOne_Common"
local Shared = require "Dont39AllInOne_TunnelShared"

local PREFIX = "[Dont39AllInOne] tunnel server list: "
local SEPARATOR = " | " -- what MultiplayerUI:drawAccountListItem puts between the address and the version
local TAG_ALPHA = 0.9 -- drawAccountListItem's
local STATE = "Dont39AllInOne_Tunnel" -- this file's mark on a list instance

if not MultiplayerUI or not MultiplayerUI.refreshList then
    error(PREFIX .. "the game's server list (MultiplayerUI) was not found")
end

local vanillaRefreshList = MultiplayerUI.refreshList
local guard = Common.guard(PREFIX)
local fail = guard.fail

-- The tags by address, read from the store on first draw; cleared whenever the store may have changed.
local tags = nil
local tagsVersion = nil

-- The tag of [server]'s address, nil when it has no tunnel.
local function tag(server)
    if not tags or tagsVersion ~= Shared.storeVersion then
        local read = {}
        for key, entry in pairs(Shared.readStore() or {}) do
            local port = (entry.mode == "tcp" and entry.tcp) or (entry.mode == "kcp" and entry.kcp) or 0
            if port ~= 0 then
                local name = getText("UI_Dont39AllInOne_Tunnel_" .. Shared.MODE_TEXT[entry.mode])
                read[key] = { name = name, withPort = name .. " " .. Shared.portText(port) }
            end
        end
        tags, tagsVersion = read, Shared.storeVersion
    end
    return tags[Shared.trim(server:getIp()) .. ":" .. Shared.portText(server:getPort())]
end

-- After vanilla's "address | version" line of a server item, at the place drawAccountListItem's layout ends it.
local function drawTag(list, y, item)
    local entry = item.item
    if not entry or entry.type ~= "server" then return end
    local server = entry.server
    local found = tag(server)
    if not found then return end
    local textManager = getTextManager()
    local version = server:getVersion()
    local iconSize = list.itemheight - Shared.GAP
    local x = iconSize + Shared.GAP
        + textManager:MeasureStringX(UIFont.Small, server:getDisplayAddress())
        + textManager:MeasureStringX(UIFont.Small, SEPARATOR)
        + textManager:MeasureStringX(UIFont.NewSmall, version and ("v " .. version) or "v -")
    local textY = y + textManager:getFontHeight(UIFont.Medium) + Shared.GAP
    list:drawText(SEPARATOR, x, textY, 1, 1, 1, TAG_ALPHA, UIFont.Small)
    x = x + textManager:MeasureStringX(UIFont.Small, SEPARATOR)
    -- streamer mode hides the port, as vanilla hides the address
    local text = getCore():getOptionStreamerMode() and found.name or found.withPort
    list:drawText(text, x, textY, 1, 1, 1, TAG_ALPHA, UIFont.NewSmall)
end

-- On the list instance, not the class: vanilla copies its renderer into the list when it builds it.
local function hookList(list)
    if list[STATE] then return end
    local draw = list.doDrawItem
    if type(draw) ~= "function" then error("the server list has no item renderer") end
    list[STATE] = true
    list.doDrawItem = function(box, y, item, alt)
        local result = draw(box, y, item, alt)
        if not guard.disabled then
            local ok, err, trace = pcall(drawTag, box, y, item)
            if not ok then
                fail("drawing a server's tunnel", err, trace)
            end
        end
        return result
    end
end

function MultiplayerUI:refreshList(...)
    vanillaRefreshList(self, ...)
    tags = nil
    if guard.disabled then return end
    local ok, err, trace = pcall(function()
        if not self.accountList then error("the list of saved servers (accountList) was not found") end
        hookList(self.accountList)
    end)
    if not ok then
        fail("hooking the list of saved servers", err, trace)
    end
end

-- a list built before this file was loaded
if MultiplayerUI.instance and MultiplayerUI.instance.accountList then
    local ok, err, trace = pcall(hookList, MultiplayerUI.instance.accountList)
    if not ok then
        fail("hooking the list of saved servers", err, trace)
    end
end
