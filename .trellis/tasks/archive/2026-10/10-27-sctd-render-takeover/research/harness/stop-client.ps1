<#
.SYNOPSIS
Ends the test client and collects its output: waits for the driver to quit (start-client.ps1 -Quit), otherwise asks
it to (Zomboid/Lua/sgtest_stop.txt), and kills it after TimeoutSec. Copies console.txt and the driver's state file
into the run folder and removes the driver's config.
#>
param([int] $TimeoutSec = 90)
. (Join-Path $PSScriptRoot 'common.ps1')

$st = Read-StateJson 'client'
if (-not $st) { throw 'no client recorded by start-client.ps1' }
$p = Get-RecordedProcess $st
if ($p) {
    # a driver that already finished with -Quit exits on its own
    if (-not $p.WaitForExit(5000)) {
        Set-Content -Path $SG.DriverStop -Value 'stop' -Encoding ascii
        if (-not $p.WaitForExit($TimeoutSec * 1000)) {
            Stop-Process -Id $p.Id -Force
            Write-Warning "killed client pid $($p.Id) after $TimeoutSec s"
        }
    }
    "client stopped"
} else {
    "client pid $($st.pid) was not running"
}
Start-Sleep -Seconds 1
$runDir = $st.runDir
$console = Join-Path $SG.Zomboid 'console.txt'
if (Test-Path $console) { Copy-Item $console (Join-Path $runDir 'client-console.txt') -Force }
if (Test-Path $SG.DriverState) { Move-Item $SG.DriverState (Join-Path $runDir 'sgtest_state.txt') -Force }
Remove-Item $SG.DriverCfg, $SG.DriverStop -ErrorAction SilentlyContinue
Remove-StateJson 'client'
"console and driver state in $runDir"
