package de.wesnik.skyline;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selbst-Update über GitHub Releases.
 * Die GitHub-Action baut bei jedem Push eine signierte APK und legt ein Release mit dem Tag „build-<versionCode>“ an.
 * Skyline schaut beim Start, beim Aufwachen des Beamers und alle 6 Stunden nach, lädt eine neuere APK und lässt sie
 * vom Shell-Helfer (KeyServer, darf „pm install“) ohne Rückfrage installieren.
 *
 * Einstellungen (Prefs „skyline“): upd_auto (an/aus), upd_repo („besitzer/repo“), upd_token (nur für private Repos).
 */
final class Updater {
    static final String DEFAULT_REPO = "trpwrmusic/skyline";

    interface Shell { String cmd(String c); }

    static volatile String status = "";
    private static volatile boolean busy;
    private static long lastCheck;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("skyline", Context.MODE_PRIVATE); }

    static String repo(Context c) { String r = prefs(c).getString("upd_repo", ""); return r.isEmpty() ? DEFAULT_REPO : r; }

    static boolean auto(Context c) { return prefs(c).getBoolean("upd_auto", true); }

    static long installedCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode(); } catch (Exception e) { return 0; }
    }

    static String installedName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    /**
     * Nach Updates schauen und ggf. installieren. manual = über „Jetzt prüfen“ (ohne Mindestabstand, auch bei auto = aus).
     * restartHome: nach der Installation den Startbildschirm wieder öffnen (weil er gerade vorne war).
     * Rückgabe: true, wenn ein Netzfehler auftrat (Aufrufer darf später erneut versuchen).
     */
    static boolean check(Context c, Shell shell, boolean manual, boolean restartHome) {
        if (busy) return false;
        if (!manual && !auto(c)) { status = "Automatische Updates sind aus"; return false; }
        long now = System.currentTimeMillis();
        if (!manual && now - lastCheck < 10 * 60_000L) return false;
        busy = true;
        lastCheck = now;
        try {
            return run(c, shell, manual, restartHome);
        } finally { busy = false; }
    }

    private static boolean run(Context c, Shell shell, boolean manual, boolean restartHome) {
        SharedPreferences p = prefs(c);
        String repo = repo(c), token = p.getString("upd_token", "");
        long have = installedCode(c);
        status = "Prüfe " + repo + " …";
        JSONObject rel;
        try {
            HttpURLConnection h = open("https://api.github.com/repos/" + repo + "/releases/latest", token, "application/vnd.github+json");
            int code = h.getResponseCode();
            if (code == 404) {
                status = token.isEmpty() ? "Kein Release gefunden (Repo privat? Dann Token eintragen)" : "Kein Release gefunden";
                h.disconnect();
                return false;
            }
            if (code == 401 || code == 403) { status = "GitHub verweigert den Zugriff (" + code + "), Token prüfen"; h.disconnect(); return false; }
            if (code != 200) { status = "GitHub antwortet " + code; h.disconnect(); return true; }
            rel = new JSONObject(readAll(h));
        } catch (Exception e) {
            status = "Keine Verbindung zu GitHub";
            return true;
        }
        String tag = rel.optString("tag_name", "");
        long code = -1;
        Matcher m = Pattern.compile("(\\d+)\\s*$").matcher(tag);
        if (m.find()) try { code = Long.parseLong(m.group(1)); } catch (Exception ignored) { }
        String when = new java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(new java.util.Date());
        if (code >= 0 && code <= have) {
            status = "Aktuell: " + installedName(c) + " (" + have + "), geprüft " + when;
            return false;
        }
        if (!manual && code >= 0 && code == p.getLong("upd_failed", -1)) {
            status = "Build " + code + " ließ sich nicht installieren, wird übersprungen (Jetzt prüfen versucht es erneut)";
            return false;
        }
        // APK im Release suchen
        String assetUrl = null;
        JSONArray as = rel.optJSONArray("assets");
        if (as != null) for (int i = 0; i < as.length(); i++) {
            JSONObject a = as.optJSONObject(i);
            if (a == null) continue;
            String n = a.optString("name").toLowerCase(java.util.Locale.ROOT);
            // Das Release enthält auch die Handy-App (skyline-remote.apk): nur die Beamer-APK nehmen
            if (n.equals("skyline.apk")) { assetUrl = a.optString("url"); break; }
            if (assetUrl == null && n.endsWith(".apk") && !n.contains("remote")) assetUrl = a.optString("url");
        }
        if (assetUrl == null || assetUrl.isEmpty()) { status = "Release " + tag + " enthält keine APK"; return false; }

        File dir = c.getExternalFilesDir("update");
        if (dir == null) { status = "Kein Speicher für das Update"; return false; }
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File apk = new File(dir, "skyline-" + (code >= 0 ? code : "neu") + ".apk");
        status = "Lade " + tag + " …";
        try {
            download(assetUrl, token, apk);
        } catch (Exception e) {
            apk.delete();
            status = "Download fehlgeschlagen";
            return true;
        }
        // Prüfen: richtiges Paket, wirklich neuer, gleiche Signatur
        PackageManager pm = c.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null || !c.getPackageName().equals(info.packageName)) { apk.delete(); status = "Release-APK ist keine Skyline-APK"; return false; }
        if (info.getLongVersionCode() <= have) {
            apk.delete();
            status = "Aktuell: " + installedName(c) + " (" + have + "), geprüft " + when;
            return false;
        }
        if (!sameSigner(c, info)) { apk.delete(); status = "Release ist anders signiert, Update abgelehnt"; return false; }

        String target = info.versionName + " (" + info.getLongVersionCode() + ")";
        new File(dir, "result.txt").delete();
        p.edit().putString("upd_pending", target).putLong("upd_pending_code", info.getLongVersionCode()).commit();
        status = "Installiere " + target + " …";
        String r = shell.cmd("install " + apk.getAbsolutePath() + " " + (restartHome ? 1 : 0));
        if (r == null || !r.startsWith("ok")) {
            status = "Helfer nicht bereit, Update " + target + " liegt bereit";
            return true;
        }
        // Ab hier beendet Android normalerweise diesen Prozess. Kommt er zurück, ist die Installation gescheitert.
        for (int i = 0; i < 120; i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
            File res = new File(dir, "result.txt");
            if (res.exists()) {
                String txt = read(res).trim();
                if (txt.startsWith("ok")) { status = "Installiert: " + target; return false; }
                status = "Installation fehlgeschlagen: " + txt.replaceFirst("^err\\s*", "");
                p.edit().putLong("upd_failed", info.getLongVersionCode()).remove("upd_pending").apply();
                return false;
            }
        }
        status = "Installation läuft ohne Rückmeldung";
        return false;
    }

    /** Nach dem Neustart in neuer Version: Meldung für den Startbildschirm (einmal), sonst null. */
    static String announce(Context c) {
        SharedPreferences p = prefs(c);
        String target = p.getString("upd_pending", null);
        if (target == null) return null;
        long want = p.getLong("upd_pending_code", -1);
        if (installedCode(c) < want) return null;            // läuft noch (oder scheiterte: dann räumt run() auf)
        p.edit().remove("upd_pending").remove("upd_pending_code").remove("upd_failed").apply();
        status = "Installiert: " + target;
        return "Skyline wurde aktualisiert auf " + target;
    }

    private static boolean sameSigner(Context c, PackageInfo neu) {
        try {
            PackageInfo cur = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] a = cur.signingInfo.getApkContentsSigners(), b = neu.signingInfo.getApkContentsSigners();
            return a.length > 0 && b.length > 0 && Arrays.equals(a[0].toByteArray(), b[0].toByteArray());
        } catch (Exception e) { return false; }
    }

    private static HttpURLConnection open(String u, String token, String accept) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(30000);
        h.setInstanceFollowRedirects(false);
        h.setRequestProperty("User-Agent", "Skyline-Updater");
        h.setRequestProperty("Accept", accept);
        h.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.isEmpty()) h.setRequestProperty("Authorization", "Bearer " + token);
        return h;
    }

    /** Asset über die API laden; GitHub leitet auf einen signierten Speicher-Link um, der kein Token braucht. */
    private static void download(String api, String token, File out) throws Exception {
        HttpURLConnection h = open(api, token, "application/octet-stream");
        for (int hop = 0; hop < 5; hop++) {
            int code = h.getResponseCode();
            if (code / 100 == 3) {
                String loc = h.getHeaderField("Location");
                h.disconnect();
                h = open(loc, null, "application/octet-stream");
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
            File part = new File(out.getPath() + ".part");
            try (InputStream in = h.getInputStream(); OutputStream o = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024]; int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            } finally { h.disconnect(); }
            if (!part.renameTo(out)) throw new Exception("Umbenennen");
            return;
        }
        throw new Exception("Zu viele Weiterleitungen");
    }

    private static String readAll(HttpURLConnection h) throws Exception {
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally { h.disconnect(); }
    }

    private static String read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }
}
