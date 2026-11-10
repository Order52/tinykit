package io.github.order52.tinykit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SavedWifiTest {
    @Test
    public void countsTheNetworkLinesOfAnExport() {
        assertEquals(3, SavedWifiScreen.countNetworks("Home\tWPA\tsecret\nCafe\tnopass\t\nOffice\tWPA\tpw2\n"));
        assertEquals(1, SavedWifiScreen.countNetworks("Home\tWPA\tsecret\r\n"));
    }

    @Test
    public void aFileThatIsNotAnExportHasNone() {
        assertEquals(0, SavedWifiScreen.countNetworks(""));
        assertEquals(0, SavedWifiScreen.countNetworks("just some notes\nnot a table"));
        assertEquals(0, SavedWifiScreen.countNetworks("\tWPA\tno name\n"));
        assertEquals(0, SavedWifiScreen.countNetworks("a\tb\tc\n"));
    }

    @Test
    public void anOpenNetworkKeepsItsPlaceWhenTheEmptyPasswordIsCutOff() {
        // The shell hands back the last line without its trailing tab (the last open network used to vanish from the list).
        assertArrayEquals(new String[]{"Cafe", "nopass", ""}, SavedWifiScreen.fields("Cafe\tnopass"));
        assertArrayEquals(new String[]{"Cafe", "nopass", ""}, SavedWifiScreen.fields("Cafe\tnopass\t"));
        assertEquals(1, SavedWifiScreen.countNetworks("Cafe\tnopass"));
    }
}
