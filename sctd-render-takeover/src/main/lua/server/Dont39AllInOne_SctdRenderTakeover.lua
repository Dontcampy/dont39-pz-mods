-- Dont39 All-in-One: sctd-render-takeover. Shadow Cat Tactics: Dispatch hides and transmogs worn clothing by swapping
-- the item for another; this replaces those steps so the worn item stays and only its drawn look changes, through
-- appearance-override. Under server/ because that folder loads last on every side, after all of SCTD's Lua.

local Takeover = require "Dont39AllInOne_SctdRenderTakeoverShared"
local Runtime = require "Dont39AllInOne_SctdRenderTakeoverRuntime"
local Authoritative = require "Dont39AllInOne_SctdRenderTakeoverAuthoritative"
local Migration = require "Dont39AllInOne_SctdRenderTakeoverMigration"

local function sides()
    if isServer() then return "server" end
    if isClient() then return "client" end
    return "single player"
end

local function install(ranges)
    if not isClient() then
        Authoritative.install()
        Migration.start()
    end
    if not isServer() then require("Dont39AllInOne_SctdRenderTakeoverUi").install(ranges) end
end

local checked, why, ranges = pcall(Takeover.check, true)
if not checked then
    Runtime.fail("checking SCTD's code", why, ranges)
elseif why then
    Runtime.idle()
    Takeover.log("idle: " .. why)
end

Authoritative.wrapRestore()

if not Runtime.stopped() then
    local ok, err, trace = pcall(install, ranges)
    if ok then
        Takeover.log("ready (" .. sides() .. ")")
    else
        Runtime.fail("taking over SCTD's hide and transmog", err, trace)
    end
end
