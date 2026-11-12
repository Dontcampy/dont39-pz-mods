<#
Launches the game client as ProjectZomboid64.exe would (Steam on, the user's mod list), lets FLTestDriver load to the
main menu, start a new sandbox world and quit; then collects console.txt and the Loading Progress reports of the run.
#>
param([Parameter(Mandatory)] [string] $Label, [Parameter(Mandatory)] [string] $RunRoot,
    [Parameter(Mandatory)] [string] $GameDir,
    [string] $World = 'fl-42-22-test', [int] $MenuSec = 35, [int] $GameSec = 20, [int] $TimeoutSec = 1500)
$ErrorActionPreference = 'Stop'
$gd = $GameDir
$zomboid = Join-Path $HOME 'Zomboid'
$lua = Join-Path $zomboid 'Lua'
$state = Join-Path $lua 'fltest_state.txt'
$runDir = Join-Path $RunRoot $Label
if (Test-Path $runDir) { throw "$runDir exists; use another label" }
New-Item -ItemType Directory -Force $runDir | Out-Null
if (Get-Process -Name 'ProjectZomboid64' -ErrorAction SilentlyContinue) { throw 'the game is running' }

Set-Content -Path (Join-Path $lua 'fltest.txt') -Value "world=$World`nmenuSec=$MenuSec`ngameSec=$GameSec" -Encoding ascii -NoNewline
Remove-Item $state -ErrorAction SilentlyContinue
$lp = Join-Path $lua 'Dont39LoadingProgress'
$before = @(if (Test-Path $lp) { Get-ChildItem $lp -File | ForEach-Object Name })

$json = Get-Content (Join-Path $gd 'ProjectZomboid64.json') -Raw | ConvertFrom-Json
$winArgs = @($json.windows.PSObject.Properties | Where-Object { [Environment]::OSVersion.Version -ge [version]$_.Name } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1 | ForEach-Object { $_.Value.vmArgs })
$argList = @($json.vmArgs) + $winArgs + @('-cp', ($json.classpath -join ';'), $json.mainClass.Replace('/', '.'))

if (-not ('FlWin' -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class FlWin {
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
$quoted = ($argList | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
$p = Start-Process -FilePath (Join-Path $gd 'jre64\bin\java.exe') -ArgumentList $quoted -WorkingDirectory $gd -PassThru `
    -RedirectStandardOutput (Join-Path $runDir 'stdout.txt') -RedirectStandardError (Join-Path $runDir 'stderr.txt')
"client pid=$($p.Id) label=$Label"
$hwnd = [IntPtr]::Zero
$shown = 0
$result = 'timeout'
while ($true) {
    Start-Sleep -Milliseconds 1000
    $lines = @(if (Test-Path $state) { Get-Content $state -ErrorAction SilentlyContinue })
    for (; $shown -lt $lines.Count; $shown++) { "driver: $($lines[$shown])  (+$([int]([DateTimeOffset]::Now - $t0).TotalSeconds) s)" }
    $all = ($lines -join "`n") + "`n"
    if ($p.HasExited) { $result = if ($all.Contains(' quit ')) { 'ok' } else { "exited early (code $($p.ExitCode))" }; break }
    if (([DateTimeOffset]::Now - $t0).TotalSeconds -gt $TimeoutSec) { Stop-Process -Id $p.Id -Force; break }
    # the world-loading screen waits for a click once loaded
    if ($all.Contains(' newgame ') -and -not $all.Contains(' ingame ')) {
        if ($hwnd -eq [IntPtr]::Zero) { $hwnd = [FlWin]::FindFor([uint32]$p.Id) }
        if ($hwnd -ne [IntPtr]::Zero) {
            $r = [FlWin+RECT]::new()
            [void][FlWin]::GetClientRect($hwnd, [ref]$r)
            $lparam = [IntPtr](([int]($r.B / 2) -shl 16) -bor [int]($r.R / 2))
            [void][FlWin]::PostMessage($hwnd, 0x201, [IntPtr]1, $lparam)
            Start-Sleep -Milliseconds 60
            [void][FlWin]::PostMessage($hwnd, 0x202, [IntPtr]0, $lparam)
        }
    }
}
"result: $result after $([int]([DateTimeOffset]::Now - $t0).TotalSeconds) s"
Start-Sleep -Seconds 2
Copy-Item (Join-Path $zomboid 'console.txt') (Join-Path $runDir 'console.txt')
if (Test-Path $state) { Copy-Item $state (Join-Path $runDir 'driver-state.txt') }
$new = @(if (Test-Path $lp) { Get-ChildItem $lp -File | Where-Object { $before -notcontains $_.Name } })
New-Item -ItemType Directory -Force (Join-Path $runDir 'reports') | Out-Null
$new | ForEach-Object { Copy-Item $_.FullName (Join-Path $runDir 'reports') }
"new Loading Progress reports: $(($new | ForEach-Object Name) -join ', ')"
Remove-Item (Join-Path $lua 'fltest.txt') -ErrorAction SilentlyContinue
if ($result -ne 'ok') { exit 1 }
