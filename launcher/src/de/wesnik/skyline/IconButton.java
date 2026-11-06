package de.wesnik.skyline;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Runder Glas-Knopf oben rechts. Nur Fläche + Schatten, keine Kontur; Fokus = weiße Fläche, Symbol dunkel, leicht größer. */
public class IconButton extends GlassPanel {

    private final ImageView glyph;
    private final View fill;

    public IconButton(Context c, Drawable icon, String label, int sizePx) {
        super(c, sizePx / 2f);
        setClipToOutline(true);
        setElevation(sizePx / 8f);
        fill = new View(c);
        GradientDrawable o = new GradientDrawable();
        o.setShape(GradientDrawable.OVAL);
        o.setColor(0xF2FFFFFF);
        fill.setBackground(o);
        fill.setAlpha(0f);
        addView(fill, new LayoutParams(-1, -1));
        glyph = new ImageView(c);
        glyph.setImageDrawable(icon);
        glyph.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        int g = Math.round(sizePx * .46f);
        addView(glyph, new LayoutParams(g, g, Gravity.CENTER));
        setFocusable(true);
        setContentDescription(label);
        setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                long d = Stage.animationsEnabled(getContext()) ? 160 : 0;
                animate().scaleX(f ? 1.15f : 1f).scaleY(f ? 1.15f : 1f).setInterpolator(new DecelerateInterpolator(1.6f)).setDuration(d).start();
                fill.animate().alpha(f ? 1f : 0f).setDuration(d).start();
                glyph.setColorFilter(f ? 0xFF111214 : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
            }
        });
    }
}
