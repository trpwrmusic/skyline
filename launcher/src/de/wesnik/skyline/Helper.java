package de.wesnik.skyline;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Kurze Befehle an den Shell-Helfer (KeyServer) schicken. */
final class Helper {
    /** Handy-Apps, die erst ab Tablet-Breite (600 dp) im Querformat laufen: mit kleinerer Dichte starten. */
    static final Set<String> TABLET_APPS = new HashSet<>(Arrays.asList("com.netflix.mediaclient"));
    static final int TABLET_DENSITY = 240;   // 1920 px / 240 dpi -> 720 dp schmalste Breite

    static String sendSync(String cmd) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", KeyServer.PORT), 400);
            s.setSoTimeout(3000);
            OutputStream o = s.getOutputStream();
            o.write((cmd + "\n").getBytes("UTF-8"));
            o.flush();
            return new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8")).readLine();
        } catch (Exception e) { return null; }
    }

    static void send(final String cmd) {
        new Thread(new Runnable() { @Override public void run() { sendSync(cmd); } }, "helper-cmd").start();
    }
}
