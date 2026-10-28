-- Server half of Server Reborn's test driver (research/harness, not shipped). On the driver's client command it changes
-- a container on the server without any packet to the client, as a lost or never-sent update leaves it (AC4, AC5,
-- AC9), or gives the player a bag (AC12). Each change is printed with the prefix [SGTEST] and answered with the server
-- command SGTest.result.
if not isServer() then return end

local function log(s) print("[SGTEST] server: " .. tostring(s)) end

local function reply(player, args)
    sendServerCommand(player, "SGTest", "result", args)
end

-- the container as the client named it: square, object index, container index, checked against its type
local function objectContainer(args)
    local sq = getCell():getGridSquare(tonumber(args.x), tonumber(args.y), tonumber(args.z))
    if not sq then return nil, "square not loaded" end
    local objs = sq:getObjects()
    local i = tonumber(args.obj)
    if i < 0 or i >= objs:size() then return nil, "no object " .. i .. " (" .. objs:size() .. " objects)" end
    local c = objs:get(i):getContainerByIndex(tonumber(args.cont))
    if not c then return nil, "object " .. i .. " has no container " .. tostring(args.cont) end
    if args.type and c:getType() ~= args.type then return nil, "container is " .. c:getType() .. ", not " .. args.type end
    return c
end

local commands = {}

-- ids: comma-separated item ids in the player's inventory. A worn one also leaves the server's worn list (no packet);
-- the hands are left alone, as setting them sends Equip to the owner.
function commands.removePlayerItems(player, args)
    local inv = player:getInventory()
    local done, missing = {}, {}
    for id in string.gmatch(tostring(args.ids), "[^,]+") do
        local item = inv:getItemWithID(tonumber(id))
        if item then
            local worn = player:isEquippedClothing(item)
            if worn then player:getWornItems():remove(item) end
            inv:Remove(item)
            table.insert(done, id .. (worn and "(worn)" or ""))
        else
            table.insert(missing, id)
        end
    end
    local line = "removePlayerItems removed=" .. table.concat(done, " ") .. " missing=" .. table.concat(missing, " ")
    log(line)
    reply(player, { what = "removePlayerItems", line = line })
end

-- after removePlayerItems: the server's hands may still name a removed item (sends Equip to the owner)
function commands.clearHands(player, args)
    player:setPrimaryHandItem(nil)
    player:setSecondaryHandItem(nil)
    log("clearHands")
end

-- a bag in the player's inventory for the nested test, sent to the client as the game sends an added item
function commands.giveBag(player, args)
    local inv = player:getInventory()
    local bag = inv:AddItem(args.type or "Base.Bag_Schoolbag")
    if not bag then
        log("giveBag: AddItem returned nothing")
        reply(player, { what = "giveBag", line = "error AddItem returned nothing" })
        return
    end
    sendAddItemToContainer(inv, bag)
    local line = "giveBag " .. bag:getFullType() .. " id=" .. bag:getID()
    log(line)
    reply(player, { what = "giveBag", id = bag:getID(), line = line })
end

-- types: comma-separated full types, each added to the player's inventory and sent as the game sends an added item
function commands.give(player, args)
    local inv = player:getInventory()
    local ids = {}
    for t in string.gmatch(tostring(args.types), "[^,]+") do
        local item = inv:AddItem(t)
        if not item then error("AddItem " .. t .. " returned nothing") end
        sendAddItemToContainer(inv, item)
        table.insert(ids, item:getID())
    end
    local line = "give " .. tostring(args.types) .. " ids=" .. table.concat(ids, ",")
    log(line)
    reply(player, { what = "give", ids = table.concat(ids, ","), line = line })
end

-- same line as the client driver's foodStats
local function foodStats(f)
    return string.format("end=%.4f hung=%.4f thirst=%.4f base=%.4f unhappy=%.4f bored=%.4f stress=%.4f fat=%.4f uses=%d",
        f:getEnduranceChange(), f:getHungChange(), f:getThirstChange(), f:getBaseHunger(), f:getUnhappyChange(),
        f:getBoredomChange(), f:getStressChange(), f:getFatigueChange(), f:getCurrentUses())
end

-- ids: foods in the player's inventory. The first gets stats the others lack, then each is synced in order through
-- ItemStats, so a stat the packet leaves out for a later one would keep the first one's value on the client.
function commands.syncFoods(player, args)
    local inv = player:getInventory()
    local foods = {}
    for id in string.gmatch(tostring(args.ids), "[^,]+") do
        local f = inv:getItemWithID(tonumber(id))
        if not f or not instanceof(f, "Food") then error("no food " .. id) end
        table.insert(foods, f)
    end
    foods[1]:setEnduranceChange(0.1)
    foods[1]:setThirstChange(-0.2)
    foods[1]:setHungChange(-0.3)
    local stats = {}
    for _, f in ipairs(foods) do
        f:syncItemFields()
        stats[tostring(f:getID())] = foodStats(f)
        log("syncFoods " .. f:getFullType() .. " " .. f:getID() .. " " .. stats[tostring(f:getID())])
    end
    reply(player, { what = "syncFoods", stats = stats, line = "syncFoods synced " .. #foods })
end

-- how: Remove (n items through ItemContainer.Remove), removeAll (removeAllItems, as fire empties a burning object),
-- listClear (the public item list cleared, which no ItemContainer method sees)
function commands.changeContainer(player, args)
    local c, why = objectContainer(args)
    local where = tostring(args.x) .. "," .. tostring(args.y) .. "," .. tostring(args.z) .. " obj " .. tostring(args.obj)
        .. " cont " .. tostring(args.cont)
    if not c then
        log("changeContainer " .. where .. ": " .. why)
        reply(player, { what = "changeContainer", tag = args.tag, line = "error " .. why })
        return
    end
    local before = c:getItems():size()
    if args.how == "removeAll" then
        c:removeAllItems()
    elseif args.how == "listClear" then
        c:getItems():clear()
    else
        for _ = 1, math.min(tonumber(args.n) or 1, before) do
            c:Remove(c:getItems():get(0))
        end
    end
    local line = string.format("changeContainer %s %s (%s) how=%s items %d -> %d", tostring(args.tag), where,
        c:getType(), tostring(args.how), before, c:getItems():size())
    log(line)
    reply(player, { what = "changeContainer", tag = args.tag, line = line })
end

-- ---- appearance override (task 10-27-appearance-override) ----

local Appearance = require "Dont39AllInOne_AppearanceOverrideShared"

local function playerItem(player, id)
    local it = player:getInventory():getItemWithID(tonumber(id))
    if not it then error("no item " .. tostring(id) .. " in the inventory") end
    return it
end

-- same line as the client driver's itemLine
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

local function wornOf(player, fullType)
    local worn = player:getWornItems()
    for i = 0, worn:size() - 1 do
        local it = worn:getItemByIndex(i)
        if it:getFullType() == fullType then return it end
    end
end

-- id: an item with a fluid container in the player's inventory; amount litres of water, sent as the game sends a
-- fluid change
function commands.fill(player, args)
    local item = playerItem(player, args.id)
    local fc = item:getFluidContainer()
    if not fc then error(item:getFullType() .. " has no fluid container") end
    fc:addFluid(FluidType.Water, tonumber(args.amount))
    item:syncItemFields()
    item:sendSyncEntity(nil)
    local line = "fill " .. itemLine(item)
    log(line)
    reply(player, { what = "fill", line = line })
end

-- every value fixed, so nothing is picked at random when drawn
local function lookOf(fullType)
    local probe = instanceItem(fullType)
    if not probe then error("no item " .. fullType) end
    local ci = probe:getClothingItem()
    if not ci then error(fullType .. " has no clothing item") end
    local choices, bases = ci:getTextureChoices(), ci:getBaseTextures()
    return { type = fullType, textureChoice = (choices and choices:size() > 0) and 0 or -1,
        baseTexture = (bases and bases:size() > 0) and 0 or -1, tint = { 1, 1, 1, 1 } }
end

-- action: hidden, look (args.type), clear
function commands.appearanceSet(player, args)
    local item = playerItem(player, args.id)
    if args.action == "hidden" then
        Appearance.setHidden(player, item)
    elseif args.action == "look" then
        Appearance.setLook(player, item, lookOf(args.type))
    elseif args.action == "clear" then
        Appearance.clear(player, item)
    else
        error("unknown action " .. tostring(args.action))
    end
    local line = "appearanceSet " .. tostring(args.action) .. " " .. itemLine(item)
    log(line)
    reply(player, { what = "appearanceSet", line = line })
end

function commands.appearanceReport(player, args)
    local line = "sprayer " .. itemLine(wornOf(player, args.sprayer)) .. " | jacket "
        .. itemLine(wornOf(player, args.jacket))
    log("appearanceReport " .. line)
    reply(player, { what = "appearanceReport", line = line })
end

-- ---- SCTD render takeover (task 10-27-sctd-render-takeover): SCTD's own functions called on the server ----

-- a table as sorted k=v, one level deep (tint lists inline)
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

local function findAnywhere(player, id)
    local inv = player:getInventory()
    local it = inv:getItemWithIDRecursiv(tonumber(id))
    return it
end

-- same line as the client driver's sctdLine
local function sctdLine(player, it)
    if not it then return "none" end
    local inv = player:getInventory()
    local where
    if player:isEquippedClothing(it) or player:getWornItems():contains(it) then
        where = "worn:" .. tostring(player:getWornItems():getLocation(it))
    elseif it:getContainer() == inv then
        where = "inv"
    elseif it:getContainer() then
        where = "in:" .. tostring(it:getContainer():getType())
    else
        where = "nowhere"
    end
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
    return s .. " record=" .. ser(md.SCTD_Transmog) .. " override=" .. ser(md.Dont39AllInOne_Appearance)
        .. " shown=" .. tostring(md.SCTD_TransmogShown) .. " hiddenFlag=" .. tostring(md.SCTD_TransmogHidden)
end

local function wornList(player)
    local worn, out = player:getWornItems(), {}
    for i = 0, worn:size() - 1 do
        local it = worn:getItemByIndex(i)
        table.insert(out, it:getFullType() .. "#" .. it:getID() .. "@" .. tostring(worn:getLocation(it)))
    end
    return table.concat(out, " ")
end

local function countAll(container)
    local items, n = container:getItems(), 0
    for i = 0, items:size() - 1 do
        n = n + 1
        local it = items:get(i)
        if it:IsInventoryContainer() then n = n + countAll(it:getItemContainer()) end
    end
    return n
end

-- ids: comma-separated; also the worn list and the inventory counts
function commands.sctdReport(player, args)
    local parts = {}
    for id in string.gmatch(tostring(args.ids or ""), "[^,]+") do
        table.insert(parts, sctdLine(player, findAnywhere(player, id)))
    end
    local inv = player:getInventory()
    local line = string.format("sctdReport %s | items=%d all=%d | worn %s | bodyForm=%s autoHideSwitch=%s",
        table.concat(parts, " || "), inv:getItems():size(), countAll(inv), wornList(player),
        tostring(SCTD_WearsBodyForm(player)), tostring(SCTD_ClothingAppearance.IsAutoHideNonSct(player)))
    log(line)
    reply(player, { what = "sctdReport", line = line })
end

-- op: hide, show, apply (base, source, ignore, invert), restore; each SCTD global as SCTD's server handler calls it
function commands.sctd(player, args)
    local a = findAnywhere(player, args.id)
    if not a then error("no item " .. tostring(args.id)) end
    local r1, r2
    if args.op == "hide" then
        r1, r2 = SCTD_Transmog_HideItem(player, a)
    elseif args.op == "show" then
        r1, r2 = SCTD_Transmog_ShowItem(player, a)
    elseif args.op == "restore" then
        r1, r2 = SCTD_Transmog_Restore(player, a)
    elseif args.op == "apply" then
        local b = findAnywhere(player, args.source)
        if not b then error("no item " .. tostring(args.source)) end
        r1, r2 = SCTD_Transmog_Apply(player, a, b, args.ignore == true, args.invert == true)
    else
        error("unknown op " .. tostring(args.op))
    end
    local line = string.format("sctd %s %s -> %s %s", tostring(args.op), a:getFullType() .. "#" .. a:getID(),
        tostring(r1), r2 and (r2.getFullType and (r2:getFullType() .. "#" .. r2:getID()) or tostring(r2)) or "nil")
    log(line)
    reply(player, { what = "sctd", line = line, ok = r1 == true, result = r2 and r2.getID and r2:getID() or nil })
end

-- the takeover's state on the server, as one line
function commands.sctdState(player, args)
    local line = "sctdState HideItem_Server=" .. tostring(getFilenameOfClosure(SCTD_Transmog_HideItem_Server)) .. ":"
        .. tostring(getFirstLineOfClosure(SCTD_Transmog_HideItem_Server)) .. " Restore="
        .. tostring(getFilenameOfClosure(SCTD_Transmog_Restore)) .. ":" .. tostring(getFirstLineOfClosure(SCTD_Transmog_Restore))
    log(line)
    reply(player, { what = "sctdState", line = line })
end

-- ---- SCTD legacy migration (task 10-27-sctd-legacy-migration) ----

-- no spaces inside a value, so the client can split the line into fields
local function flat(s) return (string.gsub(tostring(s), "%s", "_")) end

-- same line as the client driver's migLine
local function migLine(player, it, where)
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

local function migWhere(player, it, inv)
    local worn = player:getWornItems()
    if worn:contains(it) then return "worn:" .. tostring(worn:getLocation(it)) end
    if player:getPrimaryHandItem() == it then return "hand:primary" end
    if player:getSecondaryHandItem() == it then return "hand:secondary" end
    local c = it:getContainer()
    if c == inv then return "inv" end
    if c and c:getContainingItem() then
        local bag = c:getContainingItem()
        return "in:" .. bag:getFullType() .. "#" .. bag:getID()
    end
    return "nowhere"
end

local function migCollect(player, container, inv, out)
    local items = container:getItems()
    for i = 0, items:size() - 1 do
        local it = items:get(i)
        out[tostring(it:getID())] = migLine(player, it, migWhere(player, it, inv))
        if it:IsInventoryContainer() then migCollect(player, it:getItemContainer(), inv, out) end
    end
end

-- items on the floor within r squares of x,y,z that carry an SCTD record, or whose id is in ids
local function migGround(player, args, out)
    local wanted = {}
    for id in string.gmatch(tostring(args.ids or ""), "[^,]+") do wanted[tonumber(id)] = true end
    local x, y, z, r = tonumber(args.x), tonumber(args.y), tonumber(args.z), tonumber(args.r) or 3
    if not x then return end
    for dx = -r, r do
        for dy = -r, r do
            local sq = getCell():getGridSquare(x + dx, y + dy, z)
            local objs = sq and sq:getWorldObjects()
            if objs then
                for i = 0, objs:size() - 1 do
                    local it = objs:get(i):getItem()
                    if it and (wanted[it:getID()] or (it:hasModData() and it:getModData().SCTD_Transmog)) then
                        out[tostring(it:getID())] = migLine(player, it, "ground:" .. (x + dx) .. "," .. (y + dy) .. "," .. z)
                    end
                end
            end
        end
    end
end

-- every item of the player (bags included) and the matching floor items, by id
function commands.sctdAll(player, args)
    local inv = player:getInventory()
    local lines = {}
    migCollect(player, inv, inv, lines)
    migGround(player, args, lines)
    local n = 0
    for id, line in pairs(lines) do
        n = n + 1
        log("sctdAll " .. tostring(args.tag) .. " " .. line)
    end
    log("sctdAll " .. tostring(args.tag) .. " count=" .. n .. " worn " .. wornList(player))
    reply(player, { what = "sctdAll", lines = lines, worn = wornList(player), count = n,
        bodyForm = tostring(SCTD_WearsBodyForm(player)) })
end

-- when each player object first shows in the online list, with its item count (the migration's scan follows on a
-- tick of its own)
local seenPlayers = setmetatable({}, { __mode = "k" })
local nextSeen = 0
Events.OnTick.Add(function()
    local now = getTimestampMs()
    if now < nextSeen then return end
    nextSeen = now + 250
    local players = getOnlinePlayers()
    for i = 0, players:size() - 1 do
        local p = players:get(i)
        if not seenPlayers[p] then
            seenPlayers[p] = true
            log("online " .. tostring(p:getUsername()) .. " at " .. tostring(now) .. " items=" .. countAll(p:getInventory())
                .. " worn " .. wornList(p))
        end
    end
end)

-- the Lua clothing event for the player, as SCTD's swaps raise it
function commands.clothingEvent(player, args)
    triggerEvent("OnClothingUpdated", player)
    local line = "clothingEvent raised; worn " .. wornList(player)
    log(line)
    reply(player, { what = "clothingEvent", line = line })
end

-- each clothing event the server's Lua sees for a player; this mod loads last, so the worn list is the one after
-- every other handler (the migration's included)
Events.OnClothingUpdated.Add(function(character)
    if instanceof(character, "IsoPlayer") and not instanceof(character, "IsoAnimal") then
        log("OnClothingUpdated " .. tostring(character:getUsername()) .. " at " .. tostring(getTimestampMs()) .. " worn "
            .. wornList(character))
    end
end)

Events.OnClientCommand.Add(function(module, command, player, args)
    if module ~= "SGTest" then return end
    local fn = commands[command]
    if not fn then
        log("unknown command " .. tostring(command))
        return
    end
    local ok, err = pcall(fn, player, args or {})
    if not ok then
        log("error in " .. command .. ": " .. tostring(err))
        reply(player, { what = command, tag = args and args.tag, line = "error " .. tostring(err) })
    end
end)
