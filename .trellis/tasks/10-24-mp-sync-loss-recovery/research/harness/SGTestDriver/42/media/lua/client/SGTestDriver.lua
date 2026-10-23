-- Test driver of Sync Guard's local test harness (research/harness, not shipped). Idle unless Zomboid/Lua/sgtest.txt
-- exists (start-client.ps1 writes it). Joins the server from the main menu, then in game: god mode, teleport to the
-- test spot, and loops over nearby containers (open, take, put back or move, eat now and then) until the duration
-- ends. Steps go to Zomboid/Lua/sgtest_state.txt and console.txt with the prefix [SGTEST].
local CFG = "sgtest.txt"
local STATE = "sgtest_state.txt"
local STOP = "sgtest_stop.txt"

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

local function now() return getTimestampMs() end
local function num(k, default) return tonumber(cfg[k]) or default end

local function mark(s)
    local line = tostring(now()) .. " " .. tostring(s)
    local w = getFileWriter(STATE, true, true)
    w:write(line .. "\n")
    w:close()
    print("[SGTEST] " .. line)
end

-- ---- scheduler: {at, cond, deadline, fn, onErr}, pumped from the menu's and the game's ticks ----

local tasks = {}
local function after(ms, fn, onErr) table.insert(tasks, { at = now() + ms, fn = fn, onErr = onErr }) end
-- fn(true) once cond() holds, fn(false) at the deadline
local function waitFor(cond, timeoutMs, fn, onErr)
    table.insert(tasks, { at = now(), cond = cond, deadline = now() + timeoutMs, fn = fn, onErr = onErr })
end

local stopping = false
local nextStopCheck = 0
local function checkStop()
    if stopping or now() < nextStopCheck then return end
    nextStopCheck = now() + 2000
    local r = getFileReader(STOP, false)
    if r then
        r:close()
        stopping = true
        mark("stop requested; quit")
        getCore():quitToDesktop()
    end
end

local function pump()
    checkStop()
    local t, i = now(), 1
    while i <= #tasks do
        local k = tasks[i]
        local ready, arg = false, nil
        if t >= k.at then
            if not k.cond then
                ready = true
            else
                local ok, r = pcall(k.cond)
                if ok and r then ready, arg = true, true
                elseif not ok or t >= k.deadline then ready, arg = true, false end
            end
        end
        if ready then
            table.remove(tasks, i)
            local ok, err = pcall(k.fn, arg)
            if not ok then
                mark("error " .. tostring(err))
                if k.onErr then k.onErr(err) end
            end
        else
            i = i + 1
        end
    end
end

-- ---- main menu: join, character creation ----

local function scrubPassword()
    local w = getFileWriter(CFG, true, false)
    for k, v in pairs(cfg) do
        if k ~= "pw" then w:write(k .. "=" .. v .. "\n") end
    end
    w:close()
end

local function join()
    if not cfg.pw then
        mark("menu: no password in " .. CFG .. ", not joining")
        return
    end
    mark("join " .. tostring(cfg.ip) .. ":" .. tostring(cfg.port) .. " as " .. tostring(cfg.user))
    ConnectToServer.instance:connect(MainScreen.instance.bottomPanel, "", cfg.user, cfg.pw, cfg.ip, cfg.ip, cfg.port, "",
        false, true, 1)
    scrubPassword()
end

local function visible(p) return p and p:getIsVisible() end
local function createCharacter()
    local spawn = MapSpawnSelect.instance
    if visible(spawn) then
        local items = spawn.listbox.items
        for i, it in ipairs(items) do
            if it.item and it.item.region and it.item.name ~= getText("UI_mapspawn_random") then
                spawn.listbox.selected = i
                break
            end
        end
        mark("spawnselect n=" .. tostring(#items) .. " pick=" .. tostring(spawn.listbox.selected))
        spawn:onOptionMouseDown({ internal = "NEXT" })
        after(3000, createCharacter)
        return
    end
    local prof = MainScreen.instance.charCreationProfession
    if visible(prof) then
        mark("charcreation profession")
        prof:onOptionMouseDown({ internal = "NEXT" })
        after(3000, createCharacter)
        return
    end
    local main = MainScreen.instance.charCreationMain
    if visible(main) then
        mark("charnext")
        main:onOptionMouseDown({ internal = "NEXT" })
        return
    end
    mark("charcreation nothing visible")
end

Events.OnConnected.Add(function()
    if checkSavePlayerExists() then
        mark("connected existing")
    else
        mark("connected new")
        after(3000, createCharacter)
    end
end)

local menuSeen = false
Events.OnMainMenuEnter.Add(function()
    if menuSeen then return end
    menuSeen = true
    mark("menu")
    after(3000, join)
end)
Events.OnFETick.Add(pump)

-- ---- in game ----

local spot = { name = cfg.spotName or "?", x = num("x", 0), y = num("y", 0), z = num("z", 0) }
local radius = num("radius", 20)
local stats = { cycles = 0, taken = 0, putBack = 0, moved = 0, eats = 0, walkFails = 0, errors = 0 }
local endAt = 0
local finished = false

local function pos()
    local p = getPlayer()
    return string.format("%d,%d,%d", math.floor(p:getX()), math.floor(p:getY()), math.floor(p:getZ()))
end

local function statsLine()
    local cell = getCell()
    return string.format("cycles=%d taken=%d putBack=%d moved=%d eats=%d walkFails=%d errors=%d pos=%s zombies=%d",
        stats.cycles, stats.taken, stats.putBack, stats.moved, stats.eats, stats.walkFails, stats.errors, pos(),
        cell and cell:getZombieList():size() or -1)
end

local godReplied = false
if Events.OnAddMessage then
    Events.OnAddMessage.Add(function(msg)
        local ok, text = pcall(function() return msg:getText() end)
        if ok and text and string.find(text, "invincible", 1, true) then
            godReplied = true
            mark("server: " .. text)
        end
    end)
end

-- containers on the player's level within the radius, nearest first
local containers = {}
local function scan()
    local p = getPlayer()
    local cell = getCell()
    local px, py, pz = math.floor(p:getX()), math.floor(p:getY()), math.floor(p:getZ())
    local found = {}
    for x = px - radius, px + radius do
        for y = py - radius, py + radius do
            local sq = cell:getGridSquare(x, y, pz)
            if sq then
                local objs = sq:getObjects()
                for i = 0, objs:size() - 1 do
                    local o = objs:get(i)
                    local locked = instanceof(o, "IsoThumpable") and o:isLockedToCharacter(p)
                    for c = 0, o:getContainerCount() - 1 do
                        local cont = o:getContainerByIndex(c)
                        if cont and not locked then
                            local d = (x - px) * (x - px) + (y - py) * (y - py)
                            table.insert(found, { obj = o, cont = cont, sq = sq, d = d })
                        end
                    end
                end
            end
        end
    end
    table.sort(found, function(a, b) return a.d < b.d end)
    containers = found
    mark("scan radius=" .. radius .. " containers=" .. #found .. " at " .. pos())
end

local function pick(exclude)
    local n = math.min(#containers, 30)
    for _ = 1, 10 do
        if n == 0 then return nil end
        local e = containers[ZombRand(n) + 1]
        if e ~= exclude and e.obj:getSquare() ~= nil then return e end
    end
    return nil
end

local function idle()
    local q = ISTimedActionQueue.getTimedActionQueue(getPlayer())
    return #q.queue == 0
end

local cycle -- forward
local function restart()
    stats.errors = stats.errors + 1
    if not finished then after(3000, cycle) end
end
local function step(ms, fn) after(ms, fn, restart) end
local function stepWhenIdle(timeoutMs, fn)
    waitFor(idle, timeoutMs, function(done)
        if not done then ISTimedActionQueue.clear(getPlayer()) end
        fn(done)
    end, restart)
end

local function open(e)
    local loot = getPlayerLoot(0)
    if loot then
        loot:refreshBackpacks()
        loot:selectButtonForContainer(e.cont)
    end
    -- as ISInventoryPage:checkExplored, for a container the loot window did not list
    if not e.cont:isExplored() then
        e.cont:requestServerItemsForContainer()
        e.cont:setExplored(true)
    end
end

local function weight(it)
    local ok, w = pcall(function() return it:getActualWeight() end)
    return ok and w or 99
end

local carried = {}
local function stillCarried()
    local inv = getPlayer():getInventory()
    local left = {}
    for _, it in ipairs(carried) do
        if inv:contains(it) then table.insert(left, it) end
    end
    carried = left
    return left
end

local function takeFrom(e)
    local p = getPlayer()
    local inv = p:getInventory()
    local items = e.cont:getItems()
    local n = items:size()
    local want = num("take", 3)
    local picked = {}
    for _ = 1, math.min(n, 12) do
        if #picked >= want then break end
        local it = items:get(ZombRand(n))
        local dup = false
        for _, q in ipairs(picked) do if q == it then dup = true end end
        if not dup and weight(it) <= 3 then table.insert(picked, it) end
    end
    for _, it in ipairs(picked) do
        ISTimedActionQueue.add(ISInventoryTransferUtil.newInventoryTransferAction(p, it, e.cont, inv))
        table.insert(carried, it)
    end
    stats.taken = stats.taken + #picked
    return #picked
end

local function putInto(e)
    local p = getPlayer()
    local inv = p:getInventory()
    local items = stillCarried()
    for _, it in ipairs(items) do
        ISTimedActionQueue.add(ISInventoryTransferUtil.newInventoryTransferAction(p, it, inv, e.cont))
    end
    return #items
end

local function eatOne()
    local p = getPlayer()
    local food = nil
    for _, it in ipairs(stillCarried()) do
        if instanceof(it, "Food") and not it:isRotten() and it:getHungChange() < 0 then food = it break end
    end
    if not food then
        mark("eat: nothing edible carried")
        return false
    end
    ISTimedActionQueue.add(ISEatFoodAction:new(p, food, 0.25))
    stats.eats = stats.eats + 1
    mark("eat " .. food:getFullType())
    return true
end

local function finish()
    if finished then return end
    finished = true
    mark("done " .. statsLine())
    if cfg.quit == "1" then
        after(5000, function()
            mark("quit")
            getCore():quitToDesktop()
        end)
    end
end

local function walkThen(e, fn)
    luautils.walkAdj(getPlayer(), e.sq, true)
    stepWhenIdle(30000, function(done)
        if not done then stats.walkFails = stats.walkFails + 1 end
        fn(done)
    end)
end

cycle = function()
    if finished then return end
    if now() >= endAt then
        finish()
        return
    end
    if #containers == 0 or stats.cycles % 15 == 0 then scan() end
    if #containers == 0 then
        radius = math.min(radius * 2, 60)
        mark("no containers; radius now " .. radius)
        step(5000, cycle)
        return
    end
    local a = pick(nil)
    if not a then
        step(1000, cycle)
        return
    end
    walkThen(a, function(reached)
        if not reached then
            step(500, cycle)
            return
        end
        open(a)
        step(1500, function()
            takeFrom(a)
            stepWhenIdle(30000, function()
                local eatTurn = stats.cycles % num("eatEvery", 10) == num("eatEvery", 10) - 1
                local function putAway()
                    -- half the time back into the same container, otherwise moved to another one
                    local b = (ZombRand(2) == 0) and pick(a) or nil
                    local dest = b or a
                    local function put()
                        open(dest)
                        local n = putInto(dest)
                        if b then stats.moved = stats.moved + n else stats.putBack = stats.putBack + n end
                        stepWhenIdle(30000, function()
                            stats.cycles = stats.cycles + 1
                            step(300, cycle)
                        end)
                    end
                    if b then
                        walkThen(b, function() put() end)
                    else
                        put()
                    end
                end
                if eatTurn and eatOne() then
                    stepWhenIdle(60000, function() putAway() end)
                else
                    putAway()
                end
            end)
        end)
    end)
end

local function progress()
    if finished then return end
    local ok, line = pcall(statsLine)
    mark("progress " .. (ok and line or tostring(line)))
    after(60000, progress)
end

local function beginLoop()
    endAt = now() + num("duration", 1800) * 1000
    after(60000, progress)
    if cfg.loop == "0" then
        mark("loop off; staying at " .. pos())
        after(num("duration", 1800) * 1000, finish)
        return
    end
    mark("loop start duration=" .. num("duration", 1800) .. "s")
    step(0, cycle)
end

local function teleport()
    SendCommandToServer(string.format("/teleportto %d,%d,%d", spot.x, spot.y, spot.z))
    mark("teleport " .. spot.name .. " " .. spot.x .. "," .. spot.y .. "," .. spot.z)
    waitFor(function()
        local p = getPlayer()
        return math.abs(p:getX() - spot.x) < 3 and math.abs(p:getY() - spot.y) < 3
    end, 30000, function(ok)
        mark("arrived ok=" .. tostring(ok) .. " at " .. pos())
        -- chunks around the spot load before the first scan
        after(10000, beginLoop)
    end)
end

-- the server decides damage; another player's form would be /godmodeplayer
local function adminSetup(try)
    SendCommandToServer("/godmode -true")
    if cfg.invisible ~= "0" then SendCommandToServer("/invisible -true") end
    mark("admin setup try=" .. try)
    waitFor(function() return godReplied or getPlayer():isGodMod() end, 5000, function(ok)
        if ok then
            mark("god mode on")
            teleport()
        elseif try < 3 then
            adminSetup(try + 1)
        else
            mark("error no god mode after 3 tries; teleporting anyway")
            teleport()
        end
    end)
end

local inWorld = false
Events.OnTick.Add(function()
    if not inWorld then
        inWorld = true
        mark("ingame label=" .. tostring(cfg.label) .. " at " .. pos())
        after(5000, function() adminSetup(1) end)
    end
    pump()
end)
