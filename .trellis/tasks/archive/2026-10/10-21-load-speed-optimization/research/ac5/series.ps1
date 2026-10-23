# series.ps1 -Runs on1,off1,base1,...: one Steam launch + join of the Linux test instance per run.
# on*: Faster Loading preloaded, every switch on; off*: preloaded, every switch off; base*: not loaded at all.
param([Parameter(Mandatory)] [string[]] $Runs, [string] $Ip = '192.168.50.249', [int] $Port = 48261,
    [int] $TimeoutSec = 900, [int] $MenuMs = 30000, [int] $WorldMs = 10000, [string] $User = '')
$ErrorActionPreference = 'Stop'
$S = Split-Path -Parent $PSScriptRoot
$home2 = $env:USERPROFILE
$ini = Join-Path $home2 'Zomboid\Lua\Dont39FasterLoading.ini'
$list = Join-Path $home2 'Zomboid\mods\default.txt'
$config = Join-Path $home2 '.zombie_buddy\config.json'
$reports = Join-Path $home2 'Zomboid\Lua\Dont39LoadingProgress'
$flEntry = Get-Content (Join-Path $S 'fl-preload.json') -Raw | ConvertFrom-Json

function Set-Loaded([bool] $loaded) {
    $c = Get-Content $config -Raw | ConvertFrom-Json
    $has = $c.preload_mods.PSObject.Properties.Name -contains 'Dont39FasterLoading'
    if ($loaded -and -not $has) { $c.preload_mods | Add-Member -NotePropertyName Dont39FasterLoading -NotePropertyValue $flEntry }
    if (-not $loaded -and $has) { $c.preload_mods.PSObject.Properties.Remove('Dont39FasterLoading') }
    [IO.File]::WriteAllText($config, ($c | ConvertTo-Json -Depth 8))
    $text = Get-Content $list -Raw
    $line = '(?m)^(\s*)mod = Dont39FasterLoading,\r?\n'
    if ($loaded -and $text -notmatch 'mod = Dont39FasterLoading,') {
        $text = $text -replace '(?m)^(\s*)mod = Dont39LoadingProgress,', "`$1mod = Dont39LoadingProgress,`r`n`$1mod = Dont39FasterLoading,"
    }
    if (-not $loaded) { $text = $text -replace $line, '' }
    if ($text -notmatch 'mod = LPTestDriver,') {
        $text = $text -replace '(?m)^(\s*)mod = Dont39LoadingProgress,', "`$1mod = Dont39LoadingProgress,`r`n`$1mod = LPTestDriver,"
    }
    [IO.File]::WriteAllText($list, $text)
    if (($text -match 'mod = Dont39FasterLoading,') -ne $loaded) { throw "default.txt not set for loaded=$loaded" }
}

foreach ($name in $Runs) {
    $mode = $name -replace '\d+$', ''
    switch ($mode) {
        'on' { Set-Loaded $true; $v = 'true' }
        'off' { Set-Loaded $true; $v = 'false' }
        'base' { Set-Loaded $false; $v = $null }
        default { throw "run ${name}: on, off or base" }
    }
    if ($v) {
        if (Test-Path $ini) {
            $text = (Get-Content $ini -Raw) -replace '(?m)^(\w+) = (true|false)', "`$1 = $v"
            [IO.File]::WriteAllText($ini, $text)
        } elseif ($v -ne 'true') { throw "no $ini to switch off" }
    }
    $before = @(Get-ChildItem $reports -File -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
    "=== $name $(Get-Date -Format HH:mm:ss)"
    $r = & (Join-Path $PSScriptRoot 'run.ps1') -Name $name -Scenario join -Ip $Ip -Port $Port -User $User -TimeoutSec $TimeoutSec -MenuMs $MenuMs -WorldMs $WorldMs | ConvertFrom-Json
    $new = Get-ChildItem $reports -File | Where-Object { $before -notcontains $_.Name }
    $dst = Join-Path $S "runs\$name\reports"
    New-Item -ItemType Directory -Force $dst | Out-Null
    $new | Copy-Item -Destination $dst
    if (Test-Path $ini) { Copy-Item $ini (Join-Path $S "runs\$name\Dont39FasterLoading.ini") }
    "$name timedOut=$($r.timedOut) reports=$(($new | Select-Object -ExpandProperty Name) -join ',')"
    Start-Sleep -Seconds 10
}
