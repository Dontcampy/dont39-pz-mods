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

-- ---- 10-27 SCTD render takeover: SCTD's own functions on the server (SGTestDriver_Server.lua) and the client ----
-- Each test is a coroutine: sleep/ask/idle yield to the driver's scheduler. Item ids carry over between runs in
-- Zomboid/Lua/sgtest_ids.txt.

local IDS = "sgtest_ids.txt"
local SPRAYER_T, JACKET_A = "Base.KnapsackSprayer", "Base.Jacket_Black"
local LOOK_B, CROSS_C, INVERT_D, HAT = "Base.Jacket_Leather_Punk", "Base.Shirt_Lumberjack", "Base.Jacket_Varsity",
    "Base.Hat_BaseballCap"

local function seq(body, done)
    local co = coroutine.create(body)
    local resume
    resume = function(...)
        local ok, kind, a, b = coroutine.resume(co, ...)
        if not ok then
            mark("error " .. tostring(kind))
            done()
            return
        end
        if coroutine.status(co) == "dead" then done() return end
        if kind == "sleep" then after(a, function() resume() end)
        elseif kind == "ask" then askServer(a, b, 20000, function(r) resume(r) end)
        elseif kind == "idle" then stepWhenIdle(a, function(r) resume(r) end)
        elseif kind == "until" then waitFor(a, b, function(r) resume(r) end)
        else mark("error seq yielded " .. tostring(kind)) done() end
    end
    resume()
end
local function sleep(ms) coroutine.yield("sleep", ms) end
local function ask(what, args) return coroutine.yield("ask", what, args) end
local function whenIdle(ms) return coroutine.yield("idle", ms or 60000) end
local function waitUntil(cond, ms) return coroutine.yield("until", cond, ms) end

local function loadIds()
    return readKv(IDS) or {}
end
local function saveIds(t)
    local w = getFileWriter(IDS, true, false)
    for k, v in pairs(t) do w:write(k .. "=" .. tostring(v) .. "\n") end
    w:close()
end

local function ser(t)
    if type(t) ~= "table" then return tostring(t) end
    local parts = {}
    for k, v in pairs(t) do
        if type(v) == "table" then
            local inner = {}
            for k2, v2 in pairs(v) do table.insert(inner, tostring(k2) .. ":" .. tostring(v2)) end
            table.sort(inner)
            v = "{" .. table.concat(inner, ",") .. "}"
        end
        table.insert(parts, tostring(k) .. "=" .. tostring(v))
    end
    table.sort(parts)
    return "{" .. table.concat(parts, ",") .. "}"
end

-- SCTD's hide button label for the item, drawn into a list stand-in
local function hideLabel(item)
    local captured = nil
    local list = { itemheight = 30, _sctdTextZoom = 1 }
    function list.getWidth() return 400 end
    function list.getMouseX() return -1 end
    function list.getMouseY() return -1 end
    function list.isMouseOver() return false end
    function list.isMouseOverScrollBar() return false end
    function list.drawText(_, text) captured = text end
    -- every method the button draws with; a missing one would be an error inside the takeover's replacement
    function list.drawTextureScaled() end
    function list.drawRect() end
    function list.drawRectBorder() end
    local ok, err = pcall(SCTD_Transmog_DrawHideButton, list, 0, item, getPlayer())
    if not ok then return "error:" .. tostring(err) end
    if captured == getText("IGUI_SCTD_SkinPanel_Show") then return "Show" end
    if captured == getText("IGUI_SCTD_SkinPanel_Hide") then return "Hide" end
    return tostring(captured)
end

-- where SCTD's placeholder check lives now; the takeover's panel wrappers swap it in only during a call
local function invisAt()
    local f = SCTD_Transmog_IsInvisVariant
    if type(f) ~= "function" then return tostring(f) end
    local name = tostring(getFilenameOfClosure(f))
    return (string.match(name, "([^/\]+)$") or name) .. ":" .. tostring(getFirstLineOfClosure(f))
end
-- getFirstLineOfClosure gives the line after the header (SCTD_Transmog_IsInvisVariant is at 2618)
local SCTD_INVIS_AT = "LingLi_SCTD_Skins.lua:2619"
-- after each panel helper: "sctd" when the check is SCTD's own again, else where it is
local function invisMark()
    local at = invisAt()
    return at == SCTD_INVIS_AT and "sctd" or ("NOT:" .. at)
end

local function sctdLine(it)
    if not it then return "none" end
    local p = getPlayer()
    local inv = p:getInventory()
    local worn = p:getWornItems()
    local where
    if worn:contains(it) then where = "worn:" .. tostring(worn:getLocation(it))
    elseif it:getContainer() == inv then where = "inv"
    elseif it:getContainer() then where = "in:" .. tostring(it:getContainer():getType())
    else where = "nowhere" end
    local s = string.format("%s#%d %s w=%.3f cond=%d", it:getFullType(), it:getID(), where, it:getActualWeight(),
        it:getCondition())
    local fc = it:getFluidContainer()
    if fc then s = s .. string.format(" fluid=%.3f/%.3f", fc:getAmount(), fc:getCapacity()) end
    if instanceof(it, "Clothing") then
        s = s .. string.format(" bite=%.1f scratch=%.1f ins=%.2f", it:getBiteDefense(), it:getScratchDefense(),
            it:getInsulation())
    end
    local v, ci = it:getVisual(), it:getClothingItem()
    if v and ci then
        local t = v:getTint(ci)
        s = s .. string.format(" vis=%d,%d,%.2f/%.2f/%.2f", v:getTextureChoice(), v:getBaseTexture(), t:getRedFloat(),
            t:getGreenFloat(), t:getBlueFloat())
    end
    local md = it:hasModData() and it:getModData() or {}
    s = s .. " record=" .. ser(md.SCTD_Transmog) .. " override=" .. ser(md.Dont39AllInOne_Appearance)
        .. " shown=" .. tostring(md.SCTD_TransmogShown)
    local marks = {}
    local fHidden = SCTD_Transmog_MatchesBaseFilter({ baseFilter = "hidden" }, it)
    marks[#marks + 1] = invisMark()
    local fTrans = SCTD_Transmog_MatchesBaseFilter({ baseFilter = "transmogged" }, it)
    marks[#marks + 1] = invisMark()
    local label = hideLabel(it)
    marks[#marks + 1] = invisMark()
    local skipped = SCTD_IsPreviewSkipped(p, it)
    marks[#marks + 1] = invisMark()
    return s .. string.format(" | ui filterHidden=%s filterTransmogged=%s isTransmogged=%s button=%s previewSkipped=%s"
        .. " invisAfter=%s", tostring(fHidden), tostring(fTrans), tostring(SCTD_Transmog_IsTransmogged(it)), label,
        tostring(skipped), table.concat(marks, ","))
end

local function byId(id)
    return id and getPlayer():getInventory():getItemWithIDRecursiv(tonumber(id)) or nil
end

local function wornIds()
    local worn, ids = getPlayer():getWornItems(), {}
    for i = 0, worn:size() - 1 do table.insert(ids, worn:getItemByIndex(i):getID()) end
    return ids
end

local function wornText()
    local worn, out = getPlayer():getWornItems(), {}
    for i = 0, worn:size() - 1 do
        local it = worn:getItemByIndex(i)
        table.insert(out, it:getFullType() .. "#" .. it:getID() .. "@" .. tostring(worn:getLocation(it)))
    end
    return table.concat(out, " ")
end

-- client lines for the ids, then the server's
local function both(tag, ids)
    for _, id in ipairs(ids) do mark("test sctd " .. tag .. " client: " .. sctdLine(byId(id))) end
    local inv = getPlayer():getInventory()
    mark("test sctd " .. tag .. " client: items=" .. inv:getItems():size() .. " | worn " .. wornText())
    local r = ask("sctdReport", { ids = table.concat(ids, ",") })
    mark("test sctd " .. tag .. " server: " .. tostring(r and r.line))
end

local function sctdServer(op, id, extra)
    local args = { op = op, id = id }
    for k, v in pairs(extra or {}) do args[k] = v end
    local r = ask("sctd", args)
    mark("test sctd call " .. op .. ": " .. tostring(r and r.line))
    return r
end

-- SCTD's transmog panel, built as its opener builds it but never shown; [fn](panel), then the panel is dropped
local function withPanel(fn)
    local panel = SCTD_TransmogPanel:new()
    panel:initialise()
    panel:instantiate()
    local ok, err = pcall(fn, panel)
    pcall(function() panel:close() end)
    SCTD_TransmogPanel.instance = nil
    if not ok then error(err) end
end

-- each worn-list row as the panel's row closure draws it: its texts (name, hide button label) with their colors
local function panelRows(tag)
    withPanel(function(panel)
        panel:refreshBases()
        local list = panel.baseList
        local drawAt = tostring(getFilenameOfClosure(list.doDrawItem)) .. ":" .. tostring(getFirstLineOfClosure(list.doDrawItem))
        mark("test sctd panel " .. tag .. " row closure from " .. (string.match(drawAt, "([^/\]+)$") or drawAt))
        local texts = {}
        list.drawText = function(_, text, x, y, r, g, b)
            texts[#texts + 1] = string.format("%s(%.2f/%.2f/%.2f)", tostring(text), r or -1, g or -1, b or -1)
        end
        local function none() end
        list.drawTextureScaled, list.drawRect, list.drawRectBorder = none, none, none
        list.drawTextureScaledAspect, list.drawTexture, list.drawTextureScaledUniform = none, none, none
        for i, row in ipairs(list.items) do
            texts = {}
            local ok, err = pcall(list.doDrawItem, list, 0, row, false)
            mark("test sctd panel " .. tag .. " row " .. i .. " "
                .. (row.item and (row.item:getFullType() .. "#" .. row.item:getID()) or "-") .. ": "
                .. (ok and table.concat(texts, " | ") or ("error " .. tostring(err))) .. " invisAfter=" .. invisMark())
        end
    end)
end

-- a click on the item's hide/show button in the panel's worn list (SCTD_Transmog_ClickHide)
local function panelClick(tag, id)
    withPanel(function(panel)
        panel:refreshBases()
        local list = panel.baseList
        for row, entry in ipairs(list.items) do
            if entry.item and entry.item:getID() == id then
                local bx, by = SCTD_Transmog_HideBounds(list, list:topOfItem(row))
                local r = SCTD_Transmog_ClickHide(panel, list, row, bx + 1, by + 1)
                mark("test sctd panel " .. tag .. " ClickHide row " .. row .. " " .. entry.item:getFullType() .. "#" .. id
                    .. " returned " .. tostring(r) .. " invisAfter=" .. invisMark())
                return
            end
        end
        mark("test sctd panel " .. tag .. " ClickHide: item " .. tostring(id) .. " not listed")
    end)
end

local function give(types)
    local r = ask("give", { types = types })
    if not r or not r.ids then error("give " .. types .. ": " .. tostring(r and r.line)) end
    local ids = {}
    for id in string.gmatch(r.ids, "[^,]+") do table.insert(ids, tonumber(id)) end
    local inv = getPlayer():getInventory()
    local ok = waitUntil(function()
        for _, id in ipairs(ids) do if not inv:getItemWithID(id) then return false end end
        return true
    end, 15000)
    if not ok then error("give " .. types .. ": not all in the client's inventory after 15 s") end
    return ids
end

local function wear(id)
    ISTimedActionQueue.add(ISWearClothing:new(getPlayer(), byId(id)))
    whenIdle()
end

local function takeOff(id)
    ISTimedActionQueue.add(ISUnequipAction:new(getPlayer(), byId(id), 50))
    whenIdle()
end

-- SCTD's transmog notices to this client (its panel reselects on them)
Events.OnServerCommand.Add(function(module, command, args)
    if module == "SCTD_Transmog" and cfg.test and string.find(cfg.test, "^sctd") then
        mark("notice SCTD_Transmog." .. tostring(command) .. " item=" .. tostring(args and args.itemId) .. " "
            .. tostring(args and args.fullType) .. " record=" .. ser(args and args.record))
    end
    -- the appearance sets this client gets: one per publish (revision, entry count)
    if module == "Dont39AllInOne_AppearanceOverride" and command == "set" and cfg.test and string.find(cfg.test, "^sctd") then
        local n = 0
        for _ in pairs(args and args.entries or {}) do n = n + 1 end
        mark("appearance set " .. tostring(args and args.user) .. " revision=" .. tostring(args and args.revision) .. " entries=" .. n)
    end
end)

-- 1: hide the sprayer (2 L of water) and jacket A on the server; show A again from the client (SCTD's command)
function tests.sctdHide(done)
    seq(function()
        local ids = give(SPRAYER_T .. "," .. JACKET_A)
        local sprayer, a = ids[1], ids[2]
        wear(sprayer)
        wear(a)
        local r = ask("fill", { id = sprayer, amount = 2 })
        mark("test sctd fill: " .. tostring(r and r.line))
        sleep(3000)
        both("worn", { sprayer, a })
        sctdServer("hide", sprayer)
        sctdServer("hide", a)
        sleep(10000)
        both("hidden", { sprayer, a })
        panelRows("hidden")
        -- show A again through the panel's button (SCTD_Transmog_ClickHide, which sends SCTD's showItem command)
        panelClick("show", a)
        sleep(10000)
        both("shown", { sprayer, a })
        saveIds({ sprayer = sprayer, a = a })
    end, done)
end

-- 2: transmog A as B (normal), as C from another slot, invert with B, invert with D from a bag; restore each
function tests.sctdTransmog(done)
    seq(function()
        local saved = loadIds()
        local sprayer, a = tonumber(saved.sprayer), tonumber(saved.a)
        local ids = give(LOOK_B .. "," .. CROSS_C .. "," .. INVERT_D .. ",Base.Bag_Schoolbag")
        local b, c, d, bag = ids[1], ids[2], ids[3], ids[4]
        saved.b, saved.c, saved.d, saved.bag = b, c, d, bag
        saveIds(saved)
        both("start", { a, b, c, d })

        sctdServer("apply", a, { source = b, ignore = false, invert = false })
        sleep(8000)
        both("normal", { a, b })
        panelRows("normal")
        sctdServer("restore", a)
        sleep(8000)
        both("normalRestored", { a, b })

        sctdServer("apply", a, { source = c, ignore = true, invert = false })
        sleep(8000)
        both("cross", { a, c })
        sctdServer("restore", a)
        sleep(8000)
        both("crossRestored", { a, c })

        sctdServer("apply", a, { source = b, ignore = false, invert = true })
        sleep(8000)
        both("invert", { a, b })
        panelRows("invert")
        sctdServer("restore", b)
        sleep(8000)
        both("invertRestored", { a, b })
        wear(a)
        sleep(5000)
        both("aWornAgain", { a, b })

        ISTimedActionQueue.add(ISInventoryTransferAction:new(getPlayer(), byId(d), getPlayer():getInventory(),
            byId(bag):getItemContainer()))
        whenIdle()
        sleep(3000)
        both("dInBag", { a, d })
        sctdServer("apply", a, { source = d, ignore = false, invert = true })
        sleep(8000)
        both("invertFromBag", { a, d })
        sctdServer("restore", d)
        sleep(8000)
        both("invertFromBagRestored", { a, d })
        wear(a)
        sleep(5000)
        both("end", { sprayer, a, b, c, d })
    end, done)
end

-- 5: skin panel on a transmogged item (our command) and on a plain one (SCTD's own path)
function tests.sctdSkin(done)
    seq(function()
        local p = getPlayer()
        local saved = loadIds()
        local sprayer, a, b = tonumber(saved.sprayer), tonumber(saved.a), tonumber(saved.b)
        local hat = give(HAT)[1]
        saved.hat = hat
        saveIds(saved)
        wear(hat)
        sctdServer("apply", a, { source = b, ignore = false, invert = false })
        sleep(8000)
        both("transmogged", { a, b, hat })

        local function listSkins(tag)
            for _, e in ipairs(SCTD_GetWornClothingWithSkins(p)) do
                local id = e.item and e.item:getID()
                if id == a or id == hat then
                    mark(string.format("test sctd skins %s: %s#%d itemType=%s choices=%d currentIndex=%s currentChoice=%s"
                        .. " hasSkins=%s hasColorChange=%s", tag, e.item:getFullType(), id, tostring(e.itemType),
                        #(e.choices or {}), tostring(e.currentIndex), tostring(e.currentChoice), tostring(e.hasSkins),
                        tostring(e.hasColorChange)))
                end
            end
        end
        listSkins("before")

        local function sample(tag)
            local t0 = now()
            for _, ms in ipairs({ 200, 1000, 2000, 4000, 8000 }) do
                sleep(math.max(0, t0 + ms - now()))
                local o = byId(a):getModData().Dont39AllInOne_Appearance
                mark("test sctd " .. tag .. " +" .. ms .. "ms client override " .. ser(o))
            end
        end
        local r1, r2 = SCTD_ChangeSkin(p, byId(a), 2)
        mark("test sctd ChangeSkin A 2 returned " .. tostring(r1) .. " " .. tostring(r2))
        sample("skin")
        both("skinChanged", { a })
        local c1, c2 = SCTD_ChangeColor(p, byId(a), 0.2, 0.6, 0.9)
        mark("test sctd ChangeColor A returned " .. tostring(c1) .. " " .. tostring(c2))
        sample("color")
        both("colorChanged", { a })
        listSkins("after")

        local before = sctdLine(byId(hat))
        local h1 = SCTD_ChangeSkin(p, byId(hat), 1)
        mark("test sctd ChangeSkin hat 1 returned " .. tostring(h1) .. "; before " .. before)
        sleep(6000)
        local h2 = SCTD_ChangeColor(p, byId(hat), 0.9, 0.1, 0.1)
        mark("test sctd ChangeColor hat returned " .. tostring(h2))
        sleep(6000)
        both("hatChanged", { hat })
        both("end", { sprayer, a, b, hat })
    end, done)
end

-- 4: wear an SCTD body form (auto-hide on), then take it off
function tests.sctdAutoHide(done)
    seq(function()
        local p = getPlayer()
        local saved = loadIds()
        local form = give(p:isFemale() and "Base.FemaleBodyT512" or "Base.MaleBodyT512")[1]
        saved.form = form
        saveIds(saved)
        local before = wornIds()
        both("before", before)
        wear(form)
        sleep(10000)
        local during = wornIds()
        both("bodyForm", during)
        panelRows("bodyForm")
        takeOff(form)
        sleep(10000)
        both("bodyFormOff", wornIds())
    end, done)
end

-- 6: after a restart or a rejoin
function tests.sctdCheck(done)
    seq(function()
        local saved = loadIds()
        sleep(10000)
        both("rejoined", { saved.sprayer, saved.a, saved.b, saved.hat })
    end, done)
end

-- 8: the takeover disabled on the server (a wrong hash in the deployed copy): SCTD hides as shipped; its Restore
-- clears our records from before (R7a)
function tests.sctdDisabled(done)
    seq(function()
        local saved = loadIds()
        local sprayer, a, hat = tonumber(saved.sprayer), tonumber(saved.a), tonumber(saved.hat)
        local r = ask("sctdState", {})
        mark("test sctd state: " .. tostring(r and r.line))
        sleep(5000)
        both("start", { sprayer, a, hat })
        local h = sctdServer("hide", hat)
        sleep(8000)
        both("hatHiddenBySctd", { sprayer, a, hat, h and h.result })
        sctdServer("restore", sprayer)
        sctdServer("restore", a)
        sleep(8000)
        both("oursRestored", { sprayer, a })
        if h and h.result then
            sctdServer("show", h.result)
            sleep(8000)
            both("placeholderShown", {})
        end
    end, done)
end

-- 7: a second player stays near the first and reports how it sees them
function tests.sctdView(done)
    seq(function()
        local target = cfg.watch or "admin"
        local other = waitUntil(function() return getPlayerFromUsername(target) ~= nil end, 60000)
        mark("test sctd view: " .. target .. " present=" .. tostring(other))
        for n = 1, 6 do
            sleep(10000)
            local o = getPlayerFromUsername(target)
            if o then
                local vis, out = o:getItemVisuals(), {}
                for i = 0, vis:size() - 1 do
                    local v = vis:get(i)
                    table.insert(out, tostring(v:getItemType()) .. "/" .. tostring(v:getTextureChoice()))
                end
                mark(string.format("test sctd view +%ds: %s at %d,%d dist=%.1f visuals %s", n * 10, target,
                    math.floor(o:getX()), math.floor(o:getY()), o:DistTo(getPlayer()), table.concat(out, " ")))
            else
                mark("test sctd view +" .. (n * 10) .. "s: " .. target .. " not here")
            end
        end
    end, done)
end

-- ---- 10-27 SCTD legacy migration: old-style items made by SCTD's own swap (takeover disabled), then reports of every
-- item on the client next to the server's (SGTestDriver_Server.lua sctdAll) after each join ----

local MIG = {
    sprayer = "Base.KnapsackSprayer", satchel = "Base.Bag_Satchel", jacket = "Base.Jacket_Black",
    punk = "Base.Jacket_Leather_Punk", hat = "Base.Hat_BaseballCap", shirt = "Base.Shirt_Lumberjack",
    shoes = "Base.Shoes_Black", boots = "Base.Shoes_ArmyBoots", gloves = "Base.Gloves_LeatherGloves",
    bag = "Base.Bag_Schoolbag", scarf = "Base.Scarf_StripeBlackWhite", pencil = "Base.Pencil", battery = "Base.Battery",
    necklace = "Base.Necklace_Gold",
}

local function flat(s) return (string.gsub(tostring(s), "%s", "_")) end

-- same line as the server half's migLine
local function migLine(it, where)
    local s = it:getFullType() .. "#" .. it:getID() .. " " .. where
    s = s .. string.format(" w=%.3f cond=%d/%d", it:getActualWeight(), it:getCondition(), it:getConditionMax())
    local fc = it:getFluidContainer()
    s = s .. (fc and string.format(" fluid=%.3f/%.3f", fc:getAmount(), fc:getCapacity()) or " fluid=-")
    if instanceof(it, "Clothing") then
        s = s .. string.format(" bite=%.1f scratch=%.1f bullet=%.1f ins=%.2f wind=%.2f water=%.2f run=%.2f combat=%.2f",
            it:getBiteDefense(), it:getScratchDefense(), it:getBulletDefense(), it:getInsulation(),
            it:getWindresistance(), it:getWaterResistance(), it:getRunSpeedModifier(), it:getCombatSpeedModifier())
    end
    if it:IsInventoryContainer() then
        local c = it:getItemContainer()
        s = s .. string.format(" cap=%d wr=%d n=%d", c:getCapacity(), it:getWeightReduction(), c:getItems():size())
    end
    local v, ci = it:getVisual(), it:getClothingItem()
    if v and ci then
        local t = v:getTint(ci)
        s = s .. string.format(" vis=%d,%d,%.2f/%.2f/%.2f", v:getTextureChoice(), v:getBaseTexture(), t:getRedFloat(),
            t:getGreenFloat(), t:getBlueFloat())
    end
    local md = it:hasModData() and it:getModData() or {}
    -- the custom color the tint of a custom-colored item is drawn from
    s = s .. string.format(" cc=%s:%.2f/%.2f/%.2f", tostring(it:isCustomColor()), it:getR(), it:getG(), it:getB())
    return s .. " name=" .. flat(it:getName()) .. " fav=" .. tostring(it:isFavorite()) .. " record="
        .. flat(ser(md.SCTD_Transmog)) .. " override=" .. flat(ser(md.Dont39AllInOne_Appearance))
        .. " icon=" .. flat(md.customInventoryIcon)
end

local function migWhere(it, inv)
    local p = getPlayer()
    local worn = p:getWornItems()
    if worn:contains(it) then return "worn:" .. tostring(worn:getLocation(it)) end
    if p:getPrimaryHandItem() == it then return "hand:primary" end
    if p:getSecondaryHandItem() == it then return "hand:secondary" end
    local c = it:getContainer()
    if c == inv then return "inv" end
    if c and c:getContainingItem() then
        local bag = c:getContainingItem()
        return "in:" .. bag:getFullType() .. "#" .. bag:getID()
    end
    return "nowhere"
end

local function migCollect(container, inv, out)
    local items = container:getItems()
    for i = 0, items:size() - 1 do
        local it = items:get(i)
        out[tostring(it:getID())] = migLine(it, migWhere(it, inv))
        if it:IsInventoryContainer() then migCollect(it:getItemContainer(), inv, out) end
    end
end

-- floor items within r squares of the player that carry an SCTD record, or whose id is in wanted
local function migGround(out, wanted, r)
    local p = getPlayer()
    local x, y, z = math.floor(p:getX()), math.floor(p:getY()), math.floor(p:getZ())
    for dx = -r, r do
        for dy = -r, r do
            local sq = getCell():getGridSquare(x + dx, y + dy, z)
            local objs = sq and sq:getWorldObjects()
            if objs then
                for i = 0, objs:size() - 1 do
                    local it = objs:get(i):getItem()
                    if it and (wanted[it:getID()] or (it:hasModData() and it:getModData().SCTD_Transmog)) then
                        out[tostring(it:getID())] = migLine(it, "ground:" .. (x + dx) .. "," .. (y + dy) .. "," .. z)
                    end
                end
            end
        end
    end
    return x, y, z
end

local function fields(line)
    local t, n = {}, 0
    for tok in string.gmatch(line, "%S+") do
        n = n + 1
        if n == 1 then t.item = tok elseif n == 2 then t.where = tok
        else
            local k, v = string.match(tok, "^([%w_]+)=(.*)$")
            if k then t[k] = v end
        end
    end
    return t
end

local function sortedKeys(t)
    local keys = {}
    for k in pairs(t) do keys[#keys + 1] = k end
    table.sort(keys, function(a, b) return (tonumber(a) or 0) < (tonumber(b) or 0) end)
    return keys
end

-- every item on the client and on the server, then the comparison by id and field
local function migReport(tag, groundIds)
    local p = getPlayer()
    local inv = p:getInventory()
    local mine, wanted = {}, {}
    for _, id in ipairs(groundIds or {}) do wanted[tonumber(id)] = true end
    migCollect(inv, inv, mine)
    local x, y, z = migGround(mine, wanted, 3)
    for _, id in ipairs(sortedKeys(mine)) do mark("test mig " .. tag .. " client: " .. mine[id]) end
    mark("test mig " .. tag .. " client: worn " .. wornText())
    local r = ask("sctdAll", { tag = tag, x = x, y = y, z = z, r = 3, ids = table.concat(groundIds or {}, ",") })
    if not r or not r.lines then
        mark("test mig " .. tag .. " server: no answer " .. tostring(r and r.line))
        return
    end
    for _, id in ipairs(sortedKeys(r.lines)) do mark("test mig " .. tag .. " server: " .. r.lines[id]) end
    mark("test mig " .. tag .. " server: worn " .. tostring(r.worn) .. " bodyForm=" .. tostring(r.bodyForm))
    local same, differ, clientOnly, serverOnly, n = 0, {}, {}, {}, 0
    for _, id in ipairs(sortedKeys(mine)) do
        n = n + 1
        local theirs = r.lines[id]
        if not theirs then
            clientOnly[#clientOnly + 1] = fields(mine[id]).item
        elseif theirs == mine[id] then
            same = same + 1
        else
            local a, b, keys = fields(mine[id]), fields(theirs), {}
            for k, v in pairs(a) do if b[k] ~= v then keys[#keys + 1] = k .. " c=" .. v .. " s=" .. tostring(b[k]) end end
            for k, v in pairs(b) do if a[k] == nil then keys[#keys + 1] = k .. " c=nil s=" .. v end end
            table.sort(keys)
            differ[#differ + 1] = a.item .. ": " .. table.concat(keys, "; ")
        end
    end
    for _, id in ipairs(sortedKeys(r.lines)) do
        if not mine[id] then serverOnly[#serverOnly + 1] = fields(r.lines[id]).item end
    end
    mark(string.format("test mig %s compare: client=%d server=%d same=%d differ=%d clientOnly=[%s] serverOnly=[%s]",
        tag, n, r.count or 0, same, #differ, table.concat(clientOnly, " "), table.concat(serverOnly, " ")))
    for _, d in ipairs(differ) do mark("test mig " .. tag .. " differs " .. d) end
end

local function moveTo(id, container)
    local it = byId(id)
    if not it then error("no item " .. tostring(id) .. " to move") end
    ISTimedActionQueue.add(ISInventoryTransferAction:new(getPlayer(), it, it:getContainer(), container))
    whenIdle()
end

local function resultOf(r)
    return r and r.ok and r.result or nil
end

-- step 1 (takeover disabled on the server): the old-style items, through SCTD's own server functions
function tests.sctdMigMake(done)
    seq(function()
        local p = getPlayer()
        local st = ask("sctdState", {})
        mark("test mig state: " .. tostring(st and st.line))
        local order = { "sprayer", "satchel", "jacket", "punk", "hat", "shirt", "shoes", "boots", "gloves", "bag",
            "scarf", "pencil", "battery" }
        local types = {}
        for _, k in ipairs(order) do types[#types + 1] = MIG[k] end
        local got = give(table.concat(types, ","))
        local id = {}
        for i, k in ipairs(order) do id[k] = got[i] end
        for _, k in ipairs({ "sprayer", "satchel", "jacket", "hat", "shoes", "gloves", "scarf" }) do wear(id[k]) end
        moveTo(id.pencil, byId(id.satchel):getItemContainer())
        moveTo(id.battery, byId(id.satchel):getItemContainer())
        local f = ask("fill", { id = id.sprayer, amount = 2 })
        mark("test mig fill: " .. tostring(f and f.line))
        sleep(3000)
        migReport("beforeSwap")

        local old = {}
        old.sprayer = resultOf(sctdServer("hide", id.sprayer))
        old.satchel = resultOf(sctdServer("hide", id.satchel))
        old.jacket = resultOf(sctdServer("apply", id.jacket, { source = id.punk, ignore = false, invert = false }))
        old.hat = resultOf(sctdServer("apply", id.hat, { source = id.shirt, ignore = true, invert = false }))
        old.shoes = resultOf(sctdServer("apply", id.shoes, { source = id.boots, ignore = false, invert = true }))
        old.gloves = resultOf(sctdServer("hide", id.gloves))
        old.scarf = resultOf(sctdServer("hide", id.scarf))
        sleep(8000)
        -- the transmogged jacket recolored in SCTD's skin panel (SCTD's own changeColor command): a custom color
        if old.jacket then
            local c = SCTD_ChangeColor(p, byId(old.jacket), 0.2, 0.6, 0.9)
            mark("test mig ChangeColor transmogged jacket returned " .. tostring(c))
            sleep(5000)
        end
        migReport("swapped")

        -- a hidden placeholder taken off into the school bag
        if old.gloves then
            ISTimedActionQueue.add(ISUnequipAction:new(p, byId(old.gloves), 50))
            whenIdle()
            moveTo(old.gloves, byId(id.bag):getItemContainer())
        end
        -- a hidden placeholder dropped on the floor, for the pick-up after the migration
        if old.scarf then
            ISInventoryPaneContextMenu.dropItem(byId(old.scarf), 0)
            whenIdle()
            sleep(3000)
        end
        -- SCTD's auto-hide of the vanilla clothes still worn
        local form = give(p:isFemale() and "Base.FemaleBodyT512" or "Base.MaleBodyT512")[1]
        wear(form)
        sleep(10000)
        migReport("made", { old.scarf })

        local saved = { form = form, bag = id.bag, punk = id.punk, shirt = id.shirt, boots = id.boots,
            gx = math.floor(p:getX()), gy = math.floor(p:getY()), gz = math.floor(p:getZ()) }
        for k, v in pairs(old) do saved["old_" .. k] = v end
        saveIds(saved)
        mark("test mig ids: " .. ser(saved))
    end, done)
end

-- steps 2, 3 and after: every item on both sides after a join, twice
function tests.sctdMigReport(done)
    seq(function()
        local saved = loadIds()
        mark("test mig ids: " .. ser(saved))
        migReport("join", { saved.old_scarf })
        sleep(30000)
        migReport("join30s", { saved.old_scarf })
    end, done)
end

local function worldItem(id, x, y, z, r)
    for dx = -r, r do
        for dy = -r, r do
            local sq = getCell():getGridSquare(x + dx, y + dy, z)
            local objs = sq and sq:getWorldObjects()
            if objs then
                for i = 0, objs:size() - 1 do
                    local o = objs:get(i)
                    if o:getItem() and o:getItem():getID() == id then return o end
                end
            end
        end
    end
end

-- step 4: the placeholder dropped before the migration, picked up (not converted while carried) and worn (converted
-- on the clothing event)
function tests.sctdMigPickup(done)
    seq(function()
        local p = getPlayer()
        local saved = loadIds()
        local id = tonumber(saved.old_scarf)
        migReport("beforePickup", { id })
        local o = worldItem(id, math.floor(p:getX()), math.floor(p:getY()), math.floor(p:getZ()), 3)
        if not o then error("dropped item " .. tostring(id) .. " not on the floor near the player") end
        ISTimedActionQueue.add(ISGrabItemAction:new(p, o, 50))
        whenIdle()
        sleep(5000)
        local it = byId(id)
        mark("test mig picked up: " .. (it and migLine(it, migWhere(it, p:getInventory())) or "not in the inventory"))
        migReport("carried", { id })
        if it then
            wear(id)
            local worn = now()
            mark("test mig worn at " .. worn)
            -- the old item leaves the inventory when the server swaps it
            local gone = waitUntil(function() return byId(id) == nil end, 15000)
            mark("test mig " .. (gone and ("converted " .. (now() - worn) .. " ms after the wear")
                or "not converted 15 s after the wear"))
            sleep(3000)
        end
        migReport("pickedUpWorn", { id })
    end, done)
end

-- step 5 (takeover disabled again): SCTD hides and shows its own items; the migration converts nothing on the clothing
-- events; SCTD's Restore clears a converted item's record (the takeover's wrapper)
function tests.sctdMigDisabled(done)
    seq(function()
        local p = getPlayer()
        local saved = loadIds()
        local st = ask("sctdState", {})
        mark("test mig state: " .. tostring(st and st.line))
        migReport("disabledStart")
        local form = byId(saved.form)
        if form and p:getWornItems():contains(form) then
            takeOff(saved.form)
            sleep(8000)
            migReport("formOff")
        end
        local got = give("Base.Glasses_Aviators,Base.Necklace_Gold")
        wear(got[1])
        wear(got[2])
        sleep(3000)
        local glasses = resultOf(sctdServer("hide", got[1]))
        local necklace = resultOf(sctdServer("hide", got[2]))
        sleep(10000)
        migReport("sctdHidden")
        -- the necklace placeholder goes to the floor, for sctdMigPickup2 once the takeover runs again
        if necklace then
            ISInventoryPaneContextMenu.dropItem(byId(necklace), 0)
            whenIdle()
            sleep(3000)
        end
        if glasses then sctdServer("show", glasses) end
        local worn = p:getWornItems()
        for i = 0, worn:size() - 1 do
            local it = worn:getItemByIndex(i)
            if it:getFullType() == MIG.jacket then sctdServer("restore", it:getID()) break end
        end
        sleep(10000)
        migReport("sctdShownRestored", { necklace })
        saved.disabled_necklace = necklace
        saveIds(saved)
    end, done)
end

-- after sctdMigDisabled, takeover running again: the dropped necklace placeholder picked up and worn (the server's
-- wear fires no Lua clothing event), then a held pencil moved into the school bag (a transaction taking an equipped
-- item off fires one on the server)
function tests.sctdMigPickup2(done)
    seq(function()
        local p = getPlayer()
        local saved = loadIds()
        local id = tonumber(saved.disabled_necklace)
        migReport("join", { id })
        local o = worldItem(id, math.floor(p:getX()), math.floor(p:getY()), math.floor(p:getZ()), 3)
        if not o then error("dropped item " .. tostring(id) .. " not on the floor near the player") end
        ISTimedActionQueue.add(ISGrabItemAction:new(p, o, 50))
        whenIdle()
        sleep(5000)
        migReport("carried", { id })
        if not byId(id) then error("picked-up item " .. tostring(id) .. " not in the inventory") end
        wear(id)
        sleep(10000)
        migReport("wornNoEvent", { id })
        local pencil = give("Base.Pencil")[1]
        ISTimedActionQueue.add(ISEquipWeaponAction:new(p, byId(pencil), 50, true, false))
        whenIdle()
        sleep(3000)
        moveTo(pencil, byId(saved.bag):getItemContainer())
        sleep(10000)
        migReport("afterEvent", { id })
        local it = byId(id)
        if it and it:getFullType() == MIG.necklace then return end
        -- the event the server's own code fires (SCTD's swaps, a transaction), raised directly
        local r = ask("clothingEvent", {})
        mark("test mig clothingEvent: " .. tostring(r and r.line))
        sleep(10000)
        migReport("afterSyntheticEvent", { id })
    end, done)
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
