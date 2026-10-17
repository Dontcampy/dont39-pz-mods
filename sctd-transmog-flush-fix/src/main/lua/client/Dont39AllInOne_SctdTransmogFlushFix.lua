-- Dont39 All-in-One: sctd-transmog-flush-fix. Temporary fix for Shadow Cat Tactics: Dispatch until its author ships
-- one: SCTD_TransmogSync.Flush calls next(), which Kahlua lacks, so multiplayer clients error on every tick.
-- Replaced at runtime because multiplayer checks the mod's Lua files.

local PREFIX = "[Dont39AllInOne] sctd-transmog-flush-fix: "
local MOD_ID = "ShadowCatTacticsDispatch"
local FILE = "media/lua/shared/LingLi_SCTD_Skins.lua"
local FILE_NAME = "lingli_sctd_skins.lua"
local HEADER = "function SCTD_TransmogSync.Flush()"
-- The mod's Flush as of its version 131. fixedFlush stands in for exactly this text, so any other text is not ours to
-- replace.
local EXPECTED = [[
function SCTD_TransmogSync.Flush()
    if not isClient() then return end
    for i = 0, 3 do
        local player = getSpecificPlayer(i)
        local state = player and SCTD_TransmogSync.players[player]
        if state and next(state.pending) then SCTD_TransmogSync.FlushPlayer(player) end
    end
end]]

-- Never throws.
local function log(message)
    pcall(function() DebugType.Mod:println(PREFIX .. message) end)
end

-- [trace]: the Lua stack where the error was raised (the game's pcall returns it third).
local function fail(what, err, trace)
    pcall(function()
        DebugType.Mod:error(PREFIX .. "disabled after an error while " .. what .. ": " .. tostring(err) .. "\n"
            .. tostring(trace))
    end)
end

local function hasEntry(t)
    for _ in pairs(t) do return true end
    return false
end

local function fixedFlush()
    if not isClient() then return end
    for i = 0, 3 do
        local player = getSpecificPlayer(i)
        local state = player and SCTD_TransmogSync.players[player]
        if state and hasEntry(state.pending) then SCTD_TransmogSync.FlushPlayer(player) end
    end
end

local function readLines()
    local reader = getModFileReader(MOD_ID, FILE, false)
    if not reader then error(MOD_ID .. "'s " .. FILE .. " was not found") end
    local lines = {}
    local ok, err = pcall(function()
        local line = reader:readLine()
        while line ~= nil do
            lines[#lines + 1] = line
            line = reader:readLine()
        end
    end)
    -- a failing close must not hide the read error
    local closed, closeErr = pcall(function() reader:close() end)
    if not ok then error(err) end
    if not closed then error(closeErr) end
    return lines
end

-- Returns the Flush block's text and its first and last line numbers.
local function findFlush(lines)
    local first = nil
    for n, line in ipairs(lines) do
        if line == HEADER then
            if first then error(HEADER .. " appears more than once in " .. FILE) end
            first = n
        end
    end
    if not first then error(HEADER .. " was not found in " .. FILE) end
    local block = {}
    for n = first, #lines do
        block[#block + 1] = lines[n]
        if lines[n] == "end" then
            return table.concat(block, "\n"), first, n
        end
    end
    error(HEADER .. " in " .. FILE .. " has no closing 'end' line")
end

-- Returns the mod's Flush to replace, or nil and why there is nothing to do.
local function decide()
    local info = getModInfoByID(MOD_ID)
    if not info or not isModActive(info) then
        return nil, MOD_ID .. " is not enabled"
    end
    local sync = SCTD_TransmogSync
    if type(sync) ~= "table" or type(sync.Flush) ~= "function" or type(sync.FlushPlayer) ~= "function"
        or type(sync.players) ~= "table" then
        error("SCTD_TransmogSync (Flush, FlushPlayer, players) was not found")
    end
    local block, first, last = findFlush(readLines())
    if block ~= EXPECTED then
        if not string.find(block, "next%(") then
            return nil, MOD_ID .. "'s SCTD_TransmogSync.Flush no longer calls next(), so this fix is not needed"
        end
        error("SCTD_TransmogSync.Flush at " .. FILE .. ":" .. first .. " is not the version this fix replaces")
    end
    -- Another mod may have replaced it already; that one is not the text checked above.
    local original = sync.Flush
    local name = string.lower(tostring(getFilenameOfClosure(original)))
    local line = getFirstLineOfClosure(original)
    if string.sub(name, -string.len(FILE_NAME)) ~= FILE_NAME or line < first or line > last then
        error("SCTD_TransmogSync.Flush is not the one in " .. FILE .. ":" .. first .. " but from " .. name .. ":"
            .. line)
    end
    return original
end

-- The mod registered Flush by value, so the events need the replacement too.
local function apply(original)
    Events.OnContainerUpdate.Remove(original)
    Events.OnTickEvenPaused.Remove(original)
    SCTD_TransmogSync.Flush = fixedFlush
    Events.OnContainerUpdate.Add(fixedFlush)
    Events.OnTickEvenPaused.Add(fixedFlush)
end

-- Never throws: leaves the mod as it would be without this file.
local function undo(original)
    for _, event in ipairs({ Events.OnContainerUpdate, Events.OnTickEvenPaused }) do
        pcall(event.Remove, fixedFlush)
        pcall(event.Remove, original)
        pcall(event.Add, original)
    end
    pcall(function() SCTD_TransmogSync.Flush = original end)
end

local ok, original, why = pcall(decide)
if not ok then
    fail("checking " .. MOD_ID, original, why)
elseif not original then
    log("idle: " .. why)
else
    local applied, err, trace = pcall(apply, original)
    if applied then
        log("ready")
    else
        undo(original)
        fail("replacing SCTD_TransmogSync.Flush", err, trace)
    end
end
