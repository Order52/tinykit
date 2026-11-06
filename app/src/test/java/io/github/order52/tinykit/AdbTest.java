package io.github.order52.tinykit;

import static org.junit.Assert.assertArrayEquals;

import java.io.File;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.crypto.Cipher;
import org.junit.Test;

public class AdbTest {
    @Test
    public void signsTheLoginTokenTheWayAdbChecksIt() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("adbkey").toFile();
        Adb adb = new Adb(dir);
        byte[] token = new byte[20];
        new java.security.SecureRandom().nextBytes(token);
        byte[] signature = adb.sign(token);
        // adb takes the 20-byte token as a SHA-1 digest: the signature holds the SHA-1 DigestInfo of it.
        X509Certificate cert = (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.FileInputStream(new File(dir, "adbcert")));
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, cert.getPublicKey());
        byte[] prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        byte[] expected = Arrays.copyOf(prefix, prefix.length + 20);
        System.arraycopy(token, 0, expected, prefix.length, 20);
        assertArrayEquals(expected, rsa.doFinal(signature));
    }

    @Test
    public void theKeyIsKeptBetweenRuns() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("adbkey").toFile();
        byte[] first = new Adb(dir).sign(new byte[20]);
        assertArrayEquals(first, new Adb(dir).sign(new byte[20]));
    }
}
