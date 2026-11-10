package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DozeTest {
    @Test
    public void defaultHasNoConstantsAndTheOthersListTheirs() {
        assertNull(Doze.constants(0));
        assertTrue(Doze.constants(1).contains("inactive_to=600000"));
        assertTrue(Doze.constants(4).contains("idle_to=21600000"));
        assertTrue(Doze.constants(4).contains("location_accuracy=1000")); // metres, not milliseconds
        assertFalse(Doze.constants(1).contains(",max_idle_to=")); // only Moderate and up
    }

    @Test
    public void readsDozeDurations() {
        assertEquals(Long.valueOf(30_000), Doze.duration("+30s0ms"));
        assertEquals(Long.valueOf(3_600_000L * 4), Doze.duration("+4h0m0s0ms"));
        assertEquals(Long.valueOf(15 * 60_000L), Doze.duration(" +15m0s0ms"));
        assertNull(Doze.duration("20.0m"));
        assertNull(Doze.duration("true"));
    }

    @Test
    public void saysWhetherAndroidUsesAProfile() {
        Doze.Status s = new Doze.Status();
        s.settings.put("inactive_to", 30_000L);
        s.settings.put("light_after_inactive_to", 5_000L);
        s.settings.put("idle_to", 14_400_000L);
        assertTrue(s.matches(3));
        s.settings.put("inactive_to", 30L * 60_000L);
        assertFalse(s.matches(3));
        assertTrue(s.matches(0));
    }

    @Test
    public void ignoresTimingsTheSystemRefused() {
        Doze.Status s = new Doze.Status();
        s.settings.put("inactive_to", 600_000L); // Android 16 wouldn't take this one
        s.settings.put("light_after_inactive_to", 5_000L);
        s.settings.put("idle_to", 14_400_000L);
        assertFalse(s.matches(3));
        assertTrue(s.matches(3, java.util.Collections.singleton("inactive_to")));
    }

    @Test
    public void defaultSetsNoTimingsAndCountingThemDoesNotCrash() {
        assertEquals(0, Doze.wanted(0)); // Default used to read the name column as a number (#15)
        for (int p = 1; p <= 4; p++) assertTrue(Doze.wanted(p) > 0);
    }
}
