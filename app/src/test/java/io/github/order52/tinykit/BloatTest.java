package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BloatTest {
    @Test
    public void userAppsAreSafe() {
        assertEquals(Bloat.SAFE, Bloat.info("com.example.game", false).level);
    }

    @Test
    public void unknownSystemAppsGetCautiousDefaults() {
        assertEquals(Bloat.EXPERT, Bloat.info("com.android.somethingnew", true).level);
        assertEquals(Bloat.ADVANCED, Bloat.info("com.vendor.unknown", true).level);
    }

    @Test
    public void listedAppsKeepTheirRating() {
        assertEquals(Bloat.UNSAFE, Bloat.info("com.miui.home", true).level);
        assertEquals(Bloat.UNSAFE, Bloat.info("com.sec.android.app.launcher", true).level);
    }

    @Test
    public void everyListedEntryIsWellFormed() throws Exception {
        java.lang.reflect.Field f = Bloat.class.getDeclaredField("LIST");
        f.setAccessible(true);
        for (String line : (String[]) f.get(null)) {
            String[] p = line.split("\\|", 3);
            assertEquals(line, 3, p.length);
            int level = Integer.parseInt(p[1]);
            assertTrue(line, level >= Bloat.SAFE && level <= Bloat.UNSAFE);
            assertTrue(line, !p[0].isEmpty() && !p[2].isEmpty());
        }
    }
}
