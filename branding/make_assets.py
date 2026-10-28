"""Regenerates launcher icons / splash / illustration webps from branding/source (python3 + Pillow).
Run from repo root: python3 branding/make_assets.py"""
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageOps
import os
SRC = "branding/source"; RES = "app/src/main/res"
NAVY = (14, 20, 44)

def card(name, k=0.075):
    """Crop away the white/black page border and the drawn rounded frame."""
    im = Image.open(f"{SRC}/{name}").convert("RGB")
    bg = Image.new("RGB", im.size, im.getpixel((2, 2)))
    b = ImageChops.difference(im, bg).convert("L").point(lambda v: 255 if v > 40 else 0).getbbox()
    w, h = b[2] - b[0], b[3] - b[1]
    return im.crop((int(b[0] + w * k), int(b[1] + h * k * .75), int(b[2] - w * k), int(b[3] - h * k * .75)))

def save(im, path, q=86):
    os.makedirs(os.path.dirname(path), exist_ok=True); im.save(path, "WEBP", quality=q, method=6)

c1, c2, c3, c4 = card("tsuki_1_icon.png"), card("tsuki_2_brand.png"), card("tsuki_3_chibi.png"), card("tsuki_4_silhouette.png")
# --- adaptive foreground: square around the face; eyes land on the safe-zone centre line ---
W, H = c1.size; side = W; cy = int(H * 0.47)
fg = c1.crop((0, max(0, cy - side // 2), side, max(0, cy - side // 2) + side))
fg = ImageOps.autocontrast(fg, cutoff=0.5)
dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
# monochrome (themed icon) from #4: luminance of the crescent becomes alpha; silhouette eye kept
W4, H4 = c4.size; s4 = min(W4, H4); m4 = c4.crop(((W4 - s4) // 2, (H4 - s4) // 2, (W4 + s4) // 2, (H4 + s4) // 2))
lum = ImageOps.grayscale(m4).point(lambda v: 0 if v < 70 else min(255, (v - 70) * 2))
red = m4.split()[0]; g = m4.split()[1]
eye = ImageChops.subtract(red, g).point(lambda v: 255 if v > 60 else 0)
alpha = ImageChops.lighter(lum, eye)
# shrink the glyph into the 66/108 safe zone
mono = Image.new("RGBA", m4.size, (255, 255, 255, 0)); mono.putalpha(alpha)
pad = Image.new("RGBA", (int(s4 * 108 / 66), int(s4 * 108 / 66)), (255, 255, 255, 0))
pad.paste(mono, ((pad.width - s4) // 2, (pad.height - s4) // 2), mono)
for d, f in dens.items():
    n = int(108 * f)
    save(fg.resize((n, n), Image.LANCZOS).filter(ImageFilter.UnsharpMask(1, 60, 2)), f"{RES}/mipmap-{d}/ic_launcher_foreground.webp")
    save(pad.resize((n, n), Image.LANCZOS), f"{RES}/mipmap-{d}/ic_launcher_monochrome.webp")
    # legacy (pre-26 fallbacks are not needed with minSdk 26, but launchers that ignore adaptive icons still read these)
    L = int(48 * f); face = fg.crop((int(side * .1), int(side * .1), int(side * .9), int(side * .9))).resize((L, L), Image.LANCZOS)
    mask = Image.new("L", (L * 4, L * 4), 0); ImageDraw.Draw(mask).rounded_rectangle((0, 0, L * 4 - 1, L * 4 - 1), radius=L, fill=255)
    sq = face.copy(); sq.putalpha(mask.resize((L, L), Image.LANCZOS)); save(sq, f"{RES}/mipmap-{d}/ic_launcher.webp")
    mask = Image.new("L", (L * 4, L * 4), 0); ImageDraw.Draw(mask).ellipse((0, 0, L * 4 - 1, L * 4 - 1), fill=255)
    rd = face.copy(); rd.putalpha(mask.resize((L, L), Image.LANCZOS)); save(rd, f"{RES}/mipmap-{d}/ic_launcher_round.webp")
# --- illustrations (single density, nodpi, compressed) ---
def fit(im, w): return im.resize((w, int(im.height * w / im.width)), Image.LANCZOS)
save(fit(c1, 540), f"{RES}/drawable-nodpi/tsuki_portrait.webp", 80)
save(fit(c2, 540), f"{RES}/drawable-nodpi/tsuki_brand.webp", 80)
# 樱 no longer uses the chibi art in-app (pt79); kept as source only.
# save(fit(c3, 540), f"{RES}/drawable-nodpi/tsuki_chibi.webp", 80)
# splash icon: circle crop of the face (Android 12 masks to a circle of 2/3 size)
sp = fg.resize((432, 432), Image.LANCZOS); save(sp, f"{RES}/drawable-nodpi/tsuki_splash.webp", 84)
