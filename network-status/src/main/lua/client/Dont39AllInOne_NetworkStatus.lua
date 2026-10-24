-- Dont39 All-in-One: network-status. In multiplayer, a signal icon in the bottom-left corner: bars for the ping, a
-- warning triangle for packet loss, and the details on hover. The numbers come from the Java side; while the tunnel
-- feature carries the connection, its link counters replace RakNet's loss, which the tunnel hides.

require "ISUI/ISUIElement"

local Common = require "Dont39AllInOne_Common"

-- not "network-status: ", which prefixes the Java side's status lines
local PREFIX = "[Dont39AllInOne] network-status icon: "
local OPTIONS_ID = "Dont39AllInOne_NetworkStatus"
local TEXT = "UI_Dont39AllInOne_NetworkStatus_"
local BADGE_TEXTURE = "media/ui/Dont39AllInOne_NetworkStatus_Warning.png"
local FONT = UIFont.Small

local MARGIN = 10
local WIDTH, HEIGHT = 34, 22
local BAR_COUNT, BAR_WIDTH, BAR_GAP, BAR_STEP = 4, 4, 2, 4
local BAR_LEFT, BAR_BOTTOM = 4, HEIGHT - 3
local BADGE_SIZE = 13
local TIP_PAD, TIP_GAP = 6, 4

local SAMPLE_MS = 1000
local WINDOW = 5 -- samples the icon averages over
local TRAFFIC_ROWS = 6 -- packet types the tooltip lists

local BACKGROUND = { r = 0, g = 0, b = 0, a = 0.45 }
local GRAY = { r = 0.55, g = 0.55, b = 0.55 }
local TIERS = {
    { below = 100, bars = 4, color = { r = 0.25, g = 0.85, b = 0.3 } },
    { below = 200, bars = 3, color = { r = 0.7, g = 0.85, b = 0.2 } },
    { below = 350, bars = 2, color = { r = 1, g = 0.6, b = 0.1 } },
    { bars = 1, color = { r = 0.95, g = 0.25, b = 0.2 } },
}
local LOSS_AMBER, LOSS_RED = 0.01, 0.05
local AMBER = { r = 1, g = 0.75, b = 0.1 }
local RED = { r = 0.95, g = 0.2, b = 0.15 }
local TEXT_COLOR = { r = 1, g = 1, b = 1 }
local DEBUG_COLOR = { r = 0.7, g = 0.7, b = 0.7 }
local METHOD_TEXT = { udp = "Udp", steam = "Steam", tcp = "Tcp", kcp = "Kcp" } -- UI_Dont39AllInOne_NetworkStatus_Method<text>

if not PZAPI or not PZAPI.ModOptions or not PZAPI.ModOptions.create then
    error(PREFIX .. "the game's mod options (PZAPI.ModOptions) were not found")
end

local options = PZAPI.ModOptions:create(OPTIONS_ID, TEXT .. "Options")
local showOption = options:addTickBox("show", TEXT .. "Show", true, TEXT .. "ShowTooltip")
local reportOption = options:addTickBox("report", TEXT .. "Report", false, TEXT .. "ReportTooltip")

local icon = nil
local guard = Common.guard(PREFIX, function()
    if icon then
        pcall(function() icon:removeFromUIManager() end)
        icon = nil
    end
end)
local fail = guard.fail
local badgeTexture = nil

-- ---- sampling ----

local status = nil -- the Java side's last table; nil when not connected
local tunnel = nil -- the tunnel feature's last table (keys: TunnelStatus.kt); nil when no tunnel carries the connection
local history = {} -- oldest first; one more than WINDOW, so tunnel deltas span WINDOW seconds
local windowKey = nil -- method and tunnel the history belongs to
local nextSample = 0

local function resetWindow()
    history = {}
end

local function counter(t, key)
    local value = t[key]
    if type(value) ~= "number" then error("the tunnel status has no number " .. key .. ": " .. tostring(value)) end
    return value
end

-- A new tunnel restarts its counters.
local function countersWentBack(previous, current)
    if not previous or not current or current.mode ~= "kcp" or previous.mode ~= "kcp" then return false end
    for _, key in ipairs({ "segmentsSent", "retransmitsTimeout", "retransmitsFast", "seqExpected" }) do
        if counter(current, key) < counter(previous, key) then return true end
    end
    return false
end

local function sample()
    status = Dont39AllInOne_getNetworkStatus()
    tunnel = nil
    if not status or status.disabled then
        resetWindow()
        return
    end
    -- absent when the tunnel feature is not there
    if Dont39AllInOne_getTunnelStatus then
        tunnel = Dont39AllInOne_getTunnelStatus()
    end
    local key = status.method .. (tunnel and ("/" .. tunnel.mode .. "/" .. tostring(tunnel.port)) or "")
    local last = history[#history]
    if key ~= windowKey or (last and countersWentBack(last.tunnel, tunnel)) then
        windowKey = key
        resetWindow()
    end
    -- RakNet's last ping is negative before it has one
    local ping = status.pingLast
    history[#history + 1] = {
        ping = (type(ping) == "number" and ping >= 0) and ping or nil,
        loss = status.stats and status.lossLastSecond or nil,
        tunnel = tunnel,
    }
    while #history > WINDOW + 1 do
        table.remove(history, 1)
    end
end

-- The average of [field] over the last WINDOW samples that have it; nil when none has.
local function average(field)
    local sum, count = 0, 0
    for i = math.max(1, #history - WINDOW + 1), #history do
        local value = history[i][field]
        if value then
            sum, count = sum + value, count + 1
        end
    end
    return count > 0 and sum / count or nil
end

-- KCP loss over the window as fractions; nil where a quiet window has nothing to divide by.
local function kcpWindow()
    local first, last = history[1], history[#history]
    if not first or first == last or not first.tunnel or not last.tunnel then return nil, nil end
    local a, b = first.tunnel, last.tunnel
    local sent = counter(b, "segmentsSent") - counter(a, "segmentsSent")
    local resent = counter(b, "retransmitsTimeout") + counter(b, "retransmitsFast")
        - counter(a, "retransmitsTimeout") - counter(a, "retransmitsFast")
    local expected = counter(b, "seqExpected") - counter(a, "seqExpected")
    -- a late shard filling an earlier gap lowers the count
    local missing = math.max(0, counter(b, "seqMissing") - counter(a, "seqMissing"))
    local inbound = expected > 0 and missing / expected or nil
    local outbound = sent > 0 and resent / sent or nil
    return inbound, outbound
end

local function method()
    if tunnel then return tunnel.mode end
    return status.method
end

-- What the badge reads: a fraction, or nil for no badge.
local function badgeLoss()
    local m = method()
    if m == "tcp" then return nil end
    if m == "kcp" then
        local inbound, outbound = kcpWindow()
        if inbound and outbound then return math.max(inbound, outbound) end
        return inbound or outbound
    end
    if not status.stats then return nil end
    return average("loss")
end

local function tier()
    if status.disabled then return nil end
    local ping = average("ping")
    if not ping then return nil end
    for _, t in ipairs(TIERS) do
        if not t.below or ping < t.below then return t end
    end
end

-- ---- tooltip ----

local function percent(fraction)
    if not fraction then return getText(TEXT .. "NoSample") end
    return string.format("%.1f%%", fraction * 100)
end

local function kilobytes(bytes)
    return string.format("%.1f", bytes / 1024)
end

local function whole(number)
    return tostring(math.floor(number + 0.5))
end

local function megabytes(bytes)
    return string.format("%.2f", bytes / (1024 * 1024))
end

local function tooltipLines()
    local lines = {}
    local function add(text, color)
        lines[#lines + 1] = { text = text, color = color or TEXT_COLOR }
    end
    if status.disabled then
        add(getText(TEXT .. "Disabled"))
        return lines
    end
    local streamer = getCore():getOptionStreamerMode()
    local m = method()

    if not METHOD_TEXT[m] then error("unknown connection method " .. tostring(m)) end
    local methodName = getText(TEXT .. "Method" .. METHOD_TEXT[m])
    if tunnel and not streamer then
        methodName = getText(TEXT .. "WithPort", methodName, whole(tunnel.port))
    end
    add(getText(TEXT .. "Method", methodName))

    if status.pingLast >= 0 then
        add(getText(TEXT .. "Ping", whole(status.pingLast), whole(status.pingAvg), whole(status.pingLow)))
    else
        add(getText(TEXT .. "PingNone"))
    end

    if m == "tcp" then
        add(getText(TEXT .. "TcpLoss"))
    elseif m == "kcp" then
        local inbound, outbound = kcpWindow()
        add(getText(TEXT .. "KcpInbound", percent(inbound)))
        add(getText(TEXT .. "KcpOutbound", percent(outbound)))
        add(getText(TEXT .. "KcpFec", whole(counter(tunnel, "fecRecovered")), whole(counter(tunnel, "fecUnrecoverable"))))
        add(getText(TEXT .. "KcpRtt", whole(counter(tunnel, "srttMillis"))))
    elseif status.stats then
        add(getText(TEXT .. "Loss", percent(status.lossLastSecond), percent(status.lossTotal)))
    else
        add(getText(TEXT .. "LossNotReported"))
    end

    if status.stats then
        add(getText(TEXT .. "Traffic", kilobytes(status.bytesSentPerSec), kilobytes(status.bytesRecvPerSec)))
    end

    local name = getServerName()
    if streamer then
        add(getText(TEXT .. "ServerName", tostring(name)))
    else
        add(getText(TEXT .. "Server", tostring(name), tostring(getServerIP()) .. ":" .. tostring(getServerPort())))
    end

    local traffic = status.traffic -- nil before the connection's first packet
    if status.stats or traffic then
        add(getText(TEXT .. "Debug"), DEBUG_COLOR)
    end
    if status.stats then
        add(getText(TEXT .. "Resend", whole(status.resendMessages), whole(status.resendBytes)), DEBUG_COLOR)
        if status.congestionLimited then
            add(getText(TEXT .. "Congestion", kilobytes(status.congestionBps)), DEBUG_COLOR)
        else
            add(getText(TEXT .. "CongestionNone"), DEBUG_COLOR)
        end
        if status.bandwidthLimited then
            add(getText(TEXT .. "Bandwidth", kilobytes(status.bandwidthBps)), DEBUG_COLOR)
        else
            add(getText(TEXT .. "BandwidthNone"), DEBUG_COLOR)
        end
        add(getText(TEXT .. "SendBuffer", whole(status.sendBufferBytes)), DEBUG_COLOR)
    end
    if traffic then
        local received = megabytes(traffic.bytes)
        local minutes = whole(traffic.seconds / 60)
        if status.stats then
            -- RakNet's count adds its headers and acknowledgements
            local share = status.bytesRecvTotal > 0 and traffic.bytes / status.bytesRecvTotal or nil
            add(getText(TEXT .. "Received", received, minutes, percent(share)), DEBUG_COLOR)
        else
            add(getText(TEXT .. "ReceivedNoTotal", received, minutes), DEBUG_COLOR)
        end
        local types = traffic.types
        for i = 1, math.min(TRAFFIC_ROWS, #types) do
            local row = types[i]
            local share = traffic.bytes > 0 and row.bytes / traffic.bytes or nil
            add(getText(TEXT .. "ReceivedType", row.name, megabytes(row.bytes), percent(share), whole(row.packets)),
                DEBUG_COLOR)
        end
    end
    return lines
end

-- Above the icon, which sits at the screen's bottom edge.
local function drawTooltip(self)
    local lines = tooltipLines()
    local textManager = getTextManager()
    local lineHeight = textManager:getFontHeight(FONT)
    local width = 0
    for _, line in ipairs(lines) do
        width = math.max(width, textManager:MeasureStringX(FONT, line.text))
    end
    width = width + TIP_PAD * 2
    local height = #lines * lineHeight + TIP_PAD * 2
    local y = -height - TIP_GAP
    self:drawRect(0, y, width, height, 0.85, 0, 0, 0)
    self:drawRectBorder(0, y, width, height, 0.6, 0.6, 0.6, 0.6)
    for i, line in ipairs(lines) do
        local c = line.color
        self:drawText(line.text, TIP_PAD, y + TIP_PAD + (i - 1) * lineHeight, c.r, c.g, c.b, 1, FONT)
    end
end

-- ---- icon ----

local function drawBadge(self, color)
    if not badgeTexture then
        badgeTexture = getTexture(BADGE_TEXTURE)
        if not badgeTexture then error("the texture " .. BADGE_TEXTURE .. " was not found") end
    end
    local x, y = WIDTH - BADGE_SIZE, HEIGHT - BADGE_SIZE
    -- a dark rim keeps it readable over the bars
    self:drawTextureScaled(badgeTexture, x - 1, y - 1, BADGE_SIZE + 2, BADGE_SIZE + 2, 0.9, 0, 0, 0)
    self:drawTextureScaled(badgeTexture, x, y, BADGE_SIZE, BADGE_SIZE, 1, color.r, color.g, color.b)
end

local function drawIcon(self)
    self:drawRect(0, 0, WIDTH, HEIGHT, BACKGROUND.a, BACKGROUND.r, BACKGROUND.g, BACKGROUND.b)
    local t = tier()
    local filled = t and t.bars or 0
    for i = 1, BAR_COUNT do
        local x = BAR_LEFT + (i - 1) * (BAR_WIDTH + BAR_GAP)
        local h = i * BAR_STEP
        if i <= filled then
            self:drawRect(x, BAR_BOTTOM - h, BAR_WIDTH, h, 1, t.color.r, t.color.g, t.color.b)
        else
            self:drawRectBorder(x, BAR_BOTTOM - h, BAR_WIDTH, h, 0.7, GRAY.r, GRAY.g, GRAY.b)
        end
    end
    if not t then
        local right = BAR_LEFT + BAR_COUNT * BAR_WIDTH + (BAR_COUNT - 1) * BAR_GAP
        local top = BAR_BOTTOM - BAR_COUNT * BAR_STEP
        self:drawLine2(BAR_LEFT, top, right, BAR_BOTTOM, 1, GRAY.r, GRAY.g, GRAY.b)
        self:drawLine2(BAR_LEFT, BAR_BOTTOM, right, top, 1, GRAY.r, GRAY.g, GRAY.b)
        return
    end
    local loss = badgeLoss()
    if loss and loss >= LOSS_AMBER then
        drawBadge(self, loss >= LOSS_RED and RED or AMBER)
    end
end

local NetworkStatusIcon = ISUIElement:derive("Dont39AllInOne_NetworkStatusIcon")

function NetworkStatusIcon:instantiate()
    ISUIElement.instantiate(self)
    -- clicks in the corner still reach the game
    self.javaObject:setConsumeMouseEvents(false)
end

local function update(self)
    if not showOption:getValue() then
        status, tunnel, nextSample = nil, nil, 0
        resetWindow()
        return
    end
    local now = getTimestampMs()
    if now >= nextSample then
        nextSample = now + SAMPLE_MS
        sample()
    end
    if not status then return end
    drawIcon(self)
    local hovered = self:isMouseOver()
    if hovered and not self.hovered then
        -- the tooltip draws over the chat and other windows
        self:bringToTop()
    end
    self.hovered = hovered
    if hovered then
        drawTooltip(self)
    end
end

function NetworkStatusIcon:render()
    if guard.disabled then return end
    local ok, err, trace = pcall(update, self)
    if not ok then
        fail("drawing the network status icon", err, trace)
    end
end

local function anchor(element)
    element:setX(MARGIN)
    element:setY(getCore():getScreenHeight() - MARGIN - HEIGHT)
end

local function create()
    if icon then icon:removeFromUIManager() end
    status, tunnel, windowKey, nextSample = nil, nil, nil, 0
    resetWindow()
    local element = NetworkStatusIcon:new(MARGIN, 0, WIDTH, HEIGHT)
    element:initialise()
    anchor(element)
    element:addToUIManager()
    icon = element
end

local reportedNoJava = false

-- the Java side writes the table when a session ends, possibly off the game thread, so it keeps its own copy
local function pushReport()
    if guard.disabled or not Dont39AllInOne_setNetworkTrafficReport then return end
    local ok, err, trace = pcall(Dont39AllInOne_setNetworkTrafficReport, reportOption:getValue())
    if not ok then
        fail("passing the download table option to the Java side", err, trace)
    end
end

function options:apply()
    pushReport()
end

Events.OnConnected.Add(pushReport)

Events.OnGameStart.Add(function()
    pushReport()
    if guard.disabled or not isClient() then return end
    -- absent when ZombieBuddy did not load this mod's Java code
    if not Dont39AllInOne_getNetworkStatus then
        if not reportedNoJava then
            reportedNoJava = true
            Common.log(PREFIX, "no icon: the Java part of Dont39 All-in-One is not loaded")
        end
        return
    end
    local ok, err, trace = pcall(create)
    if not ok then
        fail("adding the network status icon", err, trace)
    end
end)

Events.OnResolutionChange.Add(function()
    if guard.disabled or not icon then return end
    local ok, err, trace = pcall(anchor, icon)
    if not ok then
        fail("moving the network status icon after a resolution change", err, trace)
    end
end)
