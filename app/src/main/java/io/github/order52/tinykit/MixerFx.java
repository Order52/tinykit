package io.github.order52.tinykit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.os.Build;
import android.os.IBinder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mixer's volume sliders on Android 12 and older, where the adb user can't scale an app's players: it
 * attaches an audio effect (a gain, from DynamicsProcessing; an equaliser pulled down on Android 8) to the audio
 * session of each lowered app's players. Android lets any app put an effect on any session. The effects live in this
 * process, so this runs, with a notification, while any app is lowered, and ends by itself afterwards.
 */
public final class MixerFx extends Service {
    private static final int NOTIFICATION = 9;

    private volatile boolean running;
    private final Map<Integer, AudioEffect> effects = new HashMap<>(); // by audio session
    private final Map<Integer, Float> gains = new HashMap<>();

    /** Whether this phone has an effect that can turn a session down. Asked once. */
    static boolean works(Context c) {
        AudioTrack track = null;
        AudioEffect effect = null;
        try {
            track = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
                    new AudioFormat.Builder().setSampleRate(8000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(), 2048, AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            effect = make(track.getAudioSessionId());
            return effect != null;
        } catch (RuntimeException e) {
            return false;
        } finally {
            if (effect != null) effect.release();
            if (track != null) track.release();
        }
    }

    private static AudioEffect make(int session) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                DynamicsProcessing dp = new DynamicsProcessing(0, session, null);
                dp.setEnabled(true);
                return dp;
            }
            Equalizer eq = new Equalizer(0, session);
            eq.setEnabled(true);
            return eq;
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    /** Sets the effect's attenuation: a gain of {@code db} (0 or below). */
    private static void set(AudioEffect effect, float db) {
        if (effect instanceof DynamicsProcessing) {
            ((DynamicsProcessing) effect).setInputGainAllChannelsTo(db);
        } else if (effect instanceof Equalizer) { // up to its own limit, about 15 dB
            Equalizer eq = (Equalizer) effect;
            short low = eq.getBandLevelRange()[0];
            for (short band = 0; band < eq.getNumberOfBands(); band++) eq.setBandLevel(band, (short) Math.max(low, db * 100));
        }
    }

    static void begin(Context c) {
        Intent start = new Intent(c, MixerFx.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(start); else c.startService(start);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION, n);
        if (!running) {
            running = true;
            new Thread(this::loop, "mixer-fx").start();
        }
        return START_NOT_STICKY;
    }

    private void loop() {
        SharedPreferences prefs = getSharedPreferences("tinykit", MODE_PRIVATE);
        long idleSince = System.currentTimeMillis();
        while (running) {
            Map<Integer, Integer> rules = Mixer.rules(prefs);
            if (Mixer.lowered(rules)) idleSince = System.currentTimeMillis();
            else if (System.currentTimeMillis() - idleSince > 20_000) break; // nothing lowered for a while: done
            if (Shell.ready()) {
                try {
                    apply(rules);
                } catch (RuntimeException e) {
                    android.util.Log.w("TinyKit", "mixer fx: " + e);
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        for (AudioEffect e : effects.values()) e.release();
        effects.clear();
        running = false;
        stopForeground(true);
        stopSelf();
    }

    /** Which app a player belongs to (the audio service's log), and the session of each track (the mixer's table). */
    private static final Pattern OWNER = Pattern.compile("new player piid:\\d+ uid/pid:(\\d+)/(\\d+)");
    private static final Pattern TRACK = Pattern.compile("^\\s+[A-Z]\\s+\\d+\\s+(?:yes|no)\\s+(\\d+)\\s+(\\d+)\\s");

    /** Puts a gain on the audio sessions of lowered apps, and takes it off those of apps that are done or back at 100. */
    private void apply(Map<Integer, Integer> rules) {
        String[] parts = Shell.run("dumpsys audio | grep 'new player'; echo @@; dumpsys media.audio_flinger | grep -E '^ +[A-Z] +[0-9]+ +(yes|no) '")
                .out.split("@@", 2);
        Map<Integer, Integer> uidOfPid = new HashMap<>();
        for (String line : parts[0].split("\n")) {
            Matcher m = OWNER.matcher(line);
            if (m.find()) uidOfPid.put(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        }
        Map<Integer, int[]> alive = new HashMap<>(); // session -> {uid, session}
        if (parts.length > 1) {
            for (String line : parts[1].split("\n")) {
                Matcher m = TRACK.matcher(line);
                if (!m.find()) continue;
                Integer uid = uidOfPid.get(Integer.parseInt(m.group(1)));
                int session = Integer.parseInt(m.group(2));
                if (uid != null) alive.put(session, new int[]{uid, session});
            }
        }
        Map<Integer, Float> want = new HashMap<>(); // session -> dB
        for (int[] player : alive.values()) {
            Integer percent = rules.get(player[0]);
            if (percent == null || percent >= 100 || player[1] <= 0) continue;
            want.put(player[1], percent == 0 ? -80f : (float) (20 * Math.log10(percent / 100.0)));
        }
        Set<Integer> gone = new HashSet<>(effects.keySet());
        gone.removeAll(want.keySet());
        for (int session : gone) { // the app's player ended, or its volume is back at 100
            AudioEffect e = effects.remove(session);
            gains.remove(session);
            if (e != null) e.release();
        }
        for (Map.Entry<Integer, Float> e : want.entrySet()) {
            AudioEffect effect = effects.get(e.getKey());
            if (effect == null) {
                effect = make(e.getKey());
                if (effect == null) continue;
                effects.put(e.getKey(), effect);
            }
            Float had = gains.get(e.getKey());
            if (had == null || !had.equals(e.getValue())) {
                set(effect, e.getValue());
                gains.put(e.getKey(), e.getValue());
            }
        }
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("mixer", Tr.t("Audio mixer"), NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, "mixer");
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        PendingIntent tap = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_MIXER, true),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(R.drawable.ic_nav_system).setContentTitle(Tr.t("Audio mixer"))
                .setContentText(Tr.t("Keeping apps at the volume you set")).setContentIntent(tap).setOngoing(true)
                .setShowWhen(false).build();
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
