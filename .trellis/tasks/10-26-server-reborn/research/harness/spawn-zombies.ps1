<#
.SYNOPSIS
Spawns zombies at the test spot with the server command createhorde2 over RCON; repeatable.

.DESCRIPTION
createhorde2 takes -x -y -z -count -radius (and outfit/state flags) and caps each call at 500 zombies; it answers
"invalid location" unless the square is loaded on the server, so run it once the client is at the spot
(start-client.ps1 -WaitArrived).

.PARAMETER Repeat
Spawns Count zombies Repeat times, IntervalSec apart.
#>
param([string] $Spot = 'prison', [int] $Count = 300, [int] $Radius = 15, [int] $Repeat = 1, [int] $IntervalSec = 60)
. (Join-Path $PSScriptRoot 'common.ps1')

$s = Resolve-Spot $Spot
for ($r = 1; $r -le $Repeat; $r++) {
    $left = $Count
    while ($left -gt 0) {
        $n = [Math]::Min(500, $left)
        $cmd = "createhorde2 -x $($s.X) -y $($s.Y) -z $($s.Z) -count $n -radius $Radius"
        "$(Get-Date -Format HH:mm:ss) $cmd -> $(Invoke-Rcon @($cmd))"
        $left -= $n
    }
    if ($r -lt $Repeat) { Start-Sleep -Seconds $IntervalSec }
}
