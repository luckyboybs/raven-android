#!/usr/bin/env python3
"""从 raven-icon-source.png 生成 Android launcher 图标（mipmap 各密度 + 自适应图标）。"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "raven-icon-source.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

BG = (108, 92, 231, 255)  # #6C5CE7

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

PAD_RATIO = 0.08        # 图标四周留白，避免铺满整个方形区域
CORNER_RATIO = 0.22     # 圆角半径（相对画布边长）
ART_RATIO = 0.62        # 图形本体占画布比例
ADAPTIVE_ART_RATIO = 0.66  # 自适应图标安全区比例


def legacy_icon(scale: float, rounded: bool) -> Image.Image:
    size = int(round(48 * scale))
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    pad = int(round(size * PAD_RATIO))
    box = (pad, pad, size - 1 - pad, size - 1 - pad)
    radius = int(round(size * CORNER_RATIO))

    if rounded:
        ImageDraw.Draw(canvas).ellipse(box, fill=BG)
    else:
        ImageDraw.Draw(canvas).rounded_rectangle(box, radius=radius, fill=BG)

    inner = int(round(size * ART_RATIO))
    art = Image.open(SRC).convert("RGBA").resize((inner, inner), Image.LANCZOS)
    canvas.alpha_composite(art, ((size - inner) // 2, (size - inner) // 2))
    return canvas


def adaptive_foreground(scale: float) -> Image.Image:
    size = int(round(108 * scale))
    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(round(size * ADAPTIVE_ART_RATIO))
    art = Image.open(SRC).convert("RGBA").resize((inner, inner), Image.LANCZOS)
    fg.alpha_composite(art, ((size - inner) // 2, (size - inner) // 2))
    return fg


def main() -> None:
    for name, scale in DENSITIES.items():
        out_dir = os.path.join(RES, "mipmap-" + name)
        os.makedirs(out_dir, exist_ok=True)
        legacy_icon(scale, False).save(os.path.join(out_dir, "ic_launcher.png"))
        legacy_icon(scale, True).save(os.path.join(out_dir, "ic_launcher_round.png"))
        adaptive_foreground(scale).save(os.path.join(out_dir, "ic_launcher_foreground.png"))
        print(f"{name}: icon {int(round(48*scale))}px, foreground {int(round(108*scale))}px")

    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        with open(os.path.join(anydpi, name + ".xml"), "w", encoding="utf-8") as f:
            f.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_background" />\n'
                '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />\n'
                '</adaptive-icon>\n'
            )
    print("adaptive icons written to", anydpi)


if __name__ == "__main__":
    main()
