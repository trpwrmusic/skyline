package de.wesnik.skyline;

import android.os.Handler;
import android.os.Looper;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Eigener Abgleich mit der Atomuhr (SNTP gegen die PTB Braunschweig).
 * Android stellt die Systemuhr erst bei > 5 s Abweichung nach; dieser Abgleich hält die Anzeige auf wenige ms genau.
 * Die Systemzeit selbst wird nicht verändert – Skyline rechnet nur den gemessenen Versatz ein.
 */
final class TimeSync {
    private static final String[] SERVERS = {"ptbtime1.ptb.de", "ptbtime2.ptb.de", "ptbtime3.ptb.de", "time.cloudflare.com"};
    private static final long RESYNC_MS = 30 * 60 * 1000L;

    static volatile long offsetMs;          // Atomzeit − Systemzeit
    static volatile long delayMs = -1;
    static volatile String source;          // null = noch nicht abgeglichen
    static volatile long syncedAt;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean scheduled;

    static long now() { return System.currentTimeMillis() + offsetMs; }

    static boolean synced() { return source != null; }

    /** Abgleich starten (idempotent) und alle 30 min wiederholen. */
    static synchronized void start() {
        if (scheduled) return;
        scheduled = true;
        Runnable loop = new Runnable() {
            @Override public void run() {
                new Thread(new Runnable() { @Override public void run() { syncOnce(); } }, "timesync").start();
                main.postDelayed(this, RESYNC_MS);
            }
        };
        loop.run();
    }

    private static void syncOnce() {
        for (String host : SERVERS) {
            long bestOff = 0, bestDelay = Long.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                try {
                    long[] r = query(host);
                    if (r[1] < bestDelay) { bestDelay = r[1]; bestOff = r[0]; }
                } catch (Exception ignored) { }
            }
            if (bestDelay != Long.MAX_VALUE) {
                offsetMs = bestOff;
                delayMs = bestDelay;
                source = host;
                syncedAt = System.currentTimeMillis();
                return;
            }
        }
    }

    /** Eine SNTP-Abfrage: liefert {Versatz, Laufzeit} in ms. */
    private static long[] query(String host) throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(1500);
            byte[] buf = new byte[48];
            buf[0] = 0x23;                                  // LI 0, Version 4, Mode 3 (Client)
            InetAddress addr = InetAddress.getByName(host);
            long t0 = System.currentTimeMillis();
            s.send(new DatagramPacket(buf, buf.length, addr, 123));
            DatagramPacket in = new DatagramPacket(new byte[48], 48);
            s.receive(in);
            long t3 = System.currentTimeMillis();
            byte[] d = in.getData();
            long t1 = ntp(d, 32), t2 = ntp(d, 40);
            long offset = ((t1 - t0) + (t2 - t3)) / 2;
            long delay = (t3 - t0) - (t2 - t1);
            return new long[]{offset, delay};
        }
    }

    private static long ntp(byte[] d, int o) {
        long sec = 0, frac = 0;
        for (int i = 0; i < 4; i++) sec = (sec << 8) | (d[o + i] & 0xFF);
        for (int i = 4; i < 8; i++) frac = (frac << 8) | (d[o + i] & 0xFF);
        return (sec - 2208988800L) * 1000L + (frac * 1000L >>> 32);
    }
}
