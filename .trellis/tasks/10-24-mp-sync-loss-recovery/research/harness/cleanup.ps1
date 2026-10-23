<#
.SYNOPSIS
Undoes the harness: removes the mod copies it made, restores the client backup, deletes the server cache folder.

.DESCRIPTION
1. Removes each folder in the copy manifest, only under <cache>\mods and ~/Zomboid/mods.
2. Restores backup-client.ps1's files (default.txt, ZombieBuddy config and approvals, options.ini, latestSave.ini,
   Loading Progress reports), then checks them against the backup's hashes. Files and saves the tests added under
   ~/Zomboid/Lua and Saves/Multiplayer are moved to <state>\cleanup, not deleted. The used backup is renamed, so the
   next series takes a fresh one.
3. Deletes the cache folder (server config, world, logs, admin password) unless -KeepCache.
Run outputs under <state>\runs stay; copy what the task needs into research/ first.
#>
param([switch] $KeepCache)
. (Join-Path $PSScriptRoot 'common.ps1')

if (Get-RecordedProcess (Read-StateJson 'server')) { throw 'server still running; stop-server.ps1 first' }
if (Get-RecordedProcess (Read-StateJson 'client')) { throw 'client still running; stop-client.ps1 first' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$moved = Join-Path $SG.State "cleanup\$stamp"

# 1. copies
$roots = @((Join-Path $SG.Cache 'mods') + '\', $SG.ClientMods + '\')
$n = 0
if (Test-Path $SG.Manifest) {
    foreach ($p in Get-Content $SG.Manifest -Encoding utf8) {
        if (-not ($roots | Where-Object { $p.StartsWith($_, [StringComparison]::OrdinalIgnoreCase) -and $p.Length -gt $_.Length })) {
            throw "outside the copy folders, left alone: $p"
        }
        if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Recurse -Force; $n++ }
    }
    Move-Item $SG.Manifest (Join-Path $SG.State "copies-removed-$stamp.txt")
}
"removed $n mod copies"

# 2. client backup
$bk = $SG.Backup
if (Test-Path (Join-Path $bk 'hashes.txt')) {
    New-Item -ItemType Directory -Force $moved | Out-Null
    $files = [ordered]@{
        'default.txt'        = Join-Path $SG.ClientMods 'default.txt'
        'config.json'        = Join-Path $SG.ZbConfig 'config.json'
        'mod_approvals.json' = Join-Path $SG.ZbConfig 'mod_approvals.json'
        'options.ini'        = Join-Path $SG.Zomboid 'options.ini'
        'latestSave.ini'     = Join-Path $SG.Zomboid 'latestSave.ini'
    }
    foreach ($k in $files.Keys) {
        if (Test-Path (Join-Path $bk $k)) { Copy-Item (Join-Path $bk $k) $files[$k] -Force }
    }
    $lp = Join-Path $SG.ClientLua 'Dont39LoadingProgress'
    if (Test-Path (Join-Path $bk 'Dont39LoadingProgress')) {
        if (Test-Path $lp) { Move-Item $lp (Join-Path $moved 'Dont39LoadingProgress-after-tests') }
        Copy-Item -Recurse (Join-Path $bk 'Dont39LoadingProgress') $lp
    }
    $luaBefore = @(Get-Content (Join-Path $bk 'lua-names.txt') -Encoding utf8)
    Get-ChildItem $SG.ClientLua -Name | Where-Object { $luaBefore -notcontains $_ } | ForEach-Object {
        Move-Item (Join-Path $SG.ClientLua $_) (Join-Path $moved "Lua-$_"); "moved Lua\$_"
    }
    $savesBefore = @(Get-Content (Join-Path $bk 'mp-saves.txt') -Encoding utf8 -ErrorAction SilentlyContinue)
    Get-ChildItem (Join-Path $SG.Zomboid 'Saves\Multiplayer') -Name -ErrorAction SilentlyContinue |
        Where-Object { $savesBefore -notcontains $_ } | ForEach-Object {
            Move-Item (Join-Path $SG.Zomboid "Saves\Multiplayer\$_") (Join-Path $moved "save-$_"); "moved save $_"
        }
    $bad = 0
    foreach ($line in Get-Content (Join-Path $bk 'hashes.txt') -Encoding utf8) {
        $hash, $rel = $line -split ' ', 2
        $live = if ($files.Contains($rel)) { $files[$rel] } else { Join-Path $SG.ClientLua $rel }
        if (-not (Test-Path -LiteralPath $live) -or (Get-FileHash -LiteralPath $live).Hash -ne $hash) { $bad++; Write-Warning "differs from backup: $live" }
    }
    Move-Item $bk (Join-Path $SG.State "backup-restored-$stamp")
    "client restored; $bad differences; moved items in $moved"
} else {
    'no client backup to restore'
}
Remove-Item $SG.DriverCfg, $SG.DriverStop -ErrorAction SilentlyContinue

# 3. cache folder
if (-not $KeepCache -and (Test-Path $SG.Cache)) {
    if ($SG.Cache -ne (Join-Path $env:TEMP 'sgsrv')) { throw "unexpected cache folder $($SG.Cache)" }
    Remove-Item -LiteralPath $SG.Cache -Recurse -Force
    "deleted $($SG.Cache)"
}
"runs kept in $($SG.Runs)"
