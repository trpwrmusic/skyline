package de.wesnik.skyline;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bildquellen (HDMI-Eingänge) über das Android-TV-Eingangssystem.
 * Beim XK03H stellt com.xgimi.tvinput HDMI 1–3 bereit, angezeigt werden sie vom XGIMI-Player (com.xgimi.xhplayer).
 * Gezeigt werden nur Anschlüsse, an denen gerade ein Gerät hängt. Android meldet das auf dem XGIMI nicht zuverlässig
 * (alle registrierten HDMI-Eingänge stehen auf „Bereitschaft“), deshalb fragt Skyline den Kabelstatus bei der
 * XGIMI-Systembibliothek ab (com.xgimi.api.jar im Bootclasspath: GmTvManager.getHdmiConnectStatus(port)).
 * Auf anderen Geräten gilt der Android-Eingangsstatus bzw. ein HDMI-CEC-Gerät hinter dem Anschluss.
 */
final class Sources {

    static final class Port {
        final String id, title, sub;
        Port(String i, String t, String s) { id = i; title = t; sub = s; }
    }

    static TvInputManager manager(Context c) {
        try { return (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE); } catch (Exception e) { return null; }
    }

    /** Verbundene Anschlüsse, sortiert (HDMI 1, HDMI 2, …). */
    static List<Port> connected(Context c) {
        List<Port> out = new ArrayList<>();
        TvInputManager tm = manager(c);
        if (tm == null) return out;
        List<TvInputInfo> all;
        try { all = tm.getTvInputList(); } catch (Exception e) { return out; }
        // HDMI-CEC: angeschlossene Geräte erscheinen als Kind-Eingänge ihres Anschlusses (z. B. „PlayStation 5“ an HDMI 2)
        Map<String, String> device = new HashMap<>();
        for (TvInputInfo i : all) {
            String parent = i.getParentId();
            if (parent != null && !device.containsKey(parent)) device.put(parent, label(c, i));
        }
        for (TvInputInfo i : all) {
            if (i.getParentId() != null || !i.isPassthroughInput() || i.getType() == TvInputInfo.TYPE_TUNER) continue;
            try { if (i.isHidden(c)) continue; } catch (Exception ignored) { }
            int st;
            try { st = tm.getInputState(i.getId()); } catch (Exception e) { st = TvInputManager.INPUT_STATE_CONNECTED; }
            String dev = device.get(i.getId());
            int hw = hdmiPort(i.getId());
            Boolean cable = hw > 0 ? xgimiCable(hw) : null;
            if (cable != null ? !cable && dev == null : st == TvInputManager.INPUT_STATE_DISCONNECTED && dev == null) continue;
            if (cable != null && cable) st = TvInputManager.INPUT_STATE_CONNECTED;
            String port = portName(i, c);
            CharSequence custom = null;
            try { custom = i.loadCustomLabel(c); } catch (Exception ignored) { }
            String title = port, sub = dev != null ? dev : (st == TvInputManager.INPUT_STATE_CONNECTED_STANDBY ? "Bereitschaft" : "Verbunden");
            if (custom != null && custom.length() > 0 && !custom.toString().equalsIgnoreCase(port)) { title = custom.toString(); sub = port; }
            out.add(new Port(i.getId(), title, sub));
        }
        Collections.sort(out, new Comparator<Port>() {
            @Override public int compare(Port a, Port b) { return key(a).compareTo(key(b)); }
            private String key(Port p) { return (p.sub.startsWith("HDMI") ? p.sub : p.title).replaceAll("(\\d+)", "000$1"); }
        });
        return out;
    }

    private static String label(Context c, TvInputInfo i) {
        CharSequence l = null;
        try { l = i.loadCustomLabel(c); } catch (Exception ignored) { }
        if (l == null || l.length() == 0) try { l = i.loadLabel(c); } catch (Exception ignored) { }
        return l == null ? "" : l.toString().trim();
    }

    /** „HDMI1“, „hdmi 1“ … -> „HDMI 1“. */
    private static String portName(TvInputInfo i, Context c) {
        String l = "";
        try { CharSequence s = i.loadLabel(c); if (s != null) l = s.toString().trim(); } catch (Exception ignored) { }
        if (l.isEmpty()) l = i.getType() == TvInputInfo.TYPE_HDMI ? "HDMI" : "Eingang";
        return l.replaceAll("(?i)^hdmi\\s*(\\d+)", "HDMI $1");
    }

    /** Nummer des HDMI-Anschlusses aus der Eingangs-ID („…/Hdmi2TvInputService/HW2“ -> 2), sonst -1. */
    static int hdmiPort(String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)hdmi(\\d+)").matcher(id);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** XGIMI-Kabelstatus; null = nicht verfügbar (kein XGIMI oder Aufruf verweigert). */
    static Boolean xgimiCable(int port) {
        try {
            Class<?> c = Class.forName("com.xgimi.gmpf.api.GmTvManager");
            Object m = c.getMethod("getInstance").invoke(null);
            return (Boolean) c.getMethod("getHdmiConnectStatus", byte.class).invoke(m, (byte) port);
        } catch (Throwable t) { return null; }
    }

    /**
     * Auf den Anschluss umschalten. Der XGIMI-Player (com.xgimi.xhplayer/.InputActivity) liest den Eingang aus der
     * Passthrough-Adresse oder aus dem Extra „sw“ („HDMI1“). Ein impliziter VIEW-Aufruf wird auf dem XGIMI von keiner
     * App angenommen, deshalb zuerst der Player direkt, VIEW nur als Rückfall für andere Geräte.
     */
    static Intent[] intents(Port p) {
        android.net.Uri uri = TvContract.buildChannelUriForPassthroughInput(p.id);
        Intent xgimi = new Intent("com.xgimi.action.hdmiPlayer", uri).setClassName("com.xgimi.xhplayer", "com.xgimi.xhplayer.InputActivity");
        int hw = hdmiPort(p.id);
        if (hw > 0) xgimi.putExtra("sw", "HDMI" + hw);
        return new Intent[]{xgimi, new Intent(Intent.ACTION_VIEW, uri)};
    }
}
