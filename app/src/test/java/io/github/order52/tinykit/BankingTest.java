package io.github.order52.tinykit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BankingTest {
    @Test
    public void savedListNamesWholeSettingsOnly() {
        assertTrue(Banking.has("development_settings_enabled,adb_enabled", "adb_enabled"));
        assertFalse(Banking.has("adb_wifi_enabled", "adb_enabled"));
        assertFalse(Banking.has("", "adb_enabled"));
    }
}
