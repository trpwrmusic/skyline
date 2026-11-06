package de.wesnik.skyline.remote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Skyline-Fernbedienung als echte App: lädt die Web-Oberfläche des Beamers im Vollbild (ohne Browserleiste).
 * Keine feste Adresse: zuerst der zuletzt benutzte Beamer, sonst Suche im WLAN
 * (mDNS „_skyline._tcp“ und zur Sicherheit ein Durchlauf über das eigene /24-Netz, Port 8080).
 * Mehrere Beamer: Auswahl, später über „Beamer wechseln“ in den Einstellungen.
 */
public class Main extends Activity {
    static final int PORT = 8080;
    private WebView web;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean searching;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(13, 14, 17));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " SkylineRemote/2");
        web.addJavascriptInterface(this, "Skyline");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) page("Beamer nicht erreichbar", "Ist er eingeschaltet und im selben WLAN wie dieses Handy?", null, true);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
        });
        setContentView(web);
        connect(false);
    }

    /** Zuletzt benutzten Beamer prüfen, sonst suchen. */
    private void connect(final boolean forceSearch) {
        final String last = prefs.getString("host", "");
        page(forceSearch || last.isEmpty() ? "Suche Beamer …" : "Verbinde …", "Im WLAN nach Skyline suchen", null, false);
        new Thread(new Runnable() {
            @Override public void run() {
                if (!forceSearch && !last.isEmpty() && probe(last, 1500) != null) { open(last); return; }
                runSearch();
            }
        }, "connect").start();
    }

    private void open(final String host) {
        prefs.edit().putString("host", host).apply();
        ui.post(new Runnable() { @Override public void run() { web.loadUrl("http://" + host + "/"); } });
    }

    /** Antwortet unter host ein Skyline-Beamer? Dann sein Name, sonst null. */
    private static String probe(String host, int timeout) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + "/api/state").openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout + 1500);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) > 0 && b.size() < 512 * 1024) b.write(buf, 0, n);
                JSONObject j = new JSONObject(b.toString("UTF-8"));
                if (!j.has("helper") || !j.has("apps")) return null;
                String name = j.optString("name", "");
                return name.isEmpty() ? "Skyline" : name;
            } finally { c.disconnect(); }
        } catch (Exception e) { return null; }
    }

    // ---------- Suche ----------

    private void runSearch() {
        if (searching) return;
        searching = true;
        final Map<String, String> found = Collections.synchronizedMap(new LinkedHashMap<String, String>());
        final NsdManager nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        final List<NsdServiceInfo> queue = Collections.synchronizedList(new ArrayList<NsdServiceInfo>());
        NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceFound(NsdServiceInfo i) { queue.add(i); }
            @Override public void onServiceLost(NsdServiceInfo i) { }
        };
        try { nsd.discoverServices("_skyline._tcp", NsdManager.PROTOCOL_DNS_SD, dl); } catch (Exception ignored) { }

        // Parallel: eigenes Netz durchgehen (falls der Router mDNS nicht weiterreicht)
        ExecutorService pool = Executors.newFixedThreadPool(48);
        String base = subnet();
        if (base != null) for (int i = 1; i < 255; i++) {
            final String host = base + i + ":" + PORT;
            pool.execute(new Runnable() {
                @Override public void run() { String n = probe(host, 600); if (n != null) found.put(host, n); }
            });
        }
        pool.shutdown();
        // mDNS-Funde nacheinander auflösen (ältere Android-Versionen können nur eins gleichzeitig)
        long until = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < until) {
            NsdServiceInfo next = null;
            synchronized (queue) { if (!queue.isEmpty()) next = queue.remove(0); }
            if (next != null) resolve(nsd, next, found);
            else try { Thread.sleep(150); } catch (InterruptedException e) { break; }
            if (pool.isTerminated() && queue.isEmpty() && !found.isEmpty()) break;
        }
        try { pool.awaitTermination(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        try { nsd.stopServiceDiscovery(dl); } catch (Exception ignored) { }
        searching = false;

        final Map<String, String> res = new LinkedHashMap<>(found);
        if (res.size() == 1) { open(res.keySet().iterator().next()); return; }
        ui.post(new Runnable() {
            @Override public void run() {
                if (res.isEmpty()) page("Kein Beamer gefunden", "Ist er eingeschaltet und im selben WLAN? Die Adresse steht beim ersten Start auf dem Beamer.", null, true);
                else page("Welcher Beamer?", res.size() + " Beamer mit Skyline gefunden", res, true);
            }
        });
    }

    private void resolve(NsdManager nsd, NsdServiceInfo info, final Map<String, String> found) {
        final Object lock = new Object();
        final boolean[] done = {false};
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int e) { finish(); }
                @Override public void onServiceResolved(final NsdServiceInfo i) {
                    new Thread(new Runnable() {     // Rückruf kommt auf dem Hauptthread: Prüfung im Hintergrund
                        @Override public void run() {
                            InetAddress a = i.getHost();
                            if (a instanceof Inet4Address) {
                                String host = a.getHostAddress() + ":" + i.getPort();
                                if (probe(host, 1500) != null) found.put(host, i.getServiceName());
                            }
                            finish();
                        }
                    }).start();
                }
                private void finish() { synchronized (lock) { done[0] = true; lock.notifyAll(); } }
            });
            synchronized (lock) { if (!done[0]) lock.wait(4000); }
        } catch (Exception ignored) { }
    }

    /** „192.168.178.“ aus der eigenen WLAN-Adresse. */
    private static String subnet() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.getName().startsWith("rmnet") || ni.getName().startsWith("ccmni")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        return ip.substring(0, ip.lastIndexOf('.') + 1);
                    }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ---------- Seiten (Suche, Auswahl, Fehler) im Stil der Fernbedienung ----------

    private void page(String title, String msg, Map<String, String> choices, boolean actions) {
        StringBuilder h = new StringBuilder("<!DOCTYPE html><html lang=de><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;padding:56px 22px;background:#0d0e11;color:#f4f4f6;font:16px/1.4 Roboto,sans-serif}"
                + "h1{font-size:24px;font-weight:600;margin:0 0 8px}p{color:#9b9ca5;margin:0 0 26px}"
                + "button{display:block;width:100%;padding:16px;border:0;border-radius:16px;font-size:17px;font-weight:600;margin-top:12px}"
                + ".pri{background:#f4f4f6;color:#0d0e11}.sec{background:#24262c;color:#f4f4f6}"
                + ".card{background:#18191e;border-radius:20px;padding:18px;margin-top:12px;text-align:left;box-shadow:0 10px 26px rgba(0,0,0,.35);color:#f4f4f6;font-weight:600}"
                + ".card small{display:block;color:#9b9ca5;font-weight:400;margin-top:3px;font-size:13px}"
                + "input{display:block;width:100%;box-sizing:border-box;margin:6px 0 4px;padding:14px;border:0;border-radius:14px;background:#24262c;color:#f4f4f6;font-size:17px}"
                + "label{font-size:13px;color:#9b9ca5}details{margin-top:28px}summary{color:#9b9ca5;font-size:14px}"
                + ".spin{width:34px;height:34px;border-radius:50%;border:3px solid #24262c;border-top-color:#f4f4f6;animation:r 1s linear infinite;margin:0 0 26px}"
                + "@keyframes r{to{transform:rotate(360deg)}}</style><body>");
        if (!actions) h.append("<div class=spin></div>");
        h.append("<h1>").append(esc(title)).append("</h1><p>").append(esc(msg)).append("</p>");
        if (choices != null) for (Map.Entry<String, String> e : choices.entrySet())
            h.append("<button class=card onclick=\"Skyline.choose('").append(esc(e.getKey())).append("')\">")
             .append(esc(e.getValue())).append("<small>").append(esc(e.getKey().replace(":" + PORT, ""))).append("</small></button>");
        if (actions) {
            h.append("<button class=").append(choices == null ? "pri" : "sec").append(" onclick='Skyline.search()'>Erneut suchen</button>");
            String last = prefs.getString("host", "");
            h.append("<details><summary>Adresse selbst eingeben</summary><label>Adresse des Beamers</label>")
             .append("<input id=h value='").append(esc(last.replace(":" + PORT, ""))).append("' placeholder='z. B. 192.168.0.20' inputmode=url autocapitalize=off>")
             .append("<button class=sec onclick='Skyline.retry(document.getElementById(\"h\").value)'>Verbinden</button></details>");
        }
        h.append("</body></html>");
        web.loadDataWithBaseURL("about:blank", h.toString(), "text/html", "utf-8", null);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    // ---------- Aufrufe aus den Seiten ----------

    @JavascriptInterface public void retry(final String host) {
        final String h0 = host.trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
        if (h0.isEmpty()) return;
        final String h = h0.contains(":") ? h0 : h0 + ":" + PORT;
        ui.post(new Runnable() { @Override public void run() { page("Verbinde …", h, null, false); } });
        new Thread(new Runnable() {
            @Override public void run() {
                if (probe(h, 2500) != null) open(h);
                else ui.post(new Runnable() { @Override public void run() { page("Keine Antwort", "Unter " + h + " antwortet kein Skyline-Beamer.", null, true); } });
            }
        }).start();
    }

    @JavascriptInterface public void choose(String host) { open(host); }

    /** Neu suchen, z. B. „Beamer wechseln“ in den Einstellungen der Fernbedienung. */
    @JavascriptInterface public void search() { ui.post(new Runnable() { @Override public void run() { connect(true); } }); }

    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onPause() { web.onPause(); super.onPause(); }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
