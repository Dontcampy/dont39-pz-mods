<#
.SYNOPSIS
Starts the "sgtest" dedicated server from pzServerDir with -nosteam and the short cache folder.

.DESCRIPTION
JVM flags are the server's own ProjectZomboid64.json (vmArgs plus the Windows 10 set, as its launcher uses), with
Steam off, -Xmx from -Xmx, and ZombieBuddy's config_dir moved into the cache folder so the server never writes the
user's ~/.zombie_buddy. stdout/stderr go to <state>\runs\<Label>; the game writes <cache>\server-console.txt itself.

.PARAMETER Wait
Blocks until the console shows SERVER STARTED.

.PARAMETER PrometheusPort
Starts the game's own Prometheus exporter (-DprometheusPort) on that port; sampler.ps1 -PrometheusPort reads it.
#>
param([Parameter(Mandatory)] [string] $Label, [switch] $Wait, [int] $TimeoutSec = 900, [string] $Xmx = '16g',
    [int] $PrometheusPort = 0, [string[]] $JvmArgs = @(), [switch] $ZbAllowAll)
. (Join-Path $PSScriptRoot 'common.ps1')

$name = $SG.ServerName
if (-not (Test-Path (Join-Path $SG.Cache "Server\$name.ini"))) { throw 'no server ini; run setup-server.ps1 first' }
$running = Get-RecordedProcess (Read-StateJson 'server')
if ($running) { throw "server already running (pid $($running.Id)); stop-server.ps1 first" }
$runDir = Get-RunDir $Label
if (Test-Path (Join-Path $runDir 'server-stdout.txt')) { throw "$runDir already has a server run; use another label" }

$sd = $SG.pzServerDir
$json = Get-Content (Join-Path $sd 'ProjectZomboid64.json') -Raw | ConvertFrom-Json
$zbCfg = Join-Path $SG.Cache 'zombie_buddy'
New-Item -ItemType Directory -Force $zbCfg | Out-Null
$vm = foreach ($a in @($json.vmArgs) + @($json.windows.'10'.vmArgs)) {
    if ($a -like '-Xmx*') { "-Xmx$Xmx" }
    elseif ($a -eq '-Dzomboid.steam=1') { '-Dzomboid.steam=0' }
    elseif ($a -like '-agentlib:zbNative*') {
        # allow-all: new or changed jars load without approval, for this launch only
        $opts = "config_dir=$zbCfg" + $(if ($ZbAllowAll) { ',policy=allow-all' } else { '' })
        if ($a.Contains('=')) { "$a,$opts" } else { "$a=$opts" }
    }
    else { $a }
}
if (-not ($vm | Where-Object { $_ -like '-agentlib:zbNative*' })) { throw "no -agentlib:zbNative in $sd\ProjectZomboid64.json: ZombieBuddy is not installed on the server" }
if ($PrometheusPort) { $vm += "-DprometheusPort=$PrometheusPort" }
$vm += $JvmArgs
$argList = @($vm) + @('-cp', ($json.classpath -join ';'), $json.mainClass.Replace('/', '.'),
    "-cachedir=$($SG.Cache)", '-servername', $name, '-adminpassword', (Get-AdminPassword), '-nosteam')

Remove-Item $SG.ServerConsole -ErrorAction SilentlyContinue
$t0 = [DateTimeOffset]::Now
$p = Start-Process -FilePath (Join-Path $sd 'jre64\bin\java.exe') -ArgumentList (Join-ProcessArgs $argList) `
    -WorkingDirectory $sd -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $runDir 'server-stdout.txt') -RedirectStandardError (Join-Path $runDir 'server-stderr.txt')
Write-StateJson 'server' @{ pid = $p.Id; label = $Label; runDir = $runDir; startMs = $t0.ToUnixTimeMilliseconds(); prometheusPort = $PrometheusPort }
"server pid=$($p.Id) label=$Label console=$($SG.ServerConsole)"

if ($Wait) {
    while ($true) {
        Start-Sleep -Seconds 2
        if ($p.HasExited) { throw "server exited (code $($p.ExitCode)); see $runDir" }
        if (Test-Path $SG.ServerConsole) {
            if (Select-String -Path $SG.ServerConsole -SimpleMatch 'SERVER STARTED' -Quiet) { break }
            # the process can outlive a failed start
            if (Select-String -Path $SG.ServerConsole -SimpleMatch 'Server Terminated.' -Quiet) {
                Stop-Process -Id $p.Id -Force
                throw "server terminated during start; see $($SG.ServerConsole)"
            }
        }
        if (([DateTimeOffset]::Now - $t0).TotalSeconds -gt $TimeoutSec) { throw "no SERVER STARTED after $TimeoutSec s" }
    }
    "started after $([int]([DateTimeOffset]::Now - $t0).TotalSeconds) s"
}
