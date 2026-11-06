package io.github.order52.tinykit;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * The SPAKE2 key exchange over the Ed25519 curve that Android's wireless debugging uses to pair
 * (BoringSSL's flavour of it). Written with BigInteger: it runs once per pairing, so speed does not matter.
 */
final class Spake2 {
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger[] BASE = {null, BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)};
    private static final BigInteger[] M = decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
    private static final BigInteger[] N = decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));

    static {
        BASE[0] = recoverX(BASE[1], 0);
    }

    private final boolean alice;
    private final byte[] myName, theirName;
    private final byte[] passwordHash;
    private final BigInteger password; // a multiple of 8, as BoringSSL makes it
    private final BigInteger secret;
    private byte[] myMessage;

    /** {@code alice} is the side that starts: the adb client. */
    Spake2(boolean alice, byte[] myName, byte[] theirName, byte[] password) {
        this.alice = alice;
        this.myName = myName;
        this.theirName = theirName;
        passwordHash = sha512(password);
        BigInteger w = littleEndian(passwordHash).mod(L);
        // BoringSSL adds l, 2l and 4l (each only when the low bit asks) so that w is a multiple of 8.
        if (w.testBit(0)) w = w.add(L);
        if (w.testBit(1)) w = w.add(L.shiftLeft(1));
        if (w.testBit(2)) w = w.add(L.shiftLeft(2));
        this.password = w;
        byte[] random = new byte[64];
        new SecureRandom().nextBytes(random);
        secret = littleEndian(random).mod(L).shiftLeft(3);
    }

    /** The 32 bytes to send to the other side. */
    byte[] message() {
        BigInteger[] mask = mul(alice ? M : N, password);
        myMessage = encode(add(mul(BASE, secret), mask));
        return myMessage;
    }

    /** The shared 64-byte key, from the other side's message. Throws if the message is not a point. */
    byte[] finish(byte[] theirMessage) {
        BigInteger[] their = decode(theirMessage);
        BigInteger[] peerMask = mul(alice ? N : M, password);
        BigInteger[] q = add(their, new BigInteger[]{peerMask[0].negate().mod(P), peerMask[1]});
        byte[] k = encode(mul(q, secret));
        MessageDigest sha = digest();
        byte[][] parts = alice
                ? new byte[][]{myName, theirName, myMessage, theirMessage}
                : new byte[][]{theirName, myName, theirMessage, myMessage};
        for (byte[] part : parts) update(sha, part);
        update(sha, k);
        update(sha, passwordHash);
        return sha.digest();
    }

    private static void update(MessageDigest sha, byte[] data) {
        for (int i = 0; i < 8; i++) sha.update((byte) ((long) data.length >> (8 * i)));
        sha.update(data);
    }

    // ---- Ed25519 (affine, the complete addition law) ---------------------------------------------

    private static BigInteger[] add(BigInteger[] a, BigInteger[] b) {
        BigInteger xy = a[0].multiply(b[0]).mod(P).multiply(a[1]).mod(P).multiply(b[1]).mod(P);
        BigInteger dxy = D.multiply(xy).mod(P);
        BigInteger x = a[0].multiply(b[1]).add(b[0].multiply(a[1])).mod(P)
                .multiply(BigInteger.ONE.add(dxy).modInverse(P)).mod(P);
        BigInteger y = a[1].multiply(b[1]).add(a[0].multiply(b[0])).mod(P)
                .multiply(BigInteger.ONE.subtract(dxy).mod(P).modInverse(P)).mod(P);
        return new BigInteger[]{x, y};
    }

    private static BigInteger[] mul(BigInteger[] point, BigInteger scalar) {
        BigInteger[] result = {BigInteger.ZERO, BigInteger.ONE};
        for (int i = scalar.bitLength() - 1; i >= 0; i--) {
            result = add(result, result);
            if (scalar.testBit(i)) result = add(result, point);
        }
        return result;
    }

    private static BigInteger recoverX(BigInteger y, int sign) {
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger x2 = y2.subtract(BigInteger.ONE).mod(P).multiply(D.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P);
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) x = x.multiply(SQRT_M1).mod(P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) throw new IllegalArgumentException("not a point");
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x).mod(P);
        return x;
    }

    private static BigInteger[] decode(byte[] bytes) {
        if (bytes.length != 32) throw new IllegalArgumentException("not a point");
        int sign = (bytes[31] >> 7) & 1;
        byte[] y = bytes.clone();
        y[31] &= 0x7f;
        BigInteger yy = littleEndian(y);
        if (yy.compareTo(P) >= 0) throw new IllegalArgumentException("not a point");
        return new BigInteger[]{recoverX(yy, sign), yy};
    }

    private static byte[] encode(BigInteger[] point) {
        byte[] out = toLittleEndian(point[1], 32);
        if (point[0].testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    // ---- bytes ---------------------------------------------------------------------------------------

    private static BigInteger littleEndian(byte[] in) {
        byte[] big = new byte[in.length];
        for (int i = 0; i < in.length; i++) big[i] = in[in.length - 1 - i];
        return new BigInteger(1, big);
    }

    static byte[] toLittleEndian(BigInteger value, int length) {
        byte[] out = new byte[length];
        byte[] big = value.toByteArray();
        for (int i = 0; i < big.length && i < length + 1; i++) {
            int at = big.length - 1 - i;
            if (i < length) out[i] = big[at];
        }
        return out;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha512(byte[] in) {
        return digest().digest(in);
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
