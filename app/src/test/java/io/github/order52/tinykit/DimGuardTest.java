package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DimGuardTest {
    @Test
    public void strengthNeverGoesAboveTheCap() {
        assertEquals(DimGuard.MAX_LEVEL, DimGuard.capped(100));
        assertEquals(DimGuard.MAX_LEVEL, DimGuard.capped(DimGuard.MAX_LEVEL + 1));
        assertEquals(20, DimGuard.capped(20));
        assertEquals(0, DimGuard.capped(0));
    }
}
