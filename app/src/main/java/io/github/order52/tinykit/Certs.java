package io.github.order52.tinykit;

import java.io.IOException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/**
 * The certificates this phone trusts (its root and intermediate authorities, and the ones somebody
 * installed), and the chain a website sends, checked against them. Reads only; Android keeps the
 * trust store itself, so adding and removing goes through the phone's own security settings.
 */
final class Certs {
    private Certs() {
    }

    /** One certificate in the phone's trust store. */
    static final class Info {
        final X509Certificate cert;
        /** Installed by a person or an app (the "user:" aliases), as opposed to shipped with Android. */
        final boolean user;

        Info(X509Certificate cert, boolean user) {
            this.cert = cert;
            this.user = user;
        }

        String name() {
            return nameOf(cert.getSubjectX500Principal());
        }

        String issuer() {
            return nameOf(cert.getIssuerX500Principal());
        }

        boolean selfSigned() {
            return cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
        }

        boolean expired() {
            return cert.getNotAfter().before(new Date());
        }
    }

    /** Everything in Android's CA store, the certificates installed by the user first. */
    static List<Info> trusted() {
        List<Info> out = new ArrayList<>();
        try {
            KeyStore store = KeyStore.getInstance("AndroidCAStore");
            store.load(null, null);
            for (Enumeration<String> e = store.aliases(); e.hasMoreElements(); ) {
                String alias = e.nextElement();
                if (store.getCertificate(alias) instanceof X509Certificate) {
                    out.add(new Info((X509Certificate) store.getCertificate(alias), alias.startsWith("user:")));
                }
            }
        } catch (Exception | Error e) {
            // An empty list says "couldn't read it".
        }
        Collections.sort(out, (a, b) -> a.user != b.user ? (a.user ? -1 : 1)
                : a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /** The common name of a certificate holder, else its organisation, else the whole name. */
    static String nameOf(X500Principal p) {
        String rfc = p.getName(X500Principal.RFC2253);
        String cn = value(rfc, "CN");
        if (cn.isEmpty()) cn = value(rfc, "O");
        return cn.isEmpty() ? rfc : cn;
    }

    /** The value of one attribute (CN, O, ...) in an RFC 2253 name, "" if it has none. */
    static String value(String rfc2253, String key) {
        StringBuilder current = new StringBuilder();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < rfc2253.length(); i++) {
            char c = rfc2253.charAt(i);
            if (c == '\\' && i + 1 < rfc2253.length()) {
                current.append(rfc2253.charAt(++i));
            } else if (c == ',' || c == '+') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase(key)) return part.substring(eq + 1).trim();
        }
        return "";
    }

    /** SHA-256 of the certificate, as aa:bb:cc... the way browsers show it. */
    static String fingerprint(X509Certificate cert) {
        try {
            return colons(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (Exception e) {
            return "";
        }
    }

    static String colons(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            if (sb.length() > 0) sb.append(':');
            sb.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    static String date(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(d);
    }

    /** What a website sent: its chain from the site's own certificate up, and whether the phone trusts it. */
    static final class Chain {
        final List<X509Certificate> certs = new ArrayList<>();
        /** Null when the phone trusts the chain, else why it doesn't. */
        String problem;
        String protocol = "";
    }

    /** Connects to host:port over TLS and returns the chain it presents. Blocks; call off the main thread. */
    static Chain fetch(String host, int port) throws IOException {
        Chain result = new Chain();
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            X509TrustManager phone = null;
            for (TrustManager tm : tmf.getTrustManagers()) if (tm instanceof X509TrustManager) phone = (X509TrustManager) tm;
            final X509TrustManager real = phone;
            // Takes the chain whatever it is, so an untrusted site can still be looked at, and says why it fails.
            X509TrustManager recorder = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String type) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String type) {
                    Collections.addAll(result.certs, chain);
                    try {
                        if (real != null) real.checkServerTrusted(chain, type);
                    } catch (Exception e) {
                        result.problem = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return real == null ? new X509Certificate[0] : real.getAcceptedIssuers();
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{recorder}, null);
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), 8000);
                socket.setSoTimeout(8000);
                socket.startHandshake();
                result.protocol = socket.getSession().getProtocol();
            }
        } catch (IOException e) {
            if (result.certs.isEmpty()) throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return result;
    }

    /** "example.com", "https://example.com/path" or "example.com:8443" to host and port. Null if there is no host. */
    static Object[] hostAndPort(String input) {
        String s = input.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int port = 443;
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            try {
                port = Integer.parseInt(s.substring(colon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            s = s.substring(0, colon);
        }
        if (s.isEmpty() || s.indexOf(' ') >= 0 || port < 1 || port > 65535) return null;
        return new Object[]{s, port};
    }
}
