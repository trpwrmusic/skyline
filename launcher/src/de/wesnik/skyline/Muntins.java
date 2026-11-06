package de.wesnik.skyline;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Fensterrahmen mit Sprossen über dem Video: aus einem echten Fensterfoto zusammengesetzt
 * (Kloster Heiligengrabe; tools/fenster_compose.py erzeugt je Raster eine 1920×1080-Ebene mit durchsichtigen Scheiben).
 * Farbe per Farbmatrix: Natur (wie fotografiert), Dunkel, Weiß.
 */
public class Muntins extends View {

    /** Spalten × Zeilen; {1,1} = ohne Fenster. */
    static final int[][] LAYOUTS = {{1, 1}, {2, 1}, {2, 2}, {3, 2}, {3, 3}, {4, 3}};
    static final String[] LABELS = {"Ohne", "2 × 1", "2 × 2", "3 × 2", "3 × 3", "4 × 3"};
    static final String[] COLORS = {"Natur", "Dunkel", "Weiß"};
    private static final int[] ART = {0, R.drawable.fenster_2x1, R.drawable.fenster_2x2, R.drawable.fenster_3x2,
            R.drawable.fenster_3x3, R.drawable.fenster_4x3};

    private int layout = 0, tone = 0, loaded = -1;
    private Bitmap art;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();

    public Muntins(Context c) {
        super(c);
        applyTone();
    }

    void setLayout(int index) {
        index = Math.max(0, Math.min(LAYOUTS.length - 1, index));
        if (index != layout) { layout = index; invalidate(); }
    }

    void setTone(int t) {
        t = Math.max(0, Math.min(COLORS.length - 1, t));
        if (t != tone) { tone = t; applyTone(); invalidate(); }
    }

    private void applyTone() {
        ColorMatrix m = new ColorMatrix();
        if (tone == 1) {            // Dunkel: entsättigt, deutlich dunkler (Fenster im abgedunkelten Raum)
            m.setSaturation(.45f);
            ColorMatrix d = new ColorMatrix();
            d.setScale(.42f, .42f, .42f, 1f);
            m.postConcat(d);
        } else if (tone == 2) {     // Weiß: fast ohne Farbe, aufgehellt (weiß lackiert)
            m.setSaturation(.1f);
            ColorMatrix l = new ColorMatrix(new float[]{
                    1.12f, 0, 0, 0, 46,
                    0, 1.12f, 0, 0, 44,
                    0, 0, 1.12f, 0, 40,
                    0, 0, 0, 1, 0});
            m.postConcat(l);
        }
        paint.setColorFilter(tone == 0 ? null : new ColorMatrixColorFilter(m));
    }

    @Override protected void onDraw(Canvas c) {
        if (layout == 0) return;
        if (loaded != layout) {
            if (art != null) art.recycle();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            art = BitmapFactory.decodeResource(getResources(), ART[layout], o);
            loaded = layout;
        }
        if (art == null) return;
        dst.set(0, 0, getWidth(), getHeight());
        c.drawBitmap(art, null, dst, paint);
    }

    /** Kleines Symbol für die Auswahl: die Scheiben als gefüllte Rechtecke. */
    static Drawable glyph(final int index, final int color) {
        return new Drawable() {
            private final Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas c) {
                int[] l = LAYOUTS[index];
                Rect b = getBounds();
                float gap = b.width() * .09f, cw = (b.width() - gap * (l[0] - 1)) / l[0], ch = (b.height() - gap * (l[1] - 1)) / l[1], r = b.width() * .06f;
                gp.setColor(color);
                for (int i = 0; i < l[0]; i++) for (int j = 0; j < l[1]; j++) {
                    float x = b.left + i * (cw + gap), y = b.top + j * (ch + gap);
                    c.drawRoundRect(x, y, x + cw, y + ch, r, r, gp);
                }
            }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(android.graphics.ColorFilter f) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }
}
