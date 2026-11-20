"""Extend the home-screen goat art left and down so his neck runs off the round screen."""
import sys
from PIL import Image
src, dst, px, py = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
im = Image.open(src).convert('RGBA')
w, h = im.size
out = Image.new('RGBA', (w + px, h + py), (0, 0, 0, 0))
out.paste(im, (px, 0))
col = [im.getpixel((0, y)) for y in range(h)]
fill_low = col[-1]
for k in range(1, px + 1):
    shift = round(k / 3)            # the neck's top edge slopes down to the left (1 in 3)
    for y in range(h + py):
        sy = y - shift
        p = col[sy] if 0 <= sy < h else (fill_low if sy >= h else (0, 0, 0, 0))
        out.putpixel((px - k, y), p)
last = [out.getpixel((x, h - 1)) for x in range(w + px)]
for y in range(h, h + py):
    for x in range(w + px):
        out.putpixel((x, y), last[x])
out.save(dst)
