-- Dont39 All-in-One: sctd-render-takeover, its status before a game: whether SCTD's code is still the version it was
-- reviewed against. The takeover starts with a game, from Dont39AllInOne_SctdRenderTakeover.lua under server/, which
-- also checks that SCTD's globals are its own; here part of SCTD's client Lua may not have loaded yet.

local Takeover = require "Dont39AllInOne_SctdRenderTakeoverShared"

local ok, why, trace = pcall(Takeover.check, false)
if not ok then
    Takeover.logError("disabled after an error while checking SCTD's code: " .. tostring(why) .. "\n"
        .. tostring(trace))
elseif why then
    Takeover.log("idle: " .. why)
else
    Takeover.log("ready (SCTD's code is the reviewed version; the takeover starts with a game)")
end
