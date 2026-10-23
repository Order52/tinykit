package io.github.order52.tinykit;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/** Runs shell commands as the adb shell user through Shizuku. Call off the main thread. */
final class Shell {

    static final class Result {
        final int code;
        final String out;

        Result(int code, String out) {
            this.code = code;
            this.out = out;
        }

        boolean ok() {
            return code == 0;
        }
    }

    private static Method newProcess;

    private Shell() {
    }

    static boolean installed(PackageManager pm) {
        try {
            pm.getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean running() {
        return Shizuku.pingBinder() && !Shizuku.isPreV11();
    }

    static boolean ready() {
        return running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    /** Runs {@code command} with sh; stdout and stderr are merged. */
    static Result run(String command) {
        if (!ready()) return new Result(-1, "Shizuku is not available.");
        try {
            // Shizuku keeps newProcess private in favour of UserService, which is much heavier
            // for one-line shell commands.
            if (newProcess == null) {
                newProcess = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                newProcess.setAccessible(true);
            }
            Process process = (Process) newProcess.invoke(
                    null, new String[]{"sh", "-c", "exec 2>&1\n" + command}, null, null);
            process.getOutputStream().close();
            String out = read(process.getInputStream());
            int code = process.waitFor();
            return new Result(code, out.trim());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new Result(-1, String.valueOf(cause.getMessage()));
        }
    }

    /** Single-quotes a value for sh. */
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
        in.close();
        return buffer.toString("UTF-8");
    }
}
