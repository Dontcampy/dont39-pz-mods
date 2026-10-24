-- Server half of Sync Guard's test driver (research/harness, not shipped). On the driver's client command it changes
-- a container on the server without any packet to the client, as a lost or never-sent update leaves it (AC4, AC5,
-- AC9). Each change is printed with the prefix [SGTEST] and answered with the server command SGTest.result.
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
