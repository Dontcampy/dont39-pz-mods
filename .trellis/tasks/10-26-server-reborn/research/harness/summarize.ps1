<#
.SYNOPSIS
Turns the sampler CSVs of the mod-off and mod-on load runs into the off/on table for research/load-test.md.

.DESCRIPTION
Prints Markdown (and writes it to -Out): one table of process, JVM and network figures off vs on, then the keys of
Server Reborn's summary lines from the on run (first, last, max and the rate of a running counter). -OwnBytesKey names
the summary key that counts the mod's own bytes sent; its rate over the wire rate is the mod's share.
#>
param([Parameter(Mandatory)] [string] $Off, [Parameter(Mandatory)] [string] $On, [string] $Out,
    [string] $OwnBytesKey = 'bytesOut', [int] $SkipFirstSec = 0)
$ErrorActionPreference = 'Stop'

function Load([string] $Path) {
    @(Import-Csv $Path | Where-Object { [int]$_.elapsed_s -ge $SkipFirstSec })
}
function Nums($rows, [string] $col) {
    @($rows | ForEach-Object { $_.$col } | Where-Object { $_ -ne '' -and $null -ne $_ } | ForEach-Object { [double]$_ })
}
function Mean($v) { if ($v.Count) { ($v | Measure-Object -Average).Average } }
function Max($v) { if ($v.Count) { ($v | Measure-Object -Maximum).Maximum } }
function P95($v) {
    if (-not $v.Count) { return $null }
    $s = @($v | Sort-Object)
    $s[[Math]::Min($s.Count - 1, [int][Math]::Ceiling(0.95 * $s.Count) - 1)]
}
function Fmt($x, [int] $d = 1) { if ($null -eq $x) { '-' } else { [Math]::Round([double]$x, $d).ToString("N$d") } }
function Delta($a, $b, [int] $d = 1) {
    if ($null -eq $a -or $null -eq $b) { return '-' }
    $s = Fmt ($b - $a) $d
    if ($a -ne 0) { $s += ' ({0:+0.0;-0.0}%)' -f (($b - $a) * 100 / $a) }
    $s
}

$offRows = Load $Off
$onRows = Load $On
$lines = [Collections.Generic.List[string]]::new()
$lines.Add("Off: $Off ($($offRows.Count) samples), on: $On ($($onRows.Count) samples)$(if ($SkipFirstSec) { ", first $SkipFirstSec s skipped" })")
$lines.Add('')
$lines.Add('| Metric | Mod off | Mod on | On - off |')
$lines.Add('|---|---:|---:|---:|')
$metrics = @(
    ,@('Server CPU % of machine, mean', 'srv_cpu_pct', 'Mean', 2)
    ,@('Server CPU % of machine, p95', 'srv_cpu_pct', 'P95', 2)
    ,@('Server working set MB, mean', 'srv_ws_mb', 'Mean', 0)
    ,@('Server working set MB, max', 'srv_ws_mb', 'Max', 0)
    ,@('Server private bytes MB, max', 'srv_private_mb', 'Max', 0)
    ,@('JVM heap used MB, mean', 'srv_heap_used_mb', 'Mean', 0)
    ,@('JVM heap used MB, max', 'srv_heap_used_mb', 'Max', 0)
    ,@('Main loop update period ms, mean of avg', 'srv_update_avg_ms', 'Mean', 2)
    ,@('Main loop update period ms, max', 'srv_update_max_ms', 'Max', 1)
    ,@('Server out B/s on the wire, mean', 'net_out_Bps', 'Mean', 0)
    ,@('Server out B/s on the wire, p95', 'net_out_Bps', 'P95', 0)
    ,@('Server in B/s on the wire, mean', 'net_in_Bps', 'Mean', 0)
    ,@('Server in B/s on the wire, p95', 'net_in_Bps', 'P95', 0)
    ,@('Vanilla packet bytes sent per stats period, mean', 'net_game_sent', 'Mean', 0)
    ,@('Vanilla packet bytes received per stats period, mean', 'net_game_recv', 'Mean', 0)
    ,@('Client CPU % of machine, mean', 'cli_cpu_pct', 'Mean', 2)
    ,@('Client working set MB, max', 'cli_ws_mb', 'Max', 0)
)
foreach ($m in $metrics) {
    $a = & $m[2] (Nums $offRows $m[1])
    $b = & $m[2] (Nums $onRows $m[1])
    $lines.Add("| $($m[0]) | $(Fmt $a $m[3]) | $(Fmt $b $m[3]) | $(Delta $a $b $m[3]) |")
}

# Server Reborn summary lines of the on run: each distinct line once, with the run time it was first sampled at
$seen = @{}
$points = foreach ($r in $onRows) {
    if ($r.sg_line -and -not $seen.ContainsKey($r.sg_line)) {
        $seen[$r.sg_line] = $true
        $kv = @{}
        foreach ($m in [regex]::Matches($r.sg_line, '(\w+)=(-?\d+(?:\.\d+)?)')) { $kv[$m.Groups[1].Value] = [double]$m.Groups[2].Value }
        [pscustomobject]@{ t = [double]$r.elapsed_s; kv = $kv }
    }
}
$points = @($points)
$lines.Add('')
if (-not $points.Count) {
    $lines.Add('No Server Reborn summary line in the on run (sg_line empty).')
} else {
    $keys = @($points | ForEach-Object { $_.kv.Keys } | Select-Object -Unique | Sort-Object)
    $lines.Add("Server Reborn summary keys, on run ($($points.Count) distinct lines):")
    $lines.Add('')
    $lines.Add('| Key | First | Last | Max | Rate /s (last - first) |')
    $lines.Add('|---|---:|---:|---:|---:|')
    $rates = @{}
    foreach ($k in $keys) {
        $v = @($points | Where-Object { $_.kv.ContainsKey($k) })
        $first = $v[0]; $last = $v[-1]
        $rate = if ($last.t -gt $first.t) { ($last.kv[$k] - $first.kv[$k]) / ($last.t - $first.t) }
        $rates[$k] = $rate
        $lines.Add("| $k | $(Fmt $first.kv[$k] 0) | $(Fmt $last.kv[$k] 0) | $(Fmt (Max @($v | ForEach-Object { $_.kv[$k] })) 0) | $(Fmt $rate 1) |")
    }
    $wire = Mean (Nums $onRows 'net_out_Bps')
    if ($rates.ContainsKey($OwnBytesKey) -and $null -ne $rates[$OwnBytesKey] -and $wire) {
        $lines.Add('')
        $lines.Add("Server Reborn's own bytes ($OwnBytesKey): $(Fmt $rates[$OwnBytesKey] 0) B/s = $(Fmt ($rates[$OwnBytesKey] * 100 / $wire) 2)% of the server's mean wire output.")
    }
}
$lines
if ($Out) { $lines | Set-Content -Path $Out -Encoding utf8; "written to $Out" }
