package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class WorkTest {
    @Test
    public void findsTheManagedProfileOnly() {
        assertEquals(-1, Work.parseId("Users:\n\tUserInfo{0:Owner:4c13} running"));
        assertEquals(10, Work.parseId("Users:\n\tUserInfo{0:Owner:4c13} running\n\tUserInfo{10:Work:1030} running"));
        // 0x1000 is a profile of another kind (private space, clone): not a work profile.
        assertEquals(-1, Work.parseId("Users:\n\tUserInfo{0:Owner:4c13} running\n\tUserInfo{11:Private:1010}"));
    }

    @Test
    public void samsungSecureFolderAndOtherSpacesAreNeverTheWorkProfile() {
        // Secure Folder is user 150; even if a phone flags it like a managed profile it must be skipped.
        String users = "Users:\n\tUserInfo{0:Owner:4c13} running\n\tUserInfo{150:Secure Folder:1020} running\n"
                + "\tUserInfo{95:Dual Messenger:1020} running";
        assertEquals(-1, Work.parseId(users));
        assertEquals(12, Work.parseId(users + "\n\tUserInfo{12:Work:1030} running"));
    }

    @Test
    public void severalWorkProfilesPreferTheUsersOwn() {
        String users = "\tUserInfo{0:Owner:4c13} running\n\tUserInfo{10:Work:1030}\n\tUserInfo{20:Work:1030}";
        assertEquals(10, Work.parseId(users));
        assertEquals(20, Work.parseId(users, new java.util.HashSet<>(java.util.Arrays.asList(0, 20))));
    }

    @Test
    public void oddFlagsDoNotCrash() {
        assertEquals(-1, Work.parseId("UserInfo{10:Work:zzzz}"));
        assertEquals(-1, Work.parseId(""));
        assertEquals(10, Work.parseId("UserInfo{10:Work:ffffffff1030}"));
    }

    @Test
    public void readsTheLockTypeOfTheProfile() {
        String dump = "User State:\n  User 0\n    CredentialType: NONE\n    SeparateChallenge: false\n"
                + "  User 11\n    CredentialType: PIN\n    SeparateChallenge: true\n";
        assertEquals("PIN", Work.parseLockType(dump, 11));
        assertEquals("NONE", Work.parseLockType(dump, 0));
        assertNull(Work.parseLockType(dump, 12));
        assertEquals("PIN", Work.parseLockType("User 10\n    Quality: 0\n    CredentialType: Pin\n", 10));
        assertNull(Work.parseLockType("", 11));
    }

    @Test
    public void readsAppsAndFrozenState() {
        String out = "16 activities found:\n  Activity #0:\n    priority=0 preferredOrder=0 match=0x108000\n"
                + "    com.example.frozen/.Main\n  Activity #1:\n    priority=0\n"
                + "    com.example.live/com.example.live.Ui$Home\n    com.android.stk/.Launcher";
        List<Work.App> apps = Work.build(Work.parseLaunch(out), java.util.Collections.singletonList("com.example.frozen"));
        assertEquals(3, apps.size());
        assertEquals("com.example.frozen/.Main", apps.get(0).component);
        assertTrue(apps.get(0).frozen);
        assertFalse(apps.get(1).frozen);
        assertEquals("com.example.live/com.example.live.Ui$Home", apps.get(1).component);
        assertFalse(apps.get(2).frozen); // shipped disabled, not frozen by the user
    }

    @Test
    public void nothingFoundStillParses() {
        assertEquals(0, Work.parseLaunch("No activities found").size());
        assertEquals(0, Work.parseLaunch("Exception occurred while executing:\njava.lang.IllegalArgumentException: Unknown option: --query-flags\n\tat android.content.Intent.parseCommandArgs(Intent.java:6525)").size());
        assertEquals(1, Work.build(Work.parseLaunch("  Activity #0:\n    com.example.a/.A"), new java.util.ArrayList<>()).size());
    }
}
