"""Compares the dedicated server's "[Sync] S" lines with each client's "[Sync] C" lines.

usage: python3 -I sync_check.py ts-server.log ts-alice.log [ts-bob.log ...]

A server sample (taken every 100 ticks) counts as seen by a client if one of that client's samples (every 20 ticks)
from 20 ticks before to 120 after has the same state. Building progress and the stock move with every block a site
lays (every few ticks), so they're left out of the state and their gap is reported apart. A desync is a run of three or more server samples in a row (15 s) a client never matched
while it was connected.
"""
import re, sys, collections
pat = re.compile(r"\[Sync\] ([SC]) t=(\d+) city=(\w+) (.*)")
def load(path, side):
    out = collections.defaultdict(list)  # city -> [(t, state, prog)]
    for line in open(path, errors="replace"):
        m = pat.search(line)
        if not m or m.group(1) != side: continue
        t, city, rest = int(m.group(2)), m.group(3), m.group(4)
        prog = int(re.search(r"prog=(\d+)", rest).group(1))
        state = re.sub(r" prog=\d+| stock=\d+", "", rest)
        if NO_FP: state = re.sub(r"fp=\w+ ", "", state)
        out[city].append((t, state, prog))
    return out
NO_FP = "--no-fp" in sys.argv
sys.argv = [a for a in sys.argv if a != "--no-fp"]
server = load(sys.argv[1], "S")
for path in sys.argv[2:]:
    client = load(path, "C")
    print(f"== {path}")
    for city, ss in server.items():
        cs = client.get(city, [])
        if not cs: print(f"  {city}: no client samples"); continue
        lo, hi = cs[0][0], cs[-1][0]
        times = [c[0] for c in cs]
        matched = total = run = worst = 0; prog_off = []; runs = []
        import bisect
        for t, state, prog in ss:
            if t < lo or t > hi: continue
            i, j = bisect.bisect_left(times, t - 20), bisect.bisect_right(times, t + 120)
            window = cs[i:j]
            if not window: continue  # client disconnected then
            total += 1
            if any(c[1] == state for c in window):
                matched += 1
                if run >= 3: runs.append((t, run))
                run = 0
            else:
                run += 1; worst = max(worst, run)
            near = min(window, key=lambda c: abs(c[0] - t))
            prog_off.append(abs(near[2] - prog))
        if run >= 3: runs.append((ss[-1][0], run))
        prog_off.sort()
        p95 = prog_off[int(len(prog_off) * 0.95)] if prog_off else 0
        print(f"  {city}: {matched}/{total} server samples matched ({100 * matched / max(1, total):.1f}%), longest unmatched run {worst} samples,"
              f" desyncs (>=3 in a row) {len(runs)}; progress gap p95 {p95}, max {prog_off[-1] if prog_off else 0}")
        for t, r in runs[:5]: print(f"     unmatched run of {r} ending t={t}")
