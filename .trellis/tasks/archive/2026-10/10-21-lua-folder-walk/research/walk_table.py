# walk_table.py <fullsrv dir> run...: the Lua folder walks per run from Loading Progress's CSVs, and whether the Lua
# file lists are the same in every run of a flow.
import csv, glob, hashlib, os, statistics as st, sys

S, runs = sys.argv[1], sys.argv[2:]


def walks(path):
    rows = list(csv.DictReader(open(path, encoding='utf-8-sig')))
    steps = [r for r in rows if r['kind'] == 'step']
    i = next(i for i, r in enumerate(steps) if r['step'] == 'lua')
    step = dict(steps[i])
    # the step row's duration runs to its first item; the step runs to the next step
    step['duration_ms'] = float(steps[i + 1]['start_ms']) - float(step['start_ms'])
    lua = [r for r in rows if r['kind'] == 'lua']
    # a folder starts where the index starts again at 1; its walk from the step it is in or the folder before
    folders, cur, step_at = [], None, {}
    last_step = None
    for r in rows:
        if r['kind'] == 'step':
            last_step = float(r['start_ms'])
        elif r['kind'] == 'lua':
            if r['index'] == '1':
                cur = []
                folders.append(cur)
                step_at[id(cur)] = last_step
            cur.append(r)
    out, prev_end = [], float(step['start_ms'])
    for f in folders:
        first = float(f[0]['start_ms'])
        out.append((first - max(prev_end, step_at[id(f)]), len(f)))
        last = f[-1]
        prev_end = float(last['start_ms']) + float(last['duration_ms'])
    names = '\n'.join(r['name'] for r in lua)
    return out, float(step['duration_ms']), len(lua), hashlib.sha1(names.encode()).hexdigest()[:10], names


table = {}
for run in runs:
    for kind in ('launch', 'start'):
        f = glob.glob(os.path.join(S, 'runs', run, 'reports', f'*_{kind}.csv'))
        if f:
            table[(run, kind)] = walks(f[0])

for kind in ('launch', 'start'):
    print(f'== {kind}: walk before each folder\'s first file (ms), Lua step, files, list hash')
    groups = {'off': [], 'on': []}
    for run in runs:
        t = table.get((run, kind))
        if not t:
            print(f'{run:<6} (no report)')
            continue
        w, step, n, h, _ = t
        print(f'{run:<6} ' + '  '.join(f'{ms:8.0f} ({c})' for ms, c in w) + f'   step {step:8.0f}   {n} files   {h}')
        for g in groups:
            if run.startswith(g):
                groups[g].append(t)
    for g, ts in groups.items():
        if ts:
            folders = len(ts[0][0])
            meds = [st.median([t[0][i][0] for t in ts if len(t[0]) > i]) for i in range(folders)]
            print(f'{g + " med":<6} ' + '  '.join(f'{m:8.0f}' for m in meds)
                  + f'   step {st.median([t[1] for t in ts]):8.0f}   (n={len(ts)})')
    hashes = {t[3] for (r, k), t in table.items() if k == kind and not r.startswith('warm')}
    print(f'lists the same in every on/off run: {len(hashes) == 1} ({sorted(hashes)})')
