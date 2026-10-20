-- Loading Progress: its two switches on the mod options page. The Java side reads the same file once per launch,
-- before any Lua runs, so a change applies from the next launch of the game.

local PREFIX = "[Dont39LoadingProgress] options: "
local OPTIONS_ID = "Dont39LoadingProgress"
local TEXT = "UI_Dont39LoadingProgress_"
-- in the cache folder's Lua/, where the Java side reads it; lines as progress/Settings.java writes them
local FILE = "Dont39LoadingProgress.ini"
local HEADER = "# Loading Progress, read once at each launch of the game; also on the mod options page."
local KEYS = { "display", "report" }
local COMMENTS = {
    display = "# display: show the progress bar and the log of what loads (true or false)",
    report = "# report: after each load, write a timing report to Lua/Dont39LoadingProgress/ in this folder"
        .. " (true or false)",
}
local DEFAULTS = { display = true, report = true }
-- Kahlua strings are Java strings: one char, U+FEFF
local BOM = string.char(65279)

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

local options = PZAPI.ModOptions:create(OPTIONS_ID, TEXT .. "Options")
local ticks = {
    display = options:addTickBox("display", TEXT .. "Display", DEFAULTS.display, TEXT .. "DisplayTooltip"),
    report = options:addTickBox("report", TEXT .. "Report", DEFAULTS.report, TEXT .. "ReportTooltip"),
}

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
                elseif COMMENTS[key] == nil then
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
    if not problem then
        for _, key in ipairs(KEYS) do
            if values[key] == nil then
                problem = "key " .. key .. " is missing"
                break
            end
        end
    end
    if problem then return nil, problem end
    return values
end

local function writeFile(values)
    local writer = getFileWriter(FILE, true, false)
    if not writer then error("the file could not be opened", 0) end
    local ok, err = pcall(function()
        writer:write(HEADER .. "\n")
        for _, key in ipairs(KEYS) do
            writer:write(COMMENTS[key] .. "\n" .. key .. " = " .. tostring(values[key]) .. "\n")
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
        values = DEFAULTS
    end
    for _, key in ipairs(KEYS) do
        ticks[key]:setValue(values[key])
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
        local values = { display = ticks.display:getValue(), report = ticks.report:getValue() }
        local current = readFile()
        if current and current.display == values.display and current.report == values.report then return end
        writeFile(values)
        log("wrote " .. FILE .. " (display = " .. tostring(values.display) .. ", report = "
            .. tostring(values.report) .. "); applies from the next launch of the game")
    end)
    if not ok then
        logError("cannot write " .. FILE .. ": " .. tostring(err))
    end
end
