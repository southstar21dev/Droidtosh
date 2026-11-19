# Builds the README composites from the raw shots in src/.
# Usage: python3 compose.py src .   (needs Pillow)
#
# Raw shots come straight from the apps' renderers:
#   Mac:     Droidtosh.app/Contents/MacOS/Droidtosh --render-snapshots <dir>  (Debug build)
#            main-home -> mac-overview, popover -> mac-menu-bar, call-ringing -> mac-call
#   Android: ./gradlew recordRoborazziDebug, from app/build/outputs/roborazzi/
#            home, files, find_phone -> android-home, android-files, android-find-phone
import sys
from PIL import Image, ImageDraw, ImageFilter
src, out = sys.argv[1], sys.argv[2]

def load(n, t): return Image.open(f"{src}/{n}-{t}.png").convert("RGBA")

def rounded(im, r):
    m = Image.new("L", im.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, im.size[0]-1, im.size[1]-1), r, fill=255)
    im = im.copy(); im.putalpha(m)
    return im

def panel_box(im, jump=14, span=4):
    # The floating panels are rendered on a backdrop with a soft shadow; the panel's
    # edge is the first sharp step in brightness (over a few pixels, since the edge
    # is antialiased) coming in from each side.
    g = im.convert("L"); w, h = g.size; cy, cx = h // 2, w // 2
    def edge(points):
        seen = []
        for i, p in points:
            v = g.getpixel(p)
            if len(seen) >= span and abs(v - seen[-span]) > jump: return i
            seen.append(v)
        raise ValueError("no panel edge")
    l = edge((x, (x, cy)) for x in range(w))
    r = edge((x, (x, cy)) for x in range(w-1, -1, -1))
    t = edge((y, (cx, y)) for y in range(h))
    b = edge((y, (cx, y)) for y in range(h-1, -1, -1))
    return (l, t, r+1, b+1)

def shadowed(card, r, blur=36, dy=18, alpha=70, pad=80):
    w, h = card.size
    c = Image.new("RGBA", (w+2*pad, h+2*pad), (0,0,0,0))
    sh = Image.new("L", c.size, 0)
    ImageDraw.Draw(sh).rounded_rectangle((pad, pad+dy, pad+w, pad+h+dy), r, fill=alpha)
    sh = sh.filter(ImageFilter.GaussianBlur(blur))
    black = Image.new("RGBA", c.size, (0,0,0,255)); black.putalpha(sh)
    c = Image.alpha_composite(c, black)
    c.alpha_composite(card, (pad, pad))
    return c

def scale(im, f): return im.resize((round(im.width*f), round(im.height*f)), Image.LANCZOS)

def phone(im):
    # Roborazzi renders the whole scroll height with the navigation bar pinned at the
    # bottom. Keep a phone-shaped top and put the navigation bar back under it.
    w, h = im.size; content_bg = im.getpixel((4, h // 3))
    nav_top = h - 1
    while nav_top > 0 and im.getpixel((4, nav_top - 1)) != content_bg: nav_top -= 1
    nav = im.crop((0, nav_top, w, h))
    screen_h = round(w * 19.5 / 9)
    body = im.crop((0, 0, w, screen_h - nav.height))
    s = Image.new("RGBA", (w, screen_h)); s.alpha_composite(body, (0, 0)); s.alpha_composite(nav, (0, body.height))
    return rounded(s, 96)

for t in ("light", "dark"):
    # Mac hero: main window, menu bar popover floating top-right, call panel bottom-left.
    ov = shadowed(rounded(load("mac-overview", t), 26), 26)
    pm = load("mac-menu-bar", t); pop = shadowed(rounded(pm.crop(panel_box(pm)), 28), 28)
    cm = load("mac-call", t); call = shadowed(rounded(cm.crop(panel_box(cm)), 36), 36)
    ov = scale(ov, 0.82); pop = scale(pop, 0.92)
    W, H = 2160, 1500
    c = Image.new("RGBA", (W, H), (0,0,0,0))
    c.alpha_composite(ov, (0, 90))
    c.alpha_composite(pop, (W - pop.width, 0))
    c.alpha_composite(call, (160, H - call.height))
    c.save(f"{out}/mac-{t}.png", optimize=True)

    # Android: three phones, equal size and spacing.
    phones = [shadowed(scale(phone(load(f"android-{n}", t)), 640/1233), 62) for n in ("home", "files", "find-phone")]
    pw, ph = phones[0].width, max(p.height for p in phones)
    gap = -100   # shadows overlap a little so the screens sit closer
    a = Image.new("RGBA", (3*pw + 2*gap, ph), (0,0,0,0))
    for i, p in enumerate(phones):
        a.alpha_composite(p, (i*(pw+gap), (ph - p.height)//2))
    a.save(f"{out}/android-{t}.png", optimize=True)
