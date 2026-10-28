package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class InstallOptionsTest {
    @Test
    public void noOptionsAddsNoFlags() {
        assertEquals("", ApkInstaller.flags(0));
    }

    @Test
    public void eachOptionMapsToItsPmFlag() {
        assertEquals(" -d", ApkInstaller.flags(ApkInstaller.DOWNGRADE));
        assertEquals(" -g", ApkInstaller.flags(ApkInstaller.GRANT_ALL));
        assertEquals(" -t", ApkInstaller.flags(ApkInstaller.TEST_ONLY));
        assertEquals(" -d -g -t", ApkInstaller.flags(7));
    }
}
