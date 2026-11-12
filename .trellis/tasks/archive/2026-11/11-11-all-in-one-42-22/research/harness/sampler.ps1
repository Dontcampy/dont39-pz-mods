<#
.SYNOPSIS
Appends one CSV row every IntervalSec while the test server runs: process CPU and memory of server and client, the
server's own statistics over RCON, and the newest Server Reborn summary line from the server console.

.DESCRIPTION
Process columns: CPU % of the whole machine (TotalProcessorTime delta / wall time / logical cores), working set and
private bytes in MB.

Server statistics come from the console command "stats <name> all" (zombie.network.statistics; updated every
MultiplayerStatisticsPeriod seconds, which setup-server.ps1 sets to 1):
- net_actual_sent / net_actual_recv: RakNet's running totals (total-actual-bytes-sent/-received) summed over the
  connected peers, i.e. everything on the wire including Server Reborn's own packets and RakNet resends; the rate
  columns are their deltas per second (empty when a peer joined or left in between).
- net_game_sent / net_game_recv: bytes of packets with a known PacketType in the last statistics period
  (sent-bytes/received-bytes; NetworkStatistic.addOutcomePacket skips ids outside PacketTypes, so Server Reborn's own
  packets are not in them).
- srv_heap_used_mb: memory-used of "stats performance" (JVM heap in use); srv_update_avg_ms / srv_update_max_ms:
  the main loop's update period.
With -PrometheusPort (start-server.ps1 -PrometheusPort, the game's exporter) also the exporter's JVM heap gauge and
the cumulative packet_send/packet_receive byte sums over all packet types.

Server Reborn: the newest server console line containing [Dont39ServerReborn] and key=value pairs is kept whole in
sg_line (summarize.ps1 reads its keys) with the time it was seen; empty while the mod is off.

.PARAMETER DurationSec
Stops after this long; 0 runs until the server process exits.
#>
param([Parameter(Mandatory)] [string] $Label, [int] $IntervalSec = 5, [int] $DurationSec = 0, [int] $PrometheusPort = 0,
    [string] $Out)
. (Join-Path $PSScriptRoot 'common.ps1')

$srvState = Read-StateJson 'server'
$srv = Get-RecordedProcess $srvState
if (-not $srv) { throw 'server not running; start-server.ps1 first' }
if (-not $Out) { $Out = Join-Path (Get-RunDir $Label) 'samples.csv' }
if (Test-Path $Out) { throw "$Out exists; use another label or -Out" }
$cores = [Environment]::ProcessorCount

function Get-ProcSample($p) {
    if (-not $p) { return $null }
    try {
        $p.Refresh()
        if ($p.HasExited) { return $null }
        [pscustomobject]@{ cpu = $p.TotalProcessorTime.TotalMilliseconds; ws = $p.WorkingSet64; priv = $p.PrivateMemorySize64 }
    } catch { $null }
}

# the server console from its current end, shared with the writing server
$consolePos = if (Test-Path $SG.ServerConsole) { (Get-Item $SG.ServerConsole).Length } else { 0 }
$sgLine = ''; $sgSeen = ''
function Read-NewConsole {
    if (-not (Test-Path $SG.ServerConsole)) { return }
    $fs = [IO.FileStream]::new($SG.ServerConsole, 'Open', 'Read', 'ReadWrite, Delete')
    try {
        if ($fs.Length -lt $script:consolePos) { $script:consolePos = 0 }
        $fs.Position = $script:consolePos
        $sr = [IO.StreamReader]::new($fs, [Text.UTF8Encoding]::new($false))
        $text = $sr.ReadToEnd()
        # keep a partial last line for the next read
        $cut = $text.LastIndexOf("`n")
        if ($cut -lt 0) { return }
        $script:consolePos += [Text.Encoding]::UTF8.GetByteCount($text.Substring(0, $cut + 1))
        foreach ($l in $text.Substring(0, $cut) -split "`r?`n") {
            if ($l.Contains('[Dont39ServerReborn]') -and $l -match '\w+=-?\d') {
                $script:sgLine = $l.Trim(); $script:sgSeen = (Get-Date).ToString('HH:mm:ss')
            }
        }
    } finally { $fs.Dispose() }
}

function Get-Prometheus {
    if (-not $PrometheusPort) { return $null }
    try {
        $body = (Invoke-WebRequest -Uri "http://127.0.0.1:$PrometheusPort/metrics" -TimeoutSec 3 -UseBasicParsing).Content
    } catch { return $null }
    $h = @{ heap = $null; send = [double]0; recv = [double]0 }
    foreach ($l in $body -split "`n") {
        if ($l -match '^jvm_memory_used_bytes\{[^}]*area="heap"[^}]*\}\s+(\S+)') { $h.heap = [double]$h.heap + [double]$Matches[1] }
        elseif ($l -match '^packet_send_bytes_sum\{[^}]*\}\s+(\S+)') { $h.send += [double]$Matches[1] }
        elseif ($l -match '^packet_receive_bytes_sum\{[^}]*\}\s+(\S+)') { $h.recv += [double]$Matches[1] }
    }
    $h
}

$t0 = Get-Date
$prevT = $t0
$prevSrv = Get-ProcSample $srv
$cliPid = 0; $cli = $null; $prevCli = $null
$prevNet = $null; $prevNetT = $t0
"sampling every $IntervalSec s into $Out"
while ($true) {
    Start-Sleep -Seconds $IntervalSec
    $now = Get-Date
    $dtMs = ($now - $prevT).TotalMilliseconds
    $s = Get-ProcSample $srv
    if (-not $s) { 'server exited; done'; break }

    # the client may start or stop while sampling
    $cs = Read-StateJson 'client'
    if ($cs -and $cs.pid -ne $cliPid) { $cliPid = $cs.pid; $cli = Get-RecordedProcess $cs; $prevCli = Get-ProcSample $cli }
    $c = Get-ProcSample $cli

    $net = @{}; $perf = @{}
    try {
        $replies = @(Invoke-Rcon @('stats network all', 'stats performance all'))
        $net = ConvertFrom-StatsReply $replies[0]
        $perf = ConvertFrom-StatsReply $replies[1]
    } catch { Write-Warning "RCON stats: $_" }
    Read-NewConsole
    $prom = Get-Prometheus

    $outRate = $null; $inRate = $null
    if ($prevNet -and $net.ContainsKey('total-actual-bytes-sent')) {
        $ds = $net['total-actual-bytes-sent'] - $prevNet['total-actual-bytes-sent']
        $dr = $net['total-actual-bytes-received'] - $prevNet['total-actual-bytes-received']
        $netMs = ($now - $prevNetT).TotalMilliseconds
        if ($ds -ge 0 -and $dr -ge 0) { $outRate = [Math]::Round($ds * 1000 / $netMs); $inRate = [Math]::Round($dr * 1000 / $netMs) }
    }
    $mb = { param($b) if ($null -ne $b) { [Math]::Round($b / 1MB, 1) } }
    $pct = { param($a, $b) if ($a -and $b) { [Math]::Round(($a.cpu - $b.cpu) * 100 / ($dtMs * $cores), 2) } }
    $row = [ordered]@{
        time                = $now.ToString('yyyy-MM-dd HH:mm:ss')
        elapsed_s           = [int]($now - $t0).TotalSeconds
        srv_cpu_pct         = & $pct $s $prevSrv
        srv_ws_mb           = & $mb $s.ws
        srv_private_mb      = & $mb $s.priv
        cli_cpu_pct         = & $pct $c $prevCli
        cli_ws_mb           = if ($c) { & $mb $c.ws }
        cli_private_mb      = if ($c) { & $mb $c.priv }
        srv_heap_used_mb    = & $mb $perf['memory-used']
        srv_update_avg_ms   = $perf['avg-update-period']
        srv_update_max_ms   = $perf['max-update-period']
        srv_fps             = $perf['fps']
        net_actual_sent     = $net['total-actual-bytes-sent']
        net_actual_recv     = $net['total-actual-bytes-received']
        net_out_Bps         = $outRate
        net_in_Bps          = $inRate
        net_game_sent       = $net['sent-bytes']
        net_game_recv       = $net['received-bytes']
        prom_heap_used_mb   = if ($prom) { & $mb $prom.heap }
        prom_packet_send_b  = if ($prom) { $prom.send }
        prom_packet_recv_b  = if ($prom) { $prom.recv }
        sg_seen             = $sgSeen
        sg_line             = $sgLine
    }
    [pscustomobject]$row | Export-Csv -Path $Out -Append -NoTypeInformation -Encoding utf8
    $prevT = $now; $prevSrv = $s; $prevCli = $c
    if ($net.ContainsKey('total-actual-bytes-sent')) { $prevNet = $net; $prevNetT = $now }
    if ($DurationSec -and ($now - $t0).TotalSeconds -ge $DurationSec) { 'duration reached; done'; break }
}
