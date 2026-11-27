"""Cut the generated grayscale lobes into padded runtime cells without changing the source artwork."""
import argparse
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("clouds", type=Path)
args = parser.parse_args()
source = Image.open(args.clouds).convert("RGBA")
atlas = Image.new("RGBA", (256, 256))
for i in range(4):
    x, y = i % 2, i // 2
    sprite = source.crop((round(x * source.width / 2), round(y * source.height / 2),
                          round((x + 1) * source.width / 2), round((y + 1) * source.height / 2)))
    mask = sprite.getchannel("A").point(lambda a: 0 if a < 24 else a)
    sprite = Image.merge("RGBA", (sprite.convert("L"),) * 3 + (mask,))
    bounds = mask.getbbox()
    if not bounds:
        raise ValueError(f"Empty cloud cell {i}")
    sprite = sprite.crop(bounds)
    sprite.thumbnail((112, 112), Image.Resampling.NEAREST)
    atlas.alpha_composite(sprite, (x * 128 + (128 - sprite.width) // 2,
                                    y * 128 + (128 - sprite.height) // 2))
target = Path(__file__).resolve().parents[2] / "src/main/resources/assets/fightingarts/textures/effect/awakening_clouds.png"
atlas.save(target)
print(target)
