# Shared paths and helpers of the Server Reborn test harness; every script dot-sources this file.
$ErrorActionPreference = 'Stop'

$SG = [ordered]@{
    ServerName = 'sgtest'
    # the server's -cachedir; short, since mod paths under it hit Windows' 260-character limit
    Cache      = Join-Path $env:TEMP 'sgsrv'
    # backups, the copy manifest, pids and run outputs; outlives the cache folder
    State      = Join-Path $env:TEMP 'sgtest'
    GamePort   = 16361
    UdpPort    = 16362
    RconPort   = 27115
    Zomboid    = Join-Path $env:USERPROFILE 'Zomboid'
    ZbConfig   = Join-Path $env:USERPROFILE '.zombie_buddy'
    Harness    = $PSScriptRoot
}
# the server the last setup-server.ps1 made: sgtest, or srmin with -Minimal
$SG.ServerNameFile = Join-Path $SG.State 'server-name.txt'
if (Test-Path $SG.ServerNameFile) { $SG.ServerName = (Get-Content $SG.ServerNameFile -Raw).Trim() }
$SG.AdminPwFile = Join-Path $SG.Cache 'harness-adminpw.txt'
$SG.Manifest = Join-Path $SG.State 'copies.txt'
$SG.Backup = Join-Path $SG.State 'backup'
$SG.Runs = Join-Path $SG.State 'runs'
$SG.ClientMods = Join-Path $SG.Zomboid 'mods'
$SG.ClientLua = Join-Path $SG.Zomboid 'Lua'
$SG.DriverCfg = Join-Path $SG.ClientLua 'sgtest.txt'
$SG.DriverState = Join-Path $SG.ClientLua 'sgtest_state.txt'
$SG.DriverStop = Join-Path $SG.ClientLua 'sgtest_stop.txt'
$SG.ServerConsole = Join-Path $SG.Cache 'server-console.txt'

$repo = $PSScriptRoot
while ($repo -and -not (Test-Path (Join-Path $repo 'settings.gradle.kts'))) { $repo = Split-Path -Parent $repo }
if (-not $repo) { throw "no settings.gradle.kts above $PSScriptRoot" }
$SG.Repo = $repo

# machine paths, as the build reads them (README.md of the repo)
$gp = Join-Path $env:USERPROFILE '.gradle\gradle.properties'
if (-not (Test-Path $gp)) { throw "no $gp (pzDir, pzServerDir)" }
foreach ($line in Get-Content $gp) {
    if ($line -match '^\s*(pzDir|pzServerDir)\s*=\s*(.+?)\s*$') { $SG[$Matches[1]] = $Matches[2] -replace '/', '\' }
}
foreach ($k in 'pzDir', 'pzServerDir') { if (-not $SG.Contains($k)) { throw "$k missing in $gp" } }
# <library>\steamapps\common\ProjectZomboid -> <library>\steamapps\workshop\content\108600
$SG.WorkshopContent = Join-Path (Split-Path -Parent (Split-Path -Parent $SG.pzDir)) 'workshop\content\108600'

# Test spots: vanilla Muldraugh, KY map (last on myserver1's Map= list); see README "Test spots".
$SG.Spots = [ordered]@{
    prison   = @{ Name = 'Rosewood prison'; X = 7560; Y = 11815; Z = 0 }
    military = @{ Name = 'military base'; X = 12513; Y = 4280; Z = 0 }
}

function Resolve-Spot([string] $Spot) {
    if ($SG.Spots.Contains($Spot)) { return $SG.Spots[$Spot] }
    if ($Spot -match '^(-?\d+),(-?\d+),(-?\d+)$') {
        return @{ Name = $Spot; X = [int]$Matches[1]; Y = [int]$Matches[2]; Z = [int]$Matches[3] }
    }
    throw "spot '$Spot': use $($SG.Spots.Keys -join ', ') or x,y,z"
}

function Get-AdminPassword {
    if (-not (Test-Path $SG.AdminPwFile)) { throw "no $($SG.AdminPwFile); run setup-server.ps1 first" }
    (Get-Content $SG.AdminPwFile -Raw).Trim()
}

function New-Password([int] $Length = 20) {
    $chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
    $bytes = [byte[]]::new($Length)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

function Read-StateJson([string] $Name) {
    $f = Join-Path $SG.State "$Name.json"
    if (Test-Path $f) { Get-Content $f -Raw | ConvertFrom-Json } else { $null }
}

function Write-StateJson([string] $Name, $Value) {
    New-Item -ItemType Directory -Force $SG.State | Out-Null
    $Value | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $SG.State "$Name.json") -Encoding utf8
}

function Remove-StateJson([string] $Name) {
    Remove-Item (Join-Path $SG.State "$Name.json") -ErrorAction SilentlyContinue
}

# The recorded process if it still runs; a reused pid of another program does not count.
function Get-RecordedProcess($Recorded) {
    if (-not $Recorded) { return $null }
    $p = Get-Process -Id $Recorded.pid -ErrorAction SilentlyContinue
    if ($p -and $p.ProcessName -like 'java*') { $p } else { $null }
}

function Get-RunDir([string] $Label) {
    if ($Label -notmatch '^[\w.-]+$') { throw "label '$Label': letters, digits, '.', '-', '_' only" }
    $d = Join-Path $SG.Runs $Label
    New-Item -ItemType Directory -Force $d | Out-Null
    $d
}

# Quotes what Start-Process would split.
function Join-ProcessArgs([string[]] $Arguments) {
    ($Arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
}

# Source RCON (the protocol the dedicated server's RCONPort speaks): one connection, auth, then each command.
function Invoke-Rcon([string[]] $Commands, [int] $TimeoutMs = 10000) {
    $pw = Get-AdminPassword
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $client.ReceiveTimeout = $TimeoutMs
        $client.SendTimeout = $TimeoutMs
        $client.Connect('127.0.0.1', $SG.RconPort)
        $s = $client.GetStream()
        $send = {
            param([int] $id, [int] $type, [string] $body)
            $b = [Text.Encoding]::UTF8.GetBytes($body)
            $buf = [BitConverter]::GetBytes([int](10 + $b.Length)) + [BitConverter]::GetBytes($id) +
                [BitConverter]::GetBytes($type) + $b + [byte[]](0, 0)
            $s.Write($buf, 0, $buf.Length)
        }
        $readFully = {
            param([int] $n)
            $d = [byte[]]::new($n); $got = 0
            while ($got -lt $n) {
                $r = $s.Read($d, $got, $n - $got)
                if ($r -le 0) { throw 'RCON connection closed' }
                $got += $r
            }
            , $d
        }
        $recv = {
            $len = [BitConverter]::ToInt32((& $readFully 4), 0)
            $d = & $readFully $len
            [pscustomobject]@{
                id   = [BitConverter]::ToInt32($d, 0)
                type = [BitConverter]::ToInt32($d, 4)
                body = [Text.Encoding]::UTF8.GetString($d, 8, [Math]::Max(0, $len - 10))
            }
        }
        & $send 1 3 $pw
        $r = & $recv
        if ($r.type -eq 0) { $r = & $recv }
        if ($r.id -eq -1) { throw 'RCON auth failed' }
        $id = 10
        foreach ($c in $Commands) {
            & $send $id 2 $c
            $r = & $recv
            while ($r.id -ne $id) { $r = & $recv }
            $r.body
            $id++
        }
    } finally {
        $client.Dispose()
    }
}

# key:value lines of the server's "stats <name> all" reply, as numbers
function ConvertFrom-StatsReply([string] $Reply) {
    $h = @{}
    foreach ($line in $Reply -split "`n") {
        if ($line -match '^\s*([\w-]+)\s*:\s*(-?[\d.Ee+-]+)\s*$') { $h[$Matches[1]] = [double]$Matches[2] }
    }
    $h
}

function Read-Utf8Lines([string] $Path) { [IO.File]::ReadAllLines($Path, [Text.UTF8Encoding]::new($false)) }

function Write-Utf8Lines([string] $Path, [string[]] $Lines) {
    [IO.File]::WriteAllText($Path, ($Lines -join "`r`n") + "`r`n", [Text.UTF8Encoding]::new($false))
}
