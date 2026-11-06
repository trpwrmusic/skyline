package de.wesnik.skyline;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Kleines Glasdock „Läuft gerade“ (links über dem App-Dock): Cover, Titel, Interpret, Zurück / Play-Pause / Vor.
 * Liest die aktiven Mediensitzungen aller Apps (Spotify, YouTube Music …). Dafür braucht Skyline den
 * Benachrichtigungszugriff (MediaListener) – den setzt der Shell-Helfer automatisch.
 */
public class MediaDock extends GlassPanel {

    interface Host { void onMediaVisibility(boolean visible); void onSeek(long ms, View anchor); }

    private final ImageView cover;
    private final TextView title, artist;
    final View prev, play, next;
    final LinearLayout info;
    private final ImageView playGlyph;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final MediaSessionManager msm;
    private final ComponentName listener;
    private MediaController ctrl;
    private boolean shown;
    private Host host;

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { refresh(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { refresh(); }
        @Override public void onSessionDestroyed() { pick(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = new MediaSessionManager.OnActiveSessionsChangedListener() {
        @Override public void onActiveSessionsChanged(List<MediaController> l) { pick(); }
    };
    private boolean listening;
    private final Runnable retry = new Runnable() { @Override public void run() { start(); } };

    public MediaDock(Context c, float radiusPx) {
        super(c, radiusPx);
        msm = c.getSystemService(MediaSessionManager.class);
        listener = new ComponentName(c, MediaListener.class);
        setClipToOutline(true);
        setElevation(dp(10));
        setAlpha(0f);
        setVisibility(GONE);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(6), dp(14), dp(6));
        row.setClipToPadding(false);   // vergrößerter Fokus-Kreis darf in den Innenabstand ragen
        row.setClipChildren(false);
        addView(row, new LayoutParams(-1, -1));

        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0x33FFFFFF);
        final float cr = dp(9);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), cr); }
        });
        cover.setClipToOutline(true);
        info = new LinearLayout(c);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(4), dp(4), dp(4), dp(4));
        final GradientDrawable infoBg = new GradientDrawable();
        infoBg.setCornerRadius(dp(13));
        infoBg.setColor(0x00FFFFFF);
        info.setBackground(infoBg);
        info.setFocusable(true);
        info.setId(View.generateViewId());
        info.setContentDescription("Wiedergabe-App öffnen");
        info.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { infoBg.setColor(has ? 0x40FFFFFF : 0x00FFFFFF); }
        });
        info.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { openApp(); } });
        info.addView(cover, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout txt = new LinearLayout(c);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(12), 0, dp(8), 0);
        title = label(c, 14, 0xFFFFFFFF, "sans-serif-medium");
        artist = label(c, 12, 0xC7FFFFFF, "sans-serif");
        txt.addView(title);
        txt.addView(artist);
        info.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(info, new LinearLayout.LayoutParams(0, -1, 1f));

        prev = button(c, Glyphs.media(0), "Zurück");
        play = button(c, Glyphs.media(1), "Abspielen");
        playGlyph = (ImageView) ((FrameLayout) play).getChildAt(1);
        next = button(c, Glyphs.media(3), "Weiter");
        row.addView(prev); row.addView(play); row.addView(next);

        // Zurück wie beim MP3-Player: nach mehr als 3 s an den Anfang, sonst vorheriger Titel
        prev.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (ctrl == null) return;
                PlaybackState s = ctrl.getPlaybackState();
                long now = android.os.SystemClock.elapsedRealtime();
                boolean second = now - lastPrevAt < 3000;
                if (!second && s != null && estimate(s) > 3000 && (s.getActions() & PlaybackState.ACTION_SEEK_TO) != 0) {
                    ctrl.getTransportControls().seekTo(0);
                    seekPos = 0; seekAt = now; lastPrevAt = now;
                } else {
                    ctrl.getTransportControls().skipToPrevious();
                    lastPrevAt = 0; seekAt = 0;
                }
            }
        });
        next.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (ctrl != null) ctrl.getTransportControls().skipToNext(); } });
        play.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (ctrl == null) return;
                PlaybackState s = ctrl.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) ctrl.getTransportControls().pause();
                else ctrl.getTransportControls().play();
            }
        });
    }

    void setHost(Host hst) { host = hst; }

    boolean isShown2() { return shown; }

    private static long position(PlaybackState s) {
        long p = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING)
            p += (long) ((android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        return Math.max(0, p);
    }

    // Eigene Positionsführung: nach unseren Sprüngen rechnen wir selbst weiter
    private long seekPos, seekAt, lastPrevAt;
    private String lastTitle;

    private long estimate(PlaybackState s) {
        if (seekAt > 0 && seekAt >= s.getLastPositionUpdateTime()) {
            long p = seekPos;
            if (s.getState() == PlaybackState.STATE_PLAYING) p += android.os.SystemClock.elapsedRealtime() - seekAt;
            return p;
        }
        return position(s);
    }

    /** 10 s vor/zurück (langes Drücken); nie unter 0 oder über das Songende. */
    void seekBy(long ms, View anchor) {
        if (ctrl == null) return;
        PlaybackState s = ctrl.getPlaybackState();
        if (s == null || (s.getActions() & PlaybackState.ACTION_SEEK_TO) == 0) return;
        long dur = -1;
        MediaMetadata m = ctrl.getMetadata();
        if (m != null) dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long t = Math.max(0, estimate(s) + ms);
        if (dur > 0) t = Math.min(t, Math.max(0, dur - 1500));
        ctrl.getTransportControls().seekTo(t);
        seekPos = t; seekAt = android.os.SystemClock.elapsedRealtime();
        if (host != null) host.onSeek(ms, anchor);
    }

    /** Die App öffnen, die gerade spielt. */
    private void openApp() {
        if (ctrl == null) return;
        try {
            android.app.PendingIntent pi = ctrl.getSessionActivity();
            if (pi != null) { pi.send(); return; }
        } catch (Exception ignored) { }
        android.content.pm.PackageManager pm = getContext().getPackageManager();
        android.content.Intent l = pm.getLeanbackLaunchIntentForPackage(ctrl.getPackageName());
        if (l == null) l = pm.getLaunchIntentForPackage(ctrl.getPackageName());
        if (l != null) { l.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); try { getContext().startActivity(l); } catch (Exception ignored) { } }
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView label(Context c, float sp, int color, String fam) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(fam, Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setShadowLayer(dp(4), 0, dp(1), 0x66000000);
        return t;
    }

    /** Runder Knopf: nur Fläche (weiß im Fokus), keine Kontur. */
    private View button(Context c, android.graphics.drawable.Drawable icon, String desc) {
        final FrameLayout f = new FrameLayout(c);
        final View bg = new View(c);
        GradientDrawable o = new GradientDrawable();
        o.setShape(GradientDrawable.OVAL);
        o.setColor(0xF2FFFFFF);
        bg.setBackground(o);
        bg.setAlpha(0f);
        f.addView(bg, new LayoutParams(-1, -1));
        final ImageView g = new ImageView(c);
        g.setImageDrawable(icon);
        g.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        f.addView(g, new LayoutParams(dp(20), dp(20), Gravity.CENTER));
        f.setFocusable(true);
        f.setContentDescription(desc);
        f.setId(View.generateViewId());
        f.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                long d = Stage.animationsEnabled(getContext()) ? 150 : 0;
                bg.animate().alpha(has ? 1f : 0f).setDuration(d).start();
                v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setInterpolator(new DecelerateInterpolator()).setDuration(d).start();
                g.setColorFilter(has ? 0xFF111214 : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.leftMargin = dp(4);
        f.setLayoutParams(lp);
        return f;
    }

    // ---------- Sitzungen ----------

    void start() {
        h.removeCallbacks(retry);
        try {
            if (!listening) { msm.addOnActiveSessionsChangedListener(sessions, listener, h); listening = true; }
            pick();
        } catch (SecurityException e) {
            h.postDelayed(retry, 10000);   // Zugriff noch nicht freigegeben (Helfer setzt ihn gleich)
        }
    }

    void stop() {
        h.removeCallbacks(retry);
        if (listening) { try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { } listening = false; }
        if (ctrl != null) { ctrl.unregisterCallback(cb); ctrl = null; }
    }

    private void pick() {
        List<MediaController> list;
        try { list = msm.getActiveSessions(listener); } catch (SecurityException e) { h.postDelayed(retry, 10000); return; }
        MediaController best = null;
        for (MediaController m : list) {
            PlaybackState s = m.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = m; break; }
        }
        if (best == null) for (MediaController m : list) {
            PlaybackState s = m.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PAUSED && m.getMetadata() != null) { best = m; break; }
        }
        if (ctrl != null && (best == null || !best.getSessionToken().equals(ctrl.getSessionToken()))) { ctrl.unregisterCallback(cb); ctrl = null; }
        if (best != null && ctrl == null) { ctrl = best; ctrl.registerCallback(cb, h); }
        refresh();
    }

    private void refresh() {
        MediaMetadata m = ctrl == null ? null : ctrl.getMetadata();
        PlaybackState s = ctrl == null ? null : ctrl.getPlaybackState();
        int st = s == null ? PlaybackState.STATE_NONE : s.getState();
        boolean show = m != null && (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_BUFFERING);
        if (show) {
            String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            String a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null) a = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (a == null) a = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (t != null && !t.equals(lastTitle)) { lastTitle = t; seekAt = 0; lastPrevAt = 0; }
            title.setText(t != null ? t : "");
            artist.setText(a != null ? a : "");
            artist.setVisibility(a == null || a.isEmpty() ? GONE : VISIBLE);
            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            cover.setImageBitmap(art);
            playGlyph.setImageDrawable(Glyphs.media(st == PlaybackState.STATE_PLAYING ? 2 : 1));
            ((View) play).setContentDescription(st == PlaybackState.STATE_PLAYING ? "Pause" : "Abspielen");
        }
        setShown(show);
    }

    private void setShown(boolean s) {
        if (s == shown) return;
        shown = s;
        long d = Stage.animationsEnabled(getContext()) ? 260 : 0;
        if (s) {
            setVisibility(VISIBLE);
            setTranslationY(dp(14));
            animate().alpha(1f).translationY(0).setDuration(d).setInterpolator(new DecelerateInterpolator(1.6f)).withEndAction(null).start();
        } else {
            if (hasFocus() && host != null) host.onMediaVisibility(false);
            animate().alpha(0f).translationY(dp(10)).setDuration(d).withEndAction(new Runnable() {
                @Override public void run() { if (!shown) setVisibility(GONE); }
            }).start();
        }
        if (host != null) host.onMediaVisibility(s);
    }
}
