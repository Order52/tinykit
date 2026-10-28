package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QrTest {
    @Test
    public void wifiCodeFits() {
        Qr qr = Qr.encode("WIFI:T:WPA;S:Home;P:correct horse battery staple;;");
        assertNotNull(qr);
        assertEquals(0, (qr.size - 17) % 4);
    }

    @Test
    public void finderPatternIsInTheCorners() {
        Qr qr = Qr.encode("hello");
        assertNotNull(qr);
        assertTrue(qr.get(0, 0));
        assertTrue(qr.get(qr.size - 1, 0));
        assertTrue(qr.get(0, qr.size - 1));
        assertTrue(!qr.get(7, 7) || qr.size > 21);
    }

    @Test
    public void sameTextGivesSameCode() {
        Qr a = Qr.encode("same"), b = Qr.encode("same");
        for (int y = 0; y < a.size; y++) for (int x = 0; x < a.size; x++) assertEquals(a.get(x, y), b.get(x, y));
    }

    @Test
    public void tooLongTextIsRejected() {
        assertNull(Qr.encode(new String(new char[2000]).replace('\0', 'x')));
    }
}
