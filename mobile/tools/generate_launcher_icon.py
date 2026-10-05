"""Regenerate the Your Eyes launcher icon from the master logo.

Run from `apps/android`:

    python tools/generate_launcher_icon.py

Inputs
------
`tools/your-eyes-logo-master.png` — the master lockup: the "ye" mark above a
"YOUR EYES" wordmark, drawn on white.

What it does
------------
Crops the mark out of the lockup and drops the wordmark, which is unreadable
below about 96px and is the first thing to go at launcher sizes.

Lifts an alpha channel out of the white ground while keeping the mark's original
RGB. The mark is drawn as overlapping translucent strokes, so a straight
unpremultiply turns its pale mint arm into a low-alpha wash that goes muddy over
anything but white; keeping the RGB and scaling alpha by ALPHA_LIFT preserves
how the logo was drawn.

Grounds it on a white-to-pale-cyan radial rather than a dark fill. Dark grounds
were tried first and rejected: the mark's translucency reads as dark-on-dark and
loses its shape at 48px. The radial exists so the icon still has a visible edge
on a white wallpaper, which flat white does not — it is the whole of the
"frame", and nothing else is added to it.

Outputs
-------
    drawable/ic_launcher_background.png   adaptive background, 432px
    drawable/ic_launcher_foreground.png   adaptive foreground, 432px, mark in safe zone
    drawable/ic_launcher_monochrome.png   themed-icon layer, 432px
    mipmap-{density}/ic_launcher.png      legacy square
    mipmap-{density}/ic_launcher_round.png legacy round
"""

from __future__ import annotations

import os

import numpy as np
from PIL import Image, ImageDraw

RES = os.path.join("app", "src", "main", "res")

# The full-resolution lockup, kept outside `res/` so it is not compiled into the APK.
MASTER = os.path.join("tools", "your-eyes-logo-master.png")

# Bounding box of the "ye" mark inside the master lockup, found by taking every
# chromatic pixel above the wordmark band. Re-derive this if the master changes.
MARK_BOX = (380, 304, 875, 746)

# The pale mint arm sits close to white; without a lift it nearly vanishes once
# the ground is anything but pure white.
ALPHA_LIFT = 1.35

ADAPTIVE_SIZE = 432          # 108dp at xxxhdpi
ADAPTIVE_MARK = 234          # inside the 264px (66dp) safe zone
LEGACY_MARK_RATIO = 0.68     # of the full square, for the legacy bitmaps

GROUND_CENTER = np.array([255, 255, 255])
GROUND_EDGE = np.array([214, 240, 246])

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def load_mark() -> Image.Image:
    """The mark alone, RGB as drawn, with alpha lifted out of the white ground."""
    src = Image.open(MASTER).convert("RGB").crop(MARK_BOX)
    rgb = np.asarray(src).astype(np.float32)
    alpha = np.clip((255.0 - rgb.min(axis=2)) / 255.0 * ALPHA_LIFT, 0.0, 1.0)
    return Image.fromarray(
        np.dstack([rgb, alpha * 255.0]).astype(np.uint8), "RGBA"
    )


def radial_ground(size: int) -> Image.Image:
    y, x = np.mgrid[0:size, 0:size]
    centre = (size - 1) / 2.0
    # /1.42 so the edge colour is reached at the corners, not before them
    t = np.clip(np.sqrt((x - centre) ** 2 + (y - centre) ** 2) / (centre * 1.42), 0, 1)
    rgb = GROUND_CENTER * (1 - t[..., None]) + GROUND_EDGE * t[..., None]
    opaque = np.full((size, size), 255, np.uint8)
    return Image.fromarray(np.dstack([rgb.astype(np.uint8), opaque]), "RGBA")


def fit(mark: Image.Image, box: int) -> Image.Image:
    ratio = min(box / mark.width, box / mark.height)
    return mark.resize(
        (max(1, round(mark.width * ratio)), max(1, round(mark.height * ratio))),
        Image.LANCZOS,
    )


def centred(canvas: Image.Image, layer: Image.Image) -> Image.Image:
    out = canvas.copy()
    out.alpha_composite(
        layer, ((out.width - layer.width) // 2, (out.height - layer.height) // 2)
    )
    return out


def circle_masked(image: Image.Image) -> Image.Image:
    size = image.width
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(image, (0, 0), mask)
    return out


def main() -> None:
    mark = load_mark()
    drawable = os.path.join(RES, "drawable")

    # -- adaptive layers -------------------------------------------------
    radial_ground(ADAPTIVE_SIZE).save(
        os.path.join(drawable, "ic_launcher_background.png")
    )

    foreground = centred(
        Image.new("RGBA", (ADAPTIVE_SIZE, ADAPTIVE_SIZE), (0, 0, 0, 0)),
        fit(mark, ADAPTIVE_MARK),
    )
    foreground.save(os.path.join(drawable, "ic_launcher_foreground.png"))

    # Themed icons are tinted by the system, so only the silhouette survives;
    # a full-alpha version of it keeps the shape from thinning out.
    silhouette = np.asarray(foreground).copy()
    silhouette[..., :3] = 0
    silhouette[..., 3] = np.clip(silhouette[..., 3].astype(np.int16) * 2, 0, 255)
    Image.fromarray(silhouette, "RGBA").save(
        os.path.join(drawable, "ic_launcher_monochrome.png")
    )

    # -- legacy bitmaps --------------------------------------------------
    for density, size in DENSITIES.items():
        square = centred(radial_ground(size), fit(mark, round(size * LEGACY_MARK_RATIO)))
        folder = os.path.join(RES, f"mipmap-{density}")
        square.save(os.path.join(folder, "ic_launcher.png"))
        circle_masked(square).save(os.path.join(folder, "ic_launcher_round.png"))

    print("Wrote adaptive layers and", len(DENSITIES), "legacy densities.")


if __name__ == "__main__":
    main()
