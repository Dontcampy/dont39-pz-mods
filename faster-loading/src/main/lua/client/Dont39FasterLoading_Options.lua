-- Faster Loading: a switch per fix on the mod options page. The Java side reads the same file once per launch, at
-- the first load step it speeds up, so a change applies from the next launch of the game.

local PREFIX = "[Dont39FasterLoading] options: "
local OPTIONS_ID = "Dont39FasterLoading"
local TEXT = "UI_Dont39FasterLoading_"
-- in the cache folder's Lua/, where the Java side reads it; lines as settings/Settings.java writes them
local FILE = "Dont39FasterLoading.ini"
local HEADER = "# Faster Loading, read once at each launch of the game; also on the mod options page."
-- settings/Fix.java, in its order
local FIXES = {
    {
        key = "stripComments",
        text = "StripComments",
        comment = "# stripComments: remove the comments of script and tile files in one pass, with the game's result"
            .. " (true or false)",
    },
    {
        key = "textureCompression",
        text = "TextureCompression",
        comment = "# textureCompression: compress on all CPU cores the textures the game asks the graphics driver to"
            .. " compress, to the same DXT5 format (true or false)",
    },
    {
        key = "depthMapOrder",
        text = "DepthMapOrder",
        comment = "# depthMapOrder: load the depth maps of the game and of different mods side by side instead of one"
            .. " mod after another (true or false)",
    },
    {
        key = "luaFolderWalk",
        text = "LuaFolderWalk",
        comment = "# luaFolderWalk: list the Lua files of the game and the mods without looking up each folder's full"
            .. " path once per entry in it, with the game's list (true or false)",
    },
}
-- Kahlua strings are Java strings: one char, U+FEFF
local BOM = string.char(65279)

local FIX_BY_KEY = {}
for _, fix in ipairs(FIXES) do
    FIX_BY_KEY[fix.key] = fix
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

local options = PZAPI.ModOptions:create(OPTIONS_ID, TEXT .. "Options")
local ticks = {}
for _, fix in ipairs(FIXES) do
    ticks[fix.key] = options:addTickBox(fix.key, TEXT .. fix.text, true, TEXT .. fix.text .. "Tooltip")
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
                elseif FIX_BY_KEY[key] == nil then
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
    for _, fix in ipairs(FIXES) do
        if values[fix.key] == nil then values[fix.key] = true end
    end
    return values
end

local function writeFile(values)
    local writer = getFileWriter(FILE, true, false)
    if not writer then error("the file could not be opened", 0) end
    local ok, err = pcall(function()
        writer:write(HEADER .. "\n")
        for _, fix in ipairs(FIXES) do
            writer:write(fix.comment .. "\n" .. fix.key .. " = " .. tostring(values[fix.key]) .. "\n")
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
        for _, fix in ipairs(FIXES) do
            values[fix.key] = true
        end
    end
    for _, fix in ipairs(FIXES) do
        ticks[fix.key]:setValue(values[fix.key])
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
        for _, fix in ipairs(FIXES) do
            values[fix.key] = ticks[fix.key]:getValue()
            if not current or current[fix.key] ~= values[fix.key] then changed = true end
            table.insert(written, fix.key .. " = " .. tostring(values[fix.key]))
        end
        if not changed then return end
        writeFile(values)
        log("wrote " .. FILE .. " (" .. table.concat(written, ", ") .. "); applies from the next launch of the game")
    end)
    if not ok then
        logError("cannot write " .. FILE .. ": " .. tostring(err))
    end
end
