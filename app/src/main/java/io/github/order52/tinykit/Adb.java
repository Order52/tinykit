package io.github.order52.tinykit;

import android.net.ssl.SSLSockets;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Date;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * TinyKit's own adb client for the phone it runs on, so Shizuku can be started without a computer or
 * another app: pairs with Android's Wireless debugging (Android 11+), connects to it and runs one
 * shell command as the adb user. Nothing here needs a library: TLS and the key export are Android's,
 * the pairing key exchange is {@link Spake2}.
 */
final class Adb {
    private static final int A_CNXN = 0x4e584e43, A_STLS = 0x534c5453, A_OPEN = 0x4e45504f, A_OKAY = 0x59414b4f,
            A_WRTE = 0x45545257, A_CLSE = 0x45534c43, A_AUTH = 0x48545541;
    private static final int PEER_INFO_SIZE = 8192;

    /** Why pairing or connecting failed, in words that can be shown. */
    static final class Failure extends Exception {
        /** The phone closed the door on TinyKit's key: it has not been paired, or was forgotten. */
        final boolean unpaired;

        Failure(String message) {
            this(message, false);
        }

        Failure(String message, boolean unpaired) {
            super(message);
            this.unpaired = unpaired;
        }
    }

    private final PrivateKey key;
    private final X509Certificate cert;
    private final RSAPublicKey publicKey;

    /** The phone's adb key lives in {@code dir}: made on first use, and what Wireless debugging remembers as paired. */
    Adb(File dir) throws Exception {
        File keyFile = new File(dir, "adbkey"), certFile = new File(dir, "adbcert");
        PrivateKey k;
        X509Certificate c;
        try {
            k = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(read(keyFile)));
            c = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(read(certFile)));
        } catch (Exception missing) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            byte[] der = certificate(pair);
            dir.mkdirs();
            write(keyFile, pair.getPrivate().getEncoded());
            write(certFile, der);
            k = pair.getPrivate();
            c = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(der));
        }
        key = k;
        cert = c;
        publicKey = (RSAPublicKey) c.getPublicKey();
    }

    // ---- Pairing ---------------------------------------------------------------------------------------

    /** Pairs with the phone's Wireless debugging, with the six digits it shows. */
    void pair(String host, int port, String code) throws Exception {
        try (Socket plain = new Socket(host, port)) {
            plain.setSoTimeout(15_000);
            SSLSocket ssl = (SSLSocket) context().getSocketFactory().createSocket(plain, host, port, true);
            ssl.setEnabledProtocols(new String[]{"TLSv1.3"});
            ssl.startHandshake();
            byte[] exported = SSLSockets.exportKeyingMaterial(ssl, "adb-label\0", null, 64);
            byte[] password = concat(code.getBytes(StandardCharsets.UTF_8), exported);
            Spake2 spake = new Spake2(true, Spake2.ascii("adb pair client\0"), Spake2.ascii("adb pair server\0"), password);
            InputStream in = ssl.getInputStream();
            OutputStream out = ssl.getOutputStream();
            send(out, 0, spake.message());
            byte[] theirs = receive(in, 0);
            byte[] shared;
            try {
                shared = spake.finish(theirs);
            } catch (IllegalArgumentException e) {
                throw new Failure("The pairing code is wrong.");
            }
            byte[] aes = hkdf(shared, "adb pairing_auth aes-128-gcm key", 16);
            byte[] info = new byte[PEER_INFO_SIZE];
            byte[] mine = (adbKey() + " tinykit@android\0").getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(mine, 0, info, 1, Math.min(mine.length, info.length - 1)); // type 0 is the RSA public key
            send(out, 1, gcm(Cipher.ENCRYPT_MODE, aes, 0, info));
            byte[] reply = receive(in, 1);
            try {
                gcm(Cipher.DECRYPT_MODE, aes, 0, reply);
            } catch (java.security.GeneralSecurityException e) {
                throw new Failure("The pairing code is wrong.");
            }
        } catch (java.io.IOException e) {
            throw new Failure("Pairing stopped. Is the pairing code still on screen?");
        }
    }

    private static void send(OutputStream out, int type, byte[] payload) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
        header.put((byte) 1).put((byte) type).putInt(payload.length);
        out.write(header.array());
        out.write(payload);
        out.flush();
    }

    private static byte[] receive(InputStream in, int type) throws IOException, Failure {
        DataInputStream data = new DataInputStream(in);
        byte[] header = new byte[6];
        data.readFully(header);
        int size = ByteBuffer.wrap(header, 2, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (header[1] != type || size < 0 || size > PEER_INFO_SIZE + 64) throw new Failure("Pairing failed.");
        byte[] payload = new byte[size];
        data.readFully(payload);
        return payload;
    }

    /** AES-128-GCM as the pairing does it: the 12-byte nonce is a counter that starts at zero. */
    private static byte[] gcm(int mode, byte[] key, long counter, byte[] data) throws Exception {
        byte[] iv = new byte[12];
        for (int i = 0; i < 8; i++) iv[i] = (byte) (counter >> (8 * i));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return cipher.doFinal(data);
    }

    private static byte[] hkdf(byte[] secret, String info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(secret);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info.getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), length);
    }

    // ---- Connecting ------------------------------------------------------------------------------------

    /** Runs one shell command as the adb user and returns what it printed. Throws {@link Failure} if the phone doesn't trust this key. */
    String shell(String host, int port, String command) throws Exception {
        boolean[] upgraded = {false}, connected = {false};
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(host, port), 1500);
            plain.setSoTimeout(30_000);
            InputStream in = plain.getInputStream();
            OutputStream out = plain.getOutputStream();
            write(out, A_CNXN, 0x01000001, 1024 * 1024, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Message m = read(in);
            Socket tls = null;
            if (m.command == A_STLS) {
                write(out, A_STLS, 0x01000000, 0, new byte[0]);
                upgraded[0] = true;
                SSLSocket ssl = (SSLSocket) context().getSocketFactory().createSocket(plain, host, port, true);
                ssl.startHandshake();
                tls = ssl;
                in = ssl.getInputStream();
                out = ssl.getOutputStream();
                m = read(in);
            }
            if (m.command == A_AUTH) throw new Failure("This phone doesn't know TinyKit yet. Pair it again.", true);
            if (m.command != A_CNXN) throw new Failure("Wireless debugging didn't answer.");
            connected[0] = true;
            write(out, A_OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            while ((m = read(in)).command != A_CLSE) {
                if (m.command == A_WRTE) {
                    result.write(m.data);
                    write(out, A_OKAY, 1, m.arg0, new byte[0]);
                }
            }
            if (tls != null) tls.close();
            return result.toString("UTF-8").replace("\r", "").trim();
        } catch (java.io.IOException e) {
            // Dropped after the secure channel was asked for: that is how the phone refuses a key it doesn't know.
            android.util.Log.w("TinyKit", "adb shell: " + e + " upgraded=" + upgraded[0] + " connected=" + connected[0]);
            throw new Failure("Couldn't talk to Wireless debugging.", upgraded[0] && !connected[0]);
        }
    }

    private static final class Message {
        int command, arg0;
        byte[] data;
    }

    private static void write(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(0).putInt(~command);
        out.write(header.array());
        out.write(data);
        out.flush();
    }

    private static Message read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] raw = new byte[24];
        data.readFully(raw);
        ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = header.getInt();
        m.arg0 = header.getInt();
        header.getInt();
        int length = header.getInt();
        if (length < 0 || length > 1024 * 1024) throw new IOException("bad message");
        m.data = new byte[length];
        data.readFully(m.data);
        return m;
    }

    // ---- Keys and TLS ----------------------------------------------------------------------------------

    private SSLContext context() throws Exception {
        X509ExtendedKeyManager keys = new X509ExtendedKeyManager() {
            @Override
            public String[] getClientAliases(String type, java.security.Principal[] issuers) {
                return new String[]{"adb"};
            }

            @Override
            public String chooseClientAlias(String[] types, java.security.Principal[] issuers, Socket socket) {
                return "adb";
            }

            @Override
            public String[] getServerAliases(String type, java.security.Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseServerAlias(String type, java.security.Principal[] issuers, Socket socket) {
                return null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return new X509Certificate[]{cert};
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return key;
            }
        };
        // The phone is not checked against any authority: adb's own TLS trusts the key, not a certificate.
        X509ExtendedTrustManager trustAll = new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String type, Socket socket) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String type, Socket socket) {
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String type, javax.net.ssl.SSLEngine engine) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String type, javax.net.ssl.SSLEngine engine) {
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ssl = SSLContext.getInstance("TLSv1.3");
        ssl.init(new KeyManager[]{keys}, new TrustManager[]{trustAll}, new SecureRandom());
        return ssl;
    }

    /** The public key in adb's own format: the little-endian words of the modulus and of 2^4096 mod n. */
    private String adbKey() {
        BigInteger n = publicKey.getModulus();
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        long n0inv = two32.subtract(n.mod(two32).modInverse(two32)).longValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt((int) n0inv);
        b.put(Spake2.toLittleEndian(n, 256)).put(Spake2.toLittleEndian(rr, 256));
        b.putInt(publicKey.getPublicExponent().intValue());
        return Base64.encodeToString(b.array(), Base64.NO_WRAP);
    }

    /** A self-signed certificate for the key: adb trusts the key inside it. */
    private static byte[] certificate(KeyPair pair) throws Exception {
        byte[] algorithm = der(0x30, der(0x06, new byte[]{0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 1, 1, 0x0B}),
                new byte[]{5, 0});
        byte[] name = der(0x30, der(0x31, der(0x30, der(0x06, new byte[]{0x55, 4, 3}),
                der(0x0C, "TinyKit".getBytes(StandardCharsets.US_ASCII)))));
        java.text.SimpleDateFormat utc = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US);
        utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        byte[] validity = der(0x30, der(0x17, utc.format(new Date(now - 86_400_000L)).getBytes(StandardCharsets.US_ASCII)),
                der(0x17, utc.format(new Date(now + 20L * 365 * 86_400_000L)).getBytes(StandardCharsets.US_ASCII)));
        byte[] serial = new byte[8];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7f;
        serial[0] |= 0x01;
        byte[] tbs = der(0x30, new byte[]{(byte) 0xA0, 3, 2, 1, 2}, der(0x02, serial), algorithm, name, validity, name,
                pair.getPublic().getEncoded());
        Signature sign = Signature.getInstance("SHA256withRSA");
        sign.initSign(pair.getPrivate());
        sign.update(tbs);
        byte[] signature = sign.sign();
        byte[] bits = new byte[signature.length + 1];
        System.arraycopy(signature, 0, bits, 1, signature.length);
        return der(0x30, tbs, algorithm, der(0x03, bits));
    }

    private static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] part : parts) body.write(part, 0, part.length);
        byte[] content = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (content.length < 128) {
            out.write(content.length);
        } else if (content.length < 256) {
            out.write(0x81);
            out.write(content.length);
        } else {
            out.write(0x82);
            out.write(content.length >> 8);
            out.write(content.length);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] read(File f) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(data);
        }
    }
}
