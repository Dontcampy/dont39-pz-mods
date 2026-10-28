-- Dont39 All-in-One: what the features' Lua files share on every side, the dedicated server included.

local Common = {}

-- [prefix]: the file's own; not "<feature>: ", which prefixes the Java side's status lines. Never throws.
function Common.log(prefix, message)
    pcall(function() DebugType.Mod:println(prefix .. message) end)
end

-- Never throws.
function Common.logError(prefix, message)
    pcall(function() DebugType.Mod:error(prefix .. message) end)
end

-- Turns a file off at its first error, for the session: the file checks guard.disabled (guard.reason says why, for
-- status lines) and reports through guard.fail(what, err, trace), [trace] being the Lua stack where the error was
-- raised (the game's pcall returns it third), or runs its handlers through guard.run. [onFail], if given, undoes what
-- the file added. Never throws.
function Common.guard(prefix, onFail)
    local guard = { disabled = false, reason = nil }
    function guard.fail(what, err, trace)
        if guard.disabled then return end
        guard.disabled = true
        guard.reason = "it stopped after an error while " .. what .. " (details in console.txt)"
        Common.logError(prefix, "disabled after an error while " .. what .. ": " .. tostring(err) .. "\n"
            .. tostring(trace))
        if onFail then pcall(onFail) end
    end
    -- [fn](...) unless the file is off; an error turns it off. Returns whether it ran and returned.
    function guard.run(what, fn, ...)
        if guard.disabled then return false end
        local ok, err, trace = pcall(fn, ...)
        if not ok then guard.fail(what, err, trace) end
        return ok
    end
    return guard
end

-- A player's character: an animal is an IsoPlayer too, whose user name may be a player's.
function Common.isPlayer(character)
    return instanceof(character, "IsoPlayer") and not instanceof(character, "IsoAnimal")
end

-- Calls [fn]() on a tick at most once per [ms]: paced by time, since the tick rate is a sandbox option.
function Common.every(ms, fn)
    local nextRun = 0
    Events.OnTick.Add(function()
        local now = getTimestampMs()
        if now < nextRun then return end
        nextRun = now + ms
        fn()
    end)
end

return Common
