package de.wesnik.skyline;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Wetteranzeige: Symbol, Temperatur, darunter Beschreibung und Ort. Gleiche Typografie wie die Uhr
 * (weiß, weicher Schatten, keine Fläche dahinter). Wo und ob sie erscheint, steht in den Einstellungen:
 *   wxshow_<ort> = true|false,  wxpos_<ort> = tl | tr | bl | br     (<ort> = home | window | dream)
 */
final class WeatherBadge extends LinearLayout {

    static final String[] PLACES = {"home", "window", "dream"};
    static final String[] POS = {"tl", "tr", "bl", "br"};

    static boolean defaultShow(String place) { return !place.equals("window"); }
    static String defaultPos(String place) { return place.equals("home") ? "tl" : "bl"; }

    static boolean shown(SharedPreferences p, String place) { return p.getBoolean("wxshow_" + place, defaultShow(place)); }
    static String pos(SharedPreferences p, String place) {
        String v = p.getString("wxpos_" + place, defaultPos(place));
        // Startbildschirm: unten links liegt das Medien-Dock, dort nicht
        if (place.equals("home") && v.equals("bl")) v = "br";
        return v;
    }

    private final Icon icon;
    private final TextView temp, desc;

    WeatherBadge(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        icon = new Icon(c);
        addView(icon, new LayoutParams(dp(40), dp(40)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        temp = text(c, 26, 0xFFFFFFFF, "sans-serif-light");
        desc = text(c, 12, 0xD9FFFFFF, "sans-serif-medium");
        col.addView(temp);
        col.addView(desc);
        LayoutParams cl = new LayoutParams(-2, -2);
        cl.leftMargin = dp(10);
        addView(col, cl);
        setVisibility(GONE);
    }

    private TextView text(Context c, float sp, int color, String family) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        t.setIncludeFontPadding(false);
        t.setPadding(0, dp(1), 0, dp(2));
        t.setFontFeatureSettings("tnum");
        return t;
    }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    boolean hasData() { return now != null; }
    private Weather.Now now;

    void set(Weather.Now n) {
        now = n;
        if (n == null) return;
        temp.setText(Double.isNaN(n.temp) ? "–" : String.format(Locale.GERMANY, "%d°", Math.round(n.temp)));
        String d = Weather.describe(n.code);
        String o = n.ort == null ? "" : n.ort;
        desc.setText(d.isEmpty() ? o : o.isEmpty() ? d : d + " · " + o);
        icon.set(n.code, n.night());
    }

    /** In der Bühne an eine Ecke setzen. Ränder passend zum Platz (Startbildschirm: Uhr, Knöpfe und Regal frei lassen). */
    void place(String place, String pos) {
        boolean top = pos.startsWith("t"), left = pos.endsWith("l");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                (top ? Gravity.TOP : Gravity.BOTTOM) | (left ? Gravity.START : Gravity.END));
        int side = dp(48), v = dp(40);
        if (place.equals("home")) {
            if (top) v = left ? dp(104) : dp(88);          // unter der Uhr bzw. unter den Glasknöpfen
            else v = dp(32 + HomeActivity.SHELF_H + 18);    // über dem Regal
        }
        if (left) lp.leftMargin = side; else lp.rightMargin = side;
        if (top) lp.topMargin = v; else lp.bottomMargin = v;
        setGravity(Gravity.CENTER_VERTICAL);
        setLayoutParams(lp);
    }

    /** Wettersymbol, weiß mit weichem Schatten. */
    static final class Icon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), s = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int code = 0;
        private boolean night;

        Icon(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);   // Schatten auf Pfaden
            float d = c.getResources().getDisplayMetrics().density;
            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(5 * d, 0, d, 0x73000000);
            s.set(p);
            s.setStyle(Paint.Style.STROKE);
            s.setStrokeCap(Paint.Cap.ROUND);
            s.setStrokeWidth(2.2f * d);
        }

        void set(int c, boolean n) { code = c; night = n; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), u = Math.min(w, h) / 40f;
            c.save();
            c.translate((w - 40 * u) / 2, (h - 40 * u) / 2);
            c.scale(u, u);
            s.setStrokeWidth(2.2f);
            p.setShadowLayer(5, 0, 1, 0x73000000);
            s.setShadowLayer(5, 0, 1, 0x73000000);
            int k = kind(code);
            switch (k) {
                case 0: if (night) moon(c, 20, 20, 11); else sun(c, 20, 20, 7.5f, true); break;
                case 1:   // teils bewölkt
                    if (night) moon(c, 25, 13, 7.5f); else sun(c, 25, 13, 5.5f, true);
                    cloud(c, 4, 15, 26);
                    break;
                case 2: cloud(c, 4, 10, 32); break;
                case 3: fog(c); break;
                case 4: cloud(c, 4, 6, 32); drops(c, false); break;
                case 5: cloud(c, 4, 6, 32); drops(c, true); break;
                case 6: cloud(c, 4, 6, 32); bolt(c); break;
            }
            c.restore();
        }

        /** 0 klar, 1 teils, 2 bedeckt, 3 Nebel, 4 Regen, 5 Schnee, 6 Gewitter */
        static int kind(int c) {
            if (c <= 1) return 0;
            if (c == 2) return 1;
            if (c == 3) return 2;
            if (c == 45 || c == 48) return 3;
            if (c >= 95) return 6;
            if ((c >= 71 && c <= 77) || c == 85 || c == 86) return 5;
            return 4;
        }

        private void sun(Canvas c, float cx, float cy, float r, boolean rays) {
            c.drawCircle(cx, cy, r, p);
            if (!rays) return;
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * i / 4;
                float x1 = cx + (float) Math.cos(a) * (r + 3.2f), y1 = cy + (float) Math.sin(a) * (r + 3.2f);
                float x2 = cx + (float) Math.cos(a) * (r + 6.4f), y2 = cy + (float) Math.sin(a) * (r + 6.4f);
                c.drawLine(x1, y1, x2, y2, s);
            }
        }

        private void moon(Canvas c, float cx, float cy, float r) {
            Path m = new Path();
            m.addCircle(cx, cy, r, Path.Direction.CW);
            Path cut = new Path();
            cut.addCircle(cx + r * .55f, cy - r * .45f, r * .85f, Path.Direction.CW);
            m.op(cut, Path.Op.DIFFERENCE);
            c.drawPath(m, p);
        }

        /** Wolke mit linker Kante x, Oberkante y, Breite w (Höhe ≈ 0,55 w). */
        private void cloud(Canvas c, float x, float y, float w) {
            float h = w * .55f, r1 = h * .42f, r2 = h * .56f, r3 = h * .36f;
            Path cl = new Path();
            cl.addRoundRect(new RectF(x, y + h - r1 * 2, x + w, y + h), r1, r1, Path.Direction.CW);
            cl.addCircle(x + w * .36f, y + h - r1 - r2 * .55f, r2, Path.Direction.CW);
            cl.addCircle(x + w * .66f, y + h - r1 - r3 * .45f, r3, Path.Direction.CW);
            // aus mehreren Formen: als Vereinigung zeichnen, damit der Schatten nicht doppelt fällt
            Path u = new Path();
            u.op(cl, new Path(), Path.Op.UNION);
            c.drawPath(u.isEmpty() ? cl : u, p);
        }

        private void drops(Canvas c, boolean snow) {
            float[] xs = {12, 20, 28};
            for (int i = 0; i < 3; i++) {
                float x = xs[i], y = 29 + (i % 2) * 3;
                if (snow) c.drawCircle(x, y + 2, 1.9f, p);
                else c.drawLine(x + 1.2f, y, x - 1.2f, y + 5, s);
            }
        }

        private void bolt(Canvas c) {
            Path b = new Path();
            b.moveTo(21, 26); b.lineTo(15, 33.5f); b.lineTo(19.5f, 33.5f); b.lineTo(17.5f, 39.5f); b.lineTo(25, 31); b.lineTo(20.5f, 31); b.close();
            c.drawPath(b, p);
        }

        private void fog(Canvas c) {
            float[] ys = {12, 19, 26, 33}, x1 = {7, 4, 9, 6}, x2 = {31, 35, 33, 29};
            for (int i = 0; i < 4; i++) c.drawLine(x1[i], ys[i], x2[i], ys[i], s);
        }
    }
}
