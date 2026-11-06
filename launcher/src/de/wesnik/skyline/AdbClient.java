package de.wesnik.skyline;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Minimaler ADB-Client: Skyline verbindet sich über Netzwerk-Debugging (127.0.0.1:5555) mit dem eigenen Gerät
 * und führt einen Shell-Befehl aus – nur, um den Tasten-Helfer (KeyServer) zu starten.
 * Beim allerersten Mal fragt Android TV am Bildschirm „USB-Debugging zulassen?“ (einmal mit „Immer zulassen“ bestätigen).
 */
final class AdbClient {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAXDATA = 4096;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final Context ctx;
    private Socket sock;
    private InputStream in;
    private OutputStream out;

    AdbClient(Context c) { ctx = c; }

    /** Führt einen Shell-Befehl aus und gibt die Ausgabe zurück. waitAuthMs: wie lange auf die Freigabe am TV gewartet wird. */
    String shell(String cmd, int waitAuthMs) throws Exception {
        KeyPair kp = keys();
        sock = new Socket();
        sock.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        sock.setSoTimeout(waitAuthMs + 5000);
        in = new DataInputStream(sock.getInputStream());
        out = sock.getOutputStream();
        try {
            send(CNXN, VERSION, MAXDATA, "host::\0".getBytes("UTF-8"));
            boolean sentSig = false, sentKey = false;
            while (true) {
                Msg m = read();
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.arg0 != 1) throw new IllegalStateException("unerwartet " + m.cmd);
                if (!sentSig) { send(AUTH, 2, 0, sign(kp.getPrivate(), m.data)); sentSig = true; }
                else if (!sentKey) { send(AUTH, 3, 0, (publicKey((RSAPublicKey) kp.getPublic()) + " skyline@beamer\0").getBytes("UTF-8")); sentKey = true; }
                else throw new SecurityException("ADB-Freigabe abgelehnt");
            }
            send(OPEN, 1, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
            ByteArrayOutputStream res = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                Msg m = read();
                if (m.cmd == OKAY) { remote = m.arg0; continue; }
                if (m.cmd == WRTE) { res.write(m.data); send(OKAY, 1, m.arg0, new byte[0]); continue; }
                if (m.cmd == CLSE) { send(CLSE, 1, remote, new byte[0]); break; }
            }
            return res.toString("UTF-8");
        } finally {
            try { sock.close(); } catch (Exception ignored) { }
        }
    }

    // ---------- Protokoll ----------

    private static final class Msg { int cmd, arg0, arg1; byte[] data; }

    private void send(int cmd, int a0, int a1, byte[] data) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xFF;
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private Msg read() throws Exception {
        byte[] h = new byte[24];
        ((DataInputStream) in).readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt(); m.arg0 = b.getInt(); m.arg1 = b.getInt();
        int len = b.getInt();
        m.data = new byte[len];
        ((DataInputStream) in).readFully(m.data);
        return m;
    }

    private static byte[] sign(PrivateKey k, byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, k);
        c.update(SHA1_PREFIX);
        return c.doFinal(token);
    }

    /** Öffentlicher Schlüssel im Android-ADB-Format (RSAPublicKey-Struktur, little endian, Base64). */
    private static String publicKey(RSAPublicKey pub) {
        int words = 2048 / 32;
        BigInteger n = pub.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.remainder(r32).modInverse(r32).negate().mod(r32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        BigInteger t = n;
        for (int i = 0; i < words; i++) { b.putInt(t.remainder(r32).intValue()); t = t.shiftRight(32); }
        t = rr;
        for (int i = 0; i < words; i++) { b.putInt(t.remainder(r32).intValue()); t = t.shiftRight(32); }
        b.putInt(pub.getPublicExponent().intValue());
        return Base64.encodeToString(b.array(), Base64.NO_WRAP);
    }

    private KeyPair keys() throws Exception {
        File pri = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pri.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))), kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(pri))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        try (FileOutputStream o = new FileOutputStream(pri)) { o.write(kp.getPrivate().getEncoded()); }
        try (FileOutputStream o = new FileOutputStream(pub)) { o.write(kp.getPublic().getEncoded()); }
        return kp;
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream i = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }
}
