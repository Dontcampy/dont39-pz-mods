<#
.SYNOPSIS
Stops the "sgtest" server with the console command quit over RCON (it saves, then exits), then copies its console
and logs into the run folder.

.PARAMETER Kill
Kills the process when it has not exited after TimeoutSec; without it the script fails instead.
#>
param([int] $TimeoutSec = 180, [switch] $Kill)
. (Join-Path $PSScriptRoot 'common.ps1')

$st = Read-StateJson 'server'
if (-not $st) { throw 'no server recorded by start-server.ps1' }
$p = Get-RecordedProcess $st
if ($p) {
    try { Invoke-Rcon @('quit') | ForEach-Object { "rcon: $_" } } catch { Write-Warning "RCON quit failed: $_" }
    if (-not $p.WaitForExit($TimeoutSec * 1000)) {
        if (-not $Kill) { throw "server pid $($p.Id) still running after $TimeoutSec s; rerun with -Kill" }
        Stop-Process -Id $p.Id -Force
        Write-Warning "killed server pid $($p.Id)"
    }
    "server stopped"
} else {
    "server pid $($st.pid) was not running"
}

$runDir = $st.runDir
if (Test-Path $SG.ServerConsole) { Copy-Item $SG.ServerConsole (Join-Path $runDir 'server-console.txt') -Force }
$logs = Join-Path $SG.Cache 'Logs'
$start = [DateTimeOffset]::FromUnixTimeMilliseconds($st.startMs).LocalDateTime
$dst = Join-Path $runDir 'server-logs'
New-Item -ItemType Directory -Force $dst | Out-Null
Get-ChildItem $logs -File -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $start } | Copy-Item -Destination $dst
Remove-StateJson 'server'
"console and logs in $runDir"
