<#
.SYNOPSIS
Backs up what client test runs change in the user's files, before the first run of a series; cleanup.ps1 restores it.

.DESCRIPTION
Copies ~/Zomboid/mods/default.txt (the launcher also rewrites it), ~/.zombie_buddy/config.json and
mod_approvals.json, ~/Zomboid/options.ini and latestSave.ini, and Loading Progress's reports
(~/Zomboid/Lua/Dont39LoadingProgress keeps only the newest 10 loads, so test launches prune the user's own), with
hashes, plus the names in ~/Zomboid/Lua and Saves/Multiplayer so cleanup.ps1 can move what the tests added.
An existing backup is kept: it is the state before the series.
#>
. (Join-Path $PSScriptRoot 'common.ps1')

$bk = $SG.Backup
if (Test-Path (Join-Path $bk 'hashes.txt')) {
    "backup from $((Get-Item (Join-Path $bk 'hashes.txt')).LastWriteTime) kept: $bk"
    return
}
New-Item -ItemType Directory -Force $bk | Out-Null
$files = [ordered]@{
    'default.txt'        = Join-Path $SG.ClientMods 'default.txt'
    'config.json'        = Join-Path $SG.ZbConfig 'config.json'
    'mod_approvals.json' = Join-Path $SG.ZbConfig 'mod_approvals.json'
    'options.ini'        = Join-Path $SG.Zomboid 'options.ini'
    'latestSave.ini'     = Join-Path $SG.Zomboid 'latestSave.ini'
}
foreach ($k in $files.Keys) {
    if (Test-Path $files[$k]) { Copy-Item $files[$k] (Join-Path $bk $k) }
}
$lp = Join-Path $SG.ClientLua 'Dont39LoadingProgress'
if (Test-Path $lp) { Copy-Item -Recurse $lp (Join-Path $bk 'Dont39LoadingProgress') }
Get-ChildItem $SG.ClientLua -Name | Set-Content (Join-Path $bk 'lua-names.txt') -Encoding utf8
Get-ChildItem (Join-Path $SG.Zomboid 'Saves\Multiplayer') -Name -ErrorAction SilentlyContinue |
    Set-Content (Join-Path $bk 'mp-saves.txt') -Encoding utf8
Get-ChildItem -Recurse -File $bk | Where-Object { $_.Name -notin 'lua-names.txt', 'mp-saves.txt' } |
    ForEach-Object { '{0} {1}' -f (Get-FileHash $_.FullName).Hash, $_.FullName.Substring($bk.Length + 1) } |
    Set-Content (Join-Path $bk 'hashes.txt') -Encoding utf8
"backed up to $bk"
