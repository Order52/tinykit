package io.github.order52.tinykit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public class Spake2Test {
    private static byte[] pw(String s) {
        return Spake2.ascii(s);
    }

    @Test
    public void bothSidesGetTheSameKeyWithTheSamePassword() {
        byte[] a = pw("adb pair client"), b = pw("adb pair server");
        for (int i = 0; i < 5; i++) {
            Spake2 alice = new Spake2(true, a, b, pw("123456-secret" + i));
            Spake2 bob = new Spake2(false, b, a, pw("123456-secret" + i));
            byte[] ma = alice.message(), mb = bob.message();
            assertEquals(32, ma.length);
            assertArrayEquals(alice.finish(mb), bob.finish(ma));
        }
    }

    @Test
    public void aWrongPasswordGivesAnotherKey() {
        byte[] a = pw("adb pair client"), b = pw("adb pair server");
        Spake2 alice = new Spake2(true, a, b, pw("123456"));
        Spake2 bob = new Spake2(false, b, a, pw("654321"));
        byte[] ma = alice.message(), mb = bob.message();
        assertFalse(Arrays.equals(alice.finish(mb), bob.finish(ma)));
    }
}
