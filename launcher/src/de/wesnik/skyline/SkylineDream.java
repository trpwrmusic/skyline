package de.wesnik.skyline;

import android.service.dreams.DreamService;

/** Bildschirmschoner: dieselben Videos wie der Startbildschirm (nach Wetter), mit Fensterrahmen, wenn Sprossen gewählt sind. */
public class SkylineDream extends DreamService {
    private Stage stage;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        Weather.start(this);
        stage = new Stage(this, 60 * 1000L, true);
        stage.setPlace("dream");                 // Wetteranzeige: Einstellung für den Bildschirmschoner
        // Wie der Fenster-Modus: Rahmen und Sprossen aus den Einstellungen, dann nur Video + Fenster (ohne Uhr und Logo)
        android.content.SharedPreferences p = getSharedPreferences("skyline", MODE_PRIVATE);
        int layout = p.getInt("muntins", 0);
        if (layout > 0) {
            Muntins m = new Muntins(this);
            m.setLayout(layout);
            m.setTone(p.getInt("muntinTone", 0));
            stage.addForeground(m, new android.widget.FrameLayout.LayoutParams(-1, -1));
            stage.setWindowMode(true);
        }
        setContentView(stage);
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); stage.start(); }

    @Override public void onDreamingStopped() { stage.stop(); super.onDreamingStopped(); }

    @Override public void onDetachedFromWindow() { if (stage != null) stage.release(); super.onDetachedFromWindow(); }
}
