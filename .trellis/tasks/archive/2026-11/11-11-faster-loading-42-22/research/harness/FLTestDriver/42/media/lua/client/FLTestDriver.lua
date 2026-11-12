-- Temporary test driver (not shipped). Idle unless Zomboid/Lua/fltest.txt exists. From the main menu it waits, starts
-- a new sandbox world named in the config, stays in game for a while and quits. Steps go to
-- Zomboid/Lua/fltest_state.txt and console.txt with the prefix [FLTEST].
local CFG = "fltest.txt"
local STATE = "fltest_state.txt"

local function readKv(name)
    local ok, r = pcall(getFileReader, name, false)
    if not ok or not r then return nil end
    local t, line = {}, r:readLine()
    while line do
        local k, v = string.match(line, "^([%w_]+)=(.*)$")
        if k then t[k] = v end
        line = r:readLine()
    end
    r:close()
    return t
end

local cfg = readKv(CFG)
if not cfg then return end

local WORLD = cfg.world
local MENU_MS = (tonumber(cfg.menuSec) or 35) * 1000
local GAME_MS = (tonumber(cfg.gameSec) or 20) * 1000

local function now() return getTimestampMs() end

local function mark(s)
    local line = tostring(now()) .. " " .. tostring(s)
    local w = getFileWriter(STATE, true, true)
    w:write(line .. "\n")
    w:close()
    print("[FLTEST] " .. line)
end

local function done()
    local r = getFileReader(STATE, false)
    if not r then return false end
    local found, line = false, r:readLine()
    while line do
        if string.find(line, " newgame ", 1, true) then found = true end
        line = r:readLine()
    end
    r:close()
    return found
end

-- Lua reloads when the world's mod list differs from the menu's: only the first main menu starts the world
local started = done()
local menuSince = nil

local function startWorld()
    ActiveMods.getById("currentGame"):copyFrom(ActiveMods.getById("default"))
    MainScreen.instance:setDefaultSandboxVars()
    getWorld():setGameMode(GameMode.SANDBOX)
    getWorld():setMap("Muldraugh, KY")
    getWorld():setWorld(WORLD)
    deleteSave("Sandbox/" .. WORLD)
    createWorld(WORLD)
    GameWindow.doRenderEvent(false)
    forceChangeState(LoadingQueueState.new())
end

Events.OnFETick.Add(function()
    if started or not WORLD then return end
    if not MainScreen.instance then return end
    if not menuSince then
        menuSince = now()
        mark("menu ")
        return
    end
    if now() - menuSince < MENU_MS then return end
    started = true
    mark("newgame " .. WORLD .. " ")
    local ok, err = pcall(startWorld)
    if not ok then mark("error " .. tostring(err) .. " ") end
end)

local gameSince = nil
local quitting = false
Events.OnGameStart.Add(function()
    gameSince = now()
    mark("ingame ")
end)
Events.OnTick.Add(function()
    if quitting or not gameSince or now() - gameSince < GAME_MS then return end
    quitting = true
    mark("quit ")
    getCore():quitToDesktop()
end)
