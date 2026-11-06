package de.wesnik.skyline;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Was ist draußen los? Wetter, Tageszeit und Jahreszeit für die Videoauswahl („das Fenster zeigt, was draußen ist“).
 * Ort: einmal täglich per IP (ipapi.co, Fallback ipwho.is) oder fest aus den Einstellungen.
 * Wetter + Sonnenauf-/untergang: Open-Meteo (ohne Schlüssel), alle 15 Minuten.
 */
final class Weather {
    /** wetter: klar | wolkig | regen | schnee;  zeit: tag | daemmerung | nacht;  saison: fruehling | sommer | herbst | winter */
    static final class Now {
        final String wetter, zeit, saison, ort;
        final double temp;
        final int code;          // WMO-Wettercode (für Symbol und Beschreibung)
        Now(String w, String z, String s, String o, double t, int c) { wetter = w; zeit = z; saison = s; ort = o; temp = t; code = c; }
        /** Testwetter: Code aus der Kategorie ableiten. */
        Now(String w, String z, String s, String o, double t) { this(w, z, s, o, t, w.equals("schnee") ? 71 : w.equals("regen") ? 61 : w.equals("wolkig") ? 3 : 0); }
        boolean night() { return zeit.equals("nacht"); }
        String key() { return wetter + "/" + zeit + "/" + saison; }
        @Override public String toString() { return wetter + ", " + zeit + ", " + saison + (ort.isEmpty() ? "" : " (" + ort + ")"); }
    }

    interface Listener { void onWeather(Now n); }

    private static final List<Listener> listeners = new ArrayList<>();
    private static volatile Now current, override;
    private static Handler main;
    private static Context app;
    private static boolean started;
    private static Thread worker;

    static Now get() { return override != null ? override : current; }

    static void addListener(Listener l) {
        synchronized (listeners) { listeners.add(l); }
        Now n = get();
        if (n != null) l.onWeather(n);
    }

    static void removeListener(Listener l) { synchronized (listeners) { listeners.remove(l); } }

    /** Zum Ausprobieren über die Fernbedienungs-API; null = wieder echtes Wetter. */
    static void setOverride(Now n) { override = n; publish(); }

    static synchronized void start(Context c) {
        if (started) return;
        started = true;
        app = c.getApplicationContext();
        main = new Handler(Looper.getMainLooper());
        worker = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try { refresh(); } catch (Throwable ignored) { }
                    try { Thread.sleep(current == null ? 60_000 : 15 * 60_000); } catch (InterruptedException e) { /* sofort neu abfragen */ }
                }
            }
        }, "wetter");
        worker.start();
    }

    /** Ort fest einstellen (überschreibt die IP-Ermittlung) und sofort neu abfragen. */
    static void setLocation(Context c, double lat, double lon, String ort) {
        c.getSharedPreferences("skyline", Context.MODE_PRIVATE).edit()
                .putLong("wx_lat", Double.doubleToLongBits(lat)).putLong("wx_lon", Double.doubleToLongBits(lon))
                .putString("wx_ort", ort).putBoolean("wx_fixed", true).apply();
        if (worker != null) worker.interrupt();
    }

    private static void publish() {
        final Now n = get();
        if (n == null || main == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                List<Listener> ls;
                synchronized (listeners) { ls = new ArrayList<>(listeners); }
                for (Listener l : ls) l.onWeather(n);
            }
        });
    }

    private static void refresh() throws Exception {
        SharedPreferences p = app.getSharedPreferences("skyline", Context.MODE_PRIVATE);
        double lat = Double.longBitsToDouble(p.getLong("wx_lat", Double.doubleToLongBits(Double.NaN)));
        double lon = Double.longBitsToDouble(p.getLong("wx_lon", Double.doubleToLongBits(Double.NaN)));
        String ort = p.getString("wx_ort", "");
        long locAt = p.getLong("wx_loc_at", 0);
        boolean fixed = p.getBoolean("wx_fixed", false);
        if (!fixed && (Double.isNaN(lat) || System.currentTimeMillis() - locAt > 24 * 3600_000L)) {
            try {
                JSONObject j = new JSONObject(get("https://ipapi.co/json/"));
                lat = j.getDouble("latitude"); lon = j.getDouble("longitude"); ort = j.optString("city", "");
            } catch (Exception e) {
                JSONObject j = new JSONObject(get("https://ipwho.is/"));
                lat = j.getDouble("latitude"); lon = j.getDouble("longitude"); ort = j.optString("city", "");
            }
            p.edit().putLong("wx_lat", Double.doubleToLongBits(lat)).putLong("wx_lon", Double.doubleToLongBits(lon))
                    .putString("wx_ort", ort).putLong("wx_loc_at", System.currentTimeMillis()).apply();
        }
        if (Double.isNaN(lat)) return;
        String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=weather_code,is_day,temperature_2m&daily=sunrise,sunset&timezone=auto&forecast_days=1", lat, lon);
        JSONObject j = new JSONObject(get(url));
        JSONObject cur = j.getJSONObject("current");
        int code = cur.getInt("weather_code");
        boolean day = cur.optInt("is_day", 1) == 1;
        double temp = cur.optDouble("temperature_2m", Double.NaN);
        int off = j.optInt("utc_offset_seconds", 0);
        JSONObject daily = j.getJSONObject("daily");
        long rise = localToUtc(daily.getJSONArray("sunrise"), off), set = localToUtc(daily.getJSONArray("sunset"), off);
        long now = System.currentTimeMillis();
        String zeit = !day ? "nacht" : "tag";
        long dusk = 40 * 60_000L;
        if ((rise > 0 && Math.abs(now - rise) < dusk) || (set > 0 && Math.abs(now - set) < dusk)) zeit = "daemmerung";
        current = new Now(wetter(code), zeit, saison(lat), ort, temp, code);
        publish();
    }

    /** WMO-Wettercode -> Kategorie. */
    static String wetter(int c) {
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "schnee";
        if ((c >= 51 && c <= 67) || (c >= 80 && c <= 82) || c >= 95) return "regen";
        if (c == 3 || c == 45 || c == 48) return "wolkig";
        return "klar";
    }

    /** WMO-Wettercode -> kurze deutsche Beschreibung. */
    static String describe(int c) {
        switch (c) {
            case 0: return "Klar";
            case 1: return "Überwiegend klar";
            case 2: return "Teils bewölkt";
            case 3: return "Bedeckt";
            case 45: case 48: return "Nebel";
            case 51: case 53: case 55: return "Nieselregen";
            case 56: case 57: return "Gefrierender Niesel";
            case 61: return "Leichter Regen";
            case 63: return "Regen";
            case 65: return "Starker Regen";
            case 66: case 67: return "Gefrierender Regen";
            case 71: return "Leichter Schneefall";
            case 73: return "Schneefall";
            case 75: return "Starker Schneefall";
            case 77: return "Schneegriesel";
            case 80: case 81: return "Regenschauer";
            case 82: return "Heftige Schauer";
            case 85: case 86: return "Schneeschauer";
            case 95: return "Gewitter";
            case 96: case 99: return "Gewitter mit Hagel";
            default: return "";
        }
    }

    /** Ort neu ermitteln lassen (per IP) statt fest. */
    static void setAutoLocation(Context c) {
        c.getSharedPreferences("skyline", Context.MODE_PRIVATE).edit().putBoolean("wx_fixed", false).putLong("wx_loc_at", 0).apply();
        if (worker != null) worker.interrupt();
    }

    /** Ortssuche über Open-Meteo Geocoding: JSON-Liste [{name, region, land, lat, lon}]. */
    static String geocode(String q) throws Exception {
        JSONObject j = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=6&language=de&format=json&name="
                + java.net.URLEncoder.encode(q, "UTF-8")));
        JSONArray r = j.optJSONArray("results"), out = new JSONArray();
        if (r != null) for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.getJSONObject(i);
            out.put(new JSONObject().put("name", o.optString("name")).put("region", o.optString("admin1"))
                    .put("land", o.optString("country")).put("lat", o.optDouble("latitude")).put("lon", o.optDouble("longitude")));
        }
        return out.toString();
    }

    static String saison(double lat) {
        int m = Calendar.getInstance().get(Calendar.MONTH);        // 0 = Januar
        if (lat < 0) m = (m + 6) % 12;                              // Südhalbkugel
        if (m == 11 || m <= 1) return "winter";
        if (m <= 4) return "fruehling";
        if (m <= 7) return "sommer";
        return "herbst";
    }

    /** „2026-11-03T07:12“ (Ortszeit) -> UTC-Millisekunden. */
    private static long localToUtc(JSONArray a, int offSec) {
        try {
            String s = a.getString(0);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(s).getTime() - offSec * 1000L;
        } catch (Exception e) { return 0; }
    }

    static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "Skyline/1.0 (Beamer-Startbildschirm)");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally { c.disconnect(); }
    }
}
