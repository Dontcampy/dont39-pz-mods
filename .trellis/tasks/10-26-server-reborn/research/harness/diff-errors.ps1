<#
.SYNOPSIS
Compares the error lines of two consoles of the same side (server or client), without and with Sync Guard (AC10).

.DESCRIPTION
Error lines are those matching -Pattern. Each is reduced to a signature (timestamps, frame and time stamps, numbers,
hex ids removed) so the same error from two runs compares equal. Prints the signatures only the run with Sync Guard
has (the AC10 failures), those only the run without has, and those the run with has more often, with counts and one
original line each. Exit code 1 when the run with Sync Guard has any signature the other has not.
#>
param([Parameter(Mandatory)] [string] $Without, [Parameter(Mandatory)] [string] $With,
    [string] $Pattern = '^ERROR:|\bERROR\b|Exception|^\s*Caused by:|\w+Error\b|STACK TRACE|attempted index|Callframe at|SEVERE')
$ErrorActionPreference = 'Stop'

function Get-Signature([string] $Line) {
    $s = $Line -replace '^\[[^\]]*\]\s*', ''
    $s = $s -replace '\bf:\d+', 'f:#' -replace '\bt:\d+', 't:#' -replace '\bst:[\d,]+', 'st:#'
    $s = $s -replace '0x[0-9a-fA-F]+', '0x#' -replace '@[0-9a-fA-F]{4,}\b', '@#' -replace '\d+', '#'
    ($s -replace '\s+', ' ').Trim()
}
function Get-Errors([string] $Path) {
    $h = @{}
    foreach ($m in Select-String -LiteralPath $Path -Pattern $Pattern -CaseSensitive) {
        $sig = Get-Signature $m.Line
        if (-not $h.ContainsKey($sig)) { $h[$sig] = [pscustomobject]@{ Count = 0; Example = $m.Line.Trim(); LineNo = $m.LineNumber } }
        $h[$sig].Count++
    }
    $h
}

$a = Get-Errors $Without
$b = Get-Errors $With
$onlyWith = @($b.Keys | Where-Object { -not $a.ContainsKey($_) } | Sort-Object)
$onlyWithout = @($a.Keys | Where-Object { -not $b.ContainsKey($_) } | Sort-Object)
$more = @($b.Keys | Where-Object { $a.ContainsKey($_) -and $b[$_].Count -gt $a[$_].Count } | Sort-Object)

"without: $Without ($($a.Count) signatures, $(($a.Values | Measure-Object Count -Sum).Sum) lines)"
"with:    $With ($($b.Count) signatures, $(($b.Values | Measure-Object Count -Sum).Sum) lines)"
''
"== only with Sync Guard: $($onlyWith.Count)"
foreach ($k in $onlyWith) { "  x$($b[$k].Count) line $($b[$k].LineNo): $($b[$k].Example)" }
''
"== more often with Sync Guard: $($more.Count)"
foreach ($k in $more) { "  $($a[$k].Count) -> $($b[$k].Count) line $($b[$k].LineNo): $($b[$k].Example)" }
''
"== only without Sync Guard: $($onlyWithout.Count)"
foreach ($k in $onlyWithout) { "  x$($a[$k].Count) line $($a[$k].LineNo): $($a[$k].Example)" }
if ($onlyWith.Count) { exit 1 }
