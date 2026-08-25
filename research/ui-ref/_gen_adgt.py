"""Icona ADGENT Trader: solo "ADGT" bianco bold su nero, dentro la safe zone.
Genera ic_launcher_foreground.png a 5 densita + anteprima per il maintainer."""
from PIL import Image, ImageDraw, ImageFont

FONT_B = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
RES = "/work/repo/app/src/main/res"

TEXT = "ADGT"
FRAC = 0.60  # larghezza testo rispetto al canvas (entro safe zone 61%)

def make_foreground(side):
    img = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # trova font per larghezza target
    lo, hi = 10, side * 2
    best = side // 2
    while lo <= hi:
        mid = (lo + hi) // 2
        f = ImageFont.truetype(FONT_B, mid)
        w = d.textlength(TEXT, font=f)
        if w <= side * FRAC:
            best = mid; lo = mid + 1
        else:
            hi = mid - 1
    f = ImageFont.truetype(FONT_B, best)
    d.text((side / 2, side / 2), TEXT, font=f, fill=(255, 255, 255), anchor="mm")
    return img

densities = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
import os
for name, side in densities.items():
    d = f"{RES}/mipmap-{name}"
    os.makedirs(d, exist_ok=True)
    make_foreground(side).save(f"{d}/ic_launcher_foreground.png")
    print("saved", d, side)

# --- anteprima per il maintainer ---
big = make_foreground(864)
def tile(img, side):
    return img.resize((side, side), Image.LANCZOS)
def circle(img, side):
    m = Image.new("L", (side, side), 0)
    ImageDraw.Draw(m).ellipse([0, 0, side, side], fill=255)
    c = img.resize((side, side), Image.LANCZOS).copy()
    c.putalpha(m)
    return c
def rounded(img, side, rad):
    m = Image.new("L", (side, side), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, side, side], radius=rad, fill=255)
    r = img.resize((side, side), Image.LANCZOS).copy()
    r.putalpha(m)
    return r

PW, PH = 945, 1300
page = Image.new("RGB", (PW, PH), (13, 15, 23))
d = ImageDraw.Draw(page)
f_title = ImageFont.truetype(FONT_B, 36)
f_sub = ImageFont.truetype(FONT_B, 26)
f_note = ImageFont.truetype(FONT_B, 20)
d.text((PW//2, 60), "Icona ADGENT Trader — ADGT", font=f_title, fill=(244,245,249), anchor="mm")
d.text((PW//2, 105), "bianco su nero, senza altro", font=f_sub, fill=(154,160,181), anchor="mm")

# composizioni su fondo nero
black = Image.new("RGBA", (864, 864), (0, 0, 0, 255))
comp = Image.alpha_composite(black, big)

t = 340
x0, y0 = 60, 220
for lbl, fn, off in [
    ("quadrato", lambda: rounded(comp, t, int(t*0.22)), 0),
    ("cerchio launcher", lambda: circle(comp, t), 0),
]:
    im = fn()
    page.paste(im, (x0, y0 + off), im)
    d.text((x0 + t//2, y0 + t + 20), lbl, font=f_note, fill=(154,160,181), anchor="mm")
    x0 += t + 70

page.save("/ref/icona-adgt.jpg", "JPEG", quality=92)
print("saved /ref/icona-adgt.jpg")
