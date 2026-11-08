# Builds the README composites from the raw shots in src/.
# Usage: python3 compose.py src .   (needs Pillow)
import sys
from PIL import Image, ImageDraw, ImageFilter
src, out = sys.argv[1], sys.argv[2]

def load(n, t): return Image.open(f"{src}/{n}-{t}.png").convert("RGBA")

def cutout(im, box, r):
    card = im.crop(box)
    m = Image.new("L", card.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, card.size[0]-1, card.size[1]-1), r, fill=255)
    card.putalpha(m)
    return card

def shadowed(card, r, blur=36, dy=18, alpha=70, pad=80):
    w, h = card.size
    c = Image.new("RGBA", (w+2*pad, h+2*pad), (0,0,0,0))
    sh = Image.new("L", c.size, 0)
    ImageDraw.Draw(sh).rounded_rectangle((pad, pad+dy, pad+w, pad+h+dy), r, fill=alpha)
    sh = sh.filter(ImageFilter.GaussianBlur(blur))
    black = Image.new("RGBA", c.size, (0,0,0,255)); black.putalpha(sh)
    c = Image.alpha_composite(c, black)
    c.alpha_composite(card, (pad, pad))
    return c, pad

def scale(im, f): return im.resize((round(im.width*f), round(im.height*f)), Image.LANCZOS)

for t in ("light", "dark"):
    # Mac hero: overview window, menu bar popover floating top-right, call banner bottom-left.
    ov = load("mac-overview", t)                       # 1728x1328, window at ~(64,64)-(1664,1264)
    pop, pp = shadowed(cutout(load("mac-menu-bar", t), (79, 79, 821, 974), 33), 33)
    call, cp = shadowed(cutout(load("mac-call", t), (66, 66, 834, 234), 40), 40)
    pop = scale(pop, 0.92); call = scale(call, 1.0)
    W, H = 2160, 1500
    c = Image.new("RGBA", (W, H), (0,0,0,0))
    c.alpha_composite(ov, (0, 110))
    c.alpha_composite(pop, (W - pop.width, 0))
    c.alpha_composite(call, (220, H - call.height))
    c.save(f"{out}/mac-{t}.png", optimize=True)

    # Android: three phones, equal size and spacing.
    phones = [load(f"android-{n}", t) for n in ("home", "transfers", "settings")]
    pw, ph = 800, max(p.height for p in phones)
    gap = -40   # shadows overlap a little so the screens sit closer
    a = Image.new("RGBA", (3*pw + 2*gap, ph), (0,0,0,0))
    for i, p in enumerate(phones):
        a.alpha_composite(p, (i*(pw+gap), (ph - p.height)//2))
    a.save(f"{out}/android-{t}.png", optimize=True)
