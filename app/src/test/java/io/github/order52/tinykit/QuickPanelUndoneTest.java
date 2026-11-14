package io.github.order52.tinykit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class QuickPanelUndoneTest {
    private final List<String> old = Arrays.asList("wifi", "bt", "dnd");
    private final List<String> next = Arrays.asList("bt", "wifi", "dnd");

    @Test
    public void whatWeWroteStaysIsNotUndone() {
        assertFalse(QuickPanelScreen.undone("bt,wifi,dnd\n", old, next));
    }

    @Test
    public void backToTheOldPanelIsUndone() {
        assertTrue(QuickPanelScreen.undone("wifi,bt,dnd\n", old, next));
    }

    @Test
    public void emptyOrNullMeansThePhoneResetIt() {
        assertTrue(QuickPanelScreen.undone("null\n", old, next));
        assertTrue(QuickPanelScreen.undone("", old, next));
    }

    @Test
    public void aTidiedValueIsNotCountedAsUndone() {
        assertFalse(QuickPanelScreen.undone("bt,wifi", old, next));
    }
}
