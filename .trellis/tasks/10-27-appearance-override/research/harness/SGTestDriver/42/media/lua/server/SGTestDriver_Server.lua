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
