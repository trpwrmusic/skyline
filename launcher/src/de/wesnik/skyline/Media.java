package de.wesnik.skyline;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Eigene Hintergründe: Videos in files/videos, Fotos in files/wallpapers, jeweils mit optionaler gleichnamiger .txt
 * (Zeile 1 Ort/Titel, Zeile 2 Nachweis, Zeile 3 Merkmale wie „schnee nacht winter“).
 * Hochladen, Auflisten, Löschen über die Handy-Fernbedienung; die Bühne lädt danach neu.
 */
final class Media {
    static final String[] VIDEO_EXT = {".mp4", ".m4v", ".mov", ".mkv", ".webm", ".3gp"};
    static final String[] IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".gif"};

    interface Listener { void onMediaChanged(); void onShow(String name); }

    private static final List<Listener> listeners = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void addListener(Listener l) { synchronized (listeners) { listeners.add(l); } }
    static void removeListener(Listener l) { synchronized (listeners) { listeners.remove(l); } }

    private static List<Listener> ls() { synchronized (listeners) { return new ArrayList<>(listeners); } }

    static void changed() {
        synchronized (thumbs) { thumbs.clear(); }
        main.post(new Runnable() { @Override public void run() { for (Listener l : ls()) l.onMediaChanged(); } });
    }

    /** Motiv, das gezeigt werden soll, sobald eine Bühne läuft (falls gerade keine läuft). */
    static volatile String pendingShow;

    static void show(final String name) {
        pendingShow = name;
        main.post(new Runnable() { @Override public void run() { for (Listener l : ls()) l.onShow(name); } });
    }

    static boolean isVideo(String n) { return endsWith(n, VIDEO_EXT); }
    static boolean isImage(String n) { return endsWith(n, IMAGE_EXT); }

    private static boolean endsWith(String n, String[] ext) {
        String l = n.toLowerCase(Locale.ROOT);
        for (String e : ext) if (l.endsWith(e)) return true;
        return false;
    }

    static File dirFor(Context c, String name) { return c.getExternalFilesDir(isVideo(name) ? "videos" : "wallpapers"); }

    /** Dateiname ohne Pfad und ohne Sonderzeichen. */
    static String clean(String n) {
        n = n.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).trim();
        n = n.replaceAll("[^A-Za-z0-9ÄÖÜäöüß ._()-]", "_").replaceAll("\\s+", " ");
        if (n.startsWith(".")) n = "_" + n;
        if (n.length() > 90) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 ? n.substring(dot) : "";
            n = n.substring(0, 90 - ext.length()) + ext;
        }
        return n;
    }

    /** Freier Name im Zielordner: „Foto.jpg“, sonst „Foto-2.jpg“ … */
    static File target(Context c, String name) {
        File dir = dirFor(c, name);
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; f.exists(); i++) f = new File(dir, base + "-" + i + ext);
        return f;
    }

    static File find(Context c, String name) {
        name = clean(name);
        if (!isVideo(name) && !isImage(name)) return null;
        File f = new File(dirFor(c, name), name);
        return f.isFile() ? f : null;
    }

    static File meta(File f) { return new File(f.getParentFile(), f.getName().replaceAll("\\.[^.]+$", "") + ".txt"); }

    /** {ort, nachweis, merkmale} */
    static String[] readMeta(File f) {
        String[] r = {"", "", ""};
        File m = meta(f);
        if (!m.exists()) return r;
        try (BufferedReader b = new BufferedReader(new InputStreamReader(new FileInputStream(m), "UTF-8"))) {
            for (int i = 0; i < 3; i++) { String l = b.readLine(); if (l == null) break; r[i] = l.trim(); }
        } catch (Exception ignored) { }
        return r;
    }

    static void writeMeta(File f, String ort, String credit, String tags) throws Exception {
        ort = ort == null ? "" : ort.replace('\n', ' ').trim();
        tags = tags == null ? "" : tags.toLowerCase(Locale.ROOT).replaceAll("[^a-zäöüß ]", " ").replaceAll("\\s+", " ").trim();
        File m = meta(f);
        if (ort.isEmpty() && tags.isEmpty() && (credit == null || credit.isEmpty())) { m.delete(); return; }
        try (OutputStream o = new FileOutputStream(m)) {
            o.write((ort + "\n" + (credit == null ? "" : credit) + "\n" + tags + "\n").getBytes("UTF-8"));
        }
    }

    /** Upload vom Socket direkt in die Datei streamen (Videos können groß sein). */
    static File receive(Context c, InputStream in, long len, String name, String ort, String tags) throws Exception {
        File f = target(c, name);
        File dir = f.getParentFile();
        if (dir == null || (!dir.exists() && !dir.mkdirs())) throw new Exception("Speicher nicht verfügbar");
        if (dir.getUsableSpace() - len < 300L * 1024 * 1024) throw new Exception("Zu wenig Speicher frei auf dem Beamer");
        File part = new File(dir, "." + f.getName() + ".part");
        long got = 0;
        try (OutputStream o = new FileOutputStream(part)) {
            byte[] buf = new byte[256 * 1024];
            while (got < len) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, len - got));
                if (n < 0) break;
                o.write(buf, 0, n);
                got += n;
            }
        }
        if (got != len) { part.delete(); throw new Exception("Übertragung abgebrochen"); }
        if (!part.renameTo(f)) { part.delete(); throw new Exception("Speichern fehlgeschlagen"); }
        writeMeta(f, ort, "", tags);
        changed();
        return f;
    }

    static boolean delete(Context c, String name) {
        File f = find(c, name);
        if (f == null) return false;
        boolean ok = f.delete();
        meta(f).delete();
        changed();
        return ok;
    }

    static boolean setMeta(Context c, String name, String ort, String tags) {
        File f = find(c, name);
        if (f == null) return false;
        try { writeMeta(f, ort, readMeta(f)[1], tags); } catch (Exception e) { return false; }
        changed();
        return true;
    }

    static String list(Context c, String playing) throws Exception {
        JSONArray a = new JSONArray();
        long free = 0;
        for (String sub : new String[]{"videos", "wallpapers"}) {
            File dir = c.getExternalFilesDir(sub);
            if (dir == null) continue;
            free = dir.getUsableSpace();
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            Arrays.sort(fs);
            for (File f : fs) {
                String n = f.getName();
                if (n.startsWith(".") || !(isVideo(n) || isImage(n))) continue;
                String[] m = readMeta(f);
                a.put(new JSONObject().put("name", n).put("video", isVideo(n)).put("size", f.length())
                        .put("ort", m[0]).put("tags", m[2]).put("playing", n.equals(playing)));
            }
        }
        return new JSONObject().put("items", a).put("free", free).toString();
    }

    private static final Map<String, byte[]> thumbs = new HashMap<>();

    /** Vorschaubild 320 px breit (JPEG), für Videos ein Bild aus der Mitte. */
    static byte[] thumb(Context c, String name) {
        synchronized (thumbs) { byte[] t = thumbs.get(name); if (t != null) return t; }
        File f = find(c, name);
        if (f == null) return null;
        Bitmap b = null;
        try {
            if (isVideo(f.getName())) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(f.getAbsolutePath());
                    long dur = 0;
                    try { dur = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)); } catch (Exception ignored) { }
                    b = r.getScaledFrameAtTime(dur * 500, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180);
                } finally { r.release(); }
            } else {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                int s = 1;
                while (o.outWidth / (s * 2) >= 320) s *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = s;
                b = Stage.rotateByExif(BitmapFactory.decodeFile(f.getAbsolutePath(), d), f.getAbsolutePath());
            }
        } catch (Throwable ignored) { }
        if (b == null) return null;
        if (b.getWidth() > 320) {
            Bitmap s = Bitmap.createScaledBitmap(b, 320, Math.max(1, b.getHeight() * 320 / b.getWidth()), true);
            if (s != b) b.recycle();
            b = s;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, bo);
        b.recycle();
        byte[] out = bo.toByteArray();
        synchronized (thumbs) { if (thumbs.size() > 60) thumbs.clear(); thumbs.put(name, out); }
        return out;
    }
}
