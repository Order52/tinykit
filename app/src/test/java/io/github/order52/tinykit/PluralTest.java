package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PluralTest {
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
