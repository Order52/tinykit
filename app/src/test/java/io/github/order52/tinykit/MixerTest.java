package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

public class MixerTest {
    @Test
    public void savesAndReadsTheVolumes() {
        Map<Integer, Integer> rules = Mixer.parse("10146:25,1510146:50");
        assertEquals(Integer.valueOf(25), rules.get(10146));
        assertEquals(Integer.valueOf(50), rules.get(1510146)); // a work profile app has its own uid
        assertEquals("10146:25,1510146:50", Mixer.format(rules));
        assertTrue(Mixer.lowered(rules));
    }

    @Test
    public void damagedEntriesAreSkippedAndPercentsStayInRange() {
        Map<Integer, Integer> rules = Mixer.parse("x:1,10001:150,10002:-5,10003,10004:40:2,,");
        assertEquals(2, rules.size());
        assertEquals(Integer.valueOf(100), rules.get(10001));
        assertEquals(Integer.valueOf(0), rules.get(10002));
        assertFalse(Mixer.lowered(Mixer.parse("")));
        assertFalse(Mixer.lowered(Mixer.parse("10001:100")));
    }
}
