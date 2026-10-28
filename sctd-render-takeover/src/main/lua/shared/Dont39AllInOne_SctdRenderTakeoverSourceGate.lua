-- Dont39 All-in-One: sctd-render-takeover, the check that another mod's Lua is the version this feature was reviewed
-- against: blocks of its source text by length and hash, and its live globals coming from those blocks.

local SourceGate = {}

-- Primes below 2^31: every product stays below 2^53, where Kahlua's numbers are exact. string.byte gives UTF-16
-- units in Kahlua, hence base 65536.
local P1, P2, BASE = 2147483647, 2147483629, 65536

function SourceGate.hash(text)
    local a, b = 0, 0
    for i = 1, string.len(text) do
        local c = string.byte(text, i)
        a = (a * BASE + c) % P1
        b = (b * BASE + c) % P2
    end
    return a, b
end

-- Kahlua prints large whole numbers in exponent form.
local function digits(n)
    if n < 0 then return "-" .. digits(-n) end
    if n < 100000 then return tostring(n) end
    local low = n % 100000
    return digits((n - low) / 100000) .. string.sub(tostring(100000 + low), 2)
end
SourceGate.digits = digits

function SourceGate.readLines(modId, path)
    local reader = getModFileReader(modId, path, false)
    if not reader then error(modId .. "'s " .. path .. " was not found") end
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

-- The line numbers of each line of [lines] that is one of [headers] (a set), in one pass over the file.
local function index(lines, headers)
    local at = {}
    for n, line in ipairs(lines) do
        if headers[line] then
            local found = at[line]
            if found then found[#found + 1] = n else at[line] = { n } end
        end
    end
    return { lines = lines, at = at }
end

-- The block from its header line to the first line of the header's indent plus "end": its text, first and last line.
local function cut(file, header, path)
    local found = file.at[header]
    if not found then error(header .. " was not found in " .. path) end
    if #found > 1 then error(header .. " appears more than once in " .. path) end
    local lines, first = file.lines, found[1]
    local last = string.match(header, "^(%s*)") .. "end"
    for n = first, #lines do
        if lines[n] == last then
            local block = {}
            for i = first, n do block[#block + 1] = lines[i] end
            return table.concat(block, "\n"), first, n
        end
    end
    error(header .. " in " .. path .. " has no closing '" .. last .. "' line")
end

local function resolve(path)
    local value = _G
    for part in string.gmatch(path, "[^%.]+") do
        if type(value) ~= "table" then return nil end
        value = value[part]
    end
    return value
end

-- Whether [fn] is defined inside [range] (a block's { path, first, last }).
function SourceGate.isFrom(fn, range)
    if type(fn) ~= "function" then return false end
    local name = string.lower(tostring(getFilenameOfClosure(fn)))
    local file = string.lower(string.match(range.path, "([^/]+)$"))
    local line = getFirstLineOfClosure(fn)
    return string.sub(name, -string.len(file)) == file and line >= range.first and line <= range.last
end

function SourceGate.describe(fn)
    if type(fn) ~= "function" then return tostring(fn) end
    return tostring(getFilenameOfClosure(fn)) .. ":" .. tostring(getFirstLineOfClosure(fn))
end

-- [files]: name -> path relative to [modId]'s folder (the game also looks in its common folder). [blocks]: in check
-- order, each { name, file (a key of files), header, length, hash = { a, b } }, and optionally global (the live value
-- must be the one from this block) and client (the global is in client Lua, which a dedicated server does not load).
function SourceGate.new(modId, files, blocks)
    local gate = {}

    local headersByPath = {}
    for _, block in ipairs(blocks) do
        local path = files[block.file]
        if path then
            headersByPath[path] = headersByPath[path] or {}
            headersByPath[path][block.header] = true
        end
    end

    -- Calls [visit](block, text, range) for each block in order, range being { path, first, last }. [readLines](path)
    -- reads a file; by default from the mod's folder. Each file is read once.
    function gate.cut(visit, readLines)
        readLines = readLines or function(path) return SourceGate.readLines(modId, path) end
        local indexed = {}
        for _, block in ipairs(blocks) do
            local path = files[block.file]
            local file = indexed[path]
            if not file then
                file = index(readLines(path), headersByPath[path])
                indexed[path] = file
            end
            local text, first, last = cut(file, block.header, path)
            visit(block, text, { path = path, first = first, last = last })
        end
    end

    -- The blocks' ranges by name. Throws when anything does not match. [identity]: also check that the live globals
    -- are the blocks', once all of the mod's Lua has loaded.
    function gate.check(identity)
        local ranges = {}
        gate.cut(function(block, text, range)
            local a, b = SourceGate.hash(text)
            if string.len(text) ~= block.length or a ~= block.hash[1] or b ~= block.hash[2] then
                error(block.name .. " at " .. range.path .. ":" .. digits(range.first)
                    .. " is not the reviewed version (found length " .. digits(string.len(text)) .. ", hash "
                    .. digits(a) .. ", " .. digits(b) .. ")")
            end
            if identity and block.global and not (block.client and isServer()) then
                local live = resolve(block.global)
                if type(live) ~= "function" then error(block.global .. " was not found") end
                if not SourceGate.isFrom(live, range) then
                    error(block.global .. " is not the one at " .. range.path .. ":" .. digits(range.first)
                        .. " but from " .. SourceGate.describe(live))
                end
            end
            ranges[block.name] = range
        end)
        return ranges
    end

    return gate
end

return SourceGate
