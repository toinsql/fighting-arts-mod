"""Fit ImageGen sprites into isolated, consistently padded runtime cells; retain the originals."""
import argparse
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("atlas", type=Path)
parser.add_argument("emblem", type=Path)
args = parser.parse_args()
textures = Path(__file__).resolve().parents[2] / "src/main/resources/assets/fightingarts/textures"
source = Image.open(args.atlas).convert("RGBA")
# Remove nearly invisible fringes and keep the generated palette emerald/violet, without blue halos.
clean = []
for r, g, b, a in source.getdata():
    if a < 18:
        clean.append((0, 0, 0, 0))
    else:
        if b > r * 1.5 and b > g * 1.4:
            r = max(r, round(b * .68))
        clean.append((r, g, b, a))
source.putdata(clean)
atlas = Image.new("RGBA", (512, 512))
for i in range(16):
    x, y = i % 4, i // 4
    box = (round(x * source.width / 4) + 2, round(y * source.height / 4) + 2,
           round((x + 1) * source.width / 4) - 2, round((y + 1) * source.height / 4) - 2)
    sprite = source.crop(box)
    bounds = sprite.getchannel("A").point(lambda a: 255 if a > 24 else 0).getbbox()
    if not bounds:
        raise ValueError(f"Empty VFX cell {i}")
    sprite = sprite.crop(bounds)
    sprite.thumbnail((112, 112), Image.Resampling.NEAREST)
    # All tongues share a baseline, while sparks/ring are centered on both axes.
    top = 120 - sprite.height if i < 12 else (128 - sprite.height) // 2
    atlas.alpha_composite(sprite, (x * 128 + (128 - sprite.width) // 2, y * 128 + top))
atlas.save(textures / "effect/awakening_atlas.png")
emblem = Image.open(args.emblem).convert("RGBA")
emblem.thumbnail((256, 256), Image.Resampling.LANCZOS)
square = Image.new("RGBA", (256, 256))
square.alpha_composite(emblem, ((256 - emblem.width) // 2, (256 - emblem.height) // 2))
square.save(textures / "gui/style_symbol_awakened.png")
print("Prepared 16 isolated animated cells and the awakened crest.")
