"""Setzt aus einem echten Fensterfoto (Kloster Heiligengrabe, Wikimedia Commons, „Fenster im Dormitorium“ 9972)
Rahmen + Sprossen für beliebige Raster zusammen (1920×1080, Scheiben durchsichtig).
Ausgabe: launcher/res/drawable-nodpi/fenster_CxR.webp   (mit --preview: Vorschau über einem Videobild)

Vorgehen
 1. Glas im Foto ausschneiden (Rechtecke mit gerundeten Kittecken).
 2. „Leerer“ Rahmen: dort, wo im Foto Sprossen in den Rahmen münden (und den Griff), mit glattem Rahmen
    aus derselben Zeile/Spalte überdecken (weiche Übergänge).
 3. Rahmenseiten am Stück übernehmen (natürlicher Helligkeitsverlauf, Bänder bleiben), nur in der Länge gedehnt.
 4. Sprossen als Fotostreifen an die Positionen des Rasters legen: Anschlüsse an den Rahmen, Kreuzungen und
    Kittecken kommen aus dem Foto, die glatten Stücke dazwischen werden gedehnt.
 5. Griff wieder aufsetzen.
"""
import os
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

import os, tempfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Quellfoto: Kloster Heiligengrabe, Wikimedia Commons (nicht im Repo, nach downloads/fenster/ legen)
SRC = os.path.join(ROOT, 'downloads', 'fenster', 'heiligengrabe-9972.jpg')
OUT = os.path.join(ROOT, 'launcher', 'res', 'drawable-nodpi')
PREV = tempfile.gettempdir()
W, H = 1920, 1080
S = 0.25
B = 70                                   # Scheibenrand mit Kitt/Profil, bleibt beim Dehnen erhalten (Quellpixel)

# Glaskanten im Foto
GX = [(658, 1590), (1745, 2700), (2855, 3833)]
GY = [(370, 1652), (1806, 3084)]
X_END, Y_END = 4320, 3620
MX = (1590, 1745)                        # senkrechte Sprosse
MY = (1652, 1806)                        # waagerechte Sprosse

photo = Image.open(SRC).convert('RGB')
mask = Image.new('L', photo.size, 255)
md = ImageDraw.Draw(mask)
for x0, x1 in GX:
    for y0, y1 in GY:
        md.rounded_rectangle((x0 - 6, y0 - 6, x1 + 6, y1 + 7), radius=26, fill=0)
mask = mask.filter(ImageFilter.GaussianBlur(1.2))
rgba = photo.copy()
rgba.putalpha(mask)


def feather_mask(w, h, f):
    m = Image.new('L', (w, h), 0)
    ImageDraw.Draw(m).rectangle((f, f, w - f - 1, h - f - 1), fill=255)
    return m.filter(ImageFilter.GaussianBlur(f / 2))


def patch(img, dst, src_xy, f=24):
    """Bereich dst mit gleich großem Bereich ab src_xy überdecken, weich eingeblendet."""
    x0, y0, x1, y1 = dst
    piece = img.crop((src_xy[0], src_xy[1], src_xy[0] + x1 - x0, src_xy[1] + y1 - y0))
    base = img.crop(dst)
    img.paste(Image.composite(piece, base, feather_mask(x1 - x0, y1 - y0, f)), (x0, y0))


# 2. leerer Rahmen
plain = rgba.copy()
for jx in (MX[0], 2700):                                            # Sprossen münden oben/unten
    patch(plain, (jx - 50, 230, jx + 205, 430), (2000, 230))
    patch(plain, (jx - 50, 3020, jx + 205, 3230), (2000, 3020))
patch(plain, (480, 1590, 720, 1870), (480, 1350))                   # links: waagerechte Sprosse mündet
patch(plain, (3780, 1480, X_END, 1930), (3780, 1020))               # rechts: Sprosse + Griff

ft, fb = round(GY[0][0] * S), round((Y_END - GY[-1][1]) * S)
fl, fr = round(GX[0][0] * S), round((X_END - GX[-1][1]) * S)
mw, mh = round((MX[1] - MX[0]) * S), round((MY[1] - MY[0]) * S)
bd = round(B * S)


def spans_resize(src_img, box_other, spans, vertical, level=False):
    """Streifen aus Spannen zusammensetzen. vertical: Spannen laufen entlang y (Breite = box_other).
    level: gedehnte Mittelstücke (jede 4. Spanne ab Index 2) in der Helligkeit an die Nachbarn angleichen."""
    a0, a1 = box_other
    thick = round((a1 - a0) * S)
    pieces = []
    for s0, s1, d0, d1 in spans:
        if d1 <= d0 or s1 <= s0:
            pieces.append(None); continue
        if vertical:
            pc = src_img.crop((a0, s0, a1, s1)).resize((thick, d1 - d0), Image.LANCZOS)
            arr = np.asarray(pc).astype(np.float32)                 # (len, thick, 4)
        else:
            pc = src_img.crop((s0, a0, s1, a1)).resize((d1 - d0, thick), Image.LANCZOS)
            arr = np.asarray(pc).astype(np.float32).transpose(1, 0, 2)
        pieces.append(arr)
    if level:
        for k in range(2, len(pieces) - 1, 4):
            P, prev, nxt = pieces[k], pieces[k - 1], pieces[k + 1]
            if P is None or prev is None or nxt is None or len(P) < 2:
                continue
            def edge(a, first):
                rows = a[:3] if first else a[-3:]
                return rows[..., :3].mean(0), rows[..., 3].mean(0)
            pe, pa = edge(prev, False); ne, na = edge(nxt, True)
            s0e, _ = edge(P, True); s1e, _ = edge(P, False)
            g0 = np.clip((pe + 1) / (s0e + 1), .6, 1.6); g1 = np.clip((ne + 1) / (s1e + 1), .6, 1.6)
            g0[pa < 128] = 1; g1[na < 128] = 1
            from scipy.ndimage import gaussian_filter1d
            g0 = gaussian_filter1d(g0, 2, axis=0); g1 = gaussian_filter1d(g1, 2, axis=0)
            t = np.linspace(0, 1, len(P))[:, None, None]
            P[..., :3] = np.clip(P[..., :3] * (g0[None] * (1 - t) + g1[None] * t), 0, 255)
    total = H if vertical else W
    out = np.zeros((total, thick, 4), np.float32)
    for (s0, s1, d0, d1), arr in zip(spans, pieces):
        if arr is None:
            continue
        a = arr[..., 3:4] / 255.
        out[d0:d1] = arr * a + out[d0:d1] * (1 - a)
        out[d0:d1, :, 3] = arr[..., 3] + out[d0:d1, :, 3] * (1 - a[..., 0])
    if not vertical:
        out = out.transpose(1, 0, 2)
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), 'RGBA')


def panes(n, start, end, gap):
    size = (end - start - (n - 1) * gap) / n
    return [(round(start + i * (size + gap)), round(start + i * (size + gap) + size)) for i in range(n)]


def muntin_spans(cells, glass, mpos, clean, end_a, end_b, total):
    """Spannen entlang einer Sprosse: Anschluss a, je Scheibe Rand/Mitte/Rand, Kreuzungen, Anschluss b."""
    sp = [(end_a[0], end_a[1], cells[0][0] - round((end_a[1] - end_a[0]) * S), cells[0][0])]
    for i, (p0, p1) in enumerate(cells):
        top = glass[0][0] if i == 0 else glass[-1][0]
        bot = glass[-1][1] if i == len(cells) - 1 else glass[0][1]
        sp.append((top, top + B, p0, p0 + bd))
        sp.append((clean[0], clean[1], p0 + bd, p1 - bd))
        sp.append((bot - B, bot, p1 - bd, p1))
        if i < len(cells) - 1:
            sp.append((mpos[0], mpos[1], p1, cells[i + 1][0]))
    sp.append((end_b[0], end_b[1], cells[-1][1], cells[-1][1] + round((end_b[1] - end_b[0]) * S)))
    return sp


def fade_ends(strip, vertical, a_len, b_len):
    """Enden eines Sprossenstreifens (die in den Rahmen reichen) weich ausblenden."""
    arr = np.asarray(strip).astype(np.float32).copy()
    n = arr.shape[0] if vertical else arr.shape[1]
    ramp = np.ones(n, np.float32)
    nz = np.nonzero((arr[..., 3] > 0).any(axis=1 if vertical else 0))[0]
    if len(nz):
        a, b = nz[0], nz[-1]
        la, lb = max(1, int(a_len * .55)), max(1, int(b_len * .55))
        ramp[a:a + la] = np.linspace(0, 1, la)
        ramp[b - lb + 1:b + 1] = np.linspace(1, 0, lb)
    if vertical:
        arr[..., 3] *= ramp[:, None]
    else:
        arr[..., 3] *= ramp[None, :]
    return Image.fromarray(arr.astype(np.uint8), 'RGBA')


def compose(cols, rows):
    out = Image.new('RGBA', (W, H))
    # 3. Rahmenseiten am Stück
    L = spans_resize(plain, (0, GX[0][0]), [(0, GY[0][0], 0, ft), (GY[0][0], GY[-1][1], ft, H - fb), (GY[-1][1], Y_END, H - fb, H)], True)
    R = spans_resize(plain, (GX[-1][1], X_END), [(0, GY[0][0], 0, ft), (GY[0][0], GY[-1][1], ft, H - fb), (GY[-1][1], Y_END, H - fb, H)], True)
    T = spans_resize(plain, (0, GY[0][0]), [(GX[0][0], GX[-1][1], fl, W - fr)], False)
    Bt = spans_resize(plain, (GY[-1][1], Y_END), [(GX[0][0], GX[-1][1], fl, W - fr)], False)
    out.alpha_composite(T, (0, 0)); out.alpha_composite(Bt, (0, H - fb))
    out.alpha_composite(L, (0, 0)); out.alpha_composite(R, (W - R.width, 0))

    cx = panes(cols, fl, W - fr, mw)
    cy = panes(rows, ft, H - fb, mh)
    # 4a. waagerechte Sprossen (Band y 1610..1850 um die Sprosse, mit Kittecken)
    band_y = (MY[0] - 42, MY[1] + 44)
    xs = muntin_spans(cx, GX, MX, (1900, 2550), (GX[0][0] - 118, GX[0][0]), (GX[-1][1], GX[-1][1] + 96), W)
    # rechts verdeckt im Foto der Griff den Sprossenanschluss: gespiegelten linken Anschluss einsetzen
    src_h = rgba.copy()
    jl = rgba.crop((GX[0][0] - 96, band_y[0], GX[0][0], band_y[1])).transpose(Image.FLIP_LEFT_RIGHT)
    src_h.paste(plain.crop((GX[-1][1], band_y[0], X_END, band_y[1])), (GX[-1][1], band_y[0]))
    src_h.paste(jl, (GX[-1][1], band_y[0]))
    jb = rgba.crop((GX[0][0], band_y[0], GX[0][0] + B, band_y[1])).transpose(Image.FLIP_LEFT_RIGHT)   # Scheibenrand davor ebenso
    src_h.paste(jb, (GX[-1][1] - B, band_y[0]))
    hstrip = fade_ends(spans_resize(src_h, band_y, xs, False, level=True), False, round(118 * S), round(96 * S))
    for j in range(rows - 1):
        y = cy[j][1] - round((MY[0] - band_y[0]) * S)
        out.alpha_composite(hstrip, (0, y))
    # 4b. senkrechte Sprossen
    band_x = (MX[0] - 40, MX[1] + 40)
    ys = muntin_spans(cy, GY, MY, (2200, 2800), (GY[0][0] - 90, GY[0][0]), (GY[-1][1], GY[-1][1] + 116), H)
    vstrip = fade_ends(spans_resize(rgba, band_x, ys, True, level=True), True, round(90 * S), round(116 * S))
    for i in range(cols - 1):
        x = cx[i][1] - round((MX[0] - band_x[0]) * S)
        out.alpha_composite(vstrip, (x, 0))
    # 5. Griff (rechts, auf Höhe der ersten waagerechten Sprosse, sonst mittig)
    lb = (3870, 1520, 4310, 1900)
    latch = rgba.crop(lb)
    latch = latch.resize((round(latch.width * S), round(latch.height * S)), Image.LANCZOS)
    latch.putalpha(feather_mask(latch.width, latch.height, 10))
    gy = (cy[0][1] + mh // 2) if rows > 1 else H // 2
    out.alpha_composite(latch, (W - fr + round((lb[0] - GX[-1][1]) * S), gy - round((MY[0] + (MY[1] - MY[0]) / 2 - lb[1]) * S)))
    return out


if __name__ == '__main__':
    preview = '--preview' in sys.argv
    for c, r in [(2, 1), (2, 2), (3, 2), (3, 3), (4, 3)]:
        im = compose(c, r)
        if preview:
            bg = Image.open(r'C:\dev\xgimi-home\shots\120-fenster.png').convert('RGBA')
            bg.alpha_composite(im)
            bg.convert('RGB').save(os.path.join(PREV, f'fenster_{c}x{r}.jpg'), quality=88)
        else:
            im.save(os.path.join(OUT, f'fenster_{c}x{r}.webp'), 'WEBP', quality=90, method=6)
    print('ok')
