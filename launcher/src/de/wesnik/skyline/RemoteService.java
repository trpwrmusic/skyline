package de.wesnik.skyline;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handy-Fernbedienung: kleiner Webserver auf Port 8080 (Web-App + JSON-API) und Verwaltung des Tasten-Helfers.
 * http://<beamer-ip>:8080 im Handy-Browser öffnen.
 */
public class RemoteService extends Service {
    static final int HTTP_PORT = 8080;
    static final String HELPER_VERSION = "6";

    private ServerSocket server;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<String, byte[]> iconCache = new HashMap<>();
    private volatile Socket helper;
    private volatile java.io.BufferedReader helperIn;
    private volatile String helperState = "startet";
    private volatile String lastShell;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("remote", "Fernbedienung", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "remote").setContentTitle("Skyline-Fernbedienung aktiv")
                .setSmallIcon(R.drawable.icon).build();
        startForeground(1, n);
        new Thread(new Runnable() { @Override public void run() { serve(); } }, "http").start();
        new Thread(new Runnable() { @Override public void run() { ensureHelper(); scheduleUpdate(20_000, 0); } }, "helper").start();
        // „Beim nächsten Start vom Beamer“: der XGIMI schläft meist nur, deshalb auch beim Aufwachen nachsehen
        android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenOn, f);
        advertise();
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(screenOn); } catch (Exception ignored) { }
        try { if (nsd != null) nsd.unregisterService(nsdReg); } catch (Exception ignored) { }
        super.onDestroy();
    }

    // ---------- Im WLAN auffindbar (mDNS/DNS-SD „_skyline._tcp“), damit die Handy-App den Beamer ohne feste IP findet ----------
    private android.net.nsd.NsdManager nsd;
    private final android.net.nsd.NsdManager.RegistrationListener nsdReg = new android.net.nsd.NsdManager.RegistrationListener() {
        @Override public void onServiceRegistered(android.net.nsd.NsdServiceInfo i) { }
        @Override public void onRegistrationFailed(android.net.nsd.NsdServiceInfo i, int e) { }
        @Override public void onServiceUnregistered(android.net.nsd.NsdServiceInfo i) { }
        @Override public void onUnregistrationFailed(android.net.nsd.NsdServiceInfo i, int e) { }
    };

    /** Anzeigename: Gerätename aus den Android-Einstellungen, sonst das Modell. */
    String deviceName() {
        String n = null;
        try { n = Settings.Global.getString(getContentResolver(), "device_name"); } catch (Exception ignored) { }
        if (n == null || n.trim().isEmpty()) n = android.os.Build.MODEL;
        return n.trim();
    }

    private void advertise() {
        try {
            android.net.nsd.NsdServiceInfo i = new android.net.nsd.NsdServiceInfo();
            i.setServiceName(deviceName());
            i.setServiceType("_skyline._tcp");
            i.setPort(HTTP_PORT);
            nsd = (android.net.nsd.NsdManager) getSystemService(NSD_SERVICE);
            nsd.registerService(i, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, nsdReg);
        } catch (Exception ignored) { }
    }

    /** Eigene Adresse im WLAN (für den Hinweis auf dem Startbildschirm). */
    static String lanAddress() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ---------- Selbst-Update über GitHub ----------
    private final android.os.Handler bg;
    { android.os.HandlerThread t = new android.os.HandlerThread("update"); t.start(); bg = new android.os.Handler(t.getLooper()); }

    private final android.content.BroadcastReceiver screenOn = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) { scheduleUpdate(15_000, 0); }
    };

    private final Runnable periodic = new Runnable() {
        @Override public void run() { scheduleUpdate(0, 0); }
    };

    /** Nachsehen (mit Verzögerung); bei Netzfehlern bis zu 5-mal im Abstand von 2 Minuten erneut, danach alle 6 Stunden. */
    private void scheduleUpdate(long delay, final int attempt) {
        bg.postDelayed(new Runnable() {
            @Override public void run() {
                if (!"bereit".equals(helperState)) ensureHelper();
                boolean retry = Updater.check(RemoteService.this, updaterShell, false, HomeActivity.resumed);
                if (retry && attempt < 5) scheduleUpdate(120_000, attempt + 1);
            }
        }, delay);
        bg.removeCallbacks(periodic);
        bg.postDelayed(periodic, 6 * 3600_000L);
    }

    private final Updater.Shell updaterShell = new Updater.Shell() {
        @Override public String cmd(String c) { return helperCmd(c); }
    };

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    // ---------- Tasten-Helfer ----------

    private synchronized String helperCmd(String cmd) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (helper == null) {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress("127.0.0.1", KeyServer.PORT), 500);
                    s.setTcpNoDelay(true);
                    s.setSoTimeout(2000);
                    helper = s;
                    helperIn = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                }
                helper.getOutputStream().write((cmd + "\n").getBytes("UTF-8"));
                return helperIn.readLine();
            } catch (Exception e) {
                try { if (helper != null) helper.close(); } catch (Exception ignored) { }
                helper = null;
            }
        }
        return null;
    }

    /** Helfer läuft? Sonst per ADB (an sich selbst) starten. Läuft er in alter Version, neu starten. */
    private void ensureHelper() {
        String pong = helperCmd("ping");
        if (pong != null && pong.equals("pong " + HELPER_VERSION)) { helperState = "bereit"; return; }
        helperState = "verbinde mit ADB …";
        try {
            String apk = getPackageCodePath();
            String cmd = "appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow; "
                    + "cmd notification allow_listener " + getPackageName() + "/" + MediaListener.class.getName() + "; "
                    + (pong != null ? "pkill -f 'KeyServer$'; sleep 0.3; " : "")
                    + "(CLASSPATH=" + apk + " setsid nohup app_process / de.wesnik.skyline.KeyServer </dev/null >/data/local/tmp/skyline-keyserver.log 2>&1 &); sleep 1; echo gestartet";
            String outp = new AdbClient(this).shell(cmd, 60000);
            lastShell = outp;
            for (int i = 0; i < 20; i++) {
                Thread.sleep(250);
                if (("pong " + HELPER_VERSION).equals(helperCmd("ping"))) { helperState = "bereit"; return; }
            }
            helperState = "Helfer startet nicht: " + (lastShell == null ? "" : lastShell.trim());
        } catch (SecurityException e) {
            helperState = "ADB-Freigabe am Beamer abgelehnt";
        } catch (Exception e) {
            helperState = "Netzwerk-Debugging aus? (" + e.getClass().getSimpleName() + ")";
        }
    }

    // ---------- HTTP ----------

    private void serve() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(HTTP_PORT));
            while (true) {
                final Socket s = server.accept();
                pool.execute(new Runnable() { @Override public void run() { handle(s); } });
            }
        } catch (Exception ignored) { }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            sock.setTcpNoDelay(true);
            InputStream in = sock.getInputStream();
            String reqLine = readLine(in);
            if (reqLine == null) return;
            long len = 0; String line, ctype = "";
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                String l = line.toLowerCase();
                if (l.startsWith("content-length:")) len = Long.parseLong(line.substring(15).trim());
                else if (l.startsWith("content-type:")) ctype = l.substring(13).trim();
            }
            String[] p = reqLine.split(" ");
            String method = p[0], path = p.length > 1 ? p[1] : "/";
            if (method.equals("POST") && path.startsWith("/api/upload")) {   // Fotos/Videos: direkt in die Datei, nicht in den Speicher
                sock.setSoTimeout(60000);
                upload(in, len, path, ctype, sock.getOutputStream());
                return;
            }
            if (len > 1_000_000) { write(sock.getOutputStream(), 413, "text/plain", new byte[0]); return; }
            byte[] body = new byte[(int) len];
            int r = 0; while (r < len) { int n = in.read(body, r, (int) len - r); if (n < 0) break; r += n; }
            route(method, path, new String(body, "UTF-8"), sock.getOutputStream());
        } catch (Exception ignored) { }
    }

    /** POST /api/upload?name=…&ort=…&tags=…  (Rohdaten der Datei im Körper) */
    private void upload(InputStream in, long len, String path, String ctype, OutputStream out) throws Exception {
        Map<String, String> q = query(path);
        String name = Media.clean(q.containsKey("name") ? q.get("name") : "");
        if (!(Media.isVideo(name) || Media.isImage(name))) {   // manche Handys schicken Namen ohne Endung: aus dem Dateityp ergänzen
            String ext = ctype.startsWith("image/jpeg") ? ".jpg" : ctype.startsWith("image/png") ? ".png" : ctype.startsWith("image/webp") ? ".webp"
                    : ctype.startsWith("image/hei") ? ".heic" : ctype.startsWith("video/mp4") ? ".mp4" : ctype.startsWith("video/quicktime") ? ".mov"
                    : ctype.startsWith("video/webm") ? ".webm" : ctype.startsWith("video/") ? ".mp4" : "";
            if (!ext.isEmpty()) name = Media.clean((name.isEmpty() ? "upload-" + System.currentTimeMillis() / 1000 : name) + ext);
        }
        if (len <= 0 || name.isEmpty() || !(Media.isVideo(name) || Media.isImage(name))) {
            json(out, err("Nur Fotos (JPG, PNG, WebP, HEIC) und Videos (MP4, MOV, MKV, WebM)")); return;
        }
        try {
            java.io.File f = Media.receive(this, in, len, name, q.get("ort"), q.get("tags"));
            Media.show(f.getName());     // gleich zeigen
            json(out, "{\"ok\":true,\"name\":\"" + esc(f.getName()) + "\"}");
        } catch (Exception e) {
            json(out, err(e.getMessage()));
        }
    }

    private static Map<String, String> query(String path) {
        Map<String, String> m = new HashMap<>();
        int i = path.indexOf('?');
        if (i >= 0) form(path.substring(i + 1), m);
        return m;
    }

    /** a=1&b=zwei → Map (URL-kodiert, wie bei Formularen). */
    private static Map<String, String> form(String s, Map<String, String> m) {
        for (String kv : s.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(e < 0 ? kv : kv.substring(0, e), "UTF-8");
                String v = e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                m.put(k, v);
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static String err(String msg) { return "{\"ok\":false,\"error\":\"" + esc(msg == null ? "Fehler" : msg) + "\"}"; }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) { if (c == '\n') break; if (c != '\r') b.write(c); }
        return c < 0 && b.size() == 0 ? null : b.toString("UTF-8");
    }

    private void route(String method, String path, String body, OutputStream out) throws Exception {
        String q = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        path = URLDecoder.decode(path.split("\\?")[0], "UTF-8");
        if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
            getSharedPreferences("skyline", MODE_PRIVATE).edit().putBoolean("remote_seen", true).apply();
            asset(out, "remote/index.html", "text/html; charset=utf-8");
            return;
        }
        if (method.equals("GET") && path.equals("/manifest.webmanifest")) { asset(out, "remote/manifest.webmanifest", "application/manifest+json"); return; }
        if (method.equals("GET") && path.equals("/sw.js")) { asset(out, "remote/sw.js", "text/javascript"); return; }
        if (method.equals("GET") && path.equals("/skyline-remote.apk")) { asset(out, "remote/skyline-remote.apk", "application/vnd.android.package-archive"); return; }
        if (method.equals("GET") && path.startsWith("/icon-")) { asset(out, "remote" + path, "image/png"); return; }
        if (method.equals("GET") && path.equals("/api/state")) { json(out, state()); return; }
        if (method.equals("GET") && path.startsWith("/api/icon/")) { icon(out, path.substring(10).replace(".png", "")); return; }
        if (method.equals("GET") && path.equals("/api/settings")) { json(out, settings()); return; }
        if (method.equals("POST") && path.equals("/api/settings")) { json(out, saveSettings(form(body, new HashMap<String, String>()))); return; }
        if (method.equals("GET") && path.equals("/api/geocode")) {
            String qq = query("?" + q).get("q");
            try { json(out, Weather.geocode(qq == null ? "" : qq)); } catch (Exception e) { json(out, "[]"); }
            return;
        }
        if (method.equals("GET") && path.equals("/api/media")) { json(out, Media.list(this, Stage.playing)); return; }
        if (method.equals("GET") && path.startsWith("/api/thumb/")) {
            byte[] t = Media.thumb(this, path.substring(11));
            if (t == null) write(out, 404, "text/plain", new byte[0]); else write(out, 200, "image/jpeg", t);
            return;
        }
        if (method.equals("POST") && path.startsWith("/api/media/")) {
            String rest = path.substring(11);
            int sl = rest.indexOf('/');
            String op = sl < 0 ? rest : rest.substring(0, sl), name = sl < 0 ? "" : rest.substring(sl + 1);
            boolean ok;
            switch (op) {
                case "delete": ok = Media.delete(this, name); break;
                case "show": ok = Media.find(this, name) != null; if (ok) { Media.show(Media.clean(name)); action("home"); } break;
                case "meta": { Map<String, String> f = form(body, new HashMap<String, String>()); ok = Media.setMeta(this, name, f.get("ort"), f.get("tags")); break; }
                default: ok = false;
            }
            json(out, "{\"ok\":" + ok + "}");
            return;
        }
        if (method.equals("POST") && path.equals("/api/update/check")) {
            bg.post(new Runnable() { @Override public void run() { Updater.check(RemoteService.this, updaterShell, true, HomeActivity.resumed); } });
            json(out, "{\"ok\":true}");
            return;
        }
        if (method.equals("POST") && path.startsWith("/api/")) {
            String[] a = path.substring(5).split("/");
            String res;
            switch (a[0]) {
                case "key": res = helperCmd("key " + Integer.parseInt(a[1])); break;
                case "down": res = helperCmd("down " + Integer.parseInt(a[1])); break;
                case "repeat": res = helperCmd("repeat " + Integer.parseInt(a[1]) + " " + Integer.parseInt(a[2])); break;
                case "up": res = helperCmd("up " + Integer.parseInt(a[1])); break;
                case "text": res = helperCmd("text " + body.replace("\n", " ")); break;
                case "launch": res = launch(a[1]); break;
                case "action": res = action(a[1]); break;
                case "ptr": res = pointer(a); break;
                case "location":  // /api/location/52.3759/9.7320/Hannover  oder  /api/location/auto
                    if (a.length > 1 && a[1].equals("auto")) Weather.setAutoLocation(this);
                    else Weather.setLocation(this, Double.parseDouble(a[1]), Double.parseDouble(a[2]), a.length > 3 ? a[3] : "");
                    res = "ok"; break;
                case "weather":   // Test: /api/weather/regen/nacht/herbst  oder  /api/weather/auto
                    if (a.length >= 4) Weather.setOverride(new Weather.Now(a[1], a[2], a[3], "Test", Double.NaN));
                    else Weather.setOverride(null);
                    res = "ok"; break;
                case "helper": new Thread(new Runnable() { @Override public void run() { ensureHelper(); } }).start(); res = "ok"; break;
                default: res = null;
            }
            json(out, "{\"ok\":" + (res != null && res.startsWith("ok")) + "}");
            return;
        }
        write(out, 404, "text/plain", "nicht gefunden".getBytes("UTF-8"));
    }

    // ---------- Maus-Modus: Zeiger auf dem Beamer, Tippen/Wischen als echte Touch-Ereignisse ----------
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private float px = -1, py, fx, fy;
    private boolean dragging;
    private Pointer cursor;
    private android.view.WindowManager.LayoutParams cursorLp;
    private final Runnable hideCursor = new Runnable() {
        @Override public void run() { if (cursor != null) cursor.animate().alpha(0f).setDuration(300).start(); }
    };

    private synchronized String pointer(String[] a) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int W = dm.widthPixels, H = dm.heightPixels;
        if (px < 0) { px = W / 2f; py = H / 2f; }
        String r = "ok";
        switch (a[1]) {
            case "move": {
                px = clamp(px + Float.parseFloat(a[2]), 0, W - 1); py = clamp(py + Float.parseFloat(a[3]), 0, H - 1);
                if (!dragging) showCursor(false);
                break;
            }
            // Zeigerfenster vorher entfernen: manche Dialoge verwerfen Berührungen unter fremden Fenstern
            case "tap": removeCursor(); r = helperCmd("tap " + (int) px + " " + (int) py); showCursor(true); break;
            case "long": removeCursor(); r = helperCmd("longtap " + (int) px + " " + (int) py); showCursor(true); break;
            case "dragstart": removeCursor(); fx = px; fy = py; dragging = true; r = helperCmd("touch down " + (int) fx + " " + (int) fy); break;
            case "dragmove": {
                if (!dragging) return "ok";
                fx = clamp(fx + Float.parseFloat(a[2]), 0, W - 1); fy = clamp(fy + Float.parseFloat(a[3]), 0, H - 1);
                r = helperCmd("touch move " + (int) fx + " " + (int) fy);
                break;
            }
            case "dragend": if (dragging) { dragging = false; r = helperCmd("touch up " + (int) fx + " " + (int) fy); showCursor(false); } break;
            case "hide": main.post(hideCursor); break;
            default: return null;
        }
        return r;
    }

    /** Zeigerfenster synchron entfernen (wartet auf den UI-Thread). */
    private void removeCursor() {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        main.post(new Runnable() {
            @Override public void run() {
                if (cursor != null) {
                    try { ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).removeViewImmediate(cursor); } catch (Exception ignored) { }
                    cursor = null;
                }
                done.countDown();
            }
        });
        try { done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(30); } catch (InterruptedException ignored) { }
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private void showCursor(final boolean click) {
        final float x = px, y = py;
        main.post(new Runnable() {
            @Override public void run() {
                if (!Settings.canDrawOverlays(RemoteService.this)) return;
                android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
                int s = Math.round(44 * getResources().getDisplayMetrics().density);
                if (cursor == null) {
                    cursor = new Pointer(RemoteService.this);
                    cursorLp = new android.view.WindowManager.LayoutParams(s, s,
                            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            android.graphics.PixelFormat.TRANSLUCENT);
                    cursorLp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
                    cursorLp.x = (int) x - s / 2; cursorLp.y = (int) y - s / 2;
                    try { wm.addView(cursor, cursorLp); } catch (Exception e) { cursor = null; return; }
                }
                cursorLp.x = (int) x - s / 2; cursorLp.y = (int) y - s / 2;
                try { wm.updateViewLayout(cursor, cursorLp); } catch (Exception ignored) { }
                cursor.animate().cancel();
                cursor.setAlpha(1f);
                if (click) {
                    cursor.setScaleX(.62f); cursor.setScaleY(.62f);
                    cursor.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
                }
                main.removeCallbacks(hideCursor);
                main.postDelayed(hideCursor, 5000);
            }
        });
    }

    /** Zeiger: weißer Punkt mit weichem Schatten (keine Kontur). */
    static final class Pointer extends android.view.View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        Pointer(android.content.Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            float d = c.getResources().getDisplayMetrics().density;
            p.setColor(0xF2FFFFFF);
            p.setShadowLayer(6 * d, 0, 2 * d, 0x8C000000);
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, 11 * d, p);
        }
    }

    private String launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent l = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (l == null) l = pm.getLaunchIntentForPackage(pkg);
        if (l == null) return null;
        l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (Helper.TABLET_APPS.contains(pkg)) helperCmd("density " + Helper.TABLET_DENSITY);
        try { startActivity(l); return "ok"; } catch (Exception e) { return null; }
    }

    private String action(String name) {
        Intent i;
        switch (name) {
            case "keystone": i = new Intent("com.xgimi.windowsystem.kst"); break;
            case "settings": i = new Intent(Settings.ACTION_SETTINGS); break;
            case "wifi": i = new Intent(Settings.ACTION_WIFI_SETTINGS); break;
            case "home": case "bigclock": case "seconds": case "nextvideo": case "apps": case "window":
                i = new Intent(this, HomeActivity.class).putExtra(HomeActivity.EXTRA_REMOTE, name); break;
            default: return null;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); return "ok"; } catch (Exception e) { return null; }
    }

    /** Einstellungen für die Seite „Einstellungen“ der Fernbedienung. */
    private String settings() {
        android.content.SharedPreferences p = getSharedPreferences("skyline", MODE_PRIVATE);
        try {
            org.json.JSONObject wx = new org.json.JSONObject();
            for (String pl : WeatherBadge.PLACES)
                wx.put(pl, new org.json.JSONObject().put("show", WeatherBadge.shown(p, pl)).put("pos", WeatherBadge.pos(p, pl)));
            Weather.Now n = Weather.get();
            org.json.JSONObject o = new org.json.JSONObject()
                    .put("wx", wx)
                    .put("ort", p.getString("wx_ort", ""))
                    .put("ortFest", p.getBoolean("wx_fixed", false))
                    .put("wetter", n == null ? "" : (Double.isNaN(n.temp) ? "" : Math.round(n.temp) + "° · ") + Weather.describe(n.code))
                    .put("version", Updater.installedName(this) + " (" + Updater.installedCode(this) + ")")
                    .put("updAuto", Updater.auto(this))
                    .put("updRepo", Updater.repo(this))
                    .put("updToken", !p.getString("upd_token", "").isEmpty())
                    .put("updStatus", Updater.status);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    private String saveSettings(Map<String, String> f) {
        android.content.SharedPreferences.Editor e = getSharedPreferences("skyline", MODE_PRIVATE).edit();
        for (Map.Entry<String, String> en : f.entrySet()) {
            String k = en.getKey(), v = en.getValue();
            if (k.matches("wxshow_(home|window|dream)")) e.putBoolean(k, v.equals("1") || v.equals("true"));
            else if (k.matches("wxpos_(home|window|dream)") && v.matches("tl|tr|bl|br")) e.putString(k, v);
            else if (k.equals("upd_auto")) e.putBoolean(k, v.equals("1") || v.equals("true"));
            else if (k.equals("upd_repo") && (v.isEmpty() || v.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) e.putString(k, v);
            else if (k.equals("upd_token") && v.matches("[A-Za-z0-9_]*")) e.putString(k, v);
        }
        e.apply();
        return "{\"ok\":true}";
    }

    private String state() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> all = new ArrayList<>(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        for (String t : Helper.TABLET_APPS) all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(t), 0));
        String dock = getSharedPreferences("skyline", MODE_PRIVATE).getString("dock", "");
        List<String> dockList = java.util.Arrays.asList(dock.split(","));
        Weather.Now wn = Weather.get();
        StringBuilder sb = new StringBuilder("{\"skyline\":true,\"name\":\"").append(esc(deviceName())).append("\",\"helper\":\"").append(esc(helperState)).append("\",\"wetter\":\"").append(wn == null ? "" : esc(wn.toString())).append("\",\"apps\":[");
        List<String> seen = new ArrayList<>();
        // Dock-Reihenfolge zuerst, dann der Rest alphabetisch
        List<ResolveInfo> sorted = new ArrayList<>();
        for (String d : dockList) for (ResolveInfo r : all) if (r.activityInfo.packageName.equals(d)) sorted.add(r);
        List<ResolveInfo> rest = new ArrayList<>();
        for (ResolveInfo r : all) if (!dockList.contains(r.activityInfo.packageName)) rest.add(r);
        final PackageManager fpm = pm;
        java.util.Collections.sort(rest, new java.util.Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo x, ResolveInfo y) { return String.valueOf(x.loadLabel(fpm)).compareToIgnoreCase(String.valueOf(y.loadLabel(fpm))); }
        });
        sorted.addAll(rest);
        boolean first = true;
        for (ResolveInfo r : sorted) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || HomeActivity.HIDDEN.contains(pkg) || seen.contains(pkg)) continue;
            seen.add(pkg);
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"pkg\":\"").append(pkg).append("\",\"name\":\"").append(esc(String.valueOf(r.loadLabel(pm))))
              .append("\",\"dock\":").append(dockList.contains(pkg)).append('}');
        }
        return sb.append("]}").toString();
    }

    private void icon(OutputStream out, String pkg) throws Exception {
        byte[] png;
        synchronized (iconCache) { png = iconCache.get(pkg); }
        if (png == null) {
            PackageManager pm = getPackageManager();
            Drawable d = null;
            Intent l = pm.getLeanbackLaunchIntentForPackage(pkg);
            try {
                if (l != null) { ActivityInfo ai = pm.getActivityInfo(l.getComponent(), 0); d = ai.loadBanner(pm); }
                if (d == null) d = pm.getApplicationBanner(pkg);
                if (d == null && pkg.equals("com.netflix.mediaclient")) d = getDrawable(R.drawable.banner_netflix);
                if (d == null) d = pm.getApplicationIcon(pkg);
            } catch (Exception e) { write(out, 404, "text/plain", new byte[0]); return; }
            int w = 320, h = 180;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            if (d.getIntrinsicWidth() > 0 && d.getIntrinsicWidth() < d.getIntrinsicHeight() * 1.3f) {   // quadratisches Icon: zentriert
                c.drawColor(0xFF2A2B30);
                int s = 110; d.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
            } else d.setBounds(0, 0, w, h);
            d.draw(c);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo);
            png = bo.toByteArray();
            synchronized (iconCache) { iconCache.put(pkg, png); }
        }
        write(out, 200, "image/png", png);
    }

    private void asset(OutputStream out, String name, String type) throws Exception {
        try (InputStream i = getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            write(out, 200, type, b.toByteArray());
        } catch (Exception e) { write(out, 404, "text/plain", new byte[0]); }
    }

    private void json(OutputStream out, String s) throws Exception { write(out, 200, "application/json; charset=utf-8", s.getBytes("UTF-8")); }

    private static void write(OutputStream out, int code, String type, byte[] body) throws Exception {
        String h = "HTTP/1.1 " + code + (code == 200 ? " OK" : code == 413 ? " Payload Too Large" : " Not Found") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
