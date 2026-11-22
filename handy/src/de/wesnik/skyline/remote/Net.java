package de.wesnik.skyline.remote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Netzwerkzugriffe für Erweiterungen: TCP-Verbindungen, HTTP ohne CORS, Suche im eigenen /24-Netz, mDNS. */
final class Net {
    private final Main app;
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<Integer, Conn> socks = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newCachedThreadPool();

    Net(Main app) { this.app = app; }

    // ---------- Netz-Infos ----------

    JSONObject info() throws Exception {
        JSONObject o = new JSONObject();
        String[] me = ownAddress();
        o.put("ip", me == null ? JSONObject.NULL : me[0]);
        o.put("subnet", me == null ? JSONObject.NULL : me[1]);
        boolean wifi = false, online = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            if (c != null) {
                wifi = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                online = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
        } catch (Exception ignored) { }
        o.put("wifi", wifi);
        o.put("online", online);
        return o;
    }

    /** {ip, "192.168.0."} der WLAN-/LAN-Schnittstelle, sonst null. */
    static String[] ownAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = ni.getName();
                if (!ni.isUp() || ni.isLoopback() || name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun")) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        return new String[]{ip, ip.substring(0, ip.lastIndexOf('.') + 1)};
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ---------- TCP ----------

    private final class Conn {
        final int id; final String owner; final Socket s; final Charset cs; final boolean b64;
        final ExecutorService out = Executors.newSingleThreadExecutor();
        volatile boolean closed;
        Conn(int id, String owner, Socket s, String enc) {
            this.id = id; this.owner = owner; this.s = s;
            b64 = "base64".equals(enc);
            cs = Charset.forName(b64 || enc == null || enc.isEmpty() ? "ISO-8859-1" : enc);
        }
        void close(String why) {
            if (closed) return;
            closed = true;
            socks.remove(id);
            try { s.close(); } catch (Exception ignored) { }
            out.shutdownNow();
            JSONObject e = new JSONObject();
            try { e.put("sock", id); e.put("owner", owner); if (why != null) e.put("error", why); } catch (Exception ignored) { }
            app.event("tcp.close", e);
        }
    }

    void tcpOpen(final int reqId, final String owner, final JSONObject a) {
        io.execute(new Runnable() {
            @Override public void run() {
                Socket s = new Socket();
                try {
                    String host = a.getString("host");
                    int port = a.getInt("port");
                    s.connect(new InetSocketAddress(host, port), a.optInt("timeout", 4000));
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    String enc = a.optString("encoding", "latin1").toLowerCase();
                    if (enc.equals("latin1") || enc.isEmpty()) enc = "ISO-8859-1";
                    else if (enc.equals("utf8")) enc = "UTF-8";
                    final Conn c = new Conn(seq.incrementAndGet(), owner, s, enc);
                    socks.put(c.id, c);
                    JSONObject r = new JSONObject();
                    r.put("sock", c.id);
                    app.reply(reqId, true, r, null);
                    read(c);
                } catch (Exception e) {
                    try { s.close(); } catch (Exception ignored) { }
                    app.reply(reqId, false, null, Main.msg(e));
                }
            }
        });
    }

    private void read(Conn c) {
        byte[] buf = new byte[8192];
        try {
            InputStream in = c.s.getInputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                JSONObject e = new JSONObject();
                e.put("sock", c.id);
                e.put("owner", c.owner);
                e.put("data", c.b64 ? Base64.encodeToString(buf, 0, n, Base64.NO_WRAP) : new String(buf, 0, n, c.cs));
                app.event("tcp.data", e);
            }
            c.close(null);
        } catch (Exception e) {
            c.close(c.closed ? null : Main.msg(e));
        }
    }

    void tcpWrite(int sock, final String data) throws Exception {
        final Conn c = socks.get(sock);
        if (c == null) throw new Exception("Verbindung ist geschlossen");
        c.out.execute(new Runnable() {
            @Override public void run() {
                try {
                    OutputStream o = c.s.getOutputStream();
                    o.write(c.b64 ? Base64.decode(data, Base64.DEFAULT) : data.getBytes(c.cs));
                    o.flush();
                } catch (Exception e) { c.close(Main.msg(e)); }
            }
        });
    }

    void tcpClose(int sock) { Conn c = socks.get(sock); if (c != null) c.close(null); }

    void closeOwner(String owner) {
        for (Iterator<Conn> it = new ArrayList<>(socks.values()).iterator(); it.hasNext(); ) {
            Conn c = it.next();
            if (c.owner.equals(owner)) c.close(null);
        }
    }

    void closeAll() { for (Conn c : new ArrayList<>(socks.values())) c.close(null); }

    // ---------- HTTP ----------

    void http(final int reqId, final JSONObject a) {
        io.execute(new Runnable() {
            @Override public void run() {
                // Leseanfragen (GET) bis zu zweimal wiederholen: kleine Geräte-Server und WLAN brechen gelegentlich mitten in der Antwort ab
                boolean get = "GET".equalsIgnoreCase(a.optString("method", "GET"));
                Exception last = null;
                for (int i = 0; i < (get ? 3 : 1); i++) {
                    try { app.reply(reqId, true, request(a), null); return; }
                    catch (java.net.SocketTimeoutException | java.net.ConnectException | java.net.UnknownHostException e) { last = e; break; }
                    catch (Exception e) { last = e; }
                }
                app.reply(reqId, false, null, Main.msg(last));
            }
        });
    }

    static JSONObject request(JSONObject a) throws Exception {
        String url = a.getString("url");
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw new Exception("Nur http und https");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        int timeout = a.optInt("timeout", 8000);
        c.setConnectTimeout(Math.min(timeout, 6000));
        c.setReadTimeout(timeout);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        // Kleine Gerät-Server (Beamer, Receiver) schließen nach jeder Antwort; eine wiederverwendete Verbindung endet sonst mit „unexpected end of stream“
        c.setRequestProperty("Connection", "close");
        String method = a.optString("method", "GET").toUpperCase();
        c.setRequestMethod(method);
        JSONObject h = a.optJSONObject("headers");
        if (h != null) for (Iterator<String> k = h.keys(); k.hasNext(); ) { String n = k.next(); c.setRequestProperty(n, h.getString(n)); }
        try {
            if (a.has("body") && !a.isNull("body") && !method.equals("GET")) {
                byte[] body = "base64".equals(a.optString("bodyEncoding"))
                        ? Base64.decode(a.getString("body"), Base64.DEFAULT) : a.getString("body").getBytes("UTF-8");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream o = c.getOutputStream()) { o.write(body); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = in == null ? new byte[0] : readAll(in, a.optInt("maxBytes", 8 * 1024 * 1024));
            JSONObject r = new JSONObject();
            r.put("status", code);
            r.put("url", c.getURL().toString());
            JSONObject rh = new JSONObject();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet())
                if (e.getKey() != null && !e.getValue().isEmpty()) rh.put(e.getKey().toLowerCase(), e.getValue().get(0));
            r.put("headers", rh);
            if ("base64".equals(a.optString("responseType"))) r.put("body", Base64.encodeToString(data, Base64.NO_WRAP));
            else r.put("body", new String(data, charset(c.getContentType())));
            return r;
        } finally { c.disconnect(); }
    }

    private static Charset charset(String ctype) {
        if (ctype != null) {
            int i = ctype.toLowerCase().indexOf("charset=");
            if (i >= 0) try { return Charset.forName(ctype.substring(i + 8).replace("\"", "").split(";")[0].trim()); } catch (Exception ignored) { }
        }
        return Charset.forName("UTF-8");
    }

    static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        try (InputStream i = in) {
            while ((n = i.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > max) throw new Exception("Antwort zu groß");
            }
        }
        return b.toByteArray();
    }

    // ---------- Suche im eigenen Netz ----------

    /**
     * Prüft alle Adressen im eigenen /24-Netz auf einen offenen Port. Mit „path“ zusätzlich eine HTTP-Abfrage,
     * mit „contains“ muss die Antwort diesen Text enthalten. Ergebnis: [{ip, port, body?}].
     */
    void scan(final int reqId, final JSONObject a) {
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    String[] me = ownAddress();
                    if (me == null) throw new Exception("Kein WLAN");
                    final int port = a.getInt("port");
                    final int timeout = a.optInt("timeout", 400);
                    final String path = a.optString("path", "");
                    final String contains = a.optString("contains", "");
                    final String scheme = a.optString("scheme", "http");
                    final List<JSONObject> found = Collections.synchronizedList(new ArrayList<JSONObject>());
                    ExecutorService pool = Executors.newFixedThreadPool(64);
                    for (int i = 1; i < 255; i++) {
                        final String ip = me[1] + i;
                        pool.execute(new Runnable() {
                            @Override public void run() {
                                try (Socket s = new Socket()) {
                                    s.connect(new InetSocketAddress(ip, port), timeout);
                                } catch (Exception e) { return; }
                                try {
                                    JSONObject hit = new JSONObject();
                                    hit.put("ip", ip);
                                    hit.put("port", port);
                                    if (!path.isEmpty()) {
                                        JSONObject q = new JSONObject();
                                        q.put("url", scheme + "://" + ip + ":" + port + path);
                                        q.put("timeout", 2500);
                                        q.put("maxBytes", 256 * 1024);
                                        JSONObject r = request(q);
                                        String body = r.optString("body", "");
                                        if (r.getInt("status") >= 400 || (!contains.isEmpty() && !body.contains(contains))) return;
                                        hit.put("body", body.length() > 16384 ? body.substring(0, 16384) : body);
                                    }
                                    found.add(hit);
                                } catch (Exception ignored) { }
                            }
                        });
                    }
                    pool.shutdown();
                    pool.awaitTermination(a.optInt("maxTime", 12000), TimeUnit.MILLISECONDS);
                    pool.shutdownNow();
                    JSONArray out = new JSONArray();
                    synchronized (found) { for (JSONObject o : found) out.put(o); }
                    app.reply(reqId, true, out, null);
                } catch (Exception e) { app.reply(reqId, false, null, Main.msg(e)); }
            }
        });
    }

    // ---------- mDNS ----------

    /** Dienste eines Typs suchen (z. B. „_skyline._tcp“), eine Zeit lang sammeln und auflösen: [{name, host, port, txt}]. */
    void mdns(final int reqId, final JSONObject a) {
        io.execute(new Runnable() {
            @Override public void run() {
                final NsdManager nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
                final List<NsdServiceInfo> queue = Collections.synchronizedList(new ArrayList<NsdServiceInfo>());
                NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
                    @Override public void onStartDiscoveryFailed(String t, int e) { }
                    @Override public void onStopDiscoveryFailed(String t, int e) { }
                    @Override public void onDiscoveryStarted(String t) { }
                    @Override public void onDiscoveryStopped(String t) { }
                    @Override public void onServiceFound(NsdServiceInfo i) { queue.add(i); }
                    @Override public void onServiceLost(NsdServiceInfo i) { }
                };
                JSONArray out = new JSONArray();
                try {
                    nsd.discoverServices(a.getString("type"), NsdManager.PROTOCOL_DNS_SD, dl);
                    long until = System.currentTimeMillis() + a.optInt("timeout", 3000);
                    while (System.currentTimeMillis() < until) {
                        NsdServiceInfo next = null;
                        synchronized (queue) { if (!queue.isEmpty()) next = queue.remove(0); }
                        if (next != null) { JSONObject r = resolve(nsd, next); if (r != null) out.put(r); }
                        else Thread.sleep(120);
                    }
                    app.reply(reqId, true, out, null);
                } catch (Exception e) {
                    app.reply(reqId, false, null, Main.msg(e));
                } finally {
                    try { nsd.stopServiceDiscovery(dl); } catch (Exception ignored) { }
                }
            }
        });
    }

    private static JSONObject resolve(NsdManager nsd, NsdServiceInfo info) {
        final Object lock = new Object();
        final JSONObject[] res = {null};
        final boolean[] done = {false};
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo i, int e) { finish(null); }
                @Override public void onServiceResolved(NsdServiceInfo i) {
                    JSONObject o = null;
                    try {
                        InetAddress h = i.getHost();
                        if (h instanceof Inet4Address) {
                            o = new JSONObject();
                            o.put("name", i.getServiceName());
                            o.put("host", h.getHostAddress());
                            o.put("port", i.getPort());
                            JSONObject txt = new JSONObject();
                            for (Map.Entry<String, byte[]> e : i.getAttributes().entrySet())
                                txt.put(e.getKey(), e.getValue() == null ? "" : new String(e.getValue(), "UTF-8"));
                            o.put("txt", txt);
                        }
                    } catch (Exception ignored) { }
                    finish(o);
                }
                private void finish(JSONObject o) { synchronized (lock) { res[0] = o; done[0] = true; lock.notifyAll(); } }
            });
            synchronized (lock) { if (!done[0]) lock.wait(3000); }
        } catch (Exception ignored) { }
        return res[0];
    }
}
