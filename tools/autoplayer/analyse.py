"""Summarises an autoplay run: milestone times, money and demand health, and a chart sheet.

usage: python3 -I analyse.py <days.csv> <events.log> <out.png>
"""
import csv
import re
import sys
from PIL import Image, ImageDraw, ImageFont

days_path, events_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
rows = list(csv.DictReader(open(days_path)))
f = lambda r, k: float(r[k])
H = [f(r, "hours") for r in rows]

MILESTONES = ["little_hamlet", "worthy_village", "tiny_town", "boom_town", "busy_town", "big_town", "small_city", "big_city",
              "grand_city", "capital_city", "colossal_city", "metropolis", "megalopolis"]
print("rows", len(rows), "hours", round(H[-1], 2) if H else 0)
ms = {}
for line in open(events_path):
    m = re.search(r"h=([\d.]+) MILESTONE (\w+)", line)
    if m:
        ms[m.group(2)] = float(m.group(1))
for k in MILESTONES[1:]:
    print(f"  {k:14s} {ms[k]:6.2f} h" if k in ms else f"  {k:14s}   -")
if rows:
    neg = sum(1 for r in rows if f(r, "treasury") < 0)
    print("days with treasury < 0:", neg, "of", len(rows))
    for k in "RCIO":
        vals = [int(r[k]) for r in rows[len(rows) // 5:]]
        pinned0 = sum(1 for v in vals if v == 0)
        pinned100 = sum(1 for v in vals if v >= 100)
        print(f"  demand {k}: mean {sum(vals) / max(1, len(vals)):.0f}, at 0 {pinned0}, at 100 {pinned100} (of {len(vals)} days after the first fifth)")
    last = rows[-1]
    print("last:", {k: last[k] for k in ["people", "buildings", "active", "sites", "paused", "abandoned", "milestone", "tiles", "treasury", "net",
                                          "happiness", "unemployment", "flow", "imported", "starved", "mspt"]})
    stuck = [line.strip() for line in open(events_path) if "STUCK" in line or "can't buy" in line]
    print("stuck/land notes:", len(stuck))
    for s in stuck[-6:]:
        print("  ", s)

# Chart sheet: four panels.
W, Hh = 900, 260
img = Image.new("RGB", (W, Hh * 4), "white")
d = ImageDraw.Draw(img)
try:
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 12)
except OSError:
    font = ImageFont.load_default()


def panel(i, title, series, lo=None, hi=None):
    y0 = i * Hh
    x0, x1, top, bot = 60, W - 20, y0 + 25, y0 + Hh - 30
    d.text((x0, y0 + 5), title, fill="black", font=font)
    allv = [v for _, vals, _ in series for v in vals]
    if not allv:
        return
    lo = min(allv) if lo is None else lo
    hi = max(allv) if hi is None else hi
    if hi == lo:
        hi = lo + 1
    hmax = max(H) if H and max(H) > 0 else 1
    d.rectangle([x0, top, x1, bot], outline="#999")
    for frac in (0, 0.5, 1):
        v = lo + (hi - lo) * frac
        y = bot - (bot - top) * frac
        d.text((5, y - 6), f"{v:.0f}", fill="#555", font=font)
    if lo < 0 < hi:
        y = bot - (bot - top) * (0 - lo) / (hi - lo)
        d.line([x0, y, x1, y], fill="#bbb")
    for hr in range(0, int(hmax) + 1, max(1, int(hmax // 10) or 1)):
        x = x0 + (x1 - x0) * hr / hmax
        d.text((x - 4, bot + 4), str(hr), fill="#555", font=font)
    lx = x0 + 10
    for name, vals, col in series:
        pts = [(x0 + (x1 - x0) * h / hmax, bot - (bot - top) * (min(hi, max(lo, v)) - lo) / (hi - lo)) for h, v in zip(H, vals)]
        if len(pts) > 1:
            d.line(pts, fill=col, width=2)
        d.text((lx, top + 4), name, fill=col, font=font)
        lx += 90
    for k, h in ms.items():
        x = x0 + (x1 - x0) * h / hmax
        d.line([x, top, x, bot], fill="#dde")


panel(0, "People (milestones as faint lines; x = hours at speed 1)", [("people", [f(r, "people") for r in rows], "#2a6"),
                                                                      ("buildings", [f(r, "buildings") for r in rows], "#888")])
panel(1, "Treasury and net per day (emeralds)", [("treasury", [f(r, "treasury") for r in rows], "#26c"), ("net/day", [f(r, "net") for r in rows], "#c62")])
panel(2, "Demand", [(k, [f(r, k) for r in rows], c) for k, c in zip("RCIO", ["#2a2", "#28d", "#cb2", "#2cc"])], 0, 100)
panel(3, "Happiness, traffic flow %, unemployment %", [("happy", [f(r, "happiness") for r in rows], "#d4a"), ("flow", [f(r, "flow") for r in rows], "#555"),
                                                       ("jobless", [f(r, "unemployment") for r in rows], "#c22")], 0, 100)
img.save(out)
print("chart", out)
