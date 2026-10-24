<#
.SYNOPSIS
One labelled run: server start, client join and scenario until the driver's "done" line, then both stopped.
start-client.ps1 returns once in game and stop-client.ps1 only waits 5 s for the driver, so a run that should last
DurationSec waits for the driver here.
#>
param(
    [Parameter(Mandatory)] [string] $Label,
    [int] $DurationSec = 300,
    [string[]] $ServerJvmArgs = @(),
    [hashtable] $ClientArgs = @{},
    [int] $Horde = 0
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

& "$PSScriptRoot\start-server.ps1" -Label $Label -Wait -JvmArgs $ServerJvmArgs
try {
    & "$PSScriptRoot\start-client.ps1" -Label $Label -DurationSec $DurationSec -Quit -JoinTimeoutSec 600 -WaitArrived @ClientArgs
    if ($Horde -gt 0) { & "$PSScriptRoot\spawn-zombies.ps1" -Count $Horde }
    $deadline = (Get-Date).AddSeconds($DurationSec + 180)
    # the driver marks the end of its loop with a "done" line before it quits
    while ((Get-Date) -lt $deadline) {
        $state = Get-Content -LiteralPath $SG.DriverState -ErrorAction SilentlyContinue
        if ($state -match ' done ') { break }
        Start-Sleep -Seconds 5
    }
    if (-not ($state -match ' done ')) { Write-Warning "driver did not report done within $($DurationSec + 180) s" }
} finally {
    & "$PSScriptRoot\stop-client.ps1"
    & "$PSScriptRoot\stop-server.ps1"
}
