"""Google Play store graphics: the 512 px icon and the 1024x500 feature graphic.

Drawn from the same shapes as the launcher icon (res/drawable/ic_launcher_foreground.xml, the
placeholder `cascade-steps` mark) on Forest's ground, with the bundled Sora font. Re-run after the
mark or Forest changes:  python scripts/make_store_graphics.py   (needs Pillow)
Writes docs/store/icon-512.png and docs/store/feature-graphic.png.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "store"
FONT = ROOT / "app" / "src" / "main" / "res" / "font" / "sora.ttf"

# Forest (design/assets/themes.json).
GROUND = "#123524"
TEXT = "#ECF5EE"
TEXT_SECONDARY = "#92B8A0"
# The mark on its 40-unit viewBox: (cx, cy, r, colour), Forest ramp.
MARK = [(9, 9, 4, "#2E8C72"), (20, 20, 5.2, "#93C13C"), (32, 32, 6.6, "#FFC94A")]

SUPERSAMPLE = 4  # drawn large and scaled down, for smooth edges


def draw_mark(draw, left, top, unit):
    for cx, cy, r, colour in MARK:
        x, y, rr = left + cx * unit, top + cy * unit, r * unit
        draw.ellipse((x - rr, y - rr, x + rr, y + rr), fill=colour)


def font(size, weight):
    face = ImageFont.truetype(str(FONT), size)
    try:
        face.set_variation_by_name(weight)
    except (OSError, ValueError):
        pass  # a static font: its own weight
    return face


def icon():
    """Full-bleed square: Play rounds the corners itself. The mark sits as on the launcher tile."""
    size = 512 * SUPERSAMPLE
    image = Image.new("RGB", (size, size), GROUND)
    # Launcher: a 40-unit mark scaled 1.5x at offset 21.3 on a 108 canvas (the dots span 5 to
    # 38.6, so this centres them).
    unit = size / 108 * 1.5
    draw_mark(ImageDraw.Draw(image), size / 108 * 21.3, size / 108 * 21.3, unit)
    return image.resize((512, 512), Image.LANCZOS)


def feature_graphic():
    w, h = 1024 * SUPERSAMPLE, 500 * SUPERSAMPLE
    image = Image.new("RGB", (w, h), GROUND)
    draw = ImageDraw.Draw(image)
    s = SUPERSAMPLE
    # The mark on the left, large, its last step nearly touching the bottom margin.
    unit = 9.5 * s
    draw_mark(draw, 60 * s, (500 * s - 40 * unit) / 2, unit)
    # Name and line on the right, vertically centred as a block.
    title = font(100 * s, "SemiBold")
    line = font(38 * s, "Regular")
    x = 490 * s
    draw.text((x, 170 * s), "Cascade", font=title, fill=TEXT)
    draw.text((x, 300 * s), "Alarm groups that get", font=line, fill=TEXT_SECONDARY)
    draw.text((x, 350 * s), "harder to dismiss", font=line, fill=TEXT_SECONDARY)
    return image.resize((1024, 500), Image.LANCZOS)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    icon().save(OUT / "icon-512.png")
    feature_graphic().save(OUT / "feature-graphic.png")
    print("wrote", OUT / "icon-512.png", "and", OUT / "feature-graphic.png")
