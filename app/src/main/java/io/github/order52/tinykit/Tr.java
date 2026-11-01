package io.github.order52.tinykit;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates the text TinyKit shows. The English text stays in the code; a translation is one
 * file, {@code assets/i18n/<language>.tsv}, with one {@code English<TAB>Translation} pair per line
 * ({@code \n} and {@code \t} stand for a new line and a tab, lines starting with # are comments).
 * The phone's language picks the file; with no file for it, or in English, every call returns the
 * text unchanged and costs nothing.
 *
 * A text built from pieces ("Also blocked: " + names) is translated by its known beginning or end,
 * so the fixed part is translated and the variable part is left as it is.
 */
final class Tr {
    private static volatile Map<String, String> words = Collections.emptyMap();
    private static volatile String[] starts = new String[0];
    private static volatile String[] ends = new String[0];

    private Tr() {
    }

    /** Loads the translation for the phone's language, if there is one. Safe to call again. */
    static void init(Context c) {
        String language = Locale.getDefault().getLanguage();
        try (InputStream in = c.getAssets().open("i18n/" + language + ".tsv")) {
            load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException | RuntimeException none) {
            load(null); // no translation for this language: stay in English
        }
    }

    /** Reads {@code English<TAB>Translation} lines; {@code null} clears the translation. */
    static void load(java.io.Reader reader) {
        Map<String, String> loaded = new HashMap<>();
        if (reader != null) {
            try (BufferedReader r = new BufferedReader(reader)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    String to = unescape(line.substring(tab + 1));
                    if (!to.isEmpty()) loaded.put(unescape(line.substring(0, tab)), to);
                }
            } catch (java.io.IOException e) {
                loaded.clear();
            }
        }
        List<String> s = new ArrayList<>();
        List<String> e = new ArrayList<>();
        for (String k : loaded.keySet()) {
            if (k.length() < 3) continue;
            // A fragment only counts at a word edge, so "Block" never matches inside "Blocked".
            if (!Character.isLetterOrDigit(k.charAt(k.length() - 1))) s.add(k);
            if (!Character.isLetterOrDigit(k.charAt(0))) e.add(k);
        }
        Collections.sort(s, (a, b) -> b.length() - a.length());
        Collections.sort(e, (a, b) -> b.length() - a.length());
        starts = s.toArray(new String[0]);
        ends = e.toArray(new String[0]);
        words = loaded;
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t");
    }

    static String t(String text) {
        Map<String, String> map = words;
        if (text == null || map.isEmpty() || text.isEmpty()) return text;
        String hit = map.get(text);
        if (hit != null) return hit;
        for (String k : starts) {
            if (text.length() > k.length() && text.startsWith(k)) return map.get(k) + text.substring(k.length());
        }
        for (String k : ends) {
            if (text.length() > k.length() && text.endsWith(k)) {
                return text.substring(0, text.length() - k.length()) + map.get(k);
            }
        }
        return text;
    }

    /** A translated copy of a list of choices (the original array is left as it is). */
    static String[] all(String[] texts) {
        if (words.isEmpty() || texts == null) return texts;
        String[] out = new String[texts.length];
        for (int i = 0; i < texts.length; i++) out[i] = t(texts[i]);
        return out;
    }

    static CharSequence t(CharSequence text) {
        return text instanceof String ? t((String) text) : text;
    }
}
