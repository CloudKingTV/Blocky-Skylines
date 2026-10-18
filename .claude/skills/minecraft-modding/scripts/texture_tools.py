#!/usr/bin/env python3
"""Small pixel-art helpers for Minecraft mod textures (requires Pillow).

Subcommands:
  ramp          print a hue-shifted color ramp for a base color
  render        render a pixel grid (text file) + palette into a PNG
  preview       write an 8x nearest-neighbor preview (and a 3x3 tile preview for blocks)
  check         report size, power-of-two, and the 26.x render layer implied by alpha
  nine-slice    generate a simple bordered GUI panel sprite + matching .mcmeta
  strip         stack frame PNGs into a vertical animation strip + .mcmeta

Examples:
  texture_tools.py ramp --base "#6a8f3c" --steps 5
  texture_tools.py render grid.txt --palette ".=#00000000,a=#2b1d0e,b=#5c3a1a,c=#8a5a2b" -o rune.png
  texture_tools.py preview rune.png --tile
  texture_tools.py check assets/mymod/textures/block/*.png
  texture_tools.py nine-slice -o panel.png --size 32 --border 4 --fill "#c6c6c6" --edge "#373737" --light "#ffffff" --shadow "#555555"
  texture_tools.py strip f0.png f1.png f2.png -o glow.png --frametime 3 --interpolate
"""
import argparse
import colorsys
import json
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.exit("Pillow is required: pip install pillow")


def parse_color(s):
    s = s.strip().lstrip("#")
    if len(s) == 6:
        s += "ff"
    if len(s) != 8:
        raise ValueError(f"bad color: {s!r} (use #rrggbb or #rrggbbaa)")
    return tuple(int(s[i:i + 2], 16) for i in range(0, 8, 2))


def to_hex(rgba):
    return "#" + "".join(f"{c:02x}" for c in rgba[:3]) + ("" if rgba[3] == 255 else f"{rgba[3]:02x}")


def cmd_ramp(a):
    r, g, b, _ = parse_color(a.base)
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    mid = (a.steps - 1) / 2
    out = []
    for i in range(a.steps):
        t = (i - mid) / max(mid, 1)  # -1 (darkest) .. +1 (lightest)
        # Shadows drift toward blue/purple, highlights toward yellow; extremes desaturate a bit.
        hue_target = 0.70 if t < 0 else 0.15
        nh = (h + (hue_target - h) * abs(t) * a.hue_shift) % 1.0
        nl = min(max(l + t * a.spread, 0.04), 0.96)
        ns = min(max(s * (1 - 0.25 * abs(t)), 0.0), 1.0)
        rr, gg, bb = colorsys.hls_to_rgb(nh, nl, ns)
        out.append(to_hex((round(rr * 255), round(gg * 255), round(bb * 255), 255)))
    print(" ".join(out))


def load_palette(spec):
    pal = {}
    for entry in spec.split(","):
        key, val = entry.split("=", 1)
        if len(key) != 1:
            raise ValueError(f"palette keys must be single characters: {key!r}")
        pal[key] = parse_color(val)
    return pal


def cmd_render(a):
    rows = [ln.rstrip("\n") for ln in Path(a.grid).read_text().splitlines() if ln.strip() != ""]
    width = max(len(r) for r in rows)
    pal = load_palette(a.palette)
    img = Image.new("RGBA", (width, len(rows)), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row.ljust(width, ".")):
            if ch not in pal:
                sys.exit(f"char {ch!r} at {x},{y} not in palette")
            img.putpixel((x, y), pal[ch])
    img.save(a.output)
    print(f"wrote {a.output} ({width}x{len(rows)})")


def cmd_preview(a):
    img = Image.open(a.image).convert("RGBA")
    scale = a.scale
    big = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
    bg = Image.new("RGBA", big.size, (60, 60, 60, 255))
    bg.alpha_composite(big)
    out = Path(a.image).with_suffix(".preview.png")
    bg.save(out)
    print(f"wrote {out}")
    if a.tile:
        tile = Image.new("RGBA", (img.width * 3, img.height * 3))
        for ty in range(3):
            for tx in range(3):
                tile.paste(img, (tx * img.width, ty * img.height))
        tile = tile.resize((tile.width * scale // 2, tile.height * scale // 2), Image.NEAREST)
        tout = Path(a.image).with_suffix(".tile.png")
        tile.save(tout)
        print(f"wrote {tout}")


def classify(img):
    hist = img.getchannel("A").histogram()  # 256 buckets of alpha counts
    has_transparent = hist[0] > 0
    has_translucent = sum(hist[1:255]) > 0
    if has_translucent:
        return "TRANSLUCENT"
    if has_transparent:
        return "CUTOUT"
    return "SOLID"


def cmd_check(a):
    ok = True
    for p in a.images:
        img = Image.open(p)
        rgba = img.convert("RGBA")
        w, h = img.size
        pot = (w & (w - 1) == 0) and (h & (h - 1) == 0)
        layer = classify(rgba)
        notes = []
        if img.mode not in ("RGBA", "RGB", "P", "LA", "L"):
            notes.append(f"unusual mode {img.mode}")
        if not pot and not Path(str(p) + ".mcmeta").exists():
            notes.append("not power-of-two (fine for GUI backgrounds, bad for atlas textures)")
        if h > w and h % w == 0 and not Path(str(p) + ".mcmeta").exists():
            notes.append(f"looks like a {h // w}-frame strip but has no .mcmeta animation")
        if layer == "TRANSLUCENT":
            semi = sum(rgba.getchannel("A").histogram()[1:255])
            notes.append(f"{semi} semi-transparent px -> translucent layer; intended?")
        print(f"{p}: {w}x{h} layer={layer}" + (" | " + "; ".join(notes) if notes else ""))
        ok = ok and pot
    return 0 if ok else 1


def cmd_nine_slice(a):
    n, b = a.size, a.border
    img = Image.new("RGBA", (n, n), parse_color(a.fill))
    edge, light, shadow = parse_color(a.edge), parse_color(a.light), parse_color(a.shadow)
    for i in range(n):
        for j in range(n):
            on_outer = i in (0, n - 1) or j in (0, n - 1)
            if on_outer:
                img.putpixel((i, j), edge)
            elif i < b - 1 or j < b - 1:
                img.putpixel((i, j), light)        # top-left bevel
            elif i >= n - b + 1 or j >= n - b + 1:
                img.putpixel((i, j), shadow)       # bottom-right bevel
    # keep the four outer corners transparent for a rounded look
    for c in [(0, 0), (n - 1, 0), (0, n - 1), (n - 1, n - 1)]:
        img.putpixel(c, (0, 0, 0, 0))
    img.save(a.output)
    meta = {"gui": {"scaling": {"type": "nine_slice", "width": n, "height": n, "border": b}}}
    Path(a.output + ".mcmeta").write_text(json.dumps(meta, indent=2) + "\n")
    print(f"wrote {a.output} and {a.output}.mcmeta")


def cmd_strip(a):
    frames = [Image.open(f).convert("RGBA") for f in a.frames]
    w, h = frames[0].size
    if any(f.size != (w, h) for f in frames):
        sys.exit("all frames must be the same size")
    strip = Image.new("RGBA", (w, h * len(frames)))
    for i, f in enumerate(frames):
        strip.paste(f, (0, i * h))
    strip.save(a.output)
    anim = {"frametime": a.frametime}
    if a.interpolate:
        anim["interpolate"] = True
    if w != h:
        anim["width"], anim["height"] = w, h
    Path(a.output + ".mcmeta").write_text(json.dumps({"animation": anim}, indent=2) + "\n")
    print(f"wrote {a.output} ({len(frames)} frames) and .mcmeta")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("ramp"); p.add_argument("--base", required=True)
    p.add_argument("--steps", type=int, default=5); p.add_argument("--spread", type=float, default=0.28)
    p.add_argument("--hue-shift", type=float, default=0.12); p.set_defaults(fn=cmd_ramp)

    p = sub.add_parser("render"); p.add_argument("grid"); p.add_argument("--palette", required=True)
    p.add_argument("-o", "--output", required=True); p.set_defaults(fn=cmd_render)

    p = sub.add_parser("preview"); p.add_argument("image"); p.add_argument("--scale", type=int, default=8)
    p.add_argument("--tile", action="store_true"); p.set_defaults(fn=cmd_preview)

    p = sub.add_parser("check"); p.add_argument("images", nargs="+"); p.set_defaults(fn=cmd_check)

    p = sub.add_parser("nine-slice"); p.add_argument("-o", "--output", required=True)
    p.add_argument("--size", type=int, default=32); p.add_argument("--border", type=int, default=4)
    p.add_argument("--fill", default="#c6c6c6"); p.add_argument("--edge", default="#000000")
    p.add_argument("--light", default="#ffffff"); p.add_argument("--shadow", default="#555555")
    p.set_defaults(fn=cmd_nine_slice)

    p = sub.add_parser("strip"); p.add_argument("frames", nargs="+"); p.add_argument("-o", "--output", required=True)
    p.add_argument("--frametime", type=int, default=2); p.add_argument("--interpolate", action="store_true")
    p.set_defaults(fn=cmd_strip)

    a = ap.parse_args()
    sys.exit(a.fn(a) or 0)


if __name__ == "__main__":
    main()
