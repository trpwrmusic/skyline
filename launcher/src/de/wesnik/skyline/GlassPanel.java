package de.wesnik.skyline;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * Milchglas-Regal (Entwurf 11). Zeichnet das weichgezeichnete Abbild der Bühne genau an der Stelle,
 * an der das Regal liegt, dunkel getönt (Kontrast über hellem Schnee), mit Lichtkante oben.
 */
public class GlassPanel extends FrameLayout implements Stage.BackdropListener {

    private final float radius;
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final Rect dst = new Rect();
    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallback = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap backdrop;
    private View stage;

    public GlassPanel(Context c, float radiusPx) {
        super(c);
        radius = radiusPx;
        setWillNotDraw(false);
        setClipChildren(true);
        // saturate(1.5) + brightness(.82) wie im Entwurf
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(1.5f);
        ColorMatrix dark = new ColorMatrix();
        dark.setScale(.82f, .82f, .82f, 1f);
        sat.postConcat(dark);
        img.setColorFilter(new ColorMatrixColorFilter(sat));
        fallback.setColor(0x99282A30);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, radiusPx / 22f));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        setClipToOutline(true);
    }

    void attach(Stage s) { stage = s; s.addBackdropListener(this); }

    @Override public void onBackdrop(Bitmap b) { backdrop = b; invalidate(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        box.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        // oben hell (Glas), unten dunkler (Kontrast für Text)
        tint.setShader(new LinearGradient(0, 0, 0, h, 0x1AFFFFFF, 0x5714161C, Shader.TileMode.CLAMP));
        edge.setShader(new LinearGradient(0, 0, 0, h * .35f, 0x8CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas c) {
        c.save();
        c.clipPath(clip);
        if (backdrop != null && stage != null) {
            // Abbild der ganzen Bühne so platzieren, dass der Ausschnitt deckungsgleich unter dem Regal liegt
            int left = 0, top = 0;
            for (View v = this; v != null && v != stage; v = (View) v.getParent()) { left += v.getLeft() - v.getScrollX(); top += v.getTop() - v.getScrollY(); if (!(v.getParent() instanceof View)) break; }
            dst.set(-left, -top, stage.getWidth() - left, stage.getHeight() - top);
            c.drawBitmap(backdrop, null, dst, img);
        } else {
            c.drawRect(box, fallback);
        }
        c.drawRect(box, tint);
        c.restore();
        float e = edge.getStrokeWidth() / 2f;
        c.drawRoundRect(e, e, getWidth() - e, getHeight() - e, radius, radius, edge);
    }
}
