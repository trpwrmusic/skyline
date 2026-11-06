package de.wesnik.skyline;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * App-Kachel im tvOS-Stil: 16:9-Banner, Fokus = Anheben (Skalierung + Schatten), kein Rahmen.
 * Der App-Name erscheint nur unter der fokussierten Kachel.
 */
public class Tile extends LinearLayout {

    final String key;            // Paketname oder interne Aktion ("@apps", "@settings")
    final FrameLayout card;
    final ImageView image;
    final TextView label;
    private final View sheen;
    private boolean wiggle;
    Runnable onLong;
    /** Bleibt angehoben, auch ohne Fokus (z. B. solange ihr Kontextmenü offen ist). */
    boolean pinned;
    final boolean showLabel;

    public Tile(Context c, String key, String name, Drawable banner, Drawable icon, int glyphBg, int wDp) {
        super(c);
        this.key = key;
        this.showLabel = banner != null;   // Kacheln ohne Banner tragen den Namen schon selbst
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setClipChildren(false);
        setClipToPadding(false);
        int w = dp(wDp), h = Math.round(w * 9f / 16f);

        card = new FrameLayout(c);
        final float radius = dp(10);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        card.setClipToOutline(true);
        card.setElevation(banner != null ? dp(2) : 0);   // halbtransparente Kacheln: kein Schatten (scheint sonst durch)

        image = new ImageView(c);
        if (banner != null) {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageDrawable(banner);
            card.setBackgroundColor(Color.BLACK);
            card.addView(image, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // Kein Banner: Icon zentriert auf ruhiger Fläche, Name darunter auf der Kachel.
            card.setBackgroundColor(glyphBg);
            LinearLayout inner = new LinearLayout(c);
            inner.setOrientation(VERTICAL);
            inner.setGravity(Gravity.CENTER);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageDrawable(icon);
            inner.addView(image, new LayoutParams(Math.round(h * 0.46f), Math.round(h * 0.46f)));
            TextView t = new TextView(c);
            t.setText(name);
            t.setTextColor(0xF2FFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(dp(8), dp(6), dp(8), 0);
            t.setGravity(Gravity.CENTER);
            inner.addView(t);
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        }

        // dezenter Glanz oben, nur im Fokus sichtbar (Ersatz für tvOS-Spekularlicht)
        sheen = new View(c);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x2EFFFFFF, 0x00FFFFFF}));
        sheen.setAlpha(0f);
        card.addView(sheen, new FrameLayout.LayoutParams(-1, h / 2, Gravity.TOP));
        addView(card, new LayoutParams(w, h));

        label = new TextView(c);
        label.setText(name);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setShadowLayer(dp(5), 0, dp(1), 0xB3000000);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setLineSpacing(dp(2), 1f);
        label.setAlpha(0f);
        LayoutParams lp = new LayoutParams(w, -2);
        lp.topMargin = dp(16);
        addView(label, lp);

        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription(name);
        setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) { focus(f); }
        });
    }

    /** Zweite, leisere Zeile unter dem Namen (z. B. „Im Dock“). */
    void setSubtitle(String name, String sub) {
        if (sub == null) { label.setText(name); return; }
        android.text.SpannableString s = new android.text.SpannableString(name + '\n' + sub);
        int a = name.length() + 1;
        s.setSpan(new android.text.style.RelativeSizeSpan(0.82f), a, s.length(), 0);
        s.setSpan(new android.text.style.ForegroundColorSpan(0xB3FFFFFF), a, s.length(), 0);
        label.setText(s);
    }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    void focus(boolean f) {
        if (!f && pinned) return;
        boolean anim = Stage.animationsEnabled(getContext());
        long d = anim ? 170 : 0;
        float s = f ? 1.14f : 1f;
        card.setPivotX(card.getWidth() / 2f);
        card.setPivotY(card.getHeight());
        card.animate().scaleX(s).scaleY(s).translationZ(f && showLabel ? dp(14) : 0)
                .setInterpolator(new DecelerateInterpolator(1.6f)).setDuration(d).start();
        sheen.animate().alpha(f ? 1f : 0f).setDuration(d).start();
        label.animate().alpha(f && showLabel ? 1f : 0f).translationY(f ? dp(4) : 0).setDuration(d).start();
        if (!f) setWiggle(false);
    }

    /** Verschiebe-Modus: Kachel wackelt leicht (wie tvOS). */
    void setWiggle(boolean on) {
        if (wiggle == on) return;
        wiggle = on;
        card.animate().cancel();
        setTranslationZ(on ? dp(2) : 0);   // bewegte Kachel läuft über den Nachbarn
        if (on) wiggleStep(1); else { if (wiggleAnim != null) { wiggleAnim.cancel(); wiggleAnim = null; } card.setRotation(0); focus(hasFocus()); }
    }

    private android.animation.ObjectAnimator wiggleAnim;

    /** Sanftes, gleichmäßiges Pendeln: ±1,2°, ~0,28 s pro Richtung (bei System-Animationen 0,5x). */
    private void wiggleStep(final int dir) {
        if (!wiggle) return;
        if (!Stage.animationsEnabled(getContext())) { card.setRotation(1.2f); return; }
        card.setRotation(-1.2f);
        wiggleAnim = android.animation.ObjectAnimator.ofFloat(card, View.ROTATION, -1.2f, 1.2f);
        wiggleAnim.setDuration(560);
        wiggleAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        wiggleAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        wiggleAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        wiggleAnim.start();
    }
}
