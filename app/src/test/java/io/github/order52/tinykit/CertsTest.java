package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CertsTest {
    @Test
    public void commonNameIsPickedOutOfAName() {
        assertEquals("ISRG Root X1", Certs.value("CN=ISRG Root X1,O=Internet Security Research Group,C=US", "CN"));
        assertEquals("Internet Security Research Group", Certs.value("CN=ISRG Root X1,O=Internet Security Research Group,C=US", "O"));
        assertEquals("", Certs.value("O=Only Org", "CN"));
    }

    @Test
    public void escapedCommasStayInsideTheValue() {
        assertEquals("Acme, Inc.", Certs.value("CN=Acme\\, Inc.,C=US", "CN"));
    }

    @Test
    public void fingerprintIsUpperCaseHexWithColons() {
        assertEquals("00:0A:FF", Certs.colons(new byte[]{0, 10, (byte) 255}));
    }

    @Test
    public void websiteInputBecomesHostAndPort() {
        assertEquals("example.com", Certs.hostAndPort("example.com")[0]);
        assertEquals(443, Certs.hostAndPort("example.com")[1]);
        assertEquals("example.com", Certs.hostAndPort("https://example.com/a/b?c=d")[0]);
        assertEquals(8443, Certs.hostAndPort("  example.com:8443 ")[1]);
        assertEquals("example.com", Certs.hostAndPort("user@example.com")[0]);
        assertNull(Certs.hostAndPort(""));
        assertNull(Certs.hostAndPort("exa mple.com"));
        assertNull(Certs.hostAndPort("example.com:99999"));
        assertNotNull(Certs.hostAndPort("example.com:1"));
    }

    @Test
    public void russianPluralForms() {
        assertEquals(0, Tr.ruPlural(1));
        assertEquals(0, Tr.ruPlural(21));
        assertEquals(1, Tr.ruPlural(2));
        assertEquals(1, Tr.ruPlural(24));
        assertEquals(2, Tr.ruPlural(5));
        assertEquals(2, Tr.ruPlural(11));
        assertEquals(2, Tr.ruPlural(14));
        assertEquals(2, Tr.ruPlural(0));
    }
}
