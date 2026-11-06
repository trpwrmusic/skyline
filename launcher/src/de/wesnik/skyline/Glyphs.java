package de.wesnik.skyline;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Schlichte Symbole für die System-Kacheln (Raster = Alle Apps, Zahnrad = Einstellungen), weiß, ohne Kontur. */
final class Glyphs {

    static Drawable apps(final float dp) {
        return new Base() {
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                float s = Math.min(b.width(), b.height()), cell = s / 3f, gap = cell * .22f, r = cell * .18f;
                float x0 = b.centerX() - s / 2f, y0 = b.centerY() - s / 2f;
                for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
                    float x = x0 + j * cell + gap / 2, y = y0 + i * cell + gap / 2;
                    c.drawRoundRect(x, y, x + cell - gap, y + cell - gap, r, r, p);
                }
            }
        };
    }

    static Drawable gear(final float dp) {
        return new Base() {
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                float cx = b.centerX(), cy = b.centerY(), R = Math.min(b.width(), b.height()) / 2f;
                Path g = new Path();
                g.setFillType(Path.FillType.EVEN_ODD);
                int teeth = 8;
                float outer = R, inner = R * .74f, hole = R * .34f;
                for (int i = 0; i < teeth * 4; i++) {
                    double a = Math.PI * 2 * i / (teeth * 4) - Math.PI / 2;
                    float rr = (i % 4 == 0 || i % 4 == 1) ? outer : inner;
                    float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
                    if (i == 0) g.moveTo(x, y); else g.lineTo(x, y);
                }
                g.close();
                g.addCircle(cx, cy, hole, Path.Direction.CW);
                c.drawPath(g, p);
            }
        };
    }


    /** Fenster-Modus: vier Ecken (Vollbild). */
    static Drawable window() {
        return new Base() {
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                float s = Math.min(b.width(), b.height()), cx = b.centerX(), cy = b.centerY();
                Paint st = new Paint(p);
                st.setStyle(Paint.Style.STROKE);
                st.setStrokeCap(Paint.Cap.ROUND);
                st.setStrokeJoin(Paint.Join.ROUND);
                st.setStrokeWidth(s * .1f);
                float hw = s * .44f, hh = s * .34f, l = s * .2f;
                for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) {
                    Path k = new Path();
                    float x = cx + sx * hw, y = cy + sy * hh;
                    k.moveTo(x - sx * l, y); k.lineTo(x, y); k.lineTo(x, y - sy * l);
                    c.drawPath(k, st);
                }
            }
        };
    }

    /** WLAN: drei Bögen + Punkt. */
    static Drawable wifi() {
        return new Base() {
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                float s = Math.min(b.width(), b.height()), cx = b.centerX(), by = b.centerY() + s * .36f;
                Paint st = new Paint(p);
                st.setStyle(Paint.Style.STROKE);
                st.setStrokeCap(Paint.Cap.ROUND);
                st.setStrokeWidth(s * .1f);
                for (int i = 1; i <= 3; i++) {
                    float r = s * .2f * i + s * .04f;
                    c.drawArc(cx - r, by - r, cx + r, by + r, 225, 90, false, st);
                }
                c.drawCircle(cx, by - s * .02f, s * .07f, p);
            }
        };
    }

    /** Trapezkorrektur: hohler Trapez-Rahmen (schräge Projektionsfläche) mit Mittelpunkt-Kreuz. */
    static Drawable keystone() {
        return new Base() {
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                float s = Math.min(b.width(), b.height()), cx = b.centerX(), cy = b.centerY();
                Path t = new Path();
                t.setFillType(Path.FillType.EVEN_ODD);
                float top = cy - s * .34f, bot = cy + s * .34f;
                t.moveTo(cx - s * .5f, top); t.lineTo(cx + s * .5f, top);
                t.lineTo(cx + s * .32f, bot); t.lineTo(cx - s * .32f, bot); t.close();
                float d = s * .12f, it = top + d, ib = bot - d;
                float k = (s * .18f) / (bot - top);   // Schräge der Seiten
                t.moveTo(cx - s * .5f + d + (it - top) * k, it); t.lineTo(cx + s * .5f - d - (it - top) * k, it);
                t.lineTo(cx + s * .32f - d + (bot - ib) * k * 0, ib); t.lineTo(cx - s * .32f + d, ib); t.close();
                c.drawPath(t, p);
            }
        };
    }


    /** Medien-Symbole: 0 = zurück, 1 = abspielen, 2 = pause, 3 = vor. */
    static Drawable media(final int kind) {
        return new Base() {
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                float s = Math.min(b.width(), b.height()), cx = b.centerX(), cy = b.centerY(), h = s * .34f;
                Path t = new Path();
                if (kind == 1) {
                    t.moveTo(cx - h * .7f, cy - h); t.lineTo(cx + h * .95f, cy); t.lineTo(cx - h * .7f, cy + h); t.close();
                    c.drawPath(t, p);
                } else if (kind == 2) {
                    float w = h * .42f, g = h * .32f;
                    c.drawRoundRect(cx - g - w, cy - h, cx - g, cy + h, w * .3f, w * .3f, p);
                    c.drawRoundRect(cx + g, cy - h, cx + g + w, cy + h, w * .3f, w * .3f, p);
                } else {
                    // ⏮ / ⏭: zwei Dreiecke Spitze an Basis, kleine Lücke, dann Strich (Gesamtbreite ≈ 0,8 s)
                    float d = kind == 0 ? -1 : 1, th = s * .27f, tw = s * .27f, gap = s * .05f, bar = s * .09f;
                    float total = tw * 2 + gap + bar, x0 = cx - d * total / 2;
                    for (int k = 0; k < 2; k++) {
                        float b0 = x0 + d * tw * k;           // Basis
                        t.moveTo(b0, cy - th); t.lineTo(b0 + d * tw, cy); t.lineTo(b0, cy + th); t.close();
                    }
                    c.drawPath(t, p);
                    float bx = x0 + d * (tw * 2 + gap);
                    c.drawRoundRect(Math.min(bx, bx + d * bar), cy - th, Math.max(bx, bx + d * bar), cy + th, bar * .25f, bar * .25f, p);
                }
            }
        };
    }


    /** Runder Pfeil im Uhrzeigersinn (↻); gespiegelt = gegen den Uhrzeigersinn (↺). */
    static Drawable circleArrow() {
        return new Base() {
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                float s = Math.min(b.width(), b.height()), cx = b.centerX(), cy = b.centerY(), r = s * .34f;
                Paint st = new Paint(p);
                st.setStyle(Paint.Style.STROKE);
                st.setStrokeWidth(s * .1f);
                st.setStrokeCap(Paint.Cap.ROUND);
                c.drawArc(cx - r, cy - r, cx + r, cy + r, 10, 290, false, st);   // im Uhrzeigersinn von 10° bis 300°
                double a = Math.toRadians(300);
                float ex = cx + (float) Math.cos(a) * r, ey = cy + (float) Math.sin(a) * r, k = s * .2f;
                float dx = (float) -Math.sin(a), dy = (float) Math.cos(a);   // Laufrichtung (Uhrzeigersinn)
                float nx = (float) Math.cos(a), ny = (float) Math.sin(a);    // nach außen
                Path t = new Path();
                t.moveTo(ex + dx * k * .9f, ey + dy * k * .9f);
                t.lineTo(ex + nx * k * .7f - dx * k * .35f, ey + ny * k * .7f - dy * k * .35f);
                t.lineTo(ex - nx * k * .7f - dx * k * .35f, ey - ny * k * .7f - dy * k * .35f);
                t.close();
                c.drawPath(t, p);
            }
        };
    }

    private abstract static class Base extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Base() { p.setColor(0xFFFFFFFF); }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
