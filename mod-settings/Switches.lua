-- A mod's switches on the mod options page, in the ini file its Java side (Switches.java beside this file) reads once
-- per launch, so a change applies from the next launch of the game. Each mod ships this file as
-- <mod id>_Switches.lua (pz-switches), since require names are global.

local Switches = {}

-- Kahlua strings are Java strings: one char, U+FEFF
local BOM = string.char(65279)

-- The page of spec: id (the mod id: log prefix, options id and translations UI_<id>_...), name (in the file's first
-- line), file (in the cache folder's Lua/), switches ({ key, text, description } in the Java side's order; text names
-- UI_<id>_<text> and UI_<id>_<text>Tooltip). Every switch is on by default, as on the Java side.
function Switches.page(spec)
    local PREFIX = "[" .. spec.id .. "] options: "
    local TEXT = "UI_" .. spec.id .. "_"
    local FILE = spec.file
    local HEADER = "# " .. spec.name .. ", read once at each launch of the game; also on the mod options page."
    local SWITCHES = spec.switches

    local byKey = {}
    for _, switch in ipairs(SWITCHES) do
        byKey[switch.key] = switch
    end

    -- Never throws.
    local function log(message)
        pcall(function() DebugType.Mod:println(PREFIX .. message) end)
    end

    -- Never throws.
    local function logError(message)
        pcall(function() DebugType.Mod:error(PREFIX .. message) end)
    end

    if not PZAPI or not PZAPI.ModOptions or not PZAPI.ModOptions.create or not PZAPI.ModOptions.load then
        error(PREFIX .. "the game's mod options (PZAPI.ModOptions) were not found")
    end

    local options = PZAPI.ModOptions:create(spec.id, TEXT .. "Options")
    local ticks = {}
    for _, switch in ipairs(SWITCHES) do
        ticks[switch.key] = options:addTickBox(switch.key, TEXT .. switch.text, true, TEXT .. switch.text .. "Tooltip")
    end

    -- The file's values; nil and "missing", or nil and the problem when it is not as the Java side reads it.
    local function readFile()
        local reader = getFileReader(FILE, false)
        if not reader then return nil, "missing" end
        local values, problem, number = {}, nil, 0
        local ok, err = pcall(function()
            while true do
                local line = reader:readLine()
                if line == nil then break end
                number = number + 1
                -- getFileReader reads UTF-8 and keeps a byte order mark, which the Java side skips
                if number == 1 and string.sub(line, 1, 1) == BOM then line = string.sub(line, 2) end
                line = string.match(line, "^%s*(.-)%s*$")
                if line ~= "" and string.sub(line, 1, 1) ~= "#" and not problem then
                    local key, value = string.match(line, "^(.-)%s*=%s*(.-)$")
                    value = value and string.lower(value)
                    if key == nil then
                        problem = "line " .. number .. " is not \"key = value\""
                    elseif byKey[key] == nil then
                        problem = "unknown key \"" .. key .. "\""
                    elseif value ~= "true" and value ~= "false" then
                        problem = "key " .. key .. " is not true or false"
                    elseif values[key] ~= nil then
                        problem = "key " .. key .. " is set twice"
                    else
                        values[key] = value == "true"
                    end
                end
            end
        end)
        pcall(function() reader:close() end)
        if not ok then error(err, 0) end
        if problem then return nil, problem end
        -- a file from an older version lacks the newer keys, which are on by default as on the Java side
        for _, switch in ipairs(SWITCHES) do
            if values[switch.key] == nil then values[switch.key] = true end
        end
        return values
    end

    local function writeFile(values)
        local writer = getFileWriter(FILE, true, false)
        if not writer then error("the file could not be opened", 0) end
        local ok, err = pcall(function()
            writer:write(HEADER .. "\n")
            for _, switch in ipairs(SWITCHES) do
                writer:write("# " .. switch.key .. ": " .. switch.description .. " (true or false)\n" .. switch.key
                    .. " = " .. tostring(values[switch.key]) .. "\n")
            end
        end)
        pcall(function() writer:close() end)
        if not ok then error(err, 0) end
    end

    -- The Java side reads only the file, so it wins over what the options API saved in ModOptions.ini.
    local function fromFile()
        local values, problem = readFile()
        if not values then
            -- the Java side reports the file's problems at launch
            if problem ~= "missing" then
                log(FILE .. ": " .. problem .. "; the page shows the defaults")
            end
            values = {}
            for _, switch in ipairs(SWITCHES) do
                values[switch.key] = true
            end
        end
        for _, switch in ipairs(SWITCHES) do
            ticks[switch.key]:setValue(values[switch.key])
        end
    end

    -- vanilla loads the saved values each time it builds the Mods page
    local vanillaLoad = PZAPI.ModOptions.load
    function PZAPI.ModOptions:load(...)
        -- outside pcall, so vanilla's errors surface as without this mod
        vanillaLoad(self, ...)
        local ok, err = pcall(fromFile)
        if not ok then
            logError("cannot read " .. FILE .. ": " .. tostring(err))
        end
    end

    -- after vanilla set the values from the page; the file is rewritten only when the page differs from it
    function options:apply()
        local ok, err = pcall(function()
            local values, changed, written = {}, false, {}
            local current = readFile()
            for _, switch in ipairs(SWITCHES) do
                values[switch.key] = ticks[switch.key]:getValue()
                if not current or current[switch.key] ~= values[switch.key] then changed = true end
                table.insert(written, switch.key .. " = " .. tostring(values[switch.key]))
            end
            if not changed then return end
            writeFile(values)
            log("wrote " .. FILE .. " (" .. table.concat(written, ", ") .. "); applies from the next launch of the game")
        end)
        if not ok then
            logError("cannot write " .. FILE .. ": " .. tostring(err))
        end
    end
end

return Switches
