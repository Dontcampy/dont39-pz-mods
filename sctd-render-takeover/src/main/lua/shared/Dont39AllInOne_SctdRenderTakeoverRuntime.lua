-- Dont39 All-in-One: sctd-render-takeover, whether the takeover runs and the SCTD values it replaced: the first error
-- stops it for the session and puts SCTD's own functions back.

local Common = require "Dont39AllInOne_CommonShared"
local Takeover = require "Dont39AllInOne_SctdRenderTakeoverShared"

local Runtime = {}

local idle = false -- nothing to take over
local replaced = {} -- { holder, key, original } of each replaced value

local function putBack()
    for i = #replaced, 1, -1 do
        local entry = replaced[i]
        entry.holder[entry.key] = entry.original
    end
    replaced = {}
end

local guard = Common.guard(Takeover.PREFIX, putBack)

-- Stops the takeover without an error.
function Runtime.idle()
    idle = true
end

function Runtime.stopped()
    return idle or guard.disabled
end

-- Whether an error stopped the takeover; reported once.
function Runtime.failed()
    return guard.disabled
end

-- Stops the takeover; SCTD runs as shipped.
Runtime.fail = guard.fail

function Runtime.replace(holder, key, value)
    replaced[#replaced + 1] = { holder = holder, key = key, original = holder[key] }
    holder[key] = value
end

-- A replacement whose first error stops the takeover; [onError] answers that call. Once stopped, SCTD's [original]
-- answers callers that kept a reference.
function Runtime.guard(what, fn, original, onError)
    return function(a, b, c, d, e)
        if Runtime.stopped() then return original(a, b, c, d, e) end
        local ok, r1, r2 = pcall(fn, a, b, c, d, e)
        if ok then return r1, r2 end
        Runtime.fail(what, r1, r2)
        return onError(a, b, c, d, e)
    end
end

-- [fn] returns whether the call is this feature's, and its result; any other call goes to SCTD's [original], outside
-- this feature's error handling.
function Runtime.takeOrPass(what, fn, original, onError)
    return function(a, b, c, d, e)
        if not Runtime.stopped() then
            local ok, handled, result = pcall(fn, a, b, c, d, e)
            if not ok then
                Runtime.fail(what, handled, result)
                return onError(a, b, c, d, e)
            end
            if handled then return result end
        end
        return original(a, b, c, d, e)
    end
end

function Runtime.refuse()
    return false
end

return Runtime
