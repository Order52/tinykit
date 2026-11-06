package io.github.order52.tinykit;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Scanner;
import java.util.Set;

/**
 * The per-app volume mixer. Android lets the shell user scale any one player (the AudioTrack or
 * MediaPlayer an app plays through) by a factor between 0 and 1, which is what this does: it
 * holds the volumes the user set and applies them to each app's players as they start, so a new
 * YouTube video is as quiet as the last one. TinyKit starts it with app_process as the shell
 * user via Shizuku, and it ends by itself a little after nothing needs it, putting every player
 * back to full volume.
 *
 * Args: {@code run FILE} keeps FILE's volumes applied (lines of "uid percent") and writes the apps
 * playing right now to FILE.now (lines of "uid"), touching it every pass so TinyKit can tell it runs.
 */
public final class Mixer {
    static final String PREF = "mixer";
    static final String FILE = "/data/local/tmp/tinykit-mixer";
    private static final int STARTED = 2;
    private static final long TICK = 300;
    /** The daemon stays up this long after its file was last touched, unless a volume is lowered. */
    private static final long IDLE = 25_000;

    public static void main(String[] args) {
        try {
            if (args.length < 2 || !args[0].equals("run")) throw new IllegalArgumentException("run FILE");
            run(new File(args[1]));
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.out.println("This Android doesn't let TinyKit change one app's volume: " + cause);
            System.exit(1);
        }
    }

    private static Object audio;
    private static Method active;

    @SuppressWarnings("unchecked")
    private static List<Object> players() throws Exception {
        if (active == null) {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "audio");
            audio = Class.forName("android.media.IAudioService$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            active = audio.getClass().getMethod("getActivePlaybackConfigurations");
        }
        return (List<Object>) active.invoke(audio);
    }

    private static void run(File rules) throws Exception {
        Map<Integer, Integer> volume = new HashMap<>();
        Map<Integer, Float> applied = new HashMap<>(); // player id -> what it was last set to
        long seen = -1;
        String shown = null;
        File now = new File(rules.getPath() + ".now");
        while (rules.exists()) {
            long modified = rules.lastModified();
            if (modified != seen) {
                seen = modified;
                volume = read(rules);
            }
            boolean lowered = false;
            for (int p : volume.values()) lowered |= p < 100;
            if (!lowered && System.currentTimeMillis() - modified > IDLE) break;
            Set<Integer> playing = new HashSet<>();
            Set<Integer> alive = new HashSet<>();
            StringBuilder out = new StringBuilder();
            for (Object config : players()) {
                int uid = (Integer) call(config, "getClientUid");
                // Before Android 13 the shell is told nothing about who plays: the uid is -1.
                if (uid < 0) throw new IllegalStateException("it hides which app plays");
                int id = (Integer) call(config, "getPlayerInterfaceId");
                alive.add(id);
                if ((Integer) call(config, "getPlayerState") != STARTED) continue;
                if (playing.add(uid)) out.append(uid).append('\n');
                Integer percent = volume.get(uid);
                float want = percent == null ? 1f : percent / 100f;
                Float had = applied.get(id);
                if (had == null ? want == 1f : had == want) continue;
                try {
                    Object proxy = call(config, "getPlayerProxy");
                    proxy.getClass().getMethod("setVolume", float.class).invoke(proxy, want);
                    applied.put(id, want);
                } catch (Exception e) {
                    // The player ended between the list and the call.
                }
            }
            applied.keySet().retainAll(alive);
            if (!out.toString().equals(shown)) {
                shown = out.toString();
                write(now, shown);
            }
            now.setLastModified(System.currentTimeMillis());
            Thread.sleep(TICK);
        }
        // Done: every player back to full volume, and nothing left behind.
        for (Object config : players()) {
            Float had = applied.get((Integer) call(config, "getPlayerInterfaceId"));
            if (had == null || had == 1f) continue;
            try {
                Object proxy = call(config, "getPlayerProxy");
                proxy.getClass().getMethod("setVolume", float.class).invoke(proxy, 1f);
            } catch (Exception e) {
                // gone already
            }
        }
        now.delete();
        rules.delete();
        new File(rules.getPath() + ".log").delete();
    }

    private static Object call(Object o, String name) throws Exception {
        return o.getClass().getMethod(name).invoke(o);
    }

    private static Map<Integer, Integer> read(File f) {
        Map<Integer, Integer> map = new HashMap<>();
        try (Scanner in = new Scanner(f, "UTF-8")) {
            while (in.hasNextInt()) {
                int uid = in.nextInt();
                if (!in.hasNextInt()) break;
                map.put(uid, Math.max(0, Math.min(100, in.nextInt())));
            }
        } catch (Exception e) {
            // Half-written: the next pass reads it again.
        }
        return map;
    }

    private static void write(File f, String text) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // The file is only a convenience for the screen.
        }
    }

    // ---- TinyKit's side ---------------------------------------------------------------------------

    /**
     * Android 13 and newer: older versions hide from the shell which app a player belongs to (and
     * its volume control), so there is nothing to scale. Tried on Android 8, 10, 11, 13, 14 and 16.
     */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= 33;
    }

    /** The volumes the user lowered, by uid, in percent. An app at 100 has no entry. */
    static Map<Integer, Integer> rules(SharedPreferences p) {
        return parse(p.getString(PREF, ""));
    }

    static Map<Integer, Integer> parse(String saved) {
        Map<Integer, Integer> map = new TreeMap<>();
        for (String pair : saved.split(",")) {
            String[] part = pair.split(":");
            try {
                if (part.length == 2) map.put(Integer.parseInt(part[0]), Math.max(0, Math.min(100, Integer.parseInt(part[1]))));
            } catch (NumberFormatException e) {
                // skip a damaged entry
            }
        }
        return map;
    }

    static String format(Map<Integer, Integer> rules) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : rules.entrySet()) {
            out.append(out.length() > 0 ? "," : "").append(e.getKey()).append(':').append(e.getValue());
        }
        return out.toString();
    }

    static void save(SharedPreferences p, Map<Integer, Integer> rules) {
        p.edit().putString(PREF, format(rules)).apply();
    }

    static boolean lowered(Map<Integer, Integer> rules) {
        for (int percent : rules.values()) if (percent < 100) return true;
        return false;
    }

    /** What the helper reports: whether it runs, the uids of the apps playing right now, and the open apps. */
    static final class State {
        boolean alive;
        Set<Integer> playing = new HashSet<>();
        /** Uids of the apps in the recent-apps list, the one in front first. */
        List<Integer> open = new ArrayList<>();
    }

    /**
     * Also touches the volumes file: the helper ends a little after nothing has looked at it. The open
     * apps come from Android's recent-apps list ("A=uid:package" on each task), work profile included.
     */
    static State poll() {
        State state = new State();
        Shell.Result r = Shell.run("[ -e " + FILE + " ] && touch " + FILE + "; echo $(( $(date +%s) - $(stat -c %Y "
                + FILE + ".now 2>/dev/null || echo 0) )); cat " + FILE + ".now 2>/dev/null; echo @@; "
                + "dumpsys activity recents | grep -o ' A=[0-9]*:'");
        String[] lines = r.out.split("\n");
        try {
            state.alive = r.ok() && Integer.parseInt(lines[0].trim()) <= 3;
            boolean recents = false;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.equals("@@")) recents = true;
                else if (recents && line.startsWith("A=")) {
                    Integer uid = Integer.valueOf(line.substring(2, line.length() - 1));
                    if (!state.open.contains(uid)) state.open.add(uid);
                } else if (!recents && !line.isEmpty()) state.playing.add(Integer.parseInt(line));
            }
        } catch (NumberFormatException e) {
            state.alive = false;
        }
        return state;
    }

    /**
     * Hands the helper the volumes, starting it when it isn't running. Returns null when it runs,
     * else why not. Call off the main thread.
     */
    static String apply(Context c, SharedPreferences p) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : rules(p).entrySet()) {
            text.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        if (!Shell.run("cat > " + FILE, new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.UTF_8))).ok()) {
            return "TinyKit couldn't save the volumes. Is Shizuku running?";
        }
        if (poll().alive) return null;
        Shell.run("rm -f " + FILE + ".now " + FILE + ".log; (CLASSPATH=" + c.getPackageCodePath()
                + " nohup app_process -Xmx8m -Xms1m -Xusejit:false /system/bin io.github.order52.tinykit.Mixer run " + FILE + " >" + FILE
                + ".log 2>&1 &)");
        for (int i = 0; i < 15; i++) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (poll().alive) return null;
        }
        String why = Shell.run("cat " + FILE + ".log 2>/dev/null").out.trim();
        return why.isEmpty() ? "The mixer didn't start." : why;
    }
}
