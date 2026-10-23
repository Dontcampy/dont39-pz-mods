"""AC5 table: per run the launch and join totals (menu wait excluded), Loading the world and the asset wait from
Loading Progress's reports, and the console's error signatures that a run has and no base run has."""
import re
import statistics
import sys
from pathlib import Path

runs = Path(sys.argv[1])


def report(run, kind):
    files = sorted((runs / run / "reports").glob(f"*_{kind}.txt"))
    if not files:
        return None
    text = files[-1].read_text(encoding="utf-8")
    total = int(re.search(r"^Total (\d+) ms", text, re.M).group(1))
    menu = re.search(r"waiting in the menu (\d+) ms", text)
    world = re.search(r"^\s+(\d+) ms\s+\d+ / \d+\s+Loading the world", text, re.M)
    wait = re.search(r"^Asset wait.*\n\s+(\d+) ms", text, re.M)
    return {"total": total - (int(menu.group(1)) if menu else 0), "world": int(world.group(1)) if world else None,
            "wait": int(wait.group(1)) if wait else None}


def errors(run):
    sigs = set()
    for line in (runs / run / "console.txt").read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("ERROR") or "Exception" in line and not line.startswith("\t"):
            sig = re.sub(r"st:[\d,]+|f:\d+|\d{2}:\d{2}:\d{2}[.,]\d+|\b\d{5,}\b", "", line)
            sigs.add(re.sub(r"\s+", " ", sig).strip()[:200])
    return sigs


names = sorted(p.name for p in runs.iterdir() if (p / "console.txt").exists())
rows = {n: (report(n, "launch"), report(n, "start")) for n in names}
print(f"{'run':6} {'launch s':>9} {'join s':>8} {'world s':>8} {'wait s':>7}")
for n in names:
    l, s = rows[n]
    f = lambda v: "-" if v is None else f"{v / 1000:.1f}"
    print(f"{n:6} {f(l and l['total']):>9} {f(s and s['total']):>8} {f(s and s['world']):>8} {f(s and s['wait']):>7}")
for mode in ("on", "off", "base"):
    sel = [n for n in names if re.fullmatch(mode + r"\d+", n)]
    if sel:
        med = lambda k, i: statistics.median(rows[n][i][k] for n in sel if rows[n][i]) / 1000
        print(f"{mode:5} median: launch {med('total', 0):.1f} s, join {med('total', 1):.1f} s, world"
              f" {med('world', 1):.1f} s ({len(sel)} runs)")
base = set().union(*(errors(n) for n in names if n.startswith("base"))) if any(
    n.startswith("base") for n in names) else set()
for n in names:
    if not n.startswith("base"):
        new = sorted(errors(n) - base)
        print(f"{n}: {len(new)} error signatures not in any base run")
        for s in new[:25]:
            print("   ", s)
