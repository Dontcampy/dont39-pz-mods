<#
.SYNOPSIS
Ends start-viewer.ps1's client as stop-client.ps1 ends the first: waits for its driver's quit, else asks it to quit,
kills it after TimeoutSec; copies its console.txt and driver state into the run folder.
#>
param([int] $TimeoutSec = 90, [string] $CacheDir = 'C:\pz2c')
. (Join-Path $PSScriptRoot 'common.ps1')

$lua = Join-Path $CacheDir 'Lua'
$st = Read-StateJson 'viewer'
if (-not $st) { throw 'no client recorded by start-viewer.ps1' }
$p = Get-RecordedProcess $st
if ($p) {
    if (-not $p.WaitForExit(5000)) {
        Set-Content -Path (Join-Path $lua 'sgtest_stop.txt') -Value 'stop' -Encoding ascii
        if (-not $p.WaitForExit($TimeoutSec * 1000)) {
            Stop-Process -Id $p.Id -Force
            Write-Warning "killed viewer pid $($p.Id) after $TimeoutSec s"
        }
    }
    "viewer stopped"
} else {
    "viewer pid $($st.pid) was not running"
}
Start-Sleep -Seconds 1
$console = Join-Path $CacheDir 'console.txt'
if (Test-Path $console) { Copy-Item $console (Join-Path $st.runDir 'viewer-console.txt') -Force }
$state = Join-Path $lua 'sgtest_state.txt'
if (Test-Path $state) { Move-Item $state (Join-Path $st.runDir 'viewer_state.txt') -Force }
Remove-Item (Join-Path $lua 'sgtest.txt'), (Join-Path $lua 'sgtest_stop.txt') -ErrorAction SilentlyContinue
Remove-StateJson 'viewer'
"console and driver state in $($st.runDir)"
