"""Simulates the depth-map phase of a join's asset wait under several queue orders.

Usage: python -I reprioritise_sim.py <LP start.csv> <workshop content/108600 dir>

Model (FileSystemImpl, game 42.21 4a0e9546ec):
- pending is sorted once, at insertion (priority desc, FIFO within equal priority);
- a pump every PUMP ms on the main thread reaps done items, then tops in-flight up to 16 (executor.submit);
- the executor is a fixed pool of 4 threads with a FIFO queue;
- a LoadTask holds its worker while it waits for its instance's monitor (FIFO hand-off assumed);
- a task's work is proportional to its PNG's pixel count (w*h from the IHDR), plus CONST ms;
- the phase ends at the pump that reaps the last task.
"""
import collections
import csv
import heapq
import os
import struct
import sys

PUMP = 6.954  # median gap between distinct sent times in the CSV
WORKERS = 4
CAP = 16
MEASURED_FIFO_MS = None  # set from the CSV


def png_wh(path):
    with open(path, 'rb') as f:
        b = f.read(24)
    assert b[:8] == b'\x89PNG\r\n\x1a\n', path
    return struct.unpack('>II', b[16:24])


def load(csv_path, workshop):
    index = {}
    for item in os.listdir(workshop):
        mods = os.path.join(workshop, item, 'mods')
        if not os.path.isdir(mods):
            continue
        for folder in os.listdir(mods):
            dm = os.path.join(mods, folder, 'common', 'media', 'depthmaps')
            if os.path.isdir(dm):
                for fn in os.listdir(dm):
                    index[(folder.lower(), fn.lower())] = os.path.join(dm, fn)
    rows = [r for r in csv.DictReader(open(csv_path, encoding='utf-8-sig'))
            if r['kind'] == 'file_task' and r['task_type'] == 'TileDepthTextures$LoadTask']
    tasks = []
    for r in rows:
        p = r['name']
        if not os.path.exists(p):
            parts = p.replace('/', '\\').split('\\')
            i = [k for k, s in enumerate(parts) if s.lower() == 'mods'][-1]
            p = index[(parts[i + 1].lower(), parts[-1].lower())]
        w, h = png_wh(p)
        tasks.append(dict(name=os.path.basename(r['name']), inst=r['mod'] or 'game', sent=float(r['start_ms']),
                          done=float(r['start_ms']) + float(r['duration_ms']), worker=float(r['worker_ms']),
                          px=w * h, bytes=os.path.getsize(p)))
    return tasks


def base_order(tasks):
    """Today's pending order: instances by first sent time, each in directory order (NTFS: by upper-case name)."""
    first = {}
    for t in tasks:
        first[t['inst']] = min(first.get(t['inst'], 1e18), t['sent'])
    insts = sorted(first, key=first.get)
    by = collections.defaultdict(list)
    for i, t in enumerate(tasks):
        by[t['inst']].append(i)
    order = []
    for inst in insts:
        order += sorted(by[inst], key=lambda i: tasks[i]['name'].upper())
    return order, insts


def simulate(order, cost, inst):
    """Phase length in ms (first pump to the pump that reaps the last task), and per-task reap times."""
    pending = collections.deque(order)
    inprog = []
    execq = collections.deque()
    free = WORKERS
    owner = {}
    waiters = collections.defaultdict(collections.deque)
    events = []  # (time, seq, task) completions
    seq = 0
    done = set()
    reaped = {}
    t = 0.0
    next_pump = 0.0

    def start(task, now):
        nonlocal seq
        i = inst[task]
        if i in owner:
            waiters[i].append(task)
        else:
            owner[i] = task
            heapq.heappush(events, (now + cost[task], seq, task))
            seq += 1

    def grab(now):
        nonlocal free
        while free > 0 and execq:
            free -= 1
            start(execq.popleft(), now)

    while len(reaped) < len(order):
        if events and events[0][0] <= next_pump:
            now, _, task = heapq.heappop(events)
            done.add(task)
            i = inst[task]
            del owner[i]
            if waiters[i]:
                nxt = waiters[i].popleft()
                owner[i] = nxt
                heapq.heappush(events, (now + cost[nxt], seq, nxt))
                seq += 1
            else:
                pass
            free += 1
            grab(now)
            # a worker freed by the hand-off case is the one that ran `task`; the waiter kept its own worker
        else:
            now = next_pump
            keep = []
            for task in inprog:
                if task in done:
                    reaped[task] = now
                else:
                    keep.append(task)
            inprog = keep
            while len(inprog) < CAP and pending:
                task = pending.popleft()
                inprog.append(task)
                execq.append(task)
            grab(now)
            next_pump += PUMP
    return max(reaped.values()), reaped


def order_round_robin(base, inst, rank, per_round=None):
    """Priority 4-k for the k-th task of each instance (k in its submit order); pending = stable sort.
    per_round: instance -> tasks it gets per round (priority 4 - k // that), default 1."""
    k = {}
    seen = collections.Counter()
    for task in base:
        k[task] = seen[inst[task]] // ((per_round or {}).get(inst[task], 1))
        seen[inst[task]] += 1
    pos = {task: n for n, task in enumerate(base)}
    return sorted(base, key=lambda task: (k[task], rank(inst[task]), pos[task]))


def order_greedy(base, inst, est, largest_first):
    """List schedule on 4 virtual workers by estimates: a free worker takes the next task of the not-running
    instance with the most estimated work left. The emitted order becomes the priorities."""
    chains = collections.OrderedDict()
    for task in base:
        chains.setdefault(inst[task], []).append(task)
    if largest_first:
        for c in chains.values():
            c.sort(key=lambda task: -est[task])
    left = {i: sum(est[x] for x in c) for i, c in chains.items()}
    nxt = {i: 0 for i in chains}
    running = set()
    ev = []
    free = WORKERS
    now = 0.0
    out = []
    while len(out) < len(base):
        while free > 0:
            cand = [i for i in chains if i not in running and nxt[i] < len(chains[i])]
            if not cand:
                break
            i = max(cand, key=lambda i: left[i])
            task = chains[i][nxt[i]]
            nxt[i] += 1
            left[i] -= est[task]
            running.add(i)
            out.append(task)
            heapq.heappush(ev, (now + est[task], i))
            free -= 1
        now, i = heapq.heappop(ev)
        running.discard(i)
        free += 1
    return out


def main():
    tasks = load(sys.argv[1], sys.argv[2])
    n = len(tasks)
    measured = max(t['done'] for t in tasks) - min(t['sent'] for t in tasks)
    base, insts = base_order(tasks)
    inst = {i: tasks[i]['inst'] for i in range(n)}
    px = {i: tasks[i]['px'] for i in range(n)}
    by_inst = collections.Counter()
    for i in range(n):
        by_inst[inst[i]] += px[i]
    print('tasks', n, 'instances', len(insts), 'measured depth phase %.0f ms, worker %.0f ms' % (
        measured, sum(t['worker'] for t in tasks)))
    print('instance order (queue):', ', '.join('%s(%d)' % (i, sum(1 for x in inst.values() if x == i)) for i in insts))

    # sent order within each instance vs directory order: how often the CSV's sent order disagrees
    inv = 0
    for i in insts:
        ids = [x for x in base if inst[x] == i]
        sent = [tasks[x]['sent'] for x in ids]
        inv += sum(1 for a, b in zip(sent, sent[1:]) if b < a)
    print('directory-order check: %d of %d adjacent pairs sent out of order' % (inv, n - len(insts)))

    def costs(scale, const):
        return {i: const + scale * px[i] for i in range(n)}

    # calibrate: today's FIFO reproduces the measured phase
    lo, hi = 1e-9, 1e-5
    for _ in range(50):
        mid = (lo + hi) / 2
        if simulate(base, costs(mid, 0.0), inst)[0] < measured:
            lo = mid
        else:
            hi = mid
    cal = (lo + hi) / 2
    models = [
        ('calibrated to FIFO = measured, cost ~ pixels', cal, 0.0),
        ('total work 5.6 s, cost ~ pixels', 5600.0 / sum(px.values()), 0.0),
    ]
    # a model with 2 ms fixed per task, calibrated too
    lo, hi = 1e-9, 1e-5
    for _ in range(50):
        mid = (lo + hi) / 2
        if simulate(base, costs(mid, 2.0), inst)[0] < measured:
            lo = mid
        else:
            hi = mid
    models.append(('calibrated, cost = 2 ms + ~pixels', (lo + hi) / 2, 2.0))

    queue_rank = {i: r for r, i in enumerate(insts)}
    for label, scale, const in models:
        cost = costs(scale, const)
        total = sum(cost.values())
        chain = collections.Counter()
        for i in range(n):
            chain[inst[i]] += cost[i]
        tot_rank = {i: r for r, (i, _) in enumerate(chain.most_common())}
        floor = max(total / WORKERS, max(chain.values()))
        print('\n== %s: total work %.0f ms, floor max(work/4 %.0f, longest chain %s %.0f) = %.0f ms' % (
            label, total, total / WORKERS, chain.most_common(1)[0][0], chain.most_common(1)[0][1], floor))
        print('   chains: ' + ', '.join('%s %.0f' % (i, c) for i, c in chain.most_common(6)))
        byte_est = {i: float(tasks[i]['bytes']) for i in range(n)}
        schedules = [
            ('FIFO (today)', base),
            ('A  round-robin 4-k, queue order', order_round_robin(base, inst, queue_rank.get)),
            ('A2 = A, game 2 per round (initGameData bracket)', order_round_robin(base, inst, queue_rank.get, {'game': 2})),
            ('A3 = A, game 3 per round', order_round_robin(base, inst, queue_rank.get, {'game': 3})),
            ('B1 round-robin, instances by est. work desc', order_round_robin(base, inst, tot_rank.get)),
            ('B2 greedy longest-remaining-first (est = cost)', order_greedy(base, inst, cost, False)),
            ('B3 = B2, largest task first within instance', order_greedy(base, inst, cost, True)),
            ('B2 with est = PNG file size', order_greedy(base, inst, byte_est, False)),
        ]
        for name, order in schedules:
            phase, reaped = simulate(order, cost, inst)
            g = [x for x in order if inst[x] == 'game']
            print('   %-48s %6.0f ms   game done %6.0f ms' % (name, phase, max(reaped[x] for x in g)))

    # where the game's largest maps fall
    g = [x for x in base if inst[x] == 'game']
    big = sorted(g, key=lambda x: -px[x])[:6]
    a = order_round_robin(base, inst, queue_rank.get)
    print('\ngame: %d maps; largest by pixels, with their index k in the game\'s submit order and position in A:' % len(g))
    for x in big:
        print('   %-40s %5.1f Mpx  k=%3d  A position %3d of %d' % (
            tasks[x]['name'], px[x] / 1e6, g.index(x), a.index(x), n))


if __name__ == '__main__':
    main()
