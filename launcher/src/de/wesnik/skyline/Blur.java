package de.wesnik.skyline;

import android.graphics.Bitmap;

/** Schnelle Annäherung an einen Gauß-Weichzeichner: 3× Box-Blur horizontal + vertikal, für kleine Bitmaps (240×135). */
final class Blur {
    private static int[] a, b;

    static synchronized void blurInto(Bitmap src, Bitmap dst, int r) {
        int w = src.getWidth(), h = src.getHeight(), n = w * h;
        if (a == null || a.length < n) { a = new int[n]; b = new int[n]; }
        src.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(a, b, w, h, r, true);
            box(b, a, w, h, r, false);
        }
        dst.setPixels(a, 0, w, 0, 0, w, h);
    }

    /** Box-Blur entlang einer Achse mit Randwiederholung. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horiz) {
        int len = horiz ? w : h, lines = horiz ? h : w;
        int step = horiz ? 1 : w, lineStep = horiz ? w : 1;
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = l * lineStep;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + clamp(i, len) * step];
                sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * step] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int pOut = in[base + clamp(i - r, len) * step];
                int pIn = in[base + clamp(i + r + 1, len) * step];
                sr += ((pIn >> 16) & 255) - ((pOut >> 16) & 255);
                sg += ((pIn >> 8) & 255) - ((pOut >> 8) & 255);
                sb += (pIn & 255) - (pOut & 255);
            }
        }
    }

    private static int clamp(int i, int len) { return i < 0 ? 0 : (i >= len ? len - 1 : i); }
}
