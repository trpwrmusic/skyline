package de.wesnik.skyline;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Tasten-Helfer für die Handy-Fernbedienung. Läuft NICHT in der App, sondern als eigener Prozess mit Shell-Rechten
 * (wie scrcpy): CLASSPATH=<skyline.apk> app_process / de.wesnik.skyline.KeyServer
 * Shell darf Eingaben einspeisen (INJECT_EVENTS); eine normale App darf das nicht.
 *
 * Protokoll (TCP 127.0.0.1:8766, zeilenweise):  key <code> | down <code> | up <code> | text <utf8> | ping
 *   touch down|move|up <x> <y> | tap <x> <y> | longtap <x> <y>   (Bildschirmkoordinaten, für Apps ohne Fernbedienungs-Steuerung)
 *   density <dpi|reset> | install <apk> <0|1>   (Selbst-Update)
 */
public final class KeyServer {
    static final int PORT = 8766;
    private static Object im;
    private static Method inject;

    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("android.hardware.input.InputManager");
        im = c.getMethod("getInstance").invoke(null);
        inject = c.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
        ServerSocket ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
        System.out.println("KeyServer bereit auf " + PORT);
        while (true) {
            final Socket s = ss.accept();
            new Thread(new Runnable() { @Override public void run() { serve(s); } }).start();
        }
    }

    private static void serve(Socket s) {
        try (Socket sock = s;
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
             OutputStream out = sock.getOutputStream()) {
            String line;
            while ((line = in.readLine()) != null) {
                String r = handle(line.trim());
                out.write((r + "\n").getBytes("UTF-8"));
                out.flush();
            }
        } catch (Exception ignored) { }
    }

    private static long downTime;

    private static String handle(String cmd) {
        try {
            if (cmd.equals("ping")) return "pong " + RemoteService.HELPER_VERSION;
            int sp = cmd.indexOf(' ');
            String op = sp < 0 ? cmd : cmd.substring(0, sp), arg = sp < 0 ? "" : cmd.substring(sp + 1);
            switch (op) {
                case "key": {
                    int code = Integer.parseInt(arg);
                    long t = SystemClock.uptimeMillis();
                    send(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD));
                    send(new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD));
                    return "ok";
                }
                case "down": {   // gedrückt halten: wiederholte DOWN-Ereignisse kommen vom Aufrufer als "repeat"
                    int code = Integer.parseInt(arg);
                    downTime = SystemClock.uptimeMillis();
                    send(new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD));
                    return "ok";
                }
                case "repeat": {
                    String[] p = arg.split(" ");
                    int code = Integer.parseInt(p[0]), n = Integer.parseInt(p[1]);
                    int flags = n == 1 ? KeyEvent.FLAG_LONG_PRESS : 0;
                    send(new KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, n, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_DPAD));
                    return "ok";
                }
                case "up": {
                    int code = Integer.parseInt(arg);
                    send(new KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD));
                    return "ok";
                }
                case "text": {
                    KeyEvent[] evs = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(arg.toCharArray());
                    if (evs == null) return "err";
                    for (KeyEvent e : evs) send(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0));
                    return "ok";
                }
                case "density": {   // nur Zahl oder reset
                    if (!arg.matches("reset|[0-9]{2,3}")) return "err";
                    int rc = Runtime.getRuntime().exec(new String[]{"wm", "density", arg}).waitFor();
                    return rc == 0 ? "ok" : "err " + rc;
                }
                case "install": {   // Selbst-Update: install <apk im Update-Ordner von Skyline> <1 = danach Startbildschirm öffnen>
                    String[] p = arg.split(" ");
                    final String src = p[0];
                    final boolean restart = p.length > 1 && p[1].equals("1");
                    if (!src.matches("/(sdcard|storage/emulated/0)/Android/data/de\\.wesnik\\.skyline/files/update/[A-Za-z0-9._-]+\\.apk")) return "err pfad";
                    new Thread(new Runnable() { @Override public void run() { install(src, restart); } }, "install").start();
                    return "ok";
                }
                case "touch": {
                    String[] p = arg.split(" ");
                    float x = Float.parseFloat(p[1]), y = Float.parseFloat(p[2]);
                    int a = p[0].equals("down") ? MotionEvent.ACTION_DOWN : p[0].equals("up") ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                    if (a == MotionEvent.ACTION_DOWN) touchDown = SystemClock.uptimeMillis();
                    touch(a, x, y);
                    return "ok";
                }
                case "tap": case "longtap": {
                    String[] p = arg.split(" ");
                    float x = Float.parseFloat(p[0]), y = Float.parseFloat(p[1]);
                    touchDown = SystemClock.uptimeMillis();
                    touch(MotionEvent.ACTION_DOWN, x, y);
                    Thread.sleep(op.equals("tap") ? 40 : 750);
                    touch(MotionEvent.ACTION_UP, x, y);
                    return "ok";
                }
                default: return "err";
            }
        } catch (Exception e) { return "err " + e; }
    }

    /**
     * Läuft im Shell-Prozess weiter, auch wenn Android Skyline für die Installation beendet.
     * Ergebnis landet als result.txt neben der APK („ok …“ oder „err …“), damit die (neue oder alte) App es lesen kann.
     */
    private static void install(String src, boolean restart) {
        File apk = new File(src), tmp = new File("/data/local/tmp/skyline-update.apk");
        String result;
        try {
            try (InputStream in = new FileInputStream(apk); OutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[256 * 1024]; int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            Process pr = new ProcessBuilder("pm", "install", "-r", tmp.getAbsolutePath()).redirectErrorStream(true).start();
            String out = readAll(pr.getInputStream()).trim();
            int rc = pr.waitFor();
            result = rc == 0 && out.contains("Success") ? "ok " + out : "err " + out;
        } catch (Exception e) {
            result = "err " + e;
        }
        tmp.delete();
        try (OutputStream o = new FileOutputStream(new File(apk.getParentFile(), "result.txt"))) { o.write((result + "\n").getBytes("UTF-8")); }
        catch (Exception ignored) { }
        System.out.println("install: " + result);
        if (result.startsWith("ok")) {
            apk.delete();
            try {
                Thread.sleep(1200);
                // Fernbedienungs-Dienst wieder starten (die neue App läuft sonst erst, wenn jemand Home drückt)
                if (restart) Runtime.getRuntime().exec(new String[]{"am", "start", "-n", "de.wesnik.skyline/.HomeActivity"}).waitFor();
                else Runtime.getRuntime().exec(new String[]{"am", "start-foreground-service", "-n", "de.wesnik.skyline/.RemoteService"}).waitFor();
            } catch (Exception ignored) { }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    private static long touchDown;

    private static void touch(int action, float x, float y) throws Exception {
        MotionEvent m = MotionEvent.obtain(touchDown, SystemClock.uptimeMillis(), action, x, y, 0);
        m.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        inject.invoke(im, m, 0);
        m.recycle();
    }

    private static void send(KeyEvent e) throws Exception { inject.invoke(im, e, 0); }
}
