#!/usr/bin/env python3
"""Generate MemoMind Cardio launcher icons (heart wearing glasses).

Run with the PIL-enabled venv:
    /workspace/agent/.venv/bin/python tools/gen_icon.py
"""
import math
import os

from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

BG_TOP = (18, 32, 40)
BG_BOTTOM = (8, 15, 20)
HEART = (239, 68, 68)
PULSE = (255, 255, 255)

SS = 4  # supersampling factor


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def rounded_mask(size, radius_ratio, inset=0.0):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    pad = int(size * inset)
    r = int(size * radius_ratio)
    d.rounded_rectangle([pad, pad, size - 1 - pad, size - 1 - pad], radius=r, fill=255)
    return m


def gradient_bg(size):
    img = Image.new("RGB", (size, size), BG_TOP)
    d = ImageDraw.Draw(img)
    for y in range(size):
        d.line([(0, y), (size, y)], fill=lerp(BG_TOP, BG_BOTTOM, y / max(1, size - 1)))
    return img


def heart_points(cx, cy, scale, n=240):
    """Classic heart param curve, normalized so max extent ~= scale."""
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        x = 16 * math.sin(t) ** 3
        y = -(13 * math.cos(t) - 5 * math.cos(2 * t)
              - 2 * math.cos(3 * t) - math.cos(4 * t))
        pts.append((cx + x * scale / 17.0, cy + y * scale / 17.0))
    return pts


def draw_glasses(d, cx, cy, gw, lens_r, frame, lens):
    """Draw a pair of eyeglasses centred at (cx, cy)."""
    lw = max(2, int(lens_r * 0.34))
    sep = gw * 0.30            # half distance between lens centres
    for sign in (-1, 1):
        lx = cx + sign * sep
        box = [lx - lens_r, cy - lens_r, lx + lens_r, cy + lens_r]
        d.ellipse(box, fill=lens)
        d.ellipse(box, outline=frame, width=lw)
    # bridge
    d.line([(cx - sep + lens_r * 0.9, cy), (cx + sep - lens_r * 0.9, cy)],
           fill=frame, width=lw, joint="curve")
    # temples going out and slightly up
    t = gw * 0.10
    for sign in (-1, 1):
        lx = cx + sign * sep + sign * lens_r
        d.line([(lx, cy - lens_r * 0.15), (lx + sign * t, cy - lens_r * 0.55)],
               fill=frame, width=lw, joint="curve")


def draw_art(size, pad_ratio):
    """Draw a heart wearing glasses on a transparent layer sized `size`."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    inner = size * (1 - 2 * pad_ratio)
    cx, cy = size / 2, size / 2

    scale = inner * 0.70
    pts = heart_points(cx, cy + inner * 0.04, scale)
    d.polygon(pts, fill=HEART)

    gcy = cy - inner * 0.02
    gw = inner * 0.52
    lens_r = inner * 0.135
    frame = (255, 255, 255)
    lens = (18, 32, 40, 210)
    draw_glasses(d, cx, gcy, gw, lens_r, frame, lens)
    return img


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG")


def main():
    # Legacy square launcher icon (with rounded background baked in).
    for dpi, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                    ("xxhdpi", 144), ("xxxhdpi", 192)]:
        S = px * SS
        bg = gradient_bg(S)
        art = draw_art(S, 0.14)
        comp = Image.alpha_composite(bg.convert("RGBA"), art)
        mask = rounded_mask(S, 0.22)
        out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        out.paste(comp, (0, 0), mask)
        out = out.resize((px, px), Image.LANCZOS)
        save(out, os.path.join(OUT, f"mipmap-{dpi}", "ic_launcher.png"))
        save(out, os.path.join(OUT, f"mipmap-{dpi}", "ic_launcher_round.png"))

    # Adaptive icon: background (full bleed) + foreground (art in 108dp canvas).
    for dpi, px in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
                    ("xxhdpi", 324), ("xxxhdpi", 432)]:
        S = px * SS
        bg = gradient_bg(S).convert("RGBA")
        save(bg.resize((px, px), Image.LANCZOS),
             os.path.join(OUT, f"mipmap-{dpi}", "ic_launcher_background.png"))
        art = draw_art(S, 0.26)
        save(art.resize((px, px), Image.LANCZOS),
             os.path.join(OUT, f"mipmap-{dpi}", "ic_launcher_foreground.png"))

    # Preview at 512 for quick inspection.
    S = 512 * SS
    bg = gradient_bg(S)
    art = draw_art(S, 0.14)
    comp = Image.alpha_composite(bg.convert("RGBA"), art)
    mask = rounded_mask(S, 0.22)
    prev = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    prev.paste(comp, (0, 0), mask)
    save(prev.resize((512, 512), Image.LANCZOS),
         os.path.join(os.path.dirname(__file__), "..", "icon_preview_512.png"))
    print("icons written to", os.path.abspath(OUT))


if __name__ == "__main__":
    main()
