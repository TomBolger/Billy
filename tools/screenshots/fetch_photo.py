"""Picks a public-domain/CC0 photo from Wikimedia Commons for the store
screenshots and writes it into the emulator demo assets.

Usage: python fetch_photo.py <out_dir>
Set PHOTO_FILE="File:Something.jpg" to pin a specific Commons file.
Writes app/src/pkjs/emulator/demo_assets.js, <out_dir>/CREDITS.txt and
candidate previews to <out_dir>/candidates/.
"""
import base64
import io
import json
import os
import re
import sys
import urllib.parse
import urllib.request

from PIL import Image

API = "https://commons.wikimedia.org/w/api.php"
UA = {"User-Agent": "BillyStoreScreenshots/1.0 (github.com/TomBolger/Billy)"}
# No people: a stand-in for "your" photo must not show a real person as the user.
QUERIES = [
    "golden retriever lake",
    "dog swimming lake",
    "labrador retriever lake",
    "dog on beach",
    "dog lake",
    "dog swimming",
    "dog kayak",
    "dog hiking",
    "dog park",
    "dog snow",
    "retriever",
    "dog",
]
CAPTION = "Mango, July 2025."
# Chosen from the candidates: US Forest Service photo, public domain.
DEFAULT_FILE = "File:Dog swimming in the Madison River-Custer Gallatin National Forest IMG 071722 (53282918378).jpg"
NOT_PHOTOS = re.compile(r"painting|print|engraving|lithograph|drawing|oil on|\(BM |DPLA|National Trust|poster|illustration|map", re.I)
FREE = re.compile(r"^(cc0|cc-zero|public domain|pd\b|pdm)", re.I)


def get(params):
    url = API + "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
        return json.load(r)


def info_for(titles):
    data = get({
        "action": "query", "format": "json", "titles": "|".join(titles),
        "prop": "imageinfo", "iiprop": "url|size|extmetadata|mime", "iiurlwidth": 800,
        "iiextmetadatafilter": "LicenseShortName|Artist|ImageDescription",
    })
    return list(data.get("query", {}).get("pages", {}).values())


def search(query):
    data = get({"action": "query", "format": "json", "list": "search", "srnamespace": 6,
                "srsearch": query + " filetype:bitmap", "srlimit": 50})
    return [hit["title"] for hit in data.get("query", {}).get("search", [])]


def usable(page):
    ii = (page.get("imageinfo") or [{}])[0]
    meta = ii.get("extmetadata", {})
    lic = meta.get("LicenseShortName", {}).get("value", "")
    if ii.get("mime") != "image/jpeg" or not FREE.match(lic.strip()) or NOT_PHOTOS.search(page["title"]):
        return None
    w, h = ii.get("width", 0), ii.get("height", 0)
    if w < 500 or h < 375 or not (1.0 <= w / max(h, 1) <= 1.9):
        return None
    artist = re.sub("<[^>]+>", "", meta.get("Artist", {}).get("value", "")).strip()
    return {"title": page["title"], "thumb": ii.get("thumburl") or ii["url"], "license": lic,
            "artist": artist or "unknown", "page": ii.get("descriptionurl", "")}


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return Image.open(io.BytesIO(r.read())).convert("RGB")


def crop_4_3(img):
    w, h = img.size
    target = 4 / 3
    if w / h > target:
        nw = int(h * target)
        img = img.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
    else:
        nh = int(w / target)
        top = max(0, int((h - nh) * 0.35))  # keep faces, which sit high
        img = img.crop((0, top, w, top + nh))
    return img.resize((396, 297), Image.LANCZOS)


def main():
    out = sys.argv[1]
    os.makedirs(os.path.join(out, "candidates"), exist_ok=True)
    pinned = os.environ.get("PHOTO_FILE", "").strip() or DEFAULT_FILE
    picks = []
    if pinned:
        picks = [p for p in map(usable, info_for([pinned])) if p]
    else:
        for q in QUERIES:
            titles = search(q)
            print(q, "->", len(titles), "results")
            for i in range(0, len(titles), 25):
                for page in info_for(titles[i:i + 25]):
                    p = usable(page)
                    if p and p["title"] not in [x["title"] for x in picks]:
                        p["query"] = q
                        picks.append(p)
            if len(picks) >= 16:
                break
    print("free picks:", len(picks))
    if not picks:
        sys.exit("No free photo found")
    listing = []
    for n, p in enumerate(picks[:16]):
        try:
            img = crop_4_3(fetch(p["thumb"]))
        except Exception as e:  # noqa: BLE001
            print("skip", p["title"], e)
            continue
        p["image"] = img
        img.save(os.path.join(out, "candidates", "%02d.jpg" % n), quality=88)
        listing.append("%02d  %s  [%s]  by %s  (%s)" % (n, p["title"], p["license"], p["artist"], p.get("query", "pinned")))
    with open(os.path.join(out, "candidates", "LIST.txt"), "w") as f:
        f.write("\n".join(listing) + "\n")
    # Prefer bright, colourful photos: they read best on the watch.
    def colourful(p):
        hsv = p["image"].convert("HSV").resize((64, 48))
        px = list(hsv.getdata())
        return sum(s * v for _, s, v in px) / len(px)
    loaded = [p for p in picks if "image" in p]
    chosen = loaded[0] if pinned else max(loaded, key=colourful)
    buf = io.BytesIO()
    chosen["image"].save(buf, "JPEG", quality=90)
    js = ("// Generated by tools/screenshots/fetch_photo.py for store screenshots only.\n"
          "module.exports = %s;\n" % json.dumps({
              "photo": base64.b64encode(buf.getvalue()).decode(),
              "photoCaption": CAPTION,
          }))
    with open("app/src/pkjs/emulator/demo_assets.js", "w") as f:
        f.write(js)
    with open(os.path.join(out, "CREDITS.txt"), "w") as f:
        f.write("Photo shown in the 'Google Photos' screenshot (a stand-in for the user's own photo):\n")
        f.write("%s\nBy %s, %s, via Wikimedia Commons\n%s\n" % (chosen["title"], chosen["artist"], chosen["license"], chosen["page"]))
    print("Chosen:", chosen["title"], chosen["license"])


if __name__ == "__main__":
    main()
