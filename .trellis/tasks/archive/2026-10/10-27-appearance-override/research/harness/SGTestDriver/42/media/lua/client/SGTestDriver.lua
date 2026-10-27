-- Test driver of Server Reborn's local test harness (research/harness, not shipped). Idle unless Zomboid/Lua/sgtest.txt
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
local stats = { cycles = 0, taken = 0, putBack = 0, moved = 0, eats = 0, walkFails = 0, errors = 0, shown = 0,
    notShown = 0, teleports = 0 }
local endAt = 0
local finished = false

local function pos()
    local p = getPlayer()
    return string.format("%d,%d,%d", math.floor(p:getX()), math.floor(p:getY()), math.floor(p:getZ()))
end

local function statsLine()
    local cell = getCell()
    return string.format("cycles=%d taken=%d putBack=%d moved=%d eats=%d walkFails=%d errors=%d shown=%d"
        .. " notShown=%d teleports=%d pos=%s zombies=%d",
        stats.cycles, stats.taken, stats.putBack, stats.moved, stats.eats, stats.walkFails, stats.errors, stats.shown,
        stats.notShown, stats.teleports, pos(), cell and cell:getZombieList():size() or -1)
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

-- weight is defined below; a source container must still hold something the loop would take
local hasLoot

-- a container to take from: one of the 30 nearest that still holds light items, as earlier runs empty the nearest;
-- none in the scan widens it (up to 60)
local function pickSource()
    while true do
        local n, found = 0, {}
        for _, e in ipairs(containers) do
            if e.obj:getSquare() ~= nil and hasLoot(e) then
                table.insert(found, e)
                n = n + 1
                if n >= 30 then break end
            end
        end
        if n > 0 then return found[ZombRand(n) + 1] end
        if radius >= 60 then return pick(nil) end
        radius = math.min(radius * 2, 60)
        scan()
    end
end

local function idle()
    local q = ISTimedActionQueue.getTimedActionQueue(getPlayer())
    return #q.queue == 0
end

local cycle -- forward
local function restart()
    stats.errors = stats.errors + 1
    -- a test stops at its first error (its own lines say where)
    if not finished and (cfg.test or "") == "" then after(3000, cycle) end
end
local function step(ms, fn) after(ms, fn, restart) end
local function stepWhenIdle(timeoutMs, fn)
    waitFor(idle, timeoutMs, function(done)
        if not done then ISTimedActionQueue.clear(getPlayer()) end
        fn(done)
    end, restart)
end

-- shows the container in the loot window as a click on its button does (ISInventoryPage:selectContainer), which is
-- where Server Reborn's open check (Dont39ServerReborn_OpenCheck.lua) reports it; what went wrong is logged, never thrown
local function showInLoot(e)
    local loot = getPlayerLoot(0)
    if not loot or not loot.refreshBackpacks or not loot.selectContainer then
        return "no loot window with refreshBackpacks/selectContainer"
    end
    loot:refreshBackpacks()
    for _, button in ipairs(loot.backpacks or {}) do
        if button.inventory == e.cont then
            loot:selectContainer(button)
            -- whether Server Reborn's open check Lua saw it (Dont39ServerReborn_OpenCheck.lua marks the page)
            if stats.shown < 3 then
                mark(string.format("open: shown; global=%s marked=%s explored=%s", type(Dont39ServerReborn_containerShown),
                    tostring(loot.dont39ServerRebornShown == e.cont), tostring(e.cont:isExplored())))
            end
            return true
        end
    end
    local p = getPlayer()
    local sq = e.cont:getSourceGrid() or e.sq
    local cur = p:getCurrentSquare()
    local types = {}
    for _, button in ipairs(loot.backpacks or {}) do
        table.insert(types, tostring(button.inventory and button.inventory:getType()))
    end
    return string.format("not listed in the loot window: player %s, container %s at %d,%d,%d, reach=%s,"
        .. " buttons [%s]", pos(), tostring(e.cont:getType()), sq:getX(), sq:getY(), sq:getZ(),
        tostring(cur and (cur == sq or cur:canReachTo(sq))), table.concat(types, " "))
end

local function open(e)
    local ok, shown = pcall(showInLoot, e)
    if ok and shown == true then
        stats.shown = stats.shown + 1
    else
        stats.notShown = stats.notShown + 1
        mark("open: " .. tostring(shown))
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

hasLoot = function(e)
    local items = e.cont:getItems()
    for i = 0, math.min(items:size(), 12) - 1 do
        if weight(items:get(i)) <= 3 then return true end
    end
    return false
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

-- next to the container and able to reach it, as the loot window needs (ISInventoryPage:refreshBackpacks)
local function beside(sq)
    local p = getPlayer()
    local cur = p:getCurrentSquare()
    return cur ~= nil and math.abs(sq:getX() + 0.5 - p:getX()) <= 1.6 and math.abs(sq:getY() + 0.5 - p:getY()) <= 1.6
        and sq:getZ() == cur:getZ() and (cur == sq or cur:canReachTo(sq))
end

-- the walk fails where the path is blocked (prison fences); an admin teleport to a free square next to it then
local function teleportBeside(e, fn)
    local adj = AdjacentFreeTileFinder.Find(e.sq, getPlayer())
    if not adj then
        stats.walkFails = stats.walkFails + 1
        mark("walk: no free square next to " .. e.sq:getX() .. "," .. e.sq:getY())
        fn(false)
        return
    end
    stats.teleports = stats.teleports + 1
    SendCommandToServer(string.format("/teleportto %d,%d,%d", adj:getX(), adj:getY(), adj:getZ()))
    waitFor(function() return beside(e.sq) end, 10000, function(ok)
        if not ok then
            stats.walkFails = stats.walkFails + 1
            mark("teleport: not beside " .. e.sq:getX() .. "," .. e.sq:getY() .. " at " .. pos())
        end
        fn(ok)
    end, restart)
end

local function walkThen(e, fn)
    if beside(e.sq) then
        fn(true)
        return
    end
    if not luautils.walkAdj(getPlayer(), e.sq, true) then
        teleportBeside(e, fn)
        return
    end
    stepWhenIdle(30000, function(done)
        if done and beside(e.sq) then
            fn(true)
        else
            teleportBeside(e, fn)
        end
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
    local a = pickSource()
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

-- ---- tests (cfg test=<name>): one scripted check instead of the loop; the server half is SGTestDriver_Server.lua ----

-- the server half's last answer per command (args.what)
local serverResults = {}
Events.OnServerCommand.Add(function(module, command, args)
    if module == "SGTest" and command == "result" then
        mark("server " .. tostring(args and args.line))
        if args and args.what then serverResults[args.what] = args end
    end
end)

-- logTransfers=1: each Multiplayer Weight Limit Breaker AddItem whose target container holds an instance of the item
-- other than this client's own copy (the game then logs "Error, container already has id"); the check holds whether
-- this handler runs before or after the mod's
if cfg.logTransfers == "1" then
    Events.OnServerCommand.Add(function(module, command, args)
        if module ~= "VacMod13" or command ~= "AddItem" or not args or not VacMod13 or not VacMod13.findContainer then
            return
        end
        local ok, err = pcall(function()
            local c = VacMod13.findContainer(args.container_id)
            local there = c and args.item_id and c:getItemWithID(args.item_id)
            if not there then return end
            local own = args.pid == getPlayer():getOnlineID()
            local action = own and VacMod13.ActionQueue and VacMod13.ActionQueue[args.id]
            local mine = action and action.items and action.items[args.item_id]
            if mine and there == mine then return end
            local at = mine and mine:getContainer()
            mark(string.format("transfer: AddItem of %s into %s finds another instance there; own=%s action=%s"
                .. " status=%s client copy in %s", tostring(args.item_id), tostring(c:getType()), tostring(own),
                tostring(action ~= nil), tostring(action and action.status), tostring(at and at:getType())))
        end)
        if not ok then mark("transfer log error " .. tostring(err)) end
    end)
end

local function objIndex(e) return e.sq:getObjects():indexOf(e.obj) end
local function contIndex(e)
    for c = 0, e.obj:getContainerCount() - 1 do
        if e.obj:getContainerByIndex(c) == e.cont then return c end
    end
    return -1
end
local function where(e)
    return string.format("%d,%d,%d obj %d cont %d (%s)", e.sq:getX(), e.sq:getY(), e.sq:getZ(), objIndex(e),
        contIndex(e), tostring(e.cont:getType()))
end

-- asks the server to change the container without telling this client (SGTestDriver_Server.lua)
local function changeOnServer(e, how, n, tag)
    sendClientCommand(getPlayer(), "SGTest", "changeContainer", { x = e.sq:getX(), y = e.sq:getY(), z = e.sq:getZ(),
        obj = objIndex(e), cont = contIndex(e), type = e.cont:getType(), how = how, n = n or 1, tag = tag })
end

-- explored containers holding at least min items, one entry per container
local function withItems(min)
    local out, seen = {}, {}
    for _, e in ipairs(containers) do
        if not seen[e.cont] and e.cont:isExplored() and e.cont:getItems():size() >= min then
            seen[e.cont] = true
            table.insert(out, e)
        end
    end
    return out
end

-- scans with a wider radius (up to 60) until at least count containers hold min items
local function scanFor(min, count)
    scan()
    while #withItems(min) < count and radius < 60 do
        radius = math.min(radius * 2, 60)
        scan()
    end
    return withItems(min)
end

local tests = {}

-- AC5: one carried item in the primary hand and one worn clothing item leave the inventory on the server only; the
-- driver logs each change of the client's state until Server Reborn's snapshot has taken them off
function tests.equipRemove(done)
    local p = getPlayer()
    local worn
    local wi = p:getWornItems()
    for i = 0, wi:size() - 1 do
        local it = wi:get(i):getItem()
        if it and instanceof(it, "Clothing") and not instanceof(it, "InventoryContainer") then worn = it break end
    end
    if not worn then
        mark("error test equipRemove: no worn clothing")
        done()
        return
    end
    local function pickHeld()
        local items = p:getInventory():getItems()
        for i = 0, items:size() - 1 do
            local it = items:get(i)
            if it ~= worn and not p:isEquippedClothing(it) and not p:isEquipped(it) and not instanceof(it, "InventoryContainer")
                and weight(it) <= 3 then
                return it
            end
        end
    end
    local function run(held)
        if not held then
            mark("error test equipRemove: nothing to hold")
            done()
            return
        end
        ISTimedActionQueue.add(ISEquipWeaponAction:new(p, held, 50, true, false))
        stepWhenIdle(30000, function()
            if p:getPrimaryHandItem() ~= held then
                mark("error test equipRemove: " .. held:getFullType() .. " not in the primary hand after equipping")
                done()
                return
            end
            local hid, wid = held:getID(), worn:getID()
            local inv = p:getInventory()
            local function state()
                return string.format("held %d (%s): inInventory=%s inHand=%s; worn %d (%s): inInventory=%s worn=%s",
                    hid, held:getFullType(), tostring(inv:containsID(hid)), tostring(p:getPrimaryHandItem() == held),
                    wid, worn:getFullType(), tostring(inv:containsID(wid)), tostring(p:isEquippedClothing(worn)))
            end
            mark("test equipRemove before: " .. state())
            sendClientCommand(p, "SGTest", "removePlayerItems", { ids = hid .. "," .. wid })
            local t0, last, reheld = now(), "", false
            waitFor(function()
                -- the server clears a hand item that left its inventory and sends Equip to the owner; the item goes
                -- back into this client's hand once, as if that Equip were lost, so the snapshot meets it held
                if not reheld and inv:containsID(hid) and p:getPrimaryHandItem() ~= held then
                    reheld = true
                    p:setPrimaryHandItem(held)
                    mark("test equipRemove +" .. (now() - t0) .. "ms: the game took the item out of the hand; back"
                        .. " in the hand on this client only (a lost Equip)")
                end
                local s = state()
                if s ~= last then
                    last = s
                    mark("test equipRemove +" .. (now() - t0) .. "ms: " .. s)
                end
                return now() - t0 > 20000
            end, 25000, function()
                -- the server still names the removed item as its hand item; this sets it to nothing
                sendClientCommand(p, "SGTest", "clearHands", {})
                after(2000, done)
            end)
        end)
    end
    local held = pickHeld()
    if held then
        run(held)
        return
    end
    -- nothing carried yet: take one item first
    local from = scanFor(1, 1)[1]
    if not from then
        run(nil)
        return
    end
    walkThen(from, function()
        open(from)
        takeFrom(from)
        stepWhenIdle(30000, function() run(pickHeld()) end)
    end)
end

-- AC4: container A emptied with removeAllItems (as fire does; seen by Server Reborn's content hooks), container B's item
-- list cleared directly (no hook sees it, so only the open check can find it)
function tests.empty(done)
    local cands = scanFor(2, 4)
    local a, b = cands[1], nil
    for _, e in ipairs(cands) do
        -- not next to the player, so the loot window does not show it before the driver opens it
        if e ~= a and e.obj ~= (a and a.obj) and e.d >= 16 then b = e break end
    end
    if not a or not b then
        mark("error test empty: need two explored containers with items, found " .. #cands)
        done()
        return
    end
    local function counts()
        return string.format("A %s items=%d; B %s items=%d", where(a), a.cont:getItems():size(), where(b),
            b.cont:getItems():size())
    end
    mark("test empty before: " .. counts())
    changeOnServer(a, "removeAll", nil, "A")
    changeOnServer(b, "listClear", nil, "B")
    after(8000, function()
        mark("test empty +8s, B not opened yet: " .. counts())
        walkThen(b, function(ok)
            open(b)
            mark("test empty: opened B (beside=" .. tostring(ok) .. ")")
            after(5000, function()
                mark("test empty +5s after opening B: " .. counts())
                done()
            end)
        end)
    end)
end

-- AC9: one container changed on the server, then ten more 4 s later, inside the notice's 10-s window
function tests.burst(done)
    local cands = scanFor(1, 11)
    if #cands < 11 then
        mark("error test burst: need 11 explored containers with items, found " .. #cands)
        done()
        return
    end
    mark("test burst first: " .. where(cands[1]))
    changeOnServer(cands[1], "Remove", 1, "burst0")
    after(4000, function()
        for i = 2, 11 do changeOnServer(cands[i], "Remove", 1, "burst" .. (i - 1)) end
        mark("test burst: ten more sent")
        after(30000, done)
    end)
end

-- AC12: items moved from the inventory into a bag inside a world container and back, for the whole duration.
-- RPGInventory replaces such transfers (ISInventoryTransferAction:new returns its ISRPGNestedTransfer) and syncs them
-- with its own broadcast RPGInventoryNestedTransfer.moved, which -Dserverreborn.debugDropModule drops on this client.
-- Each move logs where the items are on this client right after it and 4 s later (after Server Reborn's grace and
-- snapshot); every third round shows the bag in the loot window (Server Reborn's open check).
function tests.nested(done)
    local p = getPlayer()
    local inv = p:getInventory()
    local n = { rounds = 0, into = 0, out = 0, inBag = 0, inInv = 0, missing = 0, notNested = 0, shown = 0 }
    local crate, bagId

    local function nestedLine()
        return string.format("rounds=%d into=%d out=%d atBagAfter=%d atInventoryAfter=%d missingAfter=%d"
            .. " notNested=%d shown=%d", n.rounds, n.into, n.out, n.inBag, n.inInv, n.missing, n.notNested, n.shown)
    end
    local function bagContainer()
        local bag = crate.cont:getItemWithID(bagId)
        return bag and instanceof(bag, "InventoryContainer") and bag:getInventory() or nil
    end
    local function attached(it)
        local ok, slot = pcall(function() return it:getAttachedSlot() end)
        return ok and slot and slot >= 0
    end
    -- light items the player carries loose
    local function movable(max)
        local out, items = {}, inv:getItems()
        for i = 0, items:size() - 1 do
            local it = items:get(i)
            if #out >= max then break end
            if not instanceof(it, "InventoryContainer") and not p:isEquipped(it) and not p:isEquippedClothing(it)
                and not it:isFavorite() and not attached(it) and weight(it) <= 1 then
                table.insert(out, it)
            end
        end
        return out
    end
    -- ids: where each item is on this client
    local function places(ids, bc)
        local parts = {}
        for _, id in ipairs(ids) do
            local at = (bc and bc:containsID(id)) and "bag" or inv:containsID(id) and "inventory" or "nowhere"
            table.insert(parts, id .. ":" .. at)
        end
        return table.concat(parts, " ")
    end
    local function queue(items, from, to)
        local ids, kinds = {}, {}
        for _, it in ipairs(items) do
            local a = ISInventoryTransferUtil.newInventoryTransferAction(p, it, from, to)
            kinds[tostring(a.Type)] = true
            if a.Type ~= "ISRPGNestedTransfer" then n.notNested = n.notNested + 1 end
            ISTimedActionQueue.add(a)
            table.insert(ids, it:getID())
        end
        local k = {}
        for t in pairs(kinds) do table.insert(k, t) end
        return ids, table.concat(k, ",")
    end
    local function showBag(bc)
        local loot = getPlayerLoot(0)
        if not loot or not loot.setNewContainer then
            mark("test nested: no loot window to show the bag in")
            return
        end
        loot:setNewContainer(bc)
        n.shown = n.shown + 1
        mark("test nested: bag " .. bagId .. " shown in the loot window; open check marked="
            .. tostring(loot.dont39ServerRebornShown == bc))
    end

    local round -- forward
    local function moveBack(ids)
        local bc = bagContainer()
        local back = {}
        for _, id in ipairs(ids) do
            local it = bc and bc:getItemWithID(id)
            if it then table.insert(back, it) end
        end
        if #back == 0 then
            mark("test nested: nothing of " .. table.concat(ids, ",") .. " in the bag to move back")
            step(1000, round)
            return
        end
        local backIds, kinds = queue(back, bc, inv)
        n.out = n.out + #back
        mark("test nested out: " .. table.concat(backIds, ",") .. " bag " .. bagId .. " -> inventory via " .. kinds)
        stepWhenIdle(60000, function()
            mark("test nested out +0s: " .. places(backIds, bagContainer()))
            after(4000, function()
                mark("test nested out +4s: " .. places(backIds, bagContainer()))
                step(500, round)
            end)
        end)
    end
    round = function()
        if now() >= endAt then
            mark("test nested result " .. nestedLine())
            done()
            return
        end
        local bc = bagContainer()
        if not bc then
            mark("error test nested: bag " .. bagId .. " is no longer in " .. where(crate) .. " on this client")
            done()
            return
        end
        n.rounds = n.rounds + 1
        if n.rounds % 3 == 0 then showBag(bc) end
        local items = movable(num("take", 3))
        if #items == 0 then
            -- refill from the crate (a native transfer: the crate itself is not nested)
            local picked, list = {}, crate.cont:getItems()
            for i = 0, list:size() - 1 do
                local it = list:get(i)
                if #picked >= 3 then break end
                if not instanceof(it, "InventoryContainer") and weight(it) <= 1 then table.insert(picked, it) end
            end
            if #picked == 0 then
                mark("error test nested: nothing light to move, carried or in " .. where(crate))
                done()
                return
            end
            queue(picked, crate.cont, inv)
            mark("test nested: took " .. #picked .. " items from the crate to move")
            stepWhenIdle(60000, function() step(500, round) end)
            return
        end
        local ids, kinds = queue(items, inv, bc)
        n.into = n.into + #items
        mark("test nested in: " .. table.concat(ids, ",") .. " inventory -> bag " .. bagId .. " via " .. kinds)
        stepWhenIdle(60000, function()
            mark("test nested in +0s: " .. places(ids, bagContainer()))
            after(4000, function()
                local now4 = bagContainer()
                for _, id in ipairs(ids) do
                    if now4 and now4:containsID(id) then n.inBag = n.inBag + 1
                    elseif inv:containsID(id) then n.inInv = n.inInv + 1
                    else n.missing = n.missing + 1 end
                end
                mark("test nested in +4s: " .. places(ids, now4))
                if n.rounds % 10 == 0 then mark("test nested progress " .. nestedLine()) end
                moveBack(ids)
            end)
        end)
    end

    -- the bag into the crate (a native transfer), then the rounds
    local function placeBag(bag)
        walkThen(crate, function()
            open(crate)
            bagId = bag:getID()
            if bag:getContainer() == crate.cont then
                mark("test nested: bag " .. bagId .. " (" .. bag:getFullType() .. ") already in " .. where(crate))
                step(500, round)
                return
            end
            ISTimedActionQueue.add(ISInventoryTransferUtil.newInventoryTransferAction(p, bag, bag:getContainer(),
                crate.cont))
            stepWhenIdle(60000, function()
                if not bagContainer() then
                    mark("error test nested: bag " .. bagId .. " did not reach " .. where(crate))
                    done()
                    return
                end
                mark("test nested: bag " .. bagId .. " (" .. bag:getFullType() .. ") put into " .. where(crate))
                step(500, round)
            end)
        end)
    end

    scan()
    -- a bag already lying in a container nearby
    for _, e in ipairs(containers) do
        local items = e.cont:isExplored() and e.cont:getItems()
        for i = 0, (items and items:size() or 0) - 1 do
            local it = items:get(i)
            if instanceof(it, "InventoryContainer") and it:getInventory() then
                crate = e
                mark("test nested: found bag " .. it:getID() .. " in " .. where(e))
                placeBag(it)
                return
            end
        end
    end
    crate = scanFor(1, 1)[1] or containers[1]
    if not crate then
        mark("error test nested: no container nearby")
        done()
        return
    end
    -- a bag the player carries loose, else one from the server half
    local items = inv:getItems()
    for i = 0, items:size() - 1 do
        local it = items:get(i)
        if instanceof(it, "InventoryContainer") and not p:isEquipped(it) and not p:isEquippedClothing(it)
            and not attached(it) then
            placeBag(it)
            return
        end
    end
    serverResults.giveBag = nil
    sendClientCommand(p, "SGTest", "giveBag", {})
    waitFor(function()
        local r = serverResults.giveBag
        return r and r.id and inv:getItemWithID(r.id) ~= nil
    end, 15000, function(ok)
        local r = serverResults.giveBag
        if not ok then
            mark("error test nested: no bag from the server (" .. tostring(r and r.line) .. ")")
            done()
            return
        end
        placeBag(inv:getItemWithID(r.id))
    end)
end

-- the food stats an ItemStats packet carries; SGTestDriver_Server.lua formats the same line
local function foodStats(f)
    return string.format("end=%.4f hung=%.4f thirst=%.4f base=%.4f unhappy=%.4f bored=%.4f stress=%.4f fat=%.4f uses=%d",
        f:getEnduranceChange(), f:getHungChange(), f:getThirstChange(), f:getBaseHunger(), f:getUnhappyChange(),
        f:getBoredomChange(), f:getStressChange(), f:getFatigueChange(), f:getCurrentUses())
end

-- the server half's answer to command what, then fn(answer or nil)
local function askServer(what, args, timeoutMs, fn)
    serverResults[what] = nil
    sendClientCommand(getPlayer(), "SGTest", what, args)
    waitFor(function() return serverResults[what] ~= nil end, timeoutMs, function() fn(serverResults[what]) end, restart)
end

-- items the server gives (types: comma-separated), once all are in the client's inventory: fn({id, ...}) or fn(nil)
local function giveAndWait(types, fn)
    askServer("give", { types = types }, 15000, function(r)
        if not r or not r.ids then
            mark("error give " .. types .. ": " .. tostring(r and r.line))
            fn(nil)
            return
        end
        local ids = {}
        for id in string.gmatch(r.ids, "[^,]+") do table.insert(ids, tonumber(id)) end
        local inv = getPlayer():getInventory()
        waitFor(function()
            for _, id in ipairs(ids) do if not inv:getItemWithID(id) then return false end end
            return true
        end, 15000, function(ok)
            if not ok then mark("error give " .. types .. ": not all in the client's inventory after 15 s") end
            fn(ok and ids or nil)
        end, restart)
    end)
end

-- the model items the bandage update put in the client's inventory, as type#id
local function modelItems()
    local out = {}
    local items = getPlayer():getInventory():getItems()
    for i = 0, items:size() - 1 do
        local t = items:get(i):getFullType()
        if t:find("^Base%.Bandage_") or t:find("^Base%.Wound_") then table.insert(out, t .. "#" .. items:get(i):getID()) end
    end
    return table.concat(out, " ")
end

-- 10-27 false repairs: (1) food stats an ItemStats packet leaves out, (2) the bandage model, (3) Escape from Kentucky's
-- client-only gun data, then (4) a client-only mod data key as the control that a real difference is still repaired.
-- The Server Reborn lines in console.txt (field diff, repaired) are the verdict; the driver logs what each step did.
function tests.falseRepairs(done)
    local p = getPlayer()
    local inv = p:getInventory()

    local function control()
        giveAndWait("Base.Pencil", function(ids)
            if not ids then done() return end
            local pencil = inv:getItemWithID(ids[1])
            pencil:getModData().SGTEST_clientOnly = 1
            mark("test falseRepairs control: modData SGTEST_clientOnly on " .. ids[1] .. ", then a server change")
            giveAndWait("Base.Pencil", function()
                after(15000, function()
                    mark("test falseRepairs control: key still on the client: "
                        .. tostring(pencil:getModData().SGTEST_clientOnly ~= nil))
                    done()
                end)
            end)
        end)
    end

    local function efk()
        giveAndWait("Base.M7_cat", function(ids)
            if not ids then control() return end
            local gun = inv:getItemWithID(ids[1])
            ISTimedActionQueue.add(ISEquipWeaponAction:new(p, gun, 50, true, false))
            stepWhenIdle(30000, function()
                after(5000, function()
                    local keys = {}
                    for k, v in pairs(gun:getModData()) do
                        if tostring(k):find("^AWCWF_") or k == "MagzineTypeNow" then table.insert(keys, k .. "=" .. tostring(v)) end
                    end
                    table.sort(keys)
                    mark("test falseRepairs efk: in hand=" .. tostring(p:getPrimaryHandItem() == gun) .. " keys "
                        .. table.concat(keys, " "))
                    -- a server change to the inventory, so its digest is compared
                    giveAndWait("Base.Pencil", function() after(15000, control) end)
                end)
            end)
        end)
    end

    local function bandage()
        -- god mode keeps the body whole; invisibility keeps the zombies off
        SendCommandToServer("/godmode -false")
        after(3000, function()
            giveAndWait("Base.Bandage", function(ids)
                if not ids then efk() return end
                local part = p:getBodyDamage():getBodyPart(BodyPartType.Foot_R)
                ISTimedActionQueue.add(ISApplyBandage:new(p, p, inv:getItemWithID(ids[1]), part, true))
                stepWhenIdle(60000, function()
                    mark("test falseRepairs bandage: foot bandaged=" .. tostring(part:bandaged()))
                    local n = 0
                    local function sample()
                        n = n + 1
                        mark("test falseRepairs bandage +" .. (n * 5) .. "s models: " .. modelItems())
                        if n < 12 then after(5000, sample) else efk() end
                    end
                    after(5000, sample)
                end)
            end)
        end)
    end

    giveAndWait("Base.Apple,Base.SugarPacket,Base.CannedChili", function(ids)
        if not ids then bandage() return end
        askServer("syncFoods", { ids = table.concat(ids, ",") }, 15000, function(r)
            if not r or not r.stats then
                mark("error test falseRepairs foods: " .. tostring(r and r.line))
                bandage()
                return
            end
            after(3000, function()
                for i = 2, #ids do
                    local f = inv:getItemWithID(ids[i])
                    local mine, theirs = foodStats(f), r.stats[tostring(ids[i])]
                    mark(string.format("test falseRepairs food %s %d %s: client %s server %s", f:getFullType(), ids[i],
                        mine == theirs and "same" or "DIFFERS", mine, tostring(theirs)))
                end
                bandage()
            end)
        end)
    end)
end

-- 10-27 appearance override: the server hides a worn knapsack sprayer holding water and draws a worn padded jacket as
-- Jacket_Black. Each report puts the client's gameplay values next to the server's; with the client's
-- -Dappearanceoverride.debug=true, console.txt shows what each model build drew.
local SPRAYER, JACKET, LOOK = "Base.KnapsackSprayer", "Base.Jacket_Padded", "Base.Jacket_Black"

local function wornOf(fullType)
    local worn = getPlayer():getWornItems()
    for i = 0, worn:size() - 1 do
        local it = worn:getItemByIndex(i)
        if it:getFullType() == fullType then return it end
    end
end

local function itemLine(it)
    if not it then return "none" end
    local s = string.format("%s#%d w=%.3f", it:getFullType(), it:getID(), it:getActualWeight())
    local fc = it:getFluidContainer()
    if fc then s = s .. string.format(" fluid=%.3f/%.3f", fc:getAmount(), fc:getCapacity()) end
    if instanceof(it, "Clothing") then
        s = s .. string.format(" bite=%.1f scratch=%.1f ins=%.2f", it:getBiteDefense(), it:getScratchDefense(),
            it:getInsulation())
    end
    local o = it:hasModData() and it:getModData().Dont39AllInOne_Appearance or nil
    return s .. " override=" .. (o == nil and "none" or (o.hidden and "hidden" or tostring(o.type)))
end

local function report(tag, fn)
    mark("test appearance " .. tag .. " client: sprayer " .. itemLine(wornOf(SPRAYER)) .. " | jacket "
        .. itemLine(wornOf(JACKET)))
    askServer("appearanceReport", { sprayer = SPRAYER, jacket = JACKET }, 15000, function(r)
        mark("test appearance " .. tag .. " server: " .. tostring(r and r.line))
        fn()
    end)
end

function tests.appearance(done)
    local p = getPlayer()
    local inv = p:getInventory()
    giveAndWait(SPRAYER .. "," .. JACKET, function(ids)
        if not ids then done() return end
        ISTimedActionQueue.add(ISWearClothing:new(p, inv:getItemWithID(ids[1])))
        ISTimedActionQueue.add(ISWearClothing:new(p, inv:getItemWithID(ids[2])))
        stepWhenIdle(60000, function()
            askServer("fill", { id = ids[1], amount = 2 }, 15000, function(r)
                mark("test appearance fill: " .. tostring(r and r.line))
                after(3000, function()
                    report("worn", function()
                        askServer("appearanceSet", { id = ids[1], action = "hidden" }, 15000, function(r1)
                            mark("test appearance set sprayer: " .. tostring(r1 and r1.line))
                            askServer("appearanceSet", { id = ids[2], action = "look", type = LOOK }, 15000,
                                function(r2)
                                    mark("test appearance set jacket: " .. tostring(r2 and r2.line))
                                    after(10000, function() report("overridden", done) end)
                                end)
                        end)
                    end)
                end)
            end)
        end)
    end)
end

-- after a server restart and a rejoin: the same items, still worn and overridden
function tests.appearanceCheck(done)
    after(10000, function() report("rejoined", done) end)
end

-- the jacket's look cleared; then a second sprayer worn instead of the hidden one is drawn as itself
function tests.appearanceClear(done)
    local p = getPlayer()
    local first, jacket = wornOf(SPRAYER), wornOf(JACKET)
    if not first or not jacket then
        mark("error test appearanceClear: sprayer or jacket not worn")
        done()
        return
    end
    askServer("appearanceSet", { id = jacket:getID(), action = "clear" }, 15000, function(r)
        mark("test appearanceClear jacket: " .. tostring(r and r.line))
        after(10000, function()
            report("jacketCleared", function()
                giveAndWait(SPRAYER, function(ids)
                    if not ids then done() return end
                    ISTimedActionQueue.add(ISUnequipAction:new(p, first, 50))
                    ISTimedActionQueue.add(ISWearClothing:new(p, p:getInventory():getItemWithID(ids[1])))
                    stepWhenIdle(60000, function()
                        after(10000, function()
                            mark("test appearanceClear first sprayer in the inventory: " .. itemLine(first))
                            report("secondSprayer", done)
                        end)
                    end)
                end)
            end)
        end)
    end)
end

-- Multiplayer Weight Limit Breaker replaces the game's transaction functions and keeps the originals in VacMod13.raw;
-- vanillaTransfers=1 puts them back, so transfers go through the game's own container packets (AC1)
local function useVanillaTransfers()
    if not (VacMod13 and VacMod13.raw) then
        mark("vanillaTransfers: VacMod13.raw not found; transfers stay as they are")
        return
    end
    local names = {}
    for k, f in pairs(VacMod13.raw) do
        if type(f) == "function" then
            _G[k] = f
            table.insert(names, k)
        end
    end
    mark("vanillaTransfers: restored " .. table.concat(names, " "))
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
    if cfg.vanillaTransfers == "1" then useVanillaTransfers() end
    if cfg.test and cfg.test ~= "" then
        local t = tests[cfg.test]
        if not t then
            mark("error unknown test " .. cfg.test)
            finish()
            return
        end
        mark("test " .. cfg.test .. " start at " .. pos())
        step(0, function()
            t(function()
                mark("test " .. cfg.test .. " end")
                finish()
            end)
        end)
        return
    end
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
