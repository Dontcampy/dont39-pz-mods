<#
.SYNOPSIS
Creates or updates the local test server "sgtest" from ~/Zomboid/Server/myserver1.* in the short cache folder, and
copies the mods it needs (real copies, not junctions) for the server and the client. With -Minimal, the server
"srmin" instead: myserver1's settings with only Zabric, ZabricKotlin, Server Reborn and the driver.

.DESCRIPTION
Idempotent: rerun it to switch Server Reborn on or off (only Mods= changes) or to refresh the copies. Every copy it
makes is listed in the manifest for cleanup.ps1; a folder it did not make is never overwritten.

Mod sources: each Mods= id from the Workshop content of the ini's WorkshopItems; ids staged in
~/Zomboid/Workshop/<id>/Contents/mods/<id> (Zabric, ZabricKotlin, Dont39AllInOne: what the client's preload config
uses) from there; Dont39ServerReborn from server-reborn/build/mod; SGTestDriver from this folder.

.PARAMETER WithServerReborn
Adds Dont39ServerReborn to Mods= right after ZabricKotlin; without it Mods= has no Dont39ServerReborn.

.PARAMETER Minimal
Mods= is Zabric, ZabricKotlin (and Server Reborn) only, with no Workshop items, the vanilla map, no spawn files and a
save of its own (server "srmin"), for a smoke join. The other scripts use the server the last setup chose.

.PARAMETER DryRun
Prints what would be copied and the total size, changes nothing.
#>
param([switch] $WithServerReborn, [switch] $Minimal, [switch] $DryRun, [string] $SourceServer = 'myserver1')
. (Join-Path $PSScriptRoot 'common.ps1')
$SG.ServerName = if ($Minimal) { 'srmin' } else { 'sgtest' }

$srcDir = Join-Path $SG.Zomboid 'Server'
$iniSrc = Join-Path $srcDir "$SourceServer.ini"
if (-not (Test-Path $iniSrc)) { throw "no $iniSrc" }
$iniLines = Read-Utf8Lines $iniSrc
$kv = @{}
foreach ($l in $iniLines) { if ($l -match '^([A-Za-z0-9_]+)=(.*)$') { $kv[$Matches[1]] = $Matches[2] } }
$modIds = @($kv['Mods'] -split ';' | Where-Object { $_ } | ForEach-Object { $_.TrimStart('\') })
$items = @($kv['WorkshopItems'] -split ';' | Where-Object { $_ })
if ($Minimal) { $modIds = @('Zabric', 'ZabricKotlin'); $items = @() }

# ---- resolve mod folders ----

# The id B42 sees: the newest version folder's mod.info (42, 42.x) wins over common/ and the folder root, whose
# mod.info can be stale (e.g. "Willowbrook Bastion! 2026 map only").
function Get-ModInfoIds([string] $Folder) {
    $versions = Get-ChildItem -LiteralPath $Folder -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d+(\.\d+)*$' } | Sort-Object { [version]($_.Name + '.0') } -Descending
    $infos = @($versions | ForEach-Object { Join-Path $_.FullName 'mod.info' }) +
        @((Join-Path $Folder 'common\mod.info'), (Join-Path $Folder 'mod.info'))
    $info = $infos | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $info) { return }
    foreach ($line in [IO.File]::ReadAllLines($info)) {
        if ($line -match '^\s*id\s*=\s*(.+?)\s*$') { $Matches[1] -split ';' | ForEach-Object { $_.Trim().TrimStart('\') } }
    }
}

$byId = @{}
$missingItems = @()
foreach ($item in $items) {
    $root = Join-Path $SG.WorkshopContent "$item\mods"
    if (-not (Test-Path -LiteralPath $root)) { $missingItems += $item; continue }
    foreach ($f in Get-ChildItem -LiteralPath $root -Directory) {
        foreach ($id in @(Get-ModInfoIds $f.FullName | Select-Object -Unique)) {
            if (-not $byId.ContainsKey($id)) { $byId[$id] = @() }
            $byId[$id] += [pscustomobject]@{ Item = $item; Name = $f.Name; Path = $f.FullName }
        }
    }
}

$stagedRoot = Join-Path $SG.Zomboid 'Workshop'
$srBuild = Join-Path $SG.Repo 'server-reborn\build\mod\Dont39ServerReborn'
$srJarRel = '42\media\java\Dont39ServerReborn.jar'
$plan = [ordered]@{}   # dest folder name -> source path
$origin = @{}          # dest folder name -> where it came from
$missing = @(); $ambiguous = @()
function Add-Plan([string] $Name, [string] $Source, [string] $From) {
    if ($plan.Contains($Name) -and $plan[$Name] -ne $Source) { throw "two sources for mod folder '$Name': $($plan[$Name]) and $Source" }
    $plan[$Name] = $Source; $origin[$Name] = $From
}
foreach ($id in $modIds) {
    if ($id -in 'Dont39ServerReborn', 'SGTestDriver') { continue }
    $staged = Join-Path $stagedRoot "$id\Contents\mods\$id"
    if (Test-Path -LiteralPath (Join-Path $staged '42\mod.info')) { Add-Plan $id $staged 'staged'; continue }
    if (-not $byId.ContainsKey($id)) { $missing += $id; continue }
    $c = @($byId[$id])
    # several items carry the id: the earliest in WorkshopItems wins, as the server would download them in that order
    $pick = $c | Sort-Object { [array]::IndexOf($items, $_.Item) } | Select-Object -First 1
    if (@($c.Path | Select-Object -Unique).Count -gt 1) { $ambiguous += "$id -> $($pick.Path) (also: $(@($c.Path | Where-Object { $_ -ne $pick.Path }) -join '; '))" }
    Add-Plan $pick.Name $pick.Path "workshop $($pick.Item)"
}
Add-Plan 'SGTestDriver' (Join-Path $PSScriptRoot 'SGTestDriver') 'harness'
$srBuilt = Test-Path -LiteralPath (Join-Path $srBuild $srJarRel)
if ($srBuilt) { Add-Plan 'Dont39ServerReborn' $srBuild 'repo build' }
elseif ($WithServerReborn -and -not $DryRun) { throw "no $srBuild\$srJarRel; run :server-reborn:assembleMod first" }
if ($WithServerReborn -and 'ZabricKotlin' -notin $modIds) { throw "Mods= of $iniSrc has no ZabricKotlin to put Dont39ServerReborn after" }

# ---- client side: what the client already sees stays as it is ----

$made = [Collections.Generic.List[string]]::new()
if (Test-Path $SG.Manifest) { $made.AddRange([string[]](Get-Content $SG.Manifest -Encoding utf8)) }
$stagedFolders = @{}
foreach ($d in Get-ChildItem -LiteralPath $stagedRoot -Directory -ErrorAction SilentlyContinue) {
    foreach ($m in Get-ChildItem -LiteralPath (Join-Path $d.FullName 'Contents\mods') -Directory -ErrorAction SilentlyContinue) {
        $stagedFolders[$m.Name] = $m.FullName
    }
}
$clientPlan = [ordered]@{}
$clientSkip = [ordered]@{}
foreach ($name in $plan.Keys) {
    $dst = Join-Path $SG.ClientMods $name
    # a -nosteam client does not scan ~/Zomboid/Workshop, so staged mods are copied like the rest
    if ((Test-Path -LiteralPath $dst) -and -not $made.Contains($dst)) {
        $clientSkip[$name] = "pre-existing, not ours: $dst"
    } else {
        $clientPlan[$name] = $plan[$name]
    }
}

# ---- report ----

$sizes = @{}
function Get-FolderBytes([string] $Path) {
    if (-not $sizes.ContainsKey($Path)) {
        $sizes[$Path] = [long](Get-ChildItem -LiteralPath $Path -Recurse -File -Force -ErrorAction SilentlyContinue |
                Measure-Object Length -Sum).Sum
    }
    $sizes[$Path]
}
function Format-Size([long] $b) { '{0:N2} GB' -f ($b / 1GB) }

"source: $iniSrc ($($modIds.Count) mods, $($items.Count) Workshop items)"
if ($missingItems) { Write-Warning "Workshop items not downloaded: $($missingItems -join ', ')" }
if ($missing) { Write-Warning "mod ids not found in any Workshop item or staging: $($missing -join ', ')" }
foreach ($a in $ambiguous) { Write-Warning "several folders for $a" }
if (-not $srBuilt) { Write-Warning "Server Reborn not built ($srBuild); it is left out of the copies" }
foreach ($k in $clientSkip.Keys) { "client keeps $k ($($clientSkip[$k]))" }

if ($DryRun) {
    $serverBytes = [long]0; foreach ($s in $plan.Values) { $serverBytes += Get-FolderBytes $s }
    $clientBytes = [long]0; foreach ($s in $clientPlan.Values) { $clientBytes += Get-FolderBytes $s }
    $byOrigin = $origin.Values | Group-Object { ($_ -split ' ')[0] } | ForEach-Object { "$($_.Name) $($_.Count)" }
    "server copies -> $($SG.Cache)\mods: $($plan.Count) folders ($($byOrigin -join ', ')), $(Format-Size $serverBytes)"
    "client copies -> $($SG.ClientMods): $($clientPlan.Count) folders, $(Format-Size $clientBytes)"
    "total to copy: $(Format-Size ($serverBytes + $clientBytes)); free on $($SG.Cache.Substring(0, 2)) $(Format-Size (Get-PSDrive $SG.Cache.Substring(0, 1)).Free)"
    "largest: " + (($plan.Keys | Sort-Object { - (Get-FolderBytes $plan[$_]) } | Select-Object -First 5 |
                ForEach-Object { "$_ $(Format-Size (Get-FolderBytes $plan[$_]))" }) -join '; ')
    'dry run: nothing changed'
    return
}
if ($missing) { throw "missing mods; subscribe them or fix Mods= first" }

# ---- server config ----

New-Item -ItemType Directory -Force (Join-Path $SG.Cache 'Server'), (Join-Path $SG.Cache 'mods'), $SG.State | Out-Null
if (-not (Test-Path $SG.AdminPwFile)) { Set-Content -Path $SG.AdminPwFile -Value (New-Password) -NoNewline -Encoding ascii }
$pw = Get-AdminPassword

$mods = [Collections.Generic.List[string]]::new()
foreach ($id in $modIds) {
    if ($id -eq 'Dont39ServerReborn' -or $id -eq 'SGTestDriver') { continue }
    $mods.Add($id)
    if ($id -eq 'ZabricKotlin' -and $WithServerReborn) { $mods.Add('Dont39ServerReborn') }
}
$mods.Add('SGTestDriver')
$set = [ordered]@{
    Mods = $mods -join ';'; DefaultPort = $SG.GamePort; UDPPort = $SG.UdpPort; RCONPort = $SG.RconPort
    RCONPassword = $pw; Password = ''; Public = 'false'; PVP = 'false'; PauseEmpty = 'false'; UPnP = 'false'
    BackupsOnStart = 'false'; BackupsOnVersionChange = 'false'; MultiplayerStatisticsPeriod = 1
}
if ($Minimal) { $set.WorkshopItems = ''; $set.Map = 'Muldraugh, KY' }
$seen = @{}
$out = foreach ($l in $iniLines) {
    if ($l -match '^([A-Za-z0-9_]+)=' -and $set.Contains($Matches[1])) { $seen[$Matches[1]] = $true; "$($Matches[1])=$($set[$Matches[1]])" }
    else { $l }
}
$unset = @($set.Keys | Where-Object { -not $seen.ContainsKey($_) })
if ($unset) { throw "$iniSrc has no $($unset -join ', ')" }
$name = $SG.ServerName
Write-Utf8Lines (Join-Path $SG.Cache "Server\$name.ini") $out
Set-Content -Path $SG.ServerNameFile -Value $name -NoNewline -Encoding ascii

# sandbox: the tunnel and mod sync stay off (the tunnel refuses a server on this machine)
$sb = [IO.File]::ReadAllText((Join-Path $srcDir "${SourceServer}_SandboxVars.lua"))
$sb = $sb -replace '(Dont39AllInOne_TcpTunnel = \{[^}]*?Port = )\d+', '${1}0'
$sb = $sb -replace '(Dont39AllInOne_Tunnel = \{[^}]*?KcpPort = )\d+', '${1}0'
$sb = $sb -replace '(Dont39AllInOne_ModSync = \{[^}]*?Port = )\d+', '${1}0'
[IO.File]::WriteAllText((Join-Path $SG.Cache "Server\${name}_SandboxVars.lua"), $sb, [Text.UTF8Encoding]::new($false))
# the spawn files name the source's map mods
foreach ($f in Get-ChildItem -LiteralPath $srcDir -File | Where-Object { -not $Minimal -and $_.Name -like "${SourceServer}_spawn*.lua" }) {
    $text = [IO.File]::ReadAllText($f.FullName) -replace [regex]::Escape("${SourceServer}_"), "${name}_"
    [IO.File]::WriteAllText((Join-Path $SG.Cache "Server\$($f.Name -replace "^$([regex]::Escape($SourceServer))_", "${name}_")"),
        $text, [Text.UTF8Encoding]::new($false))
}

# ---- copies ----

function Copy-ModFolder([string] $Dir, [string] $Name, [string] $Source) {
    $dst = Join-Path $Dir $Name
    if ((Test-Path -LiteralPath $dst) -and -not $made.Contains($dst)) { throw "exists and is not our copy: $dst" }
    if (-not $made.Contains($dst)) {
        $made.Add($dst)
        $made | Set-Content $SG.Manifest -Encoding utf8
    }
    robocopy $Source $dst /MIR /MT:16 /R:2 /W:2 /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed ($LASTEXITCODE): $Source -> $dst" }
}
$n = 0
foreach ($k in $plan.Keys) { Copy-ModFolder (Join-Path $SG.Cache 'mods') $k $plan[$k]; $n++ }
foreach ($k in $clientPlan.Keys) { Copy-ModFolder $SG.ClientMods $k $clientPlan[$k]; $n++ }
$global:LASTEXITCODE = 0

"server ini: $($SG.Cache)\Server\$name.ini; Server Reborn $(if ($WithServerReborn) { 'on' } else { 'off' }); $($mods.Count) mods"
"ports: game $($SG.GamePort), UDP $($SG.UdpPort), RCON $($SG.RconPort); admin password in $($SG.AdminPwFile)"
"copied/refreshed $n folders; manifest $($SG.Manifest)"
