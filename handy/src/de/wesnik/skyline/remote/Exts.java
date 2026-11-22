package de.wesnik.skyline.remote;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installierte Erweiterungen unter files/ext/&lt;id&gt;/ (Manifest manifest.json im Wurzelordner).
 * Quellen: ZIP aus dem Katalog (Internet), ZIP aus einer Datei oder die mitgelieferte Fassung aus assets/ext/&lt;id&gt;/.
 * Entfernen verschiebt in files/ext-trash, damit „Rückgängig“ geht; der Papierkorb wird beim nächsten Start geleert.
 */
final class Exts {
    static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,30}$");
    private static final long MAX_ZIP = 64L * 1024 * 1024;
    private final Main app;
    private final File root, trash;

    Exts(Main app) {
        this.app = app;
        root = new File(app.getFilesDir(), "ext");
        trash = new File(app.getFilesDir(), "ext-trash");
        root.mkdirs();
    }

    /** Datei einer installierten Erweiterung, oder null bei unzulässigem Pfad. */
    File file(String id, String path) throws Exception {
        if (!ID.matcher(id).matches()) return null;
        File base = new File(root, id).getCanonicalFile();
        File f = new File(base, path).getCanonicalFile();
        if (!f.getPath().startsWith(base.getPath() + File.separator)) return null;
        return f;
    }

    JSONArray list() {
        JSONArray out = new JSONArray();
        File[] dirs = root.listFiles();
        if (dirs != null) for (File d : dirs) {
            if (!d.isDirectory() || d.getName().startsWith(".")) continue;
            try {
                JSONObject m = readManifest(new File(d, "manifest.json"));
                m.put("installed", new File(d, ".source").exists() ? read(new File(d, ".source")) : "bundled");
                m.put("installedAt", d.lastModified());
                out.put(m);
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** Manifeste der mitgelieferten Erweiterungen (assets/ext/&lt;id&gt;/manifest.json). */
    JSONArray bundled() {
        JSONArray out = new JSONArray();
        try {
            AssetManager am = app.getAssets();
            String[] ids = am.list("ext");
            if (ids != null) for (String id : ids) {
                try (InputStream in = am.open("ext/" + id + "/manifest.json")) {
                    out.put(new JSONObject(new String(Net.readAll(in, 256 * 1024), "UTF-8")));
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return out;
    }

    JSONObject installAsset(String id) throws Exception {
        if (!ID.matcher(id).matches()) throw new Exception("Ungültige Kennung");
        File tmp = new File(root, ".tmp-" + id);
        deleteTree(tmp);
        copyAssets(app.getAssets(), "ext/" + id, tmp);
        return commit(tmp, id, "bundled");
    }

    JSONObject installUrl(String url, String sha256, String expectId, int reqId) throws Exception {
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw new Exception("Nur http und https");
        File dl = new File(app.getCacheDir(), "ext-download.zip");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("Download fehlgeschlagen (" + code + ")");
            long total = c.getContentLengthLong(), done = 0, lastEvt = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dl)) {
                byte[] buf = new byte[32768]; int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    done += n;
                    if (done > MAX_ZIP) throw new Exception("Erweiterung ist zu groß");
                    long now = System.currentTimeMillis();
                    if (total > 0 && now - lastEvt > 120) {
                        lastEvt = now;
                        JSONObject e = new JSONObject();
                        e.put("re", reqId);
                        e.put("id", expectId);
                        e.put("progress", (double) done / total);
                        app.event("exts.progress", e);
                    }
                }
            }
        } finally { c.disconnect(); }
        if (!sha256.isEmpty()) {
            StringBuilder h = new StringBuilder();
            for (byte b : md.digest()) h.append(String.format("%02x", b));
            if (!h.toString().equalsIgnoreCase(sha256)) { dl.delete(); throw new Exception("Prüfsumme stimmt nicht, Download beschädigt"); }
        }
        try (InputStream in = new FileInputStream(dl)) {
            return installZip(in, expectId.isEmpty() ? null : expectId, url);
        } finally { dl.delete(); }
    }

    JSONObject installZip(InputStream in, String expectId, String source) throws Exception {
        File tmp = new File(root, ".tmp-" + System.nanoTime());
        deleteTree(tmp);
        tmp.mkdirs();
        String tmpBase = tmp.getCanonicalPath() + File.separator;
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[32768];
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) continue;
                File f = new File(tmp, name).getCanonicalFile();
                if (!f.getPath().startsWith(tmpBase)) throw new Exception("Ungültiger Pfad im Archiv");
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) {
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_ZIP * 2) throw new Exception("Erweiterung ist zu groß");
                        o.write(buf, 0, n);
                    }
                }
            }
        } catch (Exception e) { deleteTree(tmp); throw e; }
        // Archiv mit einem einzigen Oberordner (z. B. „denon/manifest.json“): diesen als Wurzel nehmen
        File base = tmp;
        if (!new File(tmp, "manifest.json").exists()) {
            File[] kids = tmp.listFiles();
            if (kids != null && kids.length == 1 && kids[0].isDirectory() && new File(kids[0], "manifest.json").exists()) base = kids[0];
            else { deleteTree(tmp); throw new Exception("Keine manifest.json im Archiv"); }
        }
        JSONObject m;
        try { m = readManifest(new File(base, "manifest.json")); }
        catch (Exception e) { deleteTree(tmp); throw e; }
        String id = m.getString("id");
        if (expectId != null && !expectId.equals(id)) { deleteTree(tmp); throw new Exception("Archiv enthält „" + id + "“ statt „" + expectId + "“"); }
        if (base != tmp) {
            File moved = new File(root, ".tmp-" + id + "-" + System.nanoTime());
            if (!base.renameTo(moved)) { deleteTree(tmp); throw new Exception("Entpacken fehlgeschlagen"); }
            deleteTree(tmp);
            tmp = moved;
        }
        return commit(tmp, id, source);
    }

    private JSONObject commit(File tmp, String id, String source) throws Exception {
        JSONObject m = readManifest(new File(tmp, "manifest.json"));
        if (!id.equals(m.getString("id"))) { deleteTree(tmp); throw new Exception("Kennung passt nicht"); }
        write(new File(tmp, ".source"), source);
        File dest = new File(root, id);
        File old = new File(root, ".old-" + id);
        deleteTree(old);
        if (dest.exists() && !dest.renameTo(old)) { deleteTree(tmp); throw new Exception("Alte Fassung lässt sich nicht ersetzen"); }
        if (!tmp.renameTo(dest)) { old.renameTo(dest); deleteTree(tmp); throw new Exception("Installation fehlgeschlagen"); }
        deleteTree(old);
        m.put("installed", source);
        m.put("installedAt", dest.lastModified());
        return m;
    }

    static JSONObject readManifest(File f) throws Exception {
        JSONObject m = new JSONObject(read(f));
        String id = m.optString("id", "");
        if (!ID.matcher(id).matches()) throw new Exception("Ungültige Kennung im Manifest");
        if (m.optString("name", "").isEmpty()) throw new Exception("Manifest ohne Namen");
        if (m.optString("version", "").isEmpty()) throw new Exception("Manifest ohne Version");
        return m;
    }

    void remove(String id) throws Exception {
        if (!ID.matcher(id).matches()) throw new Exception("Ungültige Kennung");
        trash.mkdirs();
        File t = new File(trash, id);
        deleteTree(t);
        File d = new File(root, id);
        if (d.exists() && !d.renameTo(t)) throw new Exception("Entfernen fehlgeschlagen");
    }

    JSONObject restore(String id) throws Exception {
        if (!ID.matcher(id).matches()) throw new Exception("Ungültige Kennung");
        File t = new File(trash, id), d = new File(root, id);
        if (!t.exists()) throw new Exception("Nicht mehr im Papierkorb");
        deleteTree(d);
        if (!t.renameTo(d)) throw new Exception("Wiederherstellen fehlgeschlagen");
        JSONObject m = readManifest(new File(d, "manifest.json"));
        m.put("installed", new File(d, ".source").exists() ? read(new File(d, ".source")) : "bundled");
        return m;
    }

    void purgeTrash() {
        deleteTree(trash);
        File[] l = root.listFiles();
        if (l != null) for (File f : l) if (f.getName().startsWith(".tmp-") || f.getName().startsWith(".old-")) deleteTree(f);
    }

    // ---------- Hilfen ----------

    private static void copyAssets(AssetManager am, String path, File dest) throws Exception {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {
            dest.getParentFile().mkdirs();
            try (InputStream in = am.open(path); OutputStream o = new FileOutputStream(dest)) {
                byte[] buf = new byte[32768]; int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            return;
        }
        dest.mkdirs();
        for (String k : kids) copyAssets(am, path + "/" + k, new File(dest, k));
    }

    static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) { return new String(Net.readAll(in, 1024 * 1024), "UTF-8"); }
    }

    static void write(File f, String s) throws Exception {
        try (OutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
