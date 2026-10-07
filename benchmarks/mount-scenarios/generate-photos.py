#!/usr/bin/env python3
"""Writes the 32 synthetic 1080x1350 JPEG photos the scenarios load (needs Pillow)."""
import pathlib
import random

from PIL import Image, ImageDraw, ImageFilter

out = pathlib.Path(__file__).resolve().parent / 'assets' / 'photos'
out.mkdir(parents=True, exist_ok=True)
random.seed(7)
for i in range(1, 33):
    w, h = 1080, 1350
    img = Image.new('RGB', (w, h))
    d = ImageDraw.Draw(img)
    c1 = [random.randint(0, 255) for _ in range(3)]
    c2 = [random.randint(0, 255) for _ in range(3)]
    for y in range(0, h, 6):
        t = y / h
        d.rectangle([0, y, w, y + 6], fill=tuple(int(c1[k] * (1 - t) + c2[k] * t) for k in range(3)))
    for _ in range(60):
        x, y, r = random.randint(0, w), random.randint(0, h), random.randint(20, 220)
        d.ellipse([x - r, y - r, x + r, y + r], fill=tuple(random.randint(0, 255) for _ in range(3)))
    img.filter(ImageFilter.GaussianBlur(2)).save(out / f'p{i:02d}.jpg', quality=85)
