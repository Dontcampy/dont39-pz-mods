<#
.SYNOPSIS
One driver test against the Linux test instance (myserver2, -nosteam): start-client.ps1 with -ServerHost, wait for
the test's end, stop-client.ps1. The admin password is the first line of -PasswordFile.

.PARAMETER Test
A driver test (start-client.ps1 -Test), or empty with -NoLoop to stay in game for -DurationSec.
#>
param([Parameter(Mandatory)] [string] $Label, [string] $Test = '', [Parameter(Mandatory)] [string] $PasswordFile,
    [string] $ServerHost = '192.168.50.249', [int] $ServerPort = 48261, [int] $DurationSec = 600, [int] $WaitMin = 10,
    [switch] $NoLoop, [string[]] $JvmArgs = @('-Dappearanceoverride.debug=true'))
. (Join-Path $PSScriptRoot 'common.ps1')

$def = Join-Path $SG.ClientMods 'default.txt'
"default.txt has SGTestDriver: $([bool](Select-String -Path $def -Pattern '^\s*mod = SGTestDriver,' -Quiet))"
# @(): a one-line file reads as a string, whose [0] is its first character
$pw = @(Get-Content $PasswordFile)[0]
$extra = @{}
if ($Test) { $extra.Test = $Test }
if ($NoLoop) { $extra.NoLoop = $true }
& (Join-Path $PSScriptRoot 'start-client.ps1') -Label $Label -ServerHost $ServerHost -ServerPort $ServerPort `
    -ServerPassword $pw -Quit -ZbAllowAll -DurationSec $DurationSec -JvmArgs $JvmArgs @extra 2>&1 | Select-Object -Last 1
$pattern = if ($Test) { "test $Test end|done cycles" } else { 'done cycles' }
$deadline = (Get-Date).AddMinutes($WaitMin)
while ((Get-Date) -lt $deadline) {
    if ((Test-Path $SG.DriverState) -and (Select-String -Path $SG.DriverState -Pattern $pattern -Quiet)) { break }
    Start-Sleep -Seconds 5
}
Start-Sleep -Seconds 12
& (Join-Path $PSScriptRoot 'stop-client.ps1') 2>&1 | Select-Object -Last 1
