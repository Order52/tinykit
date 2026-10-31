package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PermGuardTest {
    @Test
    public void restoreModeNeverWritesDefaultForUidOps() {
        // "appops set --uid ... default" exits 0 but changes nothing, so a restore must write "allow".
        assertEquals("allow", PermGuard.restoreMode("default"));
    }

    @Test
    public void restoreModeKeepsEveryOtherMode() {
        assertEquals("allow", PermGuard.restoreMode("allow"));
        assertEquals("ignore", PermGuard.restoreMode("ignore"));
        assertEquals("deny", PermGuard.restoreMode("deny"));
        assertEquals("foreground", PermGuard.restoreMode("foreground"));
    }
}
