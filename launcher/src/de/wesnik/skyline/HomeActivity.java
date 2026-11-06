package de.wesnik.skyline;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HomeActivity extends Activity {

    /** Startbelegung = bisherige „Lieblings-Apps“-Zeile des Stock-Launchers, gleiche Reihenfolge. */
    static final String[] DEFAULT_DOCK = {
            "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv", "de.swr.avp.ard.tv",
            "com.zdf.android.mediathek", "tv.emby.embyatv", "com.tvwebbrowser.v22", "com.xgimi.filemanager",
            "com.google.android.youtube.tvmusic", "de.prosiebensat1digital.seventv", "com.android.vending",
            "com.spotify.tv.android", "com.netflix.ninja"};
    /** System-Einträge, die in „Alle Apps“ nicht auftauchen (bleiben installiert und aktiv). */
    static final java.util.Set<String> HIDDEN = new java.util.HashSet<>(Arrays.asList(
            "com.android.camera2", "com.mediatek.wwtv.webview", "com.xgimi.uiconfiguration", "com.xgimi.upgrade"));
    static final String EXTRA_REMOTE = "remote_action";
    static final String APPS = "@apps", SETTINGS = "@settings";
    static final long WALLPAPER_INTERVAL = 4 * 60 * 1000L;

    private Stage stage;
    private LinearLayout dockRow;
    private HorizontalScrollView dockScroll;
    private GlassPanel shelf;
    private LinearLayout topIcons;
    private View lastDock;
    private MediaDock media;
    private FrameLayout.LayoutParams hintLp;
    private LinearLayout clock;
    private FrameLayout bigClock;
    private TextView bigTime, bigDate, bigSync;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable bigTick = new Runnable() {
        @Override public void run() {
            java.util.Date d = new java.util.Date(TimeSync.now());
            bigTime.setText(new java.text.SimpleDateFormat(Stage.timePattern(HomeActivity.this, true), java.util.Locale.getDefault()).format(d));
            bigDate.setText(new java.text.SimpleDateFormat("EEEE, d. MMMM", java.util.Locale.getDefault()).format(d));
            bigSync.setText(TimeSync.synced()
                    ? String.format(java.util.Locale.GERMANY, "Atomzeit · PTB Braunschweig · Beamer-Uhr korrigiert um %+d ms (Messgenauigkeit ±%d ms)", TimeSync.offsetMs, Math.max(1, TimeSync.delayMs / 2))
                    : "Systemzeit · Abgleich mit der Atomuhr läuft …");
            ui.postDelayed(this, 1000 - TimeSync.now() % 1000 + 5);
        }
    };
    private TextView focusName, focusSub;
    static final int SHELF_H = 122;
    private FrameLayout appsLayer;
    private GridLayout appsGrid;
    private TextView hint;
    private SharedPreferences prefs;
    private final List<String> dock = new ArrayList<>();
    private final Map<String, App> apps = new LinkedHashMap<>();
    private final Map<String, Drawable[]> artCache = new HashMap<>();
    private Tile moving;            // Kachel im Verschiebe-Modus
    private boolean appsDirty = true;
    private boolean firstResume = true;

    static final class App {
        final String pkg, name; final Intent launch; final ActivityInfo info;
        App(String p, String n, Intent l, ActivityInfo i) { pkg = p; name = n; launch = l; info = i; }
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsDirty = true;
            if (i.getData() != null) artCache.remove(i.getData().getSchemeSpecificPart());
            loadApps(); buildDock(currentFocusKey());
            if (appsLayer.getVisibility() == View.VISIBLE) buildGrid(null);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("skyline", MODE_PRIVATE);
        createdDensity = getResources().getConfiguration().densityDpi;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        Weather.start(this);
        stage = new Stage(this, WALLPAPER_INTERVAL, false);
        setContentView(stage);
        stage.setClipChildren(false);

        // Dock
        dockScroll = new HorizontalScrollView(this) {
            /** Fokus bleibt in Slot 2–4: links und rechts bleibt immer ein Nachbar sichtbar (tvOS-Verhalten). */
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
                if (getChildCount() == 0) return 0;
                int slot = dp(162), pad = dp(28), x = getScrollX();
                int lo = pad + slot, hi = pad + 3 * slot;
                int target = x;
                if (r.left - x > hi) target = r.left - hi;
                else if (r.left - x < lo) target = r.left - lo;
                int max = Math.max(0, getChildAt(0).getWidth() - getWidth());
                target = Math.max(0, Math.min(max, target));
                return target - x;
            }
        };
        dockScroll.setHorizontalScrollBarEnabled(false);
        dockScroll.setFillViewport(false);
        dockScroll.setClipChildren(false);
        dockScroll.setClipToPadding(false);
        dockScroll.setSmoothScrollingEnabled(true);
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(28), dp(22), dp(28), 0);
        dockScroll.addView(dockRow, new FrameLayout.LayoutParams(-2, -2));

        // Milchglas-Regal (Entwurf 11), ohne Namenszeile: flacher. Die Scroll-Spur ist unten 30 dp länger als das Regal
        // und das Regal schneidet nichts ab – so fällt der Kachelschatten nach unten über die Glaskante.
        // Fenstersprossen (nur im Fenster-Modus sichtbar), über dem Video, unter allem anderen
        muntins = new Muntins(this);
        muntins.setLayout(prefs.getInt("muntins", 0));
        muntins.setTone(prefs.getInt("muntinTone", 0));
        muntins.setAlpha(0f);
        muntins.setVisibility(View.GONE);
        stage.addForeground(muntins, new FrameLayout.LayoutParams(-1, -1));

        shelf = new GlassPanel(this, dp(22));
        shelf.setElevation(dp(12));
        shelf.setClipToOutline(false);
        shelf.setClipChildren(true);     // schneidet die Spur an IHREN Grenzen (die unten 30 dp über das Regal hinausgehen)
        shelf.addView(dockScroll, new FrameLayout.LayoutParams(-1, dp(SHELF_H + 30), Gravity.TOP));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, dp(SHELF_H), Gravity.BOTTOM);
        sl.leftMargin = dp(32); sl.rightMargin = dp(32); sl.bottomMargin = dp(32);
        stage.addForeground(shelf, sl);
        shelf.attach(stage);

        // „Läuft gerade“: kleines Glasdock links über dem App-Dock
        media = new MediaDock(this, dp(18));
        media.attach(stage);
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(dp(390), dp(68), Gravity.BOTTOM | Gravity.START);
        ml.leftMargin = dp(32); ml.bottomMargin = dp(32 + SHELF_H + 14);
        stage.addForeground(media, ml);
        View.OnKeyListener mediaKeys = new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                View[] b = {media.info, media.prev, media.play, media.next};
                int i = v == media.info ? 0 : v == media.prev ? 1 : v == media.play ? 2 : 3;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { if (i > 0) b[i - 1].requestFocus(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { if (i < 3) b[i + 1].requestFocus(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_UP) { clock.requestFocus(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) { focusDock(); return true; }
                return false;
            }
        };
        media.info.setOnKeyListener(mediaKeys); media.prev.setOnKeyListener(mediaKeys); media.play.setOnKeyListener(mediaKeys); media.next.setOnKeyListener(mediaKeys);
        media.setHost(new MediaDock.Host() {
            @Override public void onMediaVisibility(boolean v) {
                if (!v && media.hasFocus()) focusDock();
                applyUpTargets();
                if (hintLp != null) { hintLp.bottomMargin = dp(32 + SHELF_H + 14 + (v ? 68 + 12 : 0)); hint.setLayoutParams(hintLp); }
            }
            @Override public void onSeek(long ms, View anchor) { showSeek(ms, anchor); }
        });

        // Oben rechts: Schnellzugriffe als runde Glasknöpfe
        topIcons = new LinearLayout(this);
        topIcons.setOrientation(LinearLayout.HORIZONTAL);
        topIcons.setClipChildren(false);
        topIcons.setClipToPadding(false);
        topIcons.setPadding(dp(8), dp(8), dp(8), dp(8));
        addTopIcon(Glyphs.window(), "Fenster-Modus", new Runnable() { @Override public void run() { enterWindow(); } });
        addTopIcon(Glyphs.wifi(), "WLAN", new Intent(Settings.ACTION_WIFI_SETTINGS));
        addTopIcon(Glyphs.keystone(), "Trapezkorrektur", new Intent("com.xgimi.windowsystem.kst"));
        addTopIcon(Glyphs.gear(dp(1)), "Einstellungen", new Intent(Settings.ACTION_SETTINGS));
        // Fokus bleibt in der Knopfreihe; runter = zurück zur zuletzt gewählten App im Dock
        for (int i = 0; i < topIcons.getChildCount(); i++) {
            View ib = topIcons.getChildAt(i);
            ib.setId(View.generateViewId());
            ib.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int code, KeyEvent e) {
                    if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                    int idx = topIcons.indexOfChild(v);
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) { if (idx > 0) topIcons.getChildAt(idx - 1).requestFocus(); else clock.requestFocus(); return true; }
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { if (idx < topIcons.getChildCount() - 1) topIcons.getChildAt(idx + 1).requestFocus(); return true; }
                    if (code == KeyEvent.KEYCODE_DPAD_UP) return true;
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                        View t = lastDock != null && lastDock.getParent() == dockRow ? lastDock : dockRow.getChildAt(0);
                        if (t != null) t.requestFocus();
                        return true;
                    }
                    return false;
                }
            });
        }
        dockRow.getViewTreeObserver().addOnGlobalFocusChangeListener(new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override public void onGlobalFocusChanged(View o, View n) { if (n != null && n.getParent() == dockRow) lastDock = n; }
        });
        // Uhr oben links: per Fernbedienung erreichbar. OK = Sekunden an/aus, lang OK = große Uhr
        clock = stage.clockView();
        clock.setFocusable(true);
        clock.setId(View.generateViewId());
        final android.graphics.drawable.GradientDrawable clockBg = new android.graphics.drawable.GradientDrawable();
        clockBg.setCornerRadius(dp(16));
        clockBg.setColor(0x00FFFFFF);
        clock.setBackground(clockBg);
        clock.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                clockBg.setColor(f ? 0x40FFFFFF : 0x00FFFFFF);
                v.setPivotX(0); v.setPivotY(v.getHeight() / 2f);
                v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(Stage.animationsEnabled(HomeActivity.this) ? 160 : 0).start();
            }
        });
        clock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stage.toggleSeconds(); }
        });
        clock.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { topIcons.getChildAt(0).requestFocus(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP) return true;
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (media.isShown2()) media.play.requestFocus(); else focusDock();
                    return true;
                }
                return false;
            }
        });

        bigClock = new FrameLayout(this);
        bigClock.setBackgroundColor(0xCC000000);
        bigClock.setElevation(dp(40));   // über Regal und Knöpfen (die haben Elevation)
        bigClock.setVisibility(View.GONE);
        LinearLayout bc = new LinearLayout(this);
        bc.setOrientation(LinearLayout.VERTICAL);
        bc.setGravity(Gravity.CENTER_HORIZONTAL);
        bigTime = new TextView(this);
        bigTime.setTextColor(0xFFFFFFFF);
        bigTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 150);
        bigTime.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        bigTime.setFontFeatureSettings("tnum");
        bigTime.setIncludeFontPadding(false);
        bigTime.setShadowLayer(dp(14), 0, dp(2), 0x80000000);
        bigDate = new TextView(this);
        bigDate.setTextColor(0xCCFFFFFF);
        bigDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        bigDate.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        bigDate.setPadding(0, dp(10), 0, 0);
        bc.addView(bigTime);
        bigDate.setGravity(Gravity.CENTER);
        bc.addView(bigDate, new LinearLayout.LayoutParams(-1, -2));
        bigSync = new TextView(this);
        bigSync.setTextColor(0x8CFFFFFF);
        bigSync.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bigSync.setGravity(Gravity.CENTER);
        bigSync.setPadding(0, dp(18), 0, 0);
        bc.addView(bigSync, new LinearLayout.LayoutParams(-1, -2));
        bigClock.addView(bc, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        tl.rightMargin = dp(40); tl.topMargin = dp(22);
        stage.addForeground(topIcons, tl);

        // Hinweiszeile (Verschiebe-Modus)
        hint = new TextView(this);
        hint.setTextColor(0xD9FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hint.setShadowLayer(dp(5), 0, dp(1), 0xB3000000);
        hint.setAlpha(0f);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        hl.leftMargin = dp(48); hl.bottomMargin = dp(32 + SHELF_H + 14);
        hintLp = hl;
        stage.addForeground(hint, hl);

        // Alle Apps (Ebene über dem Hintergrund)
        appsLayer = new FrameLayout(this);
        appsLayer.setBackgroundColor(0xEB0E0F12);
        appsLayer.setVisibility(View.GONE);
        // Überschrift scrollt mit dem Raster (wie tvOS), statt dass Kacheln darunter durchlaufen.
        final ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(0, 0, 0, dp(40));
        TextView title = new TextView(this);
        title.setText("Apps");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(56), dp(30), dp(56), 0);
        FrameLayout headRow = new FrameLayout(this);
        headRow.addView(title);
        LinearLayout fbox = new LinearLayout(this);
        fbox.setOrientation(LinearLayout.VERTICAL);
        fbox.setGravity(Gravity.END);
        focusName = new TextView(this);
        focusName.setTextColor(0xFFFFFFFF);
        focusName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        focusName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        focusSub = new TextView(this);
        focusSub.setTextColor(0xB3FFFFFF);
        focusSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fbox.addView(focusName);
        fbox.addView(focusSub);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
        fl.rightMargin = dp(56);
        headRow.addView(fbox, fl);
        col.addView(headRow);
        TextView sub = new TextView(this);
        sub.setText("OK öffnet die App · OK lange drücken für weitere Optionen");
        sub.setTextColor(0xB3FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(dp(56), dp(6), dp(56), 0);
        col.addView(sub);
        appsGrid = new GridLayout(this);
        appsGrid.setColumnCount(5);
        appsGrid.setClipChildren(false);
        appsGrid.setClipToPadding(false);
        appsGrid.setPadding(dp(56), dp(38), dp(56), dp(20));
        col.addView(appsGrid);
        sv.addView(col);
        appsLayer.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        // Erste Reihe fokussiert: ganz nach oben, damit die Überschrift wieder sichtbar ist.
        sv.getViewTreeObserver().addOnGlobalFocusChangeListener(new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override public void onGlobalFocusChanged(View o, View n) {
                if (n != null && n.getParent() == appsGrid && appsGrid.indexOfChild(n) < appsGrid.getColumnCount()) sv.smoothScrollTo(0, 0);
                if (n instanceof Tile && n.getParent() == appsGrid) markInDock((Tile) n);
            }
        });
        stage.addForeground(appsLayer, new FrameLayout.LayoutParams(-1, -1));
        stage.addForeground(bigClock, new FrameLayout.LayoutParams(-1, -1));

        startForegroundService(new Intent(this, RemoteService.class));
        loadDock();
        loadApps();
        buildDock(null);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    /** Startbildschirm ist gerade vorne (Selbst-Update öffnet ihn danach wieder). */
    static volatile boolean resumed;

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        restoreDensity();
        final String upd = Updater.announce(this);
        if (upd != null) ui.postDelayed(new Runnable() { @Override public void run() { flash(upd, 6000); } }, 1500);
        stage.start();
        media.start();
        if (firstResume && dockRow.getChildCount() > 0) {
            firstResume = false;
            dockRow.post(new Runnable() { @Override public void run() { dockScroll.scrollTo(0, 0); dockRow.getChildAt(0).requestFocus(); } });
        }
    }

    @Override protected void onPause() {
        super.onPause(); resumed = false; exitWindow(false); stage.stop(); media.stop(); endMove(); closeMenu(false);
        if (bigClock.getVisibility() == View.VISIBLE) {
            bigClock.setVisibility(View.GONE);
            ui.removeCallbacks(bigTick);
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        stage.release();
        super.onDestroy();
    }

    /** HOME erneut gedrückt, während wir schon vorne sind: zurück zum Anfang des Docks. */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        endMove();
        closeMenu(false);
        String act = i.getStringExtra(EXTRA_REMOTE);
        if (act != null) { remoteAction(act); return; }
        if (windowMode) { exitWindow(true); return; }
        if (appsLayer.getVisibility() == View.VISIBLE) hideApps();
        else if (dockRow.getChildCount() > 0) { dockScroll.smoothScrollTo(0, 0); dockRow.getChildAt(0).requestFocus(); }
    }

    @Override public void onBackPressed() {
        if (moving != null) { endMove(); return; }
        if (menu != null) { closeMenu(true); return; }
        if (appsLayer.getVisibility() == View.VISIBLE) { hideApps(); return; }
        // Home-Screen: Zurück tut nichts (wie tvOS)
    }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---------- Daten ----------

    private void loadDock() {
        dock.clear();
        String s = prefs.getString("dock", null);
        if (s == null) dock.addAll(Arrays.asList(DEFAULT_DOCK));
        else if (!s.isEmpty()) dock.addAll(Arrays.asList(s.split(",")));
    }

    private void saveDock() { prefs.edit().putString("dock", TextUtils.join(",", dock)).apply(); }

    private void loadApps() {
        if (!appsDirty) return;
        appsDirty = false;
        apps.clear();
        PackageManager pm = getPackageManager();
        List<ResolveInfo> all = new ArrayList<>();
        all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        for (ResolveInfo r : all) {
            String p = r.activityInfo.packageName;
            if (p.equals(getPackageName()) || HIDDEN.contains(p) || apps.containsKey(p)) continue;
            Intent l = pm.getLeanbackLaunchIntentForPackage(p);
            if (l == null) l = pm.getLaunchIntentForPackage(p);
            if (l == null) continue;
            l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            apps.put(p, new App(p, String.valueOf(r.loadLabel(pm)), l, r.activityInfo));
        }
    }

    private Drawable[] art(App a) {
        Drawable[] d = artCache.get(a.pkg);
        if (d != null) return d;
        PackageManager pm = getPackageManager();
        Drawable banner = null, icon = null;
        try { banner = a.info.loadBanner(pm); } catch (Exception ignored) { }
        if (banner == null) try { banner = pm.getApplicationBanner(a.pkg); } catch (Exception ignored) { }
        if (banner == null && Helper.TABLET_APPS.contains(a.pkg) && a.pkg.startsWith("com.netflix")) banner = getDrawable(R.drawable.banner_netflix);
        if (banner == null) try { icon = a.info.loadIcon(pm); } catch (Exception ignored) { }
        d = new Drawable[]{banner, icon};
        artCache.put(a.pkg, d);
        return d;
    }

    // ---------- Dock ----------

    private String currentFocusKey() {
        View f = getCurrentFocus();
        return f instanceof Tile ? ((Tile) f).key : null;
    }

    /** Ersatz im Dock: fehlt die alte App, rückt die neue an ihre Stelle (Netflix TV -> Netflix-Handy-App). */
    private static final String[][] DOCK_REPLACE = {{"com.netflix.ninja", "com.netflix.mediaclient"}};

    private void buildDock(String focusKey) {
        for (String[] r : DOCK_REPLACE) {
            int i = dock.indexOf(r[0]);
            if (i >= 0 && apps.get(r[0]) == null && apps.get(r[1]) != null && !dock.contains(r[1])) { dock.set(i, r[1]); saveDock(); }
        }
        dockRow.removeAllViews();
        for (String p : dock) {
            App a = apps.get(p);
            if (a == null) continue;               // deinstalliert/deaktiviert: still überspringen, Eintrag bleibt
            addDockTile(tileFor(a, 140));
        }
        addDockTile(actionTile(APPS, "Alle Apps", Glyphs.apps(dp(1)), 0x38FFFFFF));
        pinDockEdges();
        applyUpTargets();
        if (focusKey != null) for (int i = 0; i < dockRow.getChildCount(); i++) {
            Tile t = (Tile) dockRow.getChildAt(i);
            if (t.key.equals(focusKey)) { t.requestFocus(); return; }
        }
    }

    private void showBigClock() {
        bigClock.bringToFront();
        bigClock.setAlpha(0f);
        bigClock.setVisibility(View.VISIBLE);
        bigClock.animate().alpha(1f).setDuration(Stage.animationsEnabled(this) ? 250 : 0).start();
        ui.removeCallbacks(bigTick);
        bigTick.run();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // kein Bildschirmschoner während Countdowns
    }

    private void hideBigClock() {
        ui.removeCallbacks(bigTick);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bigClock.animate().alpha(0f).setDuration(Stage.animationsEnabled(this) ? 180 : 0).withEndAction(new Runnable() {
            @Override public void run() { bigClock.setVisibility(View.GONE); }
        }).start();
        clock.requestFocus();
    }

    /** Enden des Docks: links/rechts bleibt im Dock. Nach jedem Umsortieren neu setzen (sonst behält die alte erste Kachel die Sperre). */
    private void pinDockEdges() {
        int n = dockRow.getChildCount();
        if (n == 0) return;
        for (int i = 0; i < n; i++) {
            View c = dockRow.getChildAt(i);
            if (c.getId() == View.NO_ID) c.setId(View.generateViewId());
            c.setNextFocusLeftId(i == 0 ? c.getId() : View.NO_ID);
            c.setNextFocusRightId(i == n - 1 ? c.getId() : View.NO_ID);
        }
    }

    private GlassPanel seekPill;
    private ImageView seekArrow;
    private TextView seekText;
    private final Runnable seekHide = new Runnable() {
        @Override public void run() { if (seekPill != null) seekPill.animate().alpha(0f).translationY(dp(6)).setDuration(220).start(); }
    };

    private void showSeek(long ms, View anchor) {
        if (seekPill == null) {
            seekPill = new GlassPanel(this, dp(18));
            seekPill.attach(stage);
            seekPill.setElevation(dp(14));
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(10), 0, dp(14), 0);
            seekArrow = new ImageView(this);
            seekArrow.setImageDrawable(Glyphs.circleArrow());
            r.addView(seekArrow, new LinearLayout.LayoutParams(dp(22), dp(22)));
            seekText = new TextView(this);
            seekText.setTextColor(0xFFFFFFFF);
            seekText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            seekText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            seekText.setPadding(dp(7), 0, 0, dp(1));
            r.addView(seekText);
            seekPill.addView(r, new FrameLayout.LayoutParams(-2, -1));
            seekPill.setAlpha(0f);
            stage.addForeground(seekPill, new FrameLayout.LayoutParams(-2, dp(36), Gravity.BOTTOM | Gravity.START));
        }
        boolean back = ms < 0;
        seekText.setText(back ? "−10" : "+10");
        seekArrow.setScaleX(back ? -1f : 1f);                 // gespiegelt = gegen den Uhrzeigersinn
        // über dem gedrückten Knopf zentrieren
        int[] a = new int[2], st = new int[2];
        anchor.getLocationInWindow(a); stage.getLocationInWindow(st);
        seekPill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.makeMeasureSpec(dp(36), View.MeasureSpec.EXACTLY));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) seekPill.getLayoutParams();
        lp.leftMargin = Math.max(dp(16), a[0] - st[0] + anchor.getWidth() / 2 - seekPill.getMeasuredWidth() / 2);
        lp.bottomMargin = dp(32 + SHELF_H + 14 + 68 + 10);
        seekPill.setLayoutParams(lp);
        seekPill.bringToFront();
        ui.removeCallbacks(seekHide);
        seekPill.animate().cancel();
        seekPill.setTranslationY(dp(6));
        seekPill.animate().alpha(1f).translationY(0).setDuration(160).start();
        seekArrow.animate().cancel();
        seekArrow.setRotation(0);
        seekArrow.animate().rotation(back ? -360f : 360f).setDuration(480)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
        ui.postDelayed(seekHide, 1100);
    }

    private void focusDock() {
        View t = lastDock != null && lastDock.getParent() == dockRow ? lastDock : dockRow.getChildAt(0);
        if (t != null) t.requestFocus();
    }

    /** Hoch aus dem Dock: zum Medien-Dock, wenn es sichtbar ist (sonst normale Richtungssuche). */
    private void applyUpTargets() {
        int up = media != null && media.isShown2() ? media.play.getId() : View.NO_ID;
        for (int i = 0; i < dockRow.getChildCount(); i++) dockRow.getChildAt(i).setNextFocusUpId(up);
    }

    /** Aktionen von der Handy-Fernbedienung. */
    private void remoteAction(String a) {
        if (windowMode && !"window".equals(a)) exitWindow(false);
        switch (a) {
            case "bigclock": if (bigClock.getVisibility() == View.VISIBLE) hideBigClock(); else showBigClock(); break;
            case "seconds": stage.toggleSeconds(); break;
            case "nextvideo": stage.next(); break;
            case "apps": if (appsLayer.getVisibility() != View.VISIBLE) showApps(); break;
            case "window": if (windowMode) exitWindow(true); else { if (appsLayer.getVisibility() == View.VISIBLE) hideApps(); enterWindow(); } break;
            default: if (appsLayer.getVisibility() == View.VISIBLE) hideApps(); break;   // "home"
        }
    }

    private void addTopIcon(android.graphics.drawable.Drawable icon, String label, final Intent intent) {
        addTopIcon(icon, label, new Runnable() { @Override public void run() { startSafe(new Intent(intent)); } });
    }

    private void addTopIcon(android.graphics.drawable.Drawable icon, String label, final Runnable run) {
        IconButton b = new IconButton(this, icon, label, dp(44));
        b.attach(stage);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { run.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.leftMargin = dp(14);
        topIcons.addView(b, lp);
    }

    private void addDockTile(Tile t) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(22);
        t.label.setVisibility(View.GONE);
        dockRow.addView(t, lp);
    }

    private Tile tileFor(final App a, int wDp) {
        Drawable[] d = art(a);
        final Tile t = new Tile(this, a.pkg, a.name, d[0], d[1], 0x40FFFFFF, wDp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (moving != null) { endMove(); return; }
                launch(a);
            }
        });
        t.setLongClickable(false);
        t.onLong = new Runnable() {
            @Override public void run() { if (t.getParent() == dockRow) startMove(t); else openMenu(a, t); }
        };
        return t;
    }

    private Tile actionTile(final String key, String name, Drawable icon, int bg) {
        Tile t = new Tile(this, key, name, null, icon, bg, 140);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (moving != null) { endMove(); return; }
                if (APPS.equals(key)) showApps();
                else startSafe(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        return t;
    }

    private void launch(final App a) {
        if (!Helper.TABLET_APPS.contains(a.pkg)) { startSafe(a.launch); return; }
        // Handy-App mit Tablet-Ansicht: erst Dichte senken, dann starten (zurück auf Home stellt sie wieder her)
        new Thread(new Runnable() {
            @Override public void run() {
                Helper.sendSync("density " + Helper.TABLET_DENSITY);
                ui.post(new Runnable() { @Override public void run() { startSafe(a.launch); } });
            }
        }).start();
    }

    private int createdDensity;

    /** Zurück auf Home: eine für Handy-Apps gesenkte Dichte wieder auf den Gerätewert setzen. */
    private void restoreDensity() {
        // DENSITY_DEVICE_STABLE taugt hier nicht: startet der Prozess unter gesenkter Dichte, hält er die für „normal“
        if (getResources().getConfiguration().densityDpi != Helper.TABLET_DENSITY) return;
        new Thread(new Runnable() {
            @Override public void run() {   // Helfer startet nach einem Update erst nach ein paar Sekunden: bis zu 20 s wiederholen
                for (int i = 0; i < 40; i++) {
                    if ("ok".equals(Helper.sendSync("density reset"))) return;
                    try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                }
            }
        }, "density-reset").start();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        // Andere Dichte als beim Aufbau (z. B. nach Netflix): Oberfläche neu aufbauen, sonst stimmen die Größen nicht
        if (createdDensity != c.densityDpi) recreate();
    }

    private void startSafe(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
        catch (ActivityNotFoundException | SecurityException e) { flash("Lässt sich gerade nicht öffnen"); }
    }

    // ---------- Verschiebe-Modus ----------

    private void startMove(Tile t) {
        endMove();
        moving = t;
        t.setWiggle(true);
        showHint("◀ ▶ verschieben  ·  ▼ aus dem Dock nehmen  ·  OK fertig");
    }

    private void endMove() {
        if (moving == null) return;
        moving.setWiggle(false);
        moving = null;
        hideHint();
        saveDock();
    }

    private boolean centerDown, longFired;

    // ---------- Fenster-Modus: nur das Video, wie ein Blick aus dem Fenster ----------
    private boolean windowMode;
    private View windowFocus;

    // ---------- Sprossen-Auswahl (lang OK im Fenster-Modus) ----------
    private Muntins muntins;
    private GlassPanel picker;
    private LinearLayout pickerRow;
    private int pickIndex, pickTone;
    private TextView pickerHint;

    private void updatePickerHint() {
        pickerHint.setText("◀ ▶ Raster  ·  ▲ ▼ Farbe: " + Muntins.COLORS[pickTone] + "  ·  OK speichern  ·  Zurück abbrechen");
    }

    private void openPicker() {
        if (picker == null) {
            picker = new GlassPanel(this, dp(24));
            picker.attach(stage);
            picker.setElevation(dp(20));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(dp(12), dp(12), dp(12), dp(10));
            pickerRow = new LinearLayout(this);
            pickerRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < Muntins.LAYOUTS.length; i++) {
                LinearLayout chip = new LinearLayout(this);
                chip.setOrientation(LinearLayout.VERTICAL);
                chip.setGravity(Gravity.CENTER_HORIZONTAL);
                chip.setPadding(dp(16), dp(10), dp(16), dp(9));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(dp(16));
                chip.setBackground(bg);
                ImageView g = new ImageView(this);
                chip.addView(g, new LinearLayout.LayoutParams(dp(34), dp(22)));
                TextView l = new TextView(this);
                l.setText(Muntins.LABELS[i]);
                l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                l.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                l.setPadding(0, dp(6), 0, 0);
                chip.addView(l);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (i > 0) lp.leftMargin = dp(6);
                pickerRow.addView(chip, lp);
            }
            col.addView(pickerRow);
            TextView h = new TextView(this);
            pickerHint = h;
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            h.setTextColor(0x99FFFFFF);
            h.setPadding(0, dp(10), 0, 0);
            col.addView(h);
            picker.addView(col, new FrameLayout.LayoutParams(-2, -2));
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            pl.bottomMargin = dp(48);
            stage.addForeground(picker, pl);
        }
        picker.bringToFront();
        pickTone = prefs.getInt("muntinTone", 0);
        updatePickerHint();
        pick(prefs.getInt("muntins", 0));
        picker.animate().cancel();
        picker.setVisibility(View.VISIBLE);
        picker.setAlpha(0f); picker.setTranslationY(dp(10));
        picker.animate().alpha(1f).translationY(0).setDuration(Stage.animationsEnabled(this) ? 180 : 0).start();
    }

    private void pick(int i) {
        pickIndex = Math.max(0, Math.min(Muntins.LAYOUTS.length - 1, i));
        muntins.setLayout(pickIndex);   // Vorschau sofort
        for (int k = 0; k < pickerRow.getChildCount(); k++) {
            LinearLayout chip = (LinearLayout) pickerRow.getChildAt(k);
            boolean on = k == pickIndex;
            ((android.graphics.drawable.GradientDrawable) chip.getBackground()).setColor(on ? 0xF2FFFFFF : 0x00FFFFFF);
            int fg = on ? 0xFF15161A : 0xE6FFFFFF;
            ((ImageView) chip.getChildAt(0)).setImageDrawable(Muntins.glyph(k, fg));
            ((TextView) chip.getChildAt(1)).setTextColor(fg);
        }
    }

    private void closePicker(boolean save) {
        if (picker == null || picker.getVisibility() != View.VISIBLE) return;
        if (save) prefs.edit().putInt("muntins", pickIndex).putInt("muntinTone", pickTone).apply();
        else { muntins.setLayout(prefs.getInt("muntins", 0)); muntins.setTone(prefs.getInt("muntinTone", 0)); }
        picker.animate().cancel();
        picker.animate().alpha(0f).translationY(dp(10)).setDuration(Stage.animationsEnabled(this) ? 160 : 0).withEndAction(new Runnable() {
            @Override public void run() { picker.setVisibility(View.GONE); }
        }).start();
    }

    /** Fenster-Modus: nach 2 Stunden ohne Tastendruck schlafen gehen (Lichtquelle schonen). */
    static final long WINDOW_SLEEP_MS = 2 * 3600_000L;
    private final Runnable windowSleep = new Runnable() {
        @Override public void run() {
            if (!windowMode) return;
            exitWindow(false);
            Helper.send("key " + KeyEvent.KEYCODE_SLEEP);   // über den Shell-Helfer: echtes Schlafen wie die Power-Taste
        }
    };

    private void armWindowSleep() { ui.removeCallbacks(windowSleep); if (windowMode) ui.postDelayed(windowSleep, WINDOW_SLEEP_MS); }

    private View[] windowChrome() { return new View[]{shelf, media, topIcons, hint}; }

    private void enterWindow() {
        if (windowMode) return;
        windowMode = true;
        windowFocus = getCurrentFocus();
        long d = Stage.animationsEnabled(this) ? 450 : 0;
        for (View v : windowChrome()) { v.animate().cancel(); v.animate().alpha(0f).setDuration(d).start(); }
        stage.setWindowMode(true);
        muntins.animate().cancel();
        muntins.setVisibility(View.VISIBLE);
        muntins.animate().alpha(1f).setDuration(d).start();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // ein Fenster schläft nicht ein …
        armWindowSleep();                                                                      // … außer 2 h lang drückt niemand eine Taste
    }

    private void exitWindow(boolean animate) {
        if (!windowMode) return;
        windowMode = false;
        long d = animate && Stage.animationsEnabled(this) ? 260 : 0;
        for (View v : windowChrome()) { v.animate().cancel(); v.animate().alpha(1f).setDuration(d).start(); }
        hint.setAlpha(0f);
        ui.removeCallbacks(windowSleep);
        closePicker(false);
        muntins.animate().cancel();
        muntins.animate().alpha(0f).setDuration(d).withEndAction(new Runnable() {
            @Override public void run() { if (!windowMode) muntins.setVisibility(View.GONE); }
        }).start();
        stage.setWindowMode(false);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (windowFocus != null && windowFocus.isAttachedToWindow()) windowFocus.requestFocus();
    }

    /** Medientasten (KeyEvent.isMediaSessionKey ist auf Android 11 für Apps nicht verfügbar). */
    private static boolean isMediaKey(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK: case KeyEvent.KEYCODE_MEDIA_STOP: case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MUTE:
                return true;
            default: return false;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (windowMode) {
            armWindowSleep();
            int kc = e.getKeyCode();
            // Home, Lautstärke und Medientasten wirken normal, jede andere Taste holt die Oberfläche zurück
            if (kc == KeyEvent.KEYCODE_HOME || kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN
                    || kc == KeyEvent.KEYCODE_VOLUME_MUTE || isMediaKey(kc)) return super.dispatchKeyEvent(e);
            boolean ok = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (picker != null && picker.getVisibility() == View.VISIBLE) {
                // Sprossen-Auswahl: links/rechts = Vorschau, OK = speichern, Zurück = verwerfen
                if (ok && longFired) { if (e.getAction() == KeyEvent.ACTION_UP) longFired = false; return true; }   // Rest des langen Drucks
                if (e.getAction() == KeyEvent.ACTION_DOWN && (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    pick(pickIndex + (kc == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1));
                } else if (e.getAction() == KeyEvent.ACTION_DOWN && (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    pickTone = (pickTone + 1) % Muntins.COLORS.length;
                    muntins.setTone(pickTone);
                    updatePickerHint();
                } else if (e.getAction() == KeyEvent.ACTION_UP && ok) closePicker(true);
                else if (e.getAction() == KeyEvent.ACTION_UP && kc == KeyEvent.KEYCODE_BACK) closePicker(false);
                return true;
            }
            if (ok && e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) { longFired = false; return true; }
                if (!longFired && ((e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0 || e.getRepeatCount() >= 1)) { longFired = true; openPicker(); }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) { centerDown = false; longFired = false; exitWindow(true); }
            return true;
        }
        if (bigClock != null && bigClock.getVisibility() == View.VISIBLE) {
            int kc = e.getKeyCode();
            boolean c = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER;
            if (c && longFired) {            // Rest des langen Drucks, der die Uhr geöffnet hat: schlucken
                if (e.getAction() == KeyEvent.ACTION_UP) { longFired = false; centerDown = false; }
                return true;
            }
            if (kc == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
            if (e.getAction() == KeyEvent.ACTION_UP && (kc == KeyEvent.KEYCODE_BACK || c)) hideBigClock();
            return true;
        }
        int k = e.getKeyCode();
        boolean center = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (center) {
            // Langes Drücken selbst erkennen (Tastenwiederholung / FLAG_LONG_PRESS): klappt mit jeder Fernbedienung.
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) { centerDown = true; longFired = false; return moving != null || super.dispatchKeyEvent(e); }
                if (!longFired && moving == null && ((e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0 || e.getRepeatCount() >= 1)) {
                    longFired = true;
                    View f = getCurrentFocus();
                    if (f != null) f.setPressed(false);
                    if (f instanceof Tile && ((Tile) f).onLong != null) ((Tile) f).onLong.run();
                    else if (f == clock) showBigClock();
                    else if (f == media.prev) media.seekBy(-10000, media.prev);
                    else if (f == media.next) media.seekBy(10000, media.next);
                }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) {
                boolean wasLong = longFired;
                centerDown = false; longFired = false;
                if (wasLong) { View f = getCurrentFocus(); if (f != null) f.setPressed(false); return true; }
                if (moving != null) { endMove(); return true; }
                return super.dispatchKeyEvent(e);
            }
        }
        // Menü offen: Fokus bleibt drin (links/rechts wirkungslos, hoch/runter nur zwischen Einträgen)
        if (menu != null && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) return true;
        if (menu != null && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                View f = getCurrentFocus();
                int i = (f != null && f.getParent() == menu ? menu.indexOfChild(f) : 0) + (k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                if (i >= 1 && i < menu.getChildCount()) menu.getChildAt(i).requestFocus();
            }
            return true;
        }
        if (moving != null && e.getAction() == KeyEvent.ACTION_DOWN) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) { shift(k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) { removeFromDock(moving.key); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_UP) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void shift(int dir) {
        int vi = dockRow.indexOfChild(moving), vj = vi + dir;
        if (vj < 0 || vj >= dockRow.getChildCount()) return;
        String other = ((Tile) dockRow.getChildAt(vj)).key;
        if (other.startsWith("@")) return;            // „Alle Apps“/„Einstellungen“ bleiben am Ende
        Collections.swap(dock, dock.indexOf(moving.key), dock.indexOf(other));
        // Den Nachbarn umhängen, nicht die fokussierte Kachel (sonst geht kurz der Fokus verloren).
        final View m = moving, o = dockRow.getChildAt(vj);
        final float mOld = m.getLeft() + m.getTranslationX(), oOld = o.getLeft() + o.getTranslationX();
        dockRow.removeView(o);
        dockRow.addView(o, vi, o.getLayoutParams());
        dockRow.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                dockRow.getViewTreeObserver().removeOnPreDrawListener(this);
                slide(m, mOld); slide(o, oOld);
                pinDockEdges();
                dockRow.requestChildFocus(m, m);   // Dock scrollt mit
                return true;
            }
        });
    }

    /** Kachel wandert von ihrer bisherigen sichtbaren Position weich an den neuen Platz. */
    private void slide(View v, float oldX) {
        v.animate().cancel();
        v.setTranslationX(oldX - v.getLeft());
        v.animate().translationX(0f).setDuration(Stage.animationsEnabled(this) ? 520 : 0)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();
    }

    private void removeFromDock(String key) {
        int vi = dockRow.indexOfChild(moving);
        moving.setWiggle(false);
        moving = null;
        hideHint();
        dock.remove(key);
        saveDock();
        buildDock(null);
        int n = Math.min(vi, dockRow.getChildCount() - 1);
        if (n >= 0) dockRow.getChildAt(n).requestFocus();
        flash("Aus dem Dock genommen. Über „Alle Apps“ wieder hinzufügen.");
    }

    // ---------- Alle Apps ----------

    private void showApps() {
        loadApps();
        appsLayer.setAlpha(0f);
        appsLayer.setVisibility(View.VISIBLE);
        appsLayer.animate().alpha(1f).setDuration(Stage.animationsEnabled(this) ? 180 : 0).start();
        shelf.setVisibility(View.INVISIBLE);
        stage.setChromeVisible(false);
        topIcons.setVisibility(View.INVISIBLE);
        if (media.isShown2()) media.setVisibility(View.INVISIBLE);
        buildGrid(null);
    }

    private void hideApps() {
        appsLayer.animate().alpha(0f).setDuration(Stage.animationsEnabled(this) ? 150 : 0).withEndAction(new Runnable() {
            @Override public void run() { appsLayer.setVisibility(View.GONE); }
        }).start();
        shelf.setVisibility(View.VISIBLE);
        stage.setChromeVisible(true);
        topIcons.setVisibility(View.VISIBLE);
        if (media.isShown2()) media.setVisibility(View.VISIBLE);
        Tile appsTile = null;
        for (int i = 0; i < dockRow.getChildCount(); i++) if (APPS.equals(((Tile) dockRow.getChildAt(i)).key)) appsTile = (Tile) dockRow.getChildAt(i);
        if (appsTile != null) appsTile.requestFocus();
    }

    private void buildGrid(String focusKey) {
        appsGrid.removeAllViews();
        List<App> list = new ArrayList<>(apps.values());
        final Collator col = Collator.getInstance();
        Collections.sort(list, new Comparator<App>() { @Override public int compare(App x, App y) { return col.compare(x.name, y.name); } });
        Tile first = null, target = null;
        for (App a : list) {
            Tile t = tileFor(a, 150);
            t.label.setVisibility(View.GONE);
            markInDock(t);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(0, 0, dp(26), dp(22));
            appsGrid.addView(t, lp);
            if (first == null) first = t;
            if (a.pkg.equals(focusKey)) target = t;
        }
        if (target != null) target.requestFocus(); else if (first != null) first.requestFocus();
    }

    private void markInDock(Tile t) {
        boolean in = dock.contains(t.key);
        App a = apps.get(t.key);
        t.setSubtitle(a.name, in ? "Im Dock" : null);
        if (t.hasFocus() || t.pinned) { focusName.setText(a.name); focusSub.setText(in ? "Im Dock" : ""); }
    }

    private void toggleDock(App a, Tile t) {
        if (dock.contains(a.pkg)) { dock.remove(a.pkg); flash(a.name + " aus dem Dock genommen"); }
        else { dock.add(a.pkg); flash(a.name + " liegt jetzt im Dock"); }
        saveDock();
        markInDock(t);
        buildDock(null);
        t.requestFocus();
    }

    // ---------- Kontextmenü (Alle Apps) ----------

    private LinearLayout menu;
    private Tile menuTile;

    /** Nur selbst installierte Apps lassen sich deinstallieren. */
    private boolean isUserApp(App a) {
        return (a.info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
    }

    private void openMenu(final App a, final Tile t) {
        closeMenu(false);
        menuTile = t;
        t.pinned = true;
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(8), dp(10), dp(8), dp(8));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF5232429);
        bg.setCornerRadius(dp(16));
        menu.setBackground(bg);
        menu.setElevation(dp(24));

        TextView head = new TextView(this);
        head.setText(a.name);
        head.setTextColor(0x99FFFFFF);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.setSingleLine(true);
        head.setEllipsize(TextUtils.TruncateAt.END);
        head.setPadding(dp(14), 0, dp(14), dp(6));
        menu.addView(head);

        boolean inDock = dock.contains(a.pkg);
        View first = menuItem(inDock ? "Aus dem Dock entfernen" : "Zum Dock hinzufügen", new Runnable() {
            @Override public void run() { closeMenu(true); toggleDock(a, t); }
        });
        menuItem("App-Info", new Runnable() {
            @Override public void run() {
                closeMenu(true);
                startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + a.pkg)));
            }
        });
        if (isUserApp(a)) menuItem("Deinstallieren", new Runnable() {
            @Override public void run() {
                closeMenu(true);
                startSafe(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + a.pkg)));
            }
        });

        // Raster sperren, damit der Fokus im Menü bleibt
        appsGrid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        int w = dp(250);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, -2);
        int[] tl = new int[2], ll = new int[2];
        t.card.getLocationInWindow(tl);
        appsLayer.getLocationInWindow(ll);
        int x = tl[0] - ll[0], y = tl[1] - ll[1];
        int right = x + Math.round(t.card.getWidth() * t.card.getScaleX()) + dp(16);
        int left = x - dp(16) - w;
        boolean toRight = right + w <= appsLayer.getWidth() - dp(24);
        lp.leftMargin = toRight ? right : Math.max(dp(24), left);
        lp.topMargin = Math.max(dp(24), Math.min(y, appsLayer.getHeight() - dp(210)));
        appsLayer.addView(menu, lp);

        menu.setAlpha(0f);
        menu.setScaleX(0.94f);
        menu.setScaleY(0.94f);
        menu.setPivotX(toRight ? 0 : w);
        menu.setPivotY(0);
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Stage.animationsEnabled(this) ? 170 : 0)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        first.requestFocus();
    }

    private View menuItem(String text, final Runnable action) {
        final TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setTextColor(0xFFFFFFFF);
        v.setPadding(dp(14), dp(11), dp(14), dp(11));
        final android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(0x00000000);
        v.setBackground(bg);
        v.setFocusable(true);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View x, boolean f) {
                bg.setColor(f ? 0xF2FFFFFF : 0x00000000);
                v.setTextColor(f ? 0xFF111214 : 0xFFFFFFFF);
            }
        });
        v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { action.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(2);
        menu.addView(v, lp);
        return v;
    }

    private void closeMenu(boolean refocus) {
        if (menu == null) return;
        final View m = menu;
        menu = null;
        appsGrid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        m.animate().alpha(0f).setDuration(Stage.animationsEnabled(this) ? 110 : 0).withEndAction(new Runnable() {
            @Override public void run() { appsLayer.removeView(m); }
        }).start();
        if (menuTile != null) {
            menuTile.pinned = false;
            if (refocus && menuTile.getParent() != null) menuTile.requestFocus();
            else if (!menuTile.hasFocus()) menuTile.focus(false);
        }
        menuTile = null;
    }

    // ---------- Hinweise ----------

    private void showHint(String s) { hint.setText(s); hint.bringToFront();hint.animate().alpha(1f).setDuration(160).start(); }
    private void hideHint() { hint.animate().alpha(0f).setDuration(160).start(); }

    private final Runnable clearFlash = new Runnable() { @Override public void run() { if (moving == null) hideHint(); } };

    private void flash(String s) { flash(s, 2600); }

    private void flash(String s, long ms) {
        showHint(s);
        hint.removeCallbacks(clearFlash);
        hint.postDelayed(clearFlash, ms);
    }
}
