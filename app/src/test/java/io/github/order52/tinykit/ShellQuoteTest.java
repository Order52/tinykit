package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ShellQuoteTest {
    @Test
    public void wrapsInSingleQuotes() {
        assertEquals("'com.example.app'", Shell.quote("com.example.app"));
    }

    @Test
    public void escapesEmbeddedQuotes() {
        assertEquals("'it'\\''s'", Shell.quote("it's"));
    }

    @Test
    public void keepsShellSyntaxInert() {
        assertEquals("'$(rm -rf /); `id`'", Shell.quote("$(rm -rf /); `id`"));
    }
}
