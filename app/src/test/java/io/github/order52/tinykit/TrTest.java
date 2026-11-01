package io.github.order52.tinykit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.io.StringReader;

import org.junit.After;
import org.junit.Test;

public class TrTest {
    @After
    public void reset() {
        Tr.load(null);
    }

    private static void load(String tsv) {
        Tr.load(new StringReader(tsv));
    }

    @Test
    public void englishIsUntouched() {
        Tr.load(null);
        String s = "Anything at all";
        assertSame(s, Tr.t(s));
    }

    @Test
    public void exactMatchIsTranslated() {
        load("Cancel\tCancelar\n");
        assertEquals("Cancelar", Tr.t("Cancel"));
        assertEquals("Cancel it", Tr.t("Cancel it"));
    }

    @Test
    public void fixedBeginningAndEndOfABuiltTextAreTranslated() {
        load("Also blocked: \tTambién bloqueadas: \n (other profile)\t (otro perfil)\n");
        assertEquals("También bloqueadas: Chrome, Gmail", Tr.t("Also blocked: Chrome, Gmail"));
        assertEquals("com.foo (otro perfil)", Tr.t("com.foo (other profile)"));
    }

    @Test
    public void aFragmentNeverMatchesInsideAWord() {
        load("Block\tBloquear\n");
        assertEquals("Blocked apps", Tr.t("Blocked apps"));
    }

    @Test
    public void escapesAndCommentsAndMissingTranslations() {
        load("# comment\nOne\\nTwo\tUno\\nDos\nEmpty\t\nNoTab\n");
        assertEquals("Uno\nDos", Tr.t("One\nTwo"));
        assertEquals("Empty", Tr.t("Empty"));
        assertEquals("NoTab", Tr.t("NoTab"));
    }

    @Test
    public void choicesAreCopiedAndTranslated() {
        load("Yes\tSí\n");
        String[] in = {"Yes", "Maybe"};
        String[] out = Tr.all(in);
        assertEquals("Sí", out[0]);
        assertEquals("Maybe", out[1]);
        assertEquals("Yes", in[0]);
    }
}
