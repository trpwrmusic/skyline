package de.wesnik.skyline.remote;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Selbst-Update der Handy-App über GitHub-Releases (wie beim Beamer). Die Versionsnummer der App ist die Build-Nummer
 * des Releases („build-1008“ → versionCode 1008). Installiert wird über den PackageInstaller: Android fragt dabei
 * einmal nach („Installieren?“) und prüft selbst, dass die neue APK mit demselben Schlüssel signiert ist.
 */
final class AppUpdate {
    static final String REPO = "trpwrmusic/skyline";
    static final String ASSET = "skyline-remote.apk";
    private static final String ACTION = "de.wesnik.skyline.remote.INSTALL_STATUS";
    private final Main app;
    private volatile boolean busy;

    AppUpdate(Main app) {
        this.app = app;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { status(i); }
        };
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, new IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(r, new IntentFilter(ACTION));
    }

    /** Neuestes Release: {available, code, name, notes, url, size, current}. */
    JSONObject check(long current) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        JSONObject rel;
        try {
            if (c.getResponseCode() != 200) throw new Exception("GitHub antwortet nicht (" + c.getResponseCode() + ")");
            rel = new JSONObject(new String(Net.readAll(c.getInputStream(), 2 * 1024 * 1024), "UTF-8"));
        } finally { c.disconnect(); }
        String tag = rel.optString("tag_name", "");
        long code = tag.startsWith("build-") ? Long.parseLong(tag.substring(6).replaceAll("\\D", "")) : 0;
        String url = null;
        long size = 0;
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (ASSET.equals(a.optString("name"))) { url = a.optString("browser_download_url"); size = a.optLong("size"); }
        }
        String notes = rel.optString("body", "").trim();
        int nl = notes.indexOf('\n');
        JSONObject o = new JSONObject();
        o.put("current", current);
        o.put("code", code);
        o.put("name", rel.optString("name", tag));
        o.put("notes", nl > 0 ? notes.substring(0, nl).trim() : notes);
        o.put("url", url == null ? JSONObject.NULL : url);
        o.put("size", size);
        o.put("available", url != null && code > current);
        return o;
    }

    /** Laden (mit Fortschritt als Ereignis „app.update“) und an Android zur Installation geben. */
    void install(final int reqId, final String url) {
        if (busy) { app.reply(reqId, false, null, "Update läuft schon"); return; }
        busy = true;
        app.work.execute(new Runnable() {
            @Override public void run() {
                File f = new File(app.getCacheDir(), "update.apk");
                try {
                    download(url, f);
                    event("installing", 1, null);
                    commit(f);
                    app.reply(reqId, true, true, null);
                } catch (Exception e) {
                    busy = false;
                    app.reply(reqId, false, null, Main.msg(e));
                }
            }
        });
    }

    private void download(String url, File f) throws Exception {
        if (!url.startsWith("https://")) throw new Exception("Nur https");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != 200) throw new Exception("Download fehlgeschlagen (" + c.getResponseCode() + ")");
            long total = c.getContentLengthLong(), done = 0, last = 0;
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(f)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (total > 0 && now - last > 150) { last = now; event("download", (double) done / total, null); }
                }
            }
        } finally { c.disconnect(); }
    }

    private void commit(File f) throws Exception {
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(app.getPackageName());
        p.setSize(f.length());
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int sid = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(sid)) {
            try (OutputStream o = s.openWrite("skyline.apk", 0, f.length()); InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                s.fsync(o);
            }
            Intent cb = new Intent(ACTION).setPackage(app.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(app, sid, cb, flags).getIntentSender());
        }
    }

    /** Rückmeldung des Installers: Bestätigung zeigen, Fehler melden. Bei Erfolg beendet Android die App und startet die neue. */
    private void status(Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { app.startActivity(confirm); event("confirm", 1, null); } catch (Exception e) { busy = false; event("error", 0, "Installation ließ sich nicht starten"); }
            }
            return;
        }
        busy = false;
        if (st == PackageInstaller.STATUS_SUCCESS) { event("done", 1, null); return; }
        String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (st == PackageInstaller.STATUS_FAILURE_ABORTED) event("aborted", 0, "Abgebrochen");
        else if (st == PackageInstaller.STATUS_FAILURE_CONFLICT) event("error", 0, "Signatur passt nicht zur installierten App");
        else if (st == PackageInstaller.STATUS_FAILURE_STORAGE) event("error", 0, "Zu wenig Speicher");
        else event("error", 0, m == null || m.isEmpty() ? "Installation fehlgeschlagen" : m);
    }

    private void event(String phase, double progress, String error) {
        JSONObject e = new JSONObject();
        try { e.put("phase", phase); e.put("progress", progress); if (error != null) e.put("error", error); } catch (Exception ignored) { }
        app.event("app.update", e);
    }
}
