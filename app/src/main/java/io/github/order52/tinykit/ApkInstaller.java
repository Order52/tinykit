package io.github.order52.tinykit;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs .apk files, and apps split across several APKs (.apks, .xapk, .apkm, or several .apk
 * files picked together) with Android's package installer, through Shizuku. Every file is streamed
 * straight into the install session, so nothing is unpacked or copied first when the file can be
 * opened in place. Call off the main thread.
 *
 * It installs only the parts this phone needs: the base, feature splits, and the splits for its
 * processor, screen density and languages. XAPK game data (.obb) is copied to Android/obb.
 */
final class ApkInstaller {
    interface Progress {
        void update(String message);
    }

    private interface Source {
        InputStream open() throws IOException;
    }

    private static final class Part {
        final String name;
        final long size;
        final Source source;

        Part(String name, long size, Source source) {
            this.name = name;
            this.size = size;
            this.source = source;
        }
    }

    private static final Pattern SESSION = Pattern.compile("\\[(\\d+)]");
    private static final Pattern FAILURE = Pattern.compile("INSTALL_[A-Z_]+");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(_r[A-Z]{2})?");
    private static final Set<String> ABIS = new HashSet<>(Arrays.asList(
            "arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips", "mips64", "riscv64"));
    private static final Set<String> DENSITIES = new HashSet<>(Arrays.asList(
            "ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "tvdpi", "nodpi"));

    private ApkInstaller() {
    }

    /** Installs the picked files as one app and returns the message to show. */
    static String install(Context context, List<Uri> uris, Progress progress) {
        ContentResolver resolver = context.getContentResolver();
        List<Closeable> open = new ArrayList<>();
        List<File> temp = new ArrayList<>();
        try {
            progress.update("Reading files…");
            List<Part> parts = new ArrayList<>();
            List<Part> obbs = new ArrayList<>();
            for (Uri uri : uris) {
                String error = collect(context, resolver, uri, parts, obbs, open, temp);
                if (error != null) return error;
            }
            if (parts.isEmpty()) return "No app was found in the picked files.";
            parts = needed(parts);

            long total = 0;
            for (Part p : parts) total += p.size;
            Shell.Result create = Shell.run("pm install-create -r"
                    + (Build.VERSION.SDK_INT >= 34 ? " --bypass-low-target-sdk-block" : "") + " -S " + total);
            Matcher session = SESSION.matcher(create.out);
            if (!create.ok() || !session.find()) return explain(create.out);
            String id = session.group(1);

            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(i);
                progress.update(parts.size() == 1 ? "Installing…"
                        : "Writing part " + (i + 1) + " of " + parts.size() + "…");
                Shell.Result write;
                try (InputStream in = part.source.open()) {
                    write = Shell.run("pm install-write -S " + part.size + " " + id + " "
                            + Shell.quote("p" + i + ".apk") + " -", in);
                } catch (IOException e) {
                    write = new Shell.Result(-1, "Couldn't read " + part.name + ": " + e.getMessage());
                }
                if (!write.ok()) {
                    Shell.run("pm install-abandon " + id);
                    return explain(write.out);
                }
            }
            progress.update("Finishing…");
            Shell.Result commit = Shell.run("pm install-commit " + id);
            if (!commit.ok() || !commit.out.contains("Success")) {
                Shell.run("pm install-abandon " + id);
                return explain(commit.out);
            }

            String message = parts.size() == 1 ? "Installed." : "Installed (" + parts.size() + " parts).";
            for (Part obb : obbs) {
                progress.update("Copying game data…");
                String path = "/sdcard/" + obb.name;
                String dir = path.substring(0, path.lastIndexOf('/'));
                Shell.Result copy;
                try (InputStream in = obb.source.open()) {
                    copy = Shell.run("mkdir -p " + Shell.quote(dir) + " && cat > " + Shell.quote(path), in);
                } catch (IOException e) {
                    copy = new Shell.Result(-1, String.valueOf(e.getMessage()));
                }
                if (!copy.ok()) return message + " Game data couldn't be copied: " + copy.out;
            }
            return obbs.isEmpty() ? message : message + " Game data copied.";
        } catch (RuntimeException e) {
            return "Failed: " + e.getMessage();
        } finally {
            for (Closeable c : open) {
                try {
                    c.close();
                } catch (IOException ignored) {
                }
            }
            for (File f : temp) f.delete();
        }
    }

    /** Adds what {@code uri} contains to {@code parts} (and {@code obbs}). Returns an error, or null. */
    private static String collect(Context context, ContentResolver resolver, Uri uri, List<Part> parts,
            List<Part> obbs, List<Closeable> open, List<File> temp) {
        String name = displayName(resolver, uri);
        ZipFile zip = zip(context, resolver, uri, open, temp);
        if (zip == null) return name + " isn't an app file.";
        if (zip.getEntry("AndroidManifest.xml") != null) {
            long size = size(resolver, uri);
            if (size < 0) return "Couldn't read the size of " + name + ".";
            parts.add(new Part(name, size, () -> resolver.openInputStream(uri)));
            return null;
        }
        List<ZipEntry> apks = new ArrayList<>();
        boolean bundle = false;
        for (ZipEntry e : Collections.list(zip.entries())) {
            String n = e.getName();
            if (e.isDirectory()) continue;
            if (n.startsWith("Android/obb/")) {
                obbs.add(new Part(n, e.getSize(), () -> zip.getInputStream(e)));
            } else if (n.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                apks.add(e);
                bundle |= n.startsWith("splits/");
            }
        }
        // A bundletool .apks holds splits/ plus ready-made standalone and instant APKs: only
        // splits/ is one app. Other archives (.xapk, .apkm) keep everything in the root.
        List<ZipEntry> chosen = new ArrayList<>();
        for (ZipEntry e : apks) {
            String n = e.getName();
            if (bundle ? n.startsWith("splits/") : !n.startsWith("standalones/") && !n.startsWith("instant/")) {
                chosen.add(e);
            }
        }
        if (chosen.isEmpty()) chosen = apks;
        for (ZipEntry e : chosen) {
            parts.add(new Part(e.getName(), e.getSize(), () -> zip.getInputStream(e)));
        }
        return chosen.isEmpty() ? name + " has no app inside." : null;
    }

    /**
     * Opens the file as a zip in place through its file descriptor, so big files aren't copied.
     * Falls back to a copy in the cache when it can't be opened that way (e.g. a cloud file).
     */
    private static ZipFile zip(Context context, ContentResolver resolver, Uri uri, List<Closeable> open,
            List<File> temp) {
        try (ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "r")) {
            ZipFile zip = new ZipFile("/proc/self/fd/" + fd.getFd());
            open.add(zip);
            return zip;
        } catch (IOException | RuntimeException ignored) {
        }
        try {
            File file = File.createTempFile("install", ".zip", context.getCacheDir());
            temp.add(file);
            try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
                byte[] chunk = new byte[64 * 1024];
                int n;
                while ((n = in.read(chunk)) != -1) out.write(chunk, 0, n);
            }
            ZipFile zip = new ZipFile(file);
            open.add(zip);
            return zip;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Drops splits for other processors, screens and languages; everything else stays. */
    private static List<Part> needed(List<Part> parts) {
        Set<String> abis = new HashSet<>();
        for (String abi : Build.SUPPORTED_ABIS) abis.add(abi.replace('-', '_'));
        Set<String> languages = new HashSet<>(Collections.singletonList("en"));
        LocaleList locales = LocaleList.getDefault();
        for (int i = 0; i < locales.size(); i++) {
            String lang = locales.get(i).getLanguage();
            languages.add(lang);
            // Android still reports a few languages by their old codes; bundles use the new ones.
            if (lang.equals("in")) languages.add("id");
            if (lang.equals("iw")) languages.add("he");
            if (lang.equals("ji")) languages.add("yi");
        }
        String density = density();

        boolean abiMatch = false;
        boolean densityMatch = false;
        for (Part p : parts) {
            String token = config(p.name);
            abiMatch |= token != null && abis.contains(token);
            densityMatch |= density.equals(token);
        }
        List<Part> keep = new ArrayList<>();
        for (Part p : parts) {
            String token = config(p.name);
            if (token == null) {
                keep.add(p);
            } else if (ABIS.contains(token)) {
                // If no processor matches (a mislabelled file), keep them all and let Android decide.
                if (!abiMatch || abis.contains(token)) keep.add(p);
            } else if (DENSITIES.contains(token)) {
                if (!densityMatch || density.equals(token) || token.equals("nodpi")) keep.add(p);
            } else if (LANGUAGE.matcher(token).matches()) {
                if (languages.contains(token.replaceAll("_r[A-Z]{2}$", ""))) keep.add(p);
            } else {
                keep.add(p);
            }
        }
        return keep;
    }

    /** "config.arm64_v8a" in "split_config.arm64_v8a.apk" or "splits/config.xxhdpi.apk"; else null. */
    private static String config(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        int at = file.indexOf("config.");
        if (at < 0 || !file.toLowerCase(Locale.ROOT).endsWith(".apk")) return null;
        return file.substring(at + 7, file.length() - 4);
    }

    private static String density() {
        int dpi = android.content.res.Resources.getSystem().getDisplayMetrics().densityDpi;
        if (dpi <= DisplayMetrics.DENSITY_LOW) return "ldpi";
        if (dpi <= DisplayMetrics.DENSITY_MEDIUM) return "mdpi";
        if (dpi <= DisplayMetrics.DENSITY_TV) return "tvdpi";
        if (dpi <= DisplayMetrics.DENSITY_HIGH) return "hdpi";
        if (dpi <= DisplayMetrics.DENSITY_XHIGH) return "xhdpi";
        if (dpi <= DisplayMetrics.DENSITY_XXHIGH) return "xxhdpi";
        return "xxxhdpi";
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        return "That file";
    }

    private static long size(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
        }
        try (android.content.res.AssetFileDescriptor fd = resolver.openAssetFileDescriptor(uri, "r")) {
            return fd == null ? -1 : fd.getLength();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** Turns pm's failure into something a person can act on. */
    private static String explain(String out) {
        Matcher m = FAILURE.matcher(out);
        String code = m.find() ? m.group() : "";
        switch (code) {
            case "INSTALL_FAILED_VERSION_DOWNGRADE":
                return "A newer version is already installed. Android won't install an older one over it. "
                        + "Uninstall the new one first (this deletes its data).";
            case "INSTALL_FAILED_UPDATE_INCOMPATIBLE":
            case "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE":
                return "The installed version was signed with a different key, so this can't update it. "
                        + "Uninstall the installed one first (this deletes its data).";
            case "INSTALL_FAILED_INSUFFICIENT_STORAGE":
                return "Not enough free storage.";
            case "INSTALL_FAILED_NO_MATCHING_ABIS":
                return "This app isn't built for this phone's processor.";
            case "INSTALL_FAILED_OLDER_SDK":
                return "This app needs a newer version of Android.";
            case "INSTALL_FAILED_MISSING_SPLIT":
                return "Parts of the app are missing. Pick every file of the app together.";
            case "INSTALL_FAILED_INVALID_APK":
            case "INSTALL_PARSE_FAILED_NOT_APK":
            case "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED":
                return "That isn't a valid app file.";
            case "INSTALL_FAILED_DUPLICATE_PACKAGE":
                return "Those files are for different apps. Pick one app's files at a time.";
            default:
                return out.isEmpty() ? "Install failed." : "Install failed: " + out;
        }
    }
}
