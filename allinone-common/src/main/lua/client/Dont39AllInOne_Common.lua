-- Dont39 All-in-One: what the features' client Lua files share.

local Common = {}

-- [prefix]: the file's own; not "<feature>: ", which prefixes the Java side's status lines. Never throws.
function Common.log(prefix, message)
    pcall(function() DebugType.Mod:println(prefix .. message) end)
end

-- Turns a file off at its first error, for the session: the file checks guard.disabled and reports through
-- guard.fail(what, err, trace), [trace] being the Lua stack where the error was raised (the game's pcall returns it
-- third). [onFail], if given, undoes what the file added. Never throws.
function Common.guard(prefix, onFail)
    local guard = { disabled = false }
    function guard.fail(what, err, trace)
        if guard.disabled then return end
        guard.disabled = true
        pcall(function()
            DebugType.Mod:error(prefix .. "disabled after an error while " .. what .. ": " .. tostring(err) .. "\n"
                .. tostring(trace))
        end)
        if onFail then pcall(onFail) end
    end
    return guard
end

return Common
