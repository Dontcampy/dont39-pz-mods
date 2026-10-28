<#
.SYNOPSIS
Runs server console commands on "sgtest" over RCON and prints the replies, e.g.
  rcon.ps1 players
  rcon.ps1 'stats network all' 'stats performance all'
#>
param([Parameter(Mandatory, ValueFromRemainingArguments)] [string[]] $Command)
. (Join-Path $PSScriptRoot 'common.ps1')
$i = 0
foreach ($reply in Invoke-Rcon $Command) {
    "> $($Command[$i])"
    $reply
    $i++
}
