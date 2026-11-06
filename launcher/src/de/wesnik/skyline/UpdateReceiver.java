package de.wesnik.skyline;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Nach einem Selbst-Update (und nach dem Hochfahren) den Fernbedienungs-Dienst gleich wieder starten. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        try { c.startForegroundService(new Intent(c, RemoteService.class)); } catch (Exception ignored) { }
    }
}
