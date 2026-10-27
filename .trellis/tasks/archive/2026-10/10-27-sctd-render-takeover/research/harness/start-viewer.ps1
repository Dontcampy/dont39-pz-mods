<#
.SYNOPSIS
A second client beside start-client.ps1's (remote view, task 10-27-sctd-render-takeover): its own -cachedir, so its
console.txt, Lua folder, mods and driver files are apart from ~/Zomboid. The mods it needs are copied into
<CacheDir>\mods by the caller (real copies, not junctions); default.txt there enables SGTestDriver. Joins as -User
(an account made on the server first) and runs -Test (sctdView: watches -Watch). stop-viewer.ps1 ends it.
#>
param([Parameter(Mandatory)] [string] $Label, [string] $Spot = 'prison', [int] $DurationSec = 1800, [switch] $Quit,
    [switch] $NoLoop, [switch] $Visible, [int] $Radius = 20, [int] $Take = 3, [int] $EatEvery = 10,
    [switch] $WaitArrived, [int] $JoinTimeoutSec = 900, [string[]] $JvmArgs = @(),
    [ValidateSet('', 'equipRemove', 'empty', 'burst', 'nested', 'falseRepairs', 'appearance', 'appearanceCheck', 'appearanceClear', 'sctdHide', 'sctdTransmog', 'sctdSkin', 'sctdAutoHide', 'sctdCheck', 'sctdDisabled', 'sctdView')] [string] $Test = '', [switch] $VanillaTransfers,
    [switch] $NoZombieBuddy, [switch] $LogTransfers, [switch] $ZbAllowAll, [string] $ServerHost = '',
    [int] $ServerPort = 0, [string] $ServerPassword = '',
    [string] $CacheDir = 'C:\pz2c', [string] $User = 'viewer', [string] $Watch = 'admin')
. (Join-Path $PSScriptRoot 'common.ps1')
$SG.ClientMods = Join-Path $CacheDir 'mods'
$SG.ClientLua = Join-Path $CacheDir 'Lua'
New-Item -ItemType Directory -Force $SG.ClientLua | Out-Null
$SG.DriverCfg = Join-Path $SG.ClientLua 'sgtest.txt'
$SG.DriverState = Join-Path $SG.ClientLua 'sgtest_state.txt'
$SG.DriverStop = Join-Path $SG.ClientLua 'sgtest_stop.txt'

if (-not (Test-Path (Join-Path $SG.Backup 'hashes.txt'))) { throw 'no client backup; run backup-client.ps1 first' }
if ($ServerHost) {
    if (-not $ServerPort -or -not $ServerPassword) { throw '-ServerHost needs -ServerPort and -ServerPassword' }
} elseif (-not (Get-RecordedProcess (Read-StateJson 'server'))) { throw 'server not running; start-server.ps1 -Wait first' }
$running = Get-RecordedProcess (Read-StateJson 'viewer')
if ($running) { throw "client already running (pid $($running.Id)); stop-viewer.ps1 first" }
if (-not (Test-Path (Join-Path $SG.ClientMods 'SGTestDriver\42\mod.info'))) { throw 'SGTestDriver not in ~/Zomboid/mods; run setup-server.ps1' }
$runDir = Get-RunDir $Label
if (Test-Path (Join-Path $runDir 'viewer-stdout.txt')) { throw "$runDir already has a client run; use another label" }
$s = Resolve-Spot $Spot

# the driver must run in the main menu to join; the launcher can drop it from default.txt between runs
$def = Join-Path $SG.ClientMods 'default.txt'
$text = [IO.File]::ReadAllText($def)
if ($text -notmatch '(?m)^\s*mod = SGTestDriver,') {
    if ($text -notmatch '(?m)^mods\s*\r?\n\{\r?\n') { throw "no mods { block in $def" }
    $text = $text -replace '(?m)^(mods\s*\r?\n\{\r?\n)', "`$1    mod = SGTestDriver,`r`n"
    [IO.File]::WriteAllText($def, $text, [Text.UTF8Encoding]::new($false))
    "added SGTestDriver to $def"
}

$cfg = [ordered]@{
    label = $Label; ip = $(if ($ServerHost) { $ServerHost } else { '127.0.0.1' })
    port = $(if ($ServerHost) { $ServerPort } else { $SG.GamePort }); user = $User
    pw = $(if ($ServerHost) { $ServerPassword } else { Get-AdminPassword })
    spotName = $s.Name; x = $s.X; y = $s.Y; z = $s.Z; duration = $DurationSec; quit = [int][bool]$Quit
    loop = [int](-not $NoLoop); invisible = [int](-not $Visible); radius = $Radius; take = $Take; eatEvery = $EatEvery
    test = $Test; watch = $Watch; vanillaTransfers = [int][bool]$VanillaTransfers; logTransfers = [int][bool]$LogTransfers
}
Set-Content -Path $SG.DriverCfg -Value (($cfg.Keys | ForEach-Object { "$_=$($cfg[$_])" }) -join "`n") -Encoding ascii -NoNewline
Remove-Item $SG.DriverState, $SG.DriverStop -ErrorAction SilentlyContinue

$gd = $SG.pzDir
$json = Get-Content (Join-Path $gd 'ProjectZomboid64.json') -Raw | ConvertFrom-Json
$winArgs = @($json.windows.PSObject.Properties | Where-Object { [Environment]::OSVersion.Version -ge [version]$_.Name } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1 | ForEach-Object { $_.Value.vmArgs })
$vm = foreach ($a in @($json.vmArgs) + $winArgs) {
    if ($a -eq '-Dzomboid.steam=1') { '-Dzomboid.steam=0' }
    elseif ($NoZombieBuddy -and $a -like '-agentlib:zbNative*') { }
    elseif ($ZbAllowAll -and $a -like '-agentlib:zbNative*') { if ($a.Contains('=')) { "$a,policy=allow-all" } else { "$a=policy=allow-all" } }
    else { $a }
}
$argList = @($vm) + $JvmArgs + @('-cp', ($json.classpath -join ';'), $json.mainClass.Replace('/', '.'), '-nosteam', "-cachedir=$CacheDir")

if (-not ('SgWin' -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class SgWin {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, System.Text.StringBuilder s, int n);
  public delegate bool EnumProc(IntPtr h, IntPtr p);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  public static IntPtr FindFor(uint pid) {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h, p) => { uint wp; GetWindowThreadProcessId(h, out wp);
      if (wp == pid && IsWindowVisible(h)) { var sb = new System.Text.StringBuilder(256); GetWindowText(h, sb, 256);
        if (sb.ToString().Contains("Zomboid")) { found = h; return false; } }
      return true; }, IntPtr.Zero);
    return found;
  }
}
'@
}

$t0 = [DateTimeOffset]::Now
$p = Start-Process -FilePath (Join-Path $gd 'jre64\bin\java.exe') -ArgumentList (Join-ProcessArgs $argList) `
    -WorkingDirectory $gd -PassThru `
    -RedirectStandardOutput (Join-Path $runDir 'viewer-stdout.txt') -RedirectStandardError (Join-Path $runDir 'viewer-stderr.txt')
Write-StateJson 'viewer' @{ pid = $p.Id; label = $Label; runDir = $runDir; startMs = $t0.ToUnixTimeMilliseconds() }
"client pid=$($p.Id) label=$Label spot=$($s.Name) $($s.X),$($s.Y),$($s.Z)"

$goal = if ($WaitArrived) { ' arrived ' } else { ' ingame ' }
$hwnd = [IntPtr]::Zero
$shown = 0
$warned = $false
while ($true) {
    Start-Sleep -Milliseconds 500
    if ($p.HasExited) { throw "client exited (code $($p.ExitCode)); see $runDir and ~/Zomboid/console.txt" }
    if (([DateTimeOffset]::Now - $t0).TotalSeconds -gt $JoinTimeoutSec) { throw "driver did not reach '$($goal.Trim())' in $JoinTimeoutSec s; client left running" }
    $lines = @(if (Test-Path $SG.DriverState) { Get-Content $SG.DriverState -ErrorAction SilentlyContinue })
    for (; $shown -lt $lines.Count; $shown++) { "driver: $($lines[$shown])" }
    $all = ($lines -join "`n") + "`n"
    if ($all.Contains($goal)) { break }
    if (-not $warned -and $all.Contains(' error ')) { $warned = $true; Write-Warning 'driver reported an error; see above' }
    # the world-loading screen waits for a click once loaded
    if (($all.Contains(' connected existing') -or $all.Contains(' charnext')) -and -not $all.Contains(' ingame ')) {
        if ($hwnd -eq [IntPtr]::Zero) { $hwnd = [SgWin]::FindFor([uint32]$p.Id) }
        if ($hwnd -ne [IntPtr]::Zero) {
            $r = [SgWin+RECT]::new()
            [void][SgWin]::GetClientRect($hwnd, [ref]$r)
            $lp = [IntPtr](([int]($r.B / 2) -shl 16) -bor [int]($r.R / 2))
            [void][SgWin]::PostMessage($hwnd, 0x201, [IntPtr]1, $lp)
            Start-Sleep -Milliseconds 60
            [void][SgWin]::PostMessage($hwnd, 0x202, [IntPtr]0, $lp)
        }
    }
}
"driver reached '$($goal.Trim())' after $([int]([DateTimeOffset]::Now - $t0).TotalSeconds) s; client keeps running (stop-viewer.ps1)"
