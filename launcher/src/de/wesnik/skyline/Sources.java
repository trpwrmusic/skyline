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
 * Gezeigt werden nur Anschlüsse, an denen gerade ein Gerät hängt (Eingangsstatus ≠ getrennt oder ein HDMI-CEC-Gerät dahinter).
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
            if (st == TvInputManager.INPUT_STATE_DISCONNECTED && dev == null) continue;
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

    /** Auf den Anschluss umschalten: Standardweg über TvContract, die XGIMI-Aktion als Rückfall. */
    static Intent[] intents(Port p) {
        Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(p.id));
        Intent xgimi = new Intent("com.xgimi.action.hdmiPlayer").putExtra("inputId", p.id).putExtra("input_id", p.id);
        return new Intent[]{view, xgimi};
    }
}
