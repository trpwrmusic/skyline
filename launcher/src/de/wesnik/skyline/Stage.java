package de.wesnik.skyline;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Bühne (Entwurf 11): Video- oder Foto-Hintergrund, Ortszeile oben links, Uhr oben rechts.
 * Liefert außerdem ein kleines, weichgezeichnetes Abbild des Hintergrunds für die Glasflächen,
 * weil Android 11 keinen Live-Blur kennt.
 *
 * Videos: /sdcard/Android/data/de.wesnik.skyline/files/videos/*.mp4, eigene Fotos: files/wallpapers (+ optional
 * gleichnamige .txt: Zeile 1 = Ort, Zeile 2 = Nachweis, Zeile 3 = Merkmale). Videos und eigene Fotos mischen sich;
 * gibt es keins von beiden, kommen die eingebauten NYC-Fotos.
 */
public class Stage extends FrameLayout implements TextureView.SurfaceTextureListener, Weather.Listener, Media.Listener {

    static final int BW = 240, BH = 135;          // Größe des Glas-Abbilds (1/8 von 1080p)

    interface BackdropListener { void onBackdrop(Bitmap b); }

    static final class Item {
        final String file, place, credit; final boolean external, video;
        final java.util.Set<String> tags = new java.util.HashSet<>();   // z. B. schnee nacht winter (Zeile 3 der .txt)
        Item(String f, String p, String c, boolean ext, boolean vid) { file = f; place = p; credit = c; external = ext; video = vid; }
    }

    private final List<Item> items = new ArrayList<>();
    private int index = -1;
    private boolean hasTags;       // mindestens ein Eintrag hat Wetter-Merkmale: nach Wetter wählen
    private boolean photoVisible;  // ein Foto liegt gerade über dem Video

    // Foto-Ebenen
    private final ImageView[] layers = new ImageView[2];
    private final Bitmap[] bitmaps = new Bitmap[2];
    private int front = 0;

    // Video-Ebene
    private TextureView texture;
    private Surface surface;
    private MediaPlayer player;
    private final Runnable sampler = new Runnable() {
        @Override public void run() { sampleVideo(); if (running) handler.postDelayed(this, 83); }
    };

    // Glas-Abbild
    private Bitmap backdrop, sampleBuf;
    private final List<BackdropListener> listeners = new ArrayList<>();

    private final TextView place, credit, time, date;
    private final LinearLayout captionBox, clockBox;
    private View logoView, top, bottom;
    private final View dim;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long intervalMs;
    private final boolean kenBurns;
    private boolean running;

    private final Runnable advance = new Runnable() {
        @Override public void run() {
            if (items.size() > 1) { if (useWeather()) playBest(true); else next(); }
            handler.postDelayed(this, intervalMs);
        }
    };
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    public Stage(Context ctx, long intervalMs, boolean kenBurns) {
        super(ctx);
        this.intervalMs = intervalMs;
        setBackgroundColor(Color.BLACK);
        loadItems(ctx);
        this.kenBurns = kenBurns && animationsEnabled(ctx);

        // Video ganz unten, darüber zwei Foto-Ebenen (Überblendung); beide gibt es immer, weil sich Videos und Fotos mischen
        texture = new TextureView(ctx);
        texture.setSurfaceTextureListener(this);
        texture.setOpaque(true);
        addView(texture, new LayoutParams(-1, -1));
        for (int i = 0; i < 2; i++) {
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            addView(iv, new LayoutParams(-1, -1));
            layers[i] = iv;
        }
        // Abdunkeln für Übergänge (Video-Wechsel); anfangs schwarz, bis das erste Bild da ist
        dim = new View(ctx);
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(1f);
        addView(dim, new LayoutParams(-1, -1));

        // Verläufe wie im Entwurf: oben für Ort/Uhr, unten leicht für das Regal
        top = new View(ctx);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x73000000, 0x00000000}));
        addView(top, new LayoutParams(-1, dp(120), Gravity.TOP));
        bottom = new View(ctx);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x59000000, 0x00000000}));
        addView(bottom, new LayoutParams(-1, dp(230), Gravity.BOTTOM));

        // Oben links: Uhr, Datum, darunter leise der Ort. Keine Bildnachweise (lokales Projekt).
        clockBox = new LinearLayout(ctx);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        time = text(ctx, 36, 0xFFFFFFFF, "sans-serif-light");
        date = text(ctx, 13, 0xE6FFFFFF, "sans-serif-medium");
        clockBox.addView(time);
        clockBox.addView(date);
        captionBox = new LinearLayout(ctx);
        captionBox.setOrientation(LinearLayout.VERTICAL);
        place = text(ctx, 12, 0xB3FFFFFF, "sans-serif");
        credit = place;   // Nachweise werden nicht mehr angezeigt
        captionBox.addView(place);
        clockBox.addView(captionBox);
        captionBox.setVisibility(GONE);   // Ort wird nicht mehr angezeigt
        // wesnik-Logo oben mittig (weiß, Schatten im Bild für Lesbarkeit über Schnee)
        ImageView logo = new ImageView(ctx);
        logo.setImageResource(R.drawable.wesnik_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setAlpha(.92f);
        LayoutParams ll = new LayoutParams(dp(118), dp(34), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ll.topMargin = dp(31);
        addView(logo, ll);
        logoView = logo;

        LayoutParams kl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        clockBox.setPadding(dp(14), dp(8), dp(14), dp(10));
        kl.leftMargin = dp(48 - 14); kl.topMargin = dp(26 - 8);
        addView(clockBox, kl);

        // Wetteranzeige: über allem, was der Startbildschirm noch dazulegt (Regal 12 dp, große Uhr 40 dp)
        badge = new WeatherBadge(ctx);
        badge.setElevation(dp(30));
        addView(badge, new LayoutParams(-2, -2));
        prefs = ctx.getSharedPreferences("skyline", Context.MODE_PRIVATE);
        applyBadge();

        updateClock();
    }

    // ---------- Wetteranzeige ----------
    private final WeatherBadge badge;
    private final android.content.SharedPreferences prefs;
    private String where = "home";
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefWatch =
            new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(android.content.SharedPreferences p, String k) {
                    if (k != null && k.startsWith("wx")) applyBadge();
                    if ("bg_mode".equals(k) && running) { wxKey = null; choose(true); }   // Zufällig ↔ Nach Wetter: gleich umschalten
                }
            };

    /** Wo läuft die Bühne gerade: home (Startbildschirm), window (Fenster-Modus) oder dream (Bildschirmschoner). */
    public void setPlace(String p) { where = p; applyBadge(); }

    private void applyBadge() {
        boolean on = WeatherBadge.shown(prefs, where) && badge.hasData() && (chromeVisible || !where.equals("home"));
        badge.place(where, WeatherBadge.pos(prefs, where));
        if (on && badge.getVisibility() != VISIBLE) { badge.setAlpha(0f); badge.setVisibility(VISIBLE); }
        badge.animate().cancel();
        badge.animate().alpha(on ? 1f : 0f).setDuration(animationsEnabled(getContext()) ? 300 : 0).withEndAction(new Runnable() {
            @Override public void run() { if (badge.getAlpha() == 0f) badge.setVisibility(GONE); }
        }).start();
    }

    static boolean animationsEnabled(Context c) {
        return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    private TextView text(Context c, float sp, int color, String family) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        t.setIncludeFontPadding(false);
        t.setPadding(0, dp(2), 0, dp(3));
        return t;
    }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---------- Inhalte ----------

    private void loadItems(Context ctx) {
        items.clear();
        hasTags = false;
        for (String sub : new String[]{"videos", "wallpapers"}) {
            File dir = ctx.getExternalFilesDir(sub);
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs == null) continue;
            Arrays.sort(fs);
            for (File f : fs) {
                String n = f.getName();
                if (n.startsWith(".")) continue;                       // halbfertige Uploads
                boolean vid = Media.isVideo(n);
                if (!vid && !Media.isImage(n)) continue;
                String[] m = Media.readMeta(f);
                String p = m[0].isEmpty() ? n.replaceAll("\\.[^.]+$", "").replace('-', ' ').replace('_', ' ') : m[0];
                Item it = new Item(f.getAbsolutePath(), p, m[1], true, vid);
                for (String t : m[2].toLowerCase(Locale.ROOT).split("[\\s,]+")) if (!t.isEmpty()) it.tags.add(t);
                if (!it.tags.isEmpty()) hasTags = true;
                items.add(it);
            }
        }
        if (!items.isEmpty()) { Collections.shuffle(items); return; }
        if (items.isEmpty()) {
            try (InputStream in = ctx.getAssets().open("wallpapers/captions.tsv");
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t");
                    if (p.length >= 3) items.add(new Item("wallpapers/" + p[0], p[1], "Foto: " + p[2], false, false));
                }
            } catch (Exception ignored) { }
        }
        Collections.shuffle(items);
    }

    // ---------- Lebenszyklus ----------

    public void start() {
        if (running) return;
        running = true;
        getContext().registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        TimeSync.start();
        Weather.addListener(this);
        Media.addListener(this);
        prefs.registerOnSharedPreferenceChangeListener(prefWatch);
        if (mediaDirty) { mediaDirty = false; reload(); }
        handler.removeCallbacks(secTick);
        secTick.run();
        String wanted = Media.pendingShow;   // „Jetzt zeigen“ kam, während die Bühne nicht lief
        if (wanted != null) onShow(wanted);
        else if (player != null) { if (prepared) try { player.start(); } catch (Exception ignored) { } }   // sonst startet onPrepared
        else if (index < 0 || (index < items.size() && items.get(index).video && surface != null)) choose(false);
        handler.postDelayed(sampler, 200);
        handler.postDelayed(advance, intervalMs);
    }

    public void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(advance);
        handler.removeCallbacks(sampler);
        handler.removeCallbacks(secTick);
        Weather.removeListener(this);
        Media.removeListener(this);
        prefs.unregisterOnSharedPreferenceChangeListener(prefWatch);
        if (player != null && prepared) try { player.pause(); } catch (Exception ignored) { }
        try { getContext().unregisterReceiver(tick); } catch (Exception ignored) { }
    }

    public void release() {
        stop();
        releasePlayer();
        for (ImageView iv : layers) if (iv != null) iv.setImageDrawable(null);
        for (int i = 0; i < 2; i++) if (bitmaps[i] != null) { bitmaps[i].recycle(); bitmaps[i] = null; }
    }

    private boolean prepared;

    private void releasePlayer() {
        prepared = false;
        if (player != null) { try { player.release(); } catch (Exception ignored) { } player = null; }
    }

    private boolean seconds;
    /** Tickt exakt auf der Atomzeit-Sekunde bzw. -Minute (nicht auf der evtl. falsch gehenden Systemuhr). */
    private final Runnable secTick = new Runnable() {
        @Override public void run() {
            updateClock();
            long n = TimeSync.now();
            handler.postDelayed(this, (seconds ? 1000 - n % 1000 : 60000 - n % 60000) + 5);
        }
    };

    /** Uhr mit Sekunden (an/aus). */
    public boolean toggleSeconds() {
        seconds = !seconds;
        handler.removeCallbacks(secTick);
        secTick.run();
        return seconds;
    }

    public LinearLayout clockView() { return clockBox; }

    static String timePattern(Context c, boolean sec) {
        boolean h24 = DateFormat.is24HourFormat(c);
        return h24 ? (sec ? "HH:mm:ss" : "HH:mm") : (sec ? "h:mm:ss" : "h:mm");
    }

    void updateClock() {
        Date now = new Date(TimeSync.now());
        time.setText(new java.text.SimpleDateFormat(timePattern(getContext(), seconds), Locale.getDefault()).format(now));
        date.setText(new java.text.SimpleDateFormat("EEEE, d. MMMM", Locale.getDefault()).format(now));
    }

    public void addBackdropListener(BackdropListener l) { listeners.add(l); if (backdrop != null) l.onBackdrop(backdrop); }

    private void publish(Bitmap b) { backdrop = b; for (BackdropListener l : listeners) l.onBackdrop(b); }

    // ---------- Wetter: das Fenster zeigt, was draußen ist ----------
    private Weather.Now wx;
    private String wxKey;

    @Override public void onWeather(Weather.Now n) {
        wx = n;
        badge.set(n);
        applyBadge();
        if (!weatherMode() || !hasTags || n.key().equals(wxKey)) return;
        wxKey = n.key();
        playBest(false);
    }

    /** Hintergrund-Auswahl (Einstellungen): „random“ = eigene Motive zufällig gemischt (Standard), „weather“ = nach Merkmalen. */
    static final String MODE_RANDOM = "random", MODE_WEATHER = "weather";

    static String mode(android.content.SharedPreferences p) { return MODE_WEATHER.equals(p.getString("bg_mode", MODE_RANDOM)) ? MODE_WEATHER : MODE_RANDOM; }

    private boolean weatherMode() { return MODE_WEATHER.equals(mode(prefs)); }

    private boolean useWeather() { return weatherMode() && hasTags && wx != null; }

    /** Nächstes Motiv: nach Wetter, wenn Merkmale da sind, sonst der Reihe nach. */
    private void choose(boolean rotate) { if (useWeather()) playBest(rotate); else next(); }

    // ---------- Eigene Medien: nach Upload/Löschen neu einlesen ----------
    private boolean mediaDirty;

    @Override public void onMediaChanged() { if (running) reload(); else mediaDirty = true; }

    @Override public void onShow(String name) {
        if (!running) return;                          // holt start() nach
        Media.pendingShow = null;
        for (int i = 0; i < items.size(); i++) if (new File(items.get(i).file).getName().equals(name)) {
            index = i;
            Item it = items.get(i);
            if (it.video) playVideo(it); else loadPhoto(it);
            return;
        }
    }

    /** Liste neu laden, laufendes Motiv behalten, wenn es noch da ist. */
    private void reload() {
        String cur = index >= 0 && index < items.size() ? items.get(index).file : null;
        loadItems(getContext());
        index = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).file.equals(cur)) index = i;
        if (index < 0) { wxKey = null; choose(false); }
    }

    /** Passendstes Video spielen. rotate: unter gleich guten zum nächsten wechseln (sonst aktuelles behalten, wenn es passt). */
    private void playBest(boolean rotate) {
        if (items.isEmpty() || wx == null) { next(); return; }
        double best = -1;
        List<Integer> top = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            double s = score(items.get(i));
            if (s > best + 1e-6) { best = s; top.clear(); top.add(i); }
            else if (Math.abs(s - best) < 1e-6) top.add(i);
        }
        if (!rotate && top.contains(index) && (player != null || photoVisible)) return;
        if (rotate && top.size() > 1) top.remove(Integer.valueOf(index));
        if (rotate && top.size() == 1 && top.get(0) == index) return;
        index = top.get(new java.util.Random().nextInt(top.size()));
        Item it = items.get(index);
        if (it.video) playVideo(it); else loadPhoto(it);
    }

    private static final String[] WETTER = {"klar", "wolkig", "regen", "schnee"}, ZEIT = {"tag", "daemmerung", "nacht"},
            SAISON = {"fruehling", "sommer", "herbst", "winter"};

    private double score(Item it) {
        return 4 * part(it, WETTER, wx.wetter) + 3 * part(it, ZEIT, wx.zeit) + 2 * part(it, SAISON, wx.saison);
    }

    /** 1 = passt, 0 = passt nicht, Teilpunkte für Nachbarn; ohne Angabe in dieser Gruppe: 0,5. */
    private static double part(Item it, String[] group, String want) {
        boolean any = false; double b = 0;
        for (String g : group) if (it.tags.contains(g)) { any = true; b = Math.max(b, near(g, want)); }
        return any ? b : .5;
    }

    private static double near(String have, String want) {
        if (have.equals(want)) return 1;
        String k = have + ">" + want;
        switch (k) {
            case "klar>wolkig": case "wolkig>klar": return .6;
            case "wolkig>regen": case "regen>wolkig": return .3;
            case "nacht>daemmerung": return .6;
            case "daemmerung>nacht": case "daemmerung>tag": case "tag>daemmerung": return .5;
            case "fruehling>sommer": case "sommer>fruehling": case "herbst>winter": case "fruehling>herbst": case "herbst>fruehling": return .5;
            case "winter>herbst": case "sommer>herbst": case "herbst>sommer": return .3;
            default: return 0;
        }
    }

    String nowPlaying() { return index >= 0 && index < items.size() ? new File(items.get(index).file).getName() : ""; }

    public void next() {
        if (items.isEmpty()) return;
        index = (index + 1) % items.size();
        final Item it = items.get(index);
        if (it.video) playVideo(it); else loadPhoto(it);
    }

    // ---------- Video ----------

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (index < 0) choose(false);
        else if (running && index < items.size() && items.get(index).video) playVideo(items.get(index));   // dasselbe Motiv weiter
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { fitVideo(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        releasePlayer();
        if (surface != null) { surface.release(); surface = null; }
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    private void playVideo(final Item it) {
        if (surface == null) return;
        long fade = animationsEnabled(getContext()) ? 700 : 0;
        dim.animate().cancel();
        dim.animate().alpha(1f).setDuration(player == null && !photoVisible ? 0 : fade).setStartDelay(0).withEndAction(new Runnable() {
            @Override public void run() {
                releasePlayer();
                // ein Foto darüber verschwindet hinter dem Schwarz
                for (ImageView iv : layers) { iv.animate().cancel(); iv.setAlpha(0f); }
                photoVisible = false;
                if (surface == null) return;          // Fläche inzwischen weg (z. B. Bildschirmschoner): beim Wiederkommen neu
                try {
                    final MediaPlayer mp = new MediaPlayer();
                    mp.setSurface(surface);
                    mp.setDataSource(it.file);
                    mp.setLooping(true);
                    mp.setVolume(0f, 0f);
                    mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                        @Override public void onVideoSizeChanged(MediaPlayer m, int w, int h) { videoW = w; videoH = h; fitVideo(); }
                    });
                    mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override public void onPrepared(MediaPlayer m) {
                            if (m != player) return;
                            prepared = true;
                            if (running) m.start(); else { m.start(); m.pause(); }
                            dim.animate().alpha(0f).setDuration(animationsEnabled(getContext()) ? 900 : 0).setStartDelay(150).start();
                        }
                    });
                    mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override public boolean onError(MediaPlayer m, int what, int extra) {
                            if (m == player) {                // kaputt: freigeben und gleich das nächste Video versuchen
                                releasePlayer();
                                handler.postDelayed(new Runnable() { @Override public void run() { if (surface != null && player == null) next(); } }, 1500);
                            }
                            return true;
                        }
                    });
                    player = mp;
                    mp.prepareAsync();
                } catch (Exception e) { player = null; }
                setCaption(it);
            }
        }).start();
    }

    private int videoW, videoH;

    /** Video füllend zuschneiden (TextureView würde sonst verzerren, z. B. bei Handy-Videos im Hochformat). */
    private void fitVideo() {
        int W = texture.getWidth(), H = texture.getHeight();
        if (W == 0 || H == 0 || videoW == 0 || videoH == 0) return;
        float va = videoW / (float) videoH, wa = W / (float) H;
        android.graphics.Matrix m = new android.graphics.Matrix();
        if (va > wa) m.setScale(va / wa, 1f, W / 2f, H / 2f);
        else m.setScale(1f, wa / va, W / 2f, H / 2f);
        texture.setTransform(m);
    }

    /** Kleines Abbild des laufenden Videos ziehen und weichzeichnen (≈12×/s). */
    private void sampleVideo() {
        if (texture == null || !texture.isAvailable() || player == null || photoVisible) return;
        if (sampleBuf == null) sampleBuf = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888);
        texture.getBitmap(sampleBuf);
        Bitmap out = backdrop != null && backdrop != sampleBuf && backdrop.isMutable() ? backdrop : Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888);
        Blur.blurInto(sampleBuf, out, 5);
        publish(out);
    }

    // ---------- Fotos ----------

    private void loadPhoto(final Item p) {
        final int w = getResources().getDisplayMetrics().widthPixels;
        final int h = getResources().getDisplayMetrics().heightPixels;
        new AsyncTask<Void, Void, Bitmap[]>() {
            @Override protected Bitmap[] doInBackground(Void... v) {
                Bitmap b = decode(p, w, h);
                if (b == null) return null;
                Bitmap small = Bitmap.createScaledBitmap(b, BW, BH, true);
                Bitmap blurred = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888);
                Blur.blurInto(small, blurred, 5);
                small.recycle();
                return new Bitmap[]{b, blurred};
            }
            @Override protected void onPostExecute(Bitmap[] r) { if (r != null) { showPhoto(r[0], p); publish(r[1]); } }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private Bitmap decode(Item p, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            open(p, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            Bitmap b = open(p, d);
            return p.external ? rotateByExif(b, p.file) : b;
        } catch (Throwable t) { return null; }
    }

    /** Handy-Fotos liegen oft quer gespeichert und tragen die Drehung nur in den EXIF-Daten. */
    static Bitmap rotateByExif(Bitmap b, String file) {
        if (b == null) return null;
        try {
            int o = new android.media.ExifInterface(file).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            int deg = o == 6 ? 90 : o == 3 ? 180 : o == 8 ? 270 : 0;
            if (deg == 0) return b;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            return r;
        } catch (Exception e) { return b; }
    }

    private Bitmap open(Item p, BitmapFactory.Options o) throws Exception {
        if (p.external) return BitmapFactory.decodeFile(p.file, o);
        try (InputStream in = getContext().getAssets().open(p.file)) { return BitmapFactory.decodeStream(in, null, o); }
    }

    private void showPhoto(Bitmap b, Item p) {
        final int nf = 1 - front;
        final ImageView in = layers[nf], out = layers[front];
        final Bitmap old = bitmaps[nf];
        in.setImageBitmap(b);
        bitmaps[nf] = b;
        if (old != null && old != b) old.recycle();
        in.bringToFront();
        keepOverlaysOnTop();
        in.setAlpha(0f);
        in.setScaleX(1f); in.setScaleY(1f);
        long fade = animationsEnabled(getContext()) ? 1400 : 0;
        photoVisible = true;
        dim.animate().cancel();
        dim.animate().alpha(0f).setStartDelay(0).setDuration(fade).start();
        in.animate().alpha(1f).setDuration(fade).withEndAction(new Runnable() {
            @Override public void run() {
                if (photoVisible) releasePlayer();     // das Video darunter wird nicht mehr gebraucht
                out.animate().cancel();
                out.setAlpha(0f); out.setScaleX(1f); out.setScaleY(1f);
                if (kenBurns) {
                    in.setPivotX(in.getWidth() * (0.3f + (float) Math.random() * 0.4f));
                    in.setPivotY(in.getHeight() * 0.5f);
                    in.animate().scaleX(1.06f).scaleY(1.06f).setDuration(intervalMs)
                            .setInterpolator(new LinearInterpolator()).start();
                }
            }
        }).start();
        front = nf;
        setCaption(p);
    }

    private void keepOverlaysOnTop() {
        List<View> overlays = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c != layers[0] && c != layers[1] && c != texture) overlays.add(c);
        }
        for (View v : overlays) v.bringToFront();
    }

    /** Dateiname des gerade gezeigten eigenen Motivs (für die Medienliste der Fernbedienung). */
    static volatile String playing = "";

    private void setCaption(final Item p) {
        playing = p.external ? new File(p.file).getName() : "";
        long d = animationsEnabled(getContext()) ? 400 : 0;
        captionBox.animate().alpha(0f).setDuration(d).withEndAction(new Runnable() {
            @Override public void run() {
                place.setText(p.place);
                captionBox.animate().alpha(chromeVisible ? 1f : 0f).setDuration(600).start();
            }
        }).start();
    }

    private boolean chromeVisible = true;

    /** Ortszeile und Uhr aus-/einblenden (z. B. unter „Alle Apps“). */
    public void setChromeVisible(boolean v) {
        chromeVisible = v;
        long d = animationsEnabled(getContext()) ? 160 : 0;
        captionBox.animate().alpha(v ? 1f : 0f).setDuration(d).start();
        clockBox.animate().alpha(v ? 1f : 0f).setDuration(d).start();
        if (logoView != null) logoView.animate().alpha(v ? .92f : 0f).setDuration(d).start();
        if (badge != null) applyBadge();
    }

    /** Fenster-Modus: nur das Bild, ohne Uhr, Logo und Verläufe. */
    public void setWindowMode(boolean w) {
        setChromeVisible(!w);
        long d = animationsEnabled(getContext()) ? 450 : 0;
        top.animate().alpha(w ? 0f : 1f).setDuration(d).start();
        bottom.animate().alpha(w ? 0f : 1f).setDuration(d).start();
        if (!where.equals("dream")) setPlace(w ? "window" : "home");
    }

    /** Weitere Ebenen (Regal, Overlays) kommen danach und bleiben oben. */
    public void addForeground(View v, LayoutParams lp) { addView(v, lp); }
}
