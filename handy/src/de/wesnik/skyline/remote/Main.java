package de.wesnik.skyline.remote;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Skyline Hub: zentrale App für Heimmedien. Die Oberfläche (assets/shell) läuft in einer WebView unter dem
 * virtuellen Ursprung http://hub.local. Jede Erweiterung bekommt einen eigenen Ursprung
 * (http://&lt;id&gt;.ext.hub.local; http, damit Bilder und Anfragen an Geräte im WLAN nicht als Mixed Content fallen) und läuft dort in einem iframe: eigener Speicher, kein Zugriff auf die Hülle.
 * Systemzugriffe (TCP, HTTP ohne CORS, WLAN-Suche, mDNS) gehen nur über die Hülle, die die Rechte aus dem
 * Manifest der Erweiterung prüft, und von dort über {@link Bridge} hierher. Die Brücke ist zwar technisch in
 * jedem Frame sichtbar, nimmt aber nur Aufrufe mit dem Schlüssel an, den allein die Hülle kennt.
 */
public class Main extends Activity {
    static final String TAG = "SkylineHub";
    static final String HOST = "hub.local";
    static final String EXT_SUFFIX = ".ext.hub.local";
    static final String HOME = "http://" + HOST + "/index.html";

    WebView web;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService work = Executors.newCachedThreadPool();
    Net net;
    Exts exts;
    AppUpdate update;
    private final String token = randomToken();
    private boolean booted;
    private String pendingLink;
    private ValueCallback<Uri[]> fileCb;
    private static final int REQ_FILES = 1, REQ_ZIP = 2;
    private int zipReq;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Color.rgb(13, 14, 17));
        w.setNavigationBarColor(Color.rgb(23, 24, 29));
        System.setProperty("http.keepAlive", "false");
        net = new Net(this);
        exts = new Exts(this);
        exts.purgeTrash();
        update = new AppUpdate(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(13, 14, 17));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);   // Geräte im WLAN sprechen nur http
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " SkylineHub/" + versionName());
        WebView.setWebContentsDebuggingEnabled(true);   // Erweiterungen lassen sich so über chrome://inspect entwickeln
        web.addJavascriptInterface(new Bridge(), "HubNative");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        setContentView(web);
        pendingLink = linkOf(getIntent());
        web.loadUrl(HOME);
    }

    // ---------- Virtuelle Ursprünge ----------

    private class Client extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            String host = u.getHost();
            if (host == null || !("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) return null;
            String path = u.getPath() == null || u.getPath().equals("/") ? "/index.html" : u.getPath();
            try {
                if (host.equals(HOST)) {
                    if (path.startsWith("/bundled/")) return asset("ext/" + path.substring(9));
                    return asset("shell" + path);
                }
                if (host.endsWith(EXT_SUFFIX)) {
                    String id = host.substring(0, host.length() - EXT_SUFFIX.length());
                    File f = exts.file(id, path);
                    if (f == null || !f.isFile()) return notFound();
                    return response(mime(f.getName()), new FileInputStream(f));
                }
            } catch (Exception e) { return notFound(); }
            return null;
        }

        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if ("skyline".equals(u.getScheme())) { link(u.toString()); return true; }
            if (!r.isForMainFrame()) return false;
            if (HOST.equals(u.getHost())) return false;
            openExternal(u.toString());   // Hülle bleibt immer geladen
            return true;
        }

        @Override public void onPageFinished(WebView v, String url) {
            if (url == null || !url.startsWith("http://" + HOST + "/")) return;
            JSONObject info = new JSONObject();
            try {
                info.put("version", versionName());
                info.put("versionCode", versionCode());
                info.put("android", Build.VERSION.SDK_INT);
                info.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            } catch (Exception ignored) { }
            v.evaluateJavascript("window.HubBoot&&HubBoot(" + JSONObject.quote(token) + "," + info + ")", null);
            booted = true;
            if (pendingLink != null) { link(pendingLink); pendingLink = null; }
        }
    }

    private WebResourceResponse asset(String name) throws Exception {
        return response(mime(name), getAssets().open(name));
    }

    private static WebResourceResponse response(String mime, InputStream in) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-cache");
        return new WebResourceResponse(mime, mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") ? "utf-8" : null,
                200, "OK", h, in);
    }

    private static WebResourceResponse notFound() {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h, new ByteArrayInputStream(new byte[0]));
    }

    static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        switch (ext) {
            case "html": case "htm": return "text/html";
            case "js": case "mjs": return "text/javascript";
            case "css": return "text/css";
            case "json": case "webmanifest": return "application/json";
            case "svg": return "image/svg+xml";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "woff2": return "font/woff2";
            case "woff": return "font/woff";
            case "ttf": return "font/ttf";
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "mp3": return "audio/mpeg";
            case "wasm": return "application/wasm";
            case "glb": return "model/gltf-binary";
            case "txt": case "md": return "text/plain";
            default: return "application/octet-stream";
        }
    }

    // ---------- Dateiauswahl (für <input type=file> in Erweiterungen) ----------

    private class Chrome extends WebChromeClient {
        @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE);
            String[] types = p.getAcceptTypes();
            StringBuilder mimes = new StringBuilder();
            if (types != null) for (String t : types) for (String one : t.split(",")) if (!one.trim().isEmpty()) {
                if (mimes.length() > 0) mimes.append(',');
                mimes.append(one.trim());
            }
            String[] list = mimes.length() == 0 ? new String[0] : mimes.toString().split(",");
            i.setType(list.length == 1 ? list[0] : "*/*");
            if (list.length > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, list);
            if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try { startActivityForResult(i, REQ_FILES); } catch (ActivityNotFoundException e) { fileCb = null; return false; }
            return true;
        }

        @Override public boolean onConsoleMessage(ConsoleMessage m) {
            Log.d(TAG, m.sourceId() + ":" + m.lineNumber() + " " + m.message());
            return true;
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILES) {
            if (fileCb == null) return;
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                ClipData c = data.getClipData();
                if (c != null) { out = new Uri[c.getItemCount()]; for (int i = 0; i < out.length; i++) out[i] = c.getItemAt(i).getUri(); }
                else if (data.getData() != null) out = new Uri[]{data.getData()};
            }
            fileCb.onReceiveValue(out);
            fileCb = null;
        } else if (req == REQ_ZIP) {
            final int id = zipReq;
            if (res != RESULT_OK || data == null || data.getData() == null) { reply(id, false, null, "Abgebrochen"); return; }
            final Uri u = data.getData();
            work.execute(new Runnable() {
                @Override public void run() {
                    try (InputStream in = getContentResolver().openInputStream(u)) {
                        reply(id, true, exts.installZip(in, null, "file"), null);
                    } catch (Exception e) { reply(id, false, null, msg(e)); }
                }
            });
        }
    }

    // ---------- Verbindung Hülle <-> System ----------

    /** Antwort auf einen Aufruf an die Hülle. */
    void reply(int id, boolean ok, Object result, String error) {
        JSONObject m = new JSONObject();
        try {
            m.put("re", id);
            m.put("ok", ok);
            if (ok) m.put("result", result == null ? JSONObject.NULL : result);
            else m.put("error", error == null ? "Fehler" : error);
        } catch (Exception ignored) { }
        js("Hub._native(" + m + ")");
    }

    /** Ereignis an die Hülle (z. B. Daten auf einem Socket). */
    void event(String name, JSONObject data) {
        try { data.put("event", name); } catch (Exception ignored) { }
        js("Hub._native(" + data + ")");
    }

    void js(final String code) {
        ui.post(new Runnable() { @Override public void run() { if (web != null) web.evaluateJavascript(code, null); } });
    }

    static String msg(Throwable e) {
        String m = e.getMessage();
        if (e instanceof java.net.SocketTimeoutException) return "Zeitüberschreitung";
        if (e instanceof java.net.ConnectException) return "Verbindung abgelehnt";
        if (e instanceof java.net.UnknownHostException) return "Adresse unbekannt";
        if (e instanceof java.net.NoRouteToHostException) return "Gerät nicht erreichbar";
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    final class Bridge {
        /** Ein Aufruf aus der Hülle: {id, method, args, owner}. Rückgabe sofort bei schnellen Aufrufen, sonst später über reply(). */
        @JavascriptInterface public String call(String tok, String json) {
            if (!token.equals(tok)) return "{\"ok\":false,\"error\":\"Kein Zugriff\"}";
            final int id;
            final String method, owner;
            final JSONObject a;
            try {
                JSONObject r = new JSONObject(json);
                id = r.getInt("id");
                method = r.getString("method");
                owner = r.optString("owner", "");
                a = r.optJSONObject("args") == null ? new JSONObject() : r.getJSONObject("args");
            } catch (Exception e) { return "{\"ok\":false,\"error\":\"Ungültiger Aufruf\"}"; }
            try {
                switch (method) {
                    // schnell und synchron
                    case "net.info": return ok(net.info());
                    case "tcp.write": net.tcpWrite(a.getInt("sock"), a.getString("data")); return ok(true);
                    case "tcp.close": net.tcpClose(a.getInt("sock")); return ok(true);
                    case "tcp.closeOwner": net.closeOwner(a.getString("owner")); return ok(true);
                    case "exts.list": return ok(exts.list());
                    case "exts.bundled": return ok(exts.bundled());
                    case "exts.remove": exts.remove(a.getString("id")); return ok(true);
                    case "exts.restore": return ok(exts.restore(a.getString("id")));
                    case "haptic": haptic(a.optString("kind", "tap")); return ok(true);
                    case "openUrl": openExternal(a.getString("url")); return ok(true);
                    case "exit": ui.post(new Runnable() { @Override public void run() { moveTaskToBack(true); } }); return ok(true);
                    case "share": share(a.optString("text", "")); return ok(true);
                    // dauert: Antwort später
                    case "tcp.open": net.tcpOpen(id, owner, a); return async();
                    case "http": net.http(id, a); return async();
                    case "net.scan": net.scan(id, a); return async();
                    case "net.mdns": net.mdns(id, a); return async();
                    case "app.update.check": {
                        work.execute(new Runnable() {
                            @Override public void run() {
                                try { reply(id, true, update.check(versionCode()), null); } catch (Exception e) { reply(id, false, null, msg(e)); }
                            }
                        });
                        return async();
                    }
                    case "app.update.install": update.install(id, a.getString("url")); return async();
                    case "exts.installAsset":
                    case "exts.installUrl": {
                        work.execute(new Runnable() {
                            @Override public void run() {
                                try {
                                    JSONObject m = method.equals("exts.installAsset")
                                            ? exts.installAsset(a.getString("id"))
                                            : exts.installUrl(a.getString("url"), a.optString("sha256", ""), a.optString("id", ""), id);
                                    reply(id, true, m, null);
                                } catch (Exception e) { reply(id, false, null, msg(e)); }
                            }
                        });
                        return async();
                    }
                    case "exts.pickZip": {
                        zipReq = id;
                        ui.post(new Runnable() {
                            @Override public void run() {
                                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                                try { startActivityForResult(i, REQ_ZIP); } catch (Exception e) { reply(id, false, null, "Keine Dateiauswahl vorhanden"); }
                            }
                        });
                        return async();
                    }
                    default: return err("Unbekannt: " + method);
                }
            } catch (Exception e) {
                return err(msg(e));
            }
        }

        private String ok(Object v) {
            JSONObject o = new JSONObject();
            try { o.put("ok", true); o.put("result", v == null ? JSONObject.NULL : v); } catch (Exception ignored) { }
            return o.toString();
        }
        private String err(String m) {
            JSONObject o = new JSONObject();
            try { o.put("ok", false); o.put("error", m); } catch (Exception ignored) { }
            return o.toString();
        }
        private String async() { return "{\"async\":true}"; }
    }

    private void haptic(String kind) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if ("long".equals(kind)) v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE));
        else if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined("tick".equals(kind) ? VibrationEffect.EFFECT_TICK : VibrationEffect.EFFECT_CLICK));
        else v.vibrate(VibrationEffect.createOneShot("tick".equals(kind) ? 6 : 10, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private void openExternal(final String url) {
        ui.post(new Runnable() {
            @Override public void run() {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { Log.w(TAG, "Kann nicht öffnen: " + url); }
            }
        });
    }

    private void share(final String text) {
        ui.post(new Runnable() {
            @Override public void run() {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                try { startActivity(Intent.createChooser(i, null)); } catch (Exception ignored) { }
            }
        });
    }

    // ---------- Links (skyline://pair?host=…) ----------

    private static String linkOf(Intent i) {
        if (i == null || i.getData() == null) return null;
        String s = i.getData().toString();
        return s.startsWith("skyline:") ? s : null;
    }

    private void link(String url) {
        if (!booted) { pendingLink = url; return; }
        js("Hub.link(" + JSONObject.quote(url) + ")");
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String l = linkOf(i);
        if (l != null) link(l);
    }

    // ---------- Lebenszyklus ----------

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
        if (booted) js("Hub.lifecycle('resume')");
    }

    @Override protected void onPause() {
        if (booted) web.evaluateJavascript("Hub.lifecycle('pause')", null);
        web.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        net.closeAll();
        web.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (booted) js("Hub.back()"); else super.onBackPressed();
    }

    private String versionName() {
        try { PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0); return p.versionName; } catch (Exception e) { return "?"; }
    }
    private long versionCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
