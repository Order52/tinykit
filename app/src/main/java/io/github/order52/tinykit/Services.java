package io.github.order52.tinykit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The services that are running right now, from {@code dumpsys activity services}, and what can be done to one. */
final class Services {
    /** One running service. */
    static final class Service {
        int user;
        String pkg, cls;
        boolean foreground, started, bound;
        String age = "";

        String component() {
            return pkg + "/" + cls;
        }

        /** The class without its package prefix. */
        String shortName() {
            return cls.startsWith(pkg + ".") ? cls.substring(pkg.length()) : cls;
        }
    }

    private static final Pattern HEAD = Pattern.compile("ServiceRecord\\{\\w+ u(\\d+) ([^/\\s]+)/(\\S+)\\}");

    private Services() {
    }

    /** Services whose app is running, foreground ones first, then started ones, then the ones only bound to. Off the main thread. */
    static List<Service> running() {
        List<Service> list = new ArrayList<>();
        Service current = null;
        boolean alive = false;
        for (String line : Shell.run("dumpsys activity services").out.split("\n")) {
            if (line.startsWith("  * ServiceRecord{")) {
                if (current != null && alive) list.add(current);
                Matcher m = HEAD.matcher(line);
                current = null;
                alive = false;
                if (m.find()) {
                    current = new Service();
                    current.user = Integer.parseInt(m.group(1));
                    current.pkg = m.group(2);
                    current.cls = m.group(3);
                }
            } else if (current != null) {
                String t = line.trim();
                if (t.startsWith("app=ProcessRecord")) alive = true;
                else if (t.startsWith("isForeground=true")) current.foreground = true;
                else if (t.contains("startRequested=true")) current.started = true;
                else if (t.startsWith("ConnectionRecord{")) current.bound = true;
                else if (t.startsWith("createTime=")) {
                    String age = t.substring(11).split(" ")[0];
                    age = age.startsWith("-") ? age.substring(1) : age;
                    current.age = age.length() > 3 ? age.replaceAll("\\d+ms$", "") : age; // seconds are precise enough
                }
            }
        }
        if (current != null && alive) list.add(current);
        java.util.Collections.sort(list, (a, b) -> rank(a) != rank(b) ? rank(a) - rank(b) : a.pkg.compareTo(b.pkg));
        return list;
    }

    private static int rank(Service s) {
        return s.foreground ? 0 : s.started ? 1 : 2;
    }

    /** Asks the service to stop. It may start again if the app wants it: this is not a block. */
    static String stop(Service s) {
        String out = Shell.run("am stopservice --user " + s.user + " -n " + Shell.quote(s.component())).out;
        return out.contains("stopped") && !out.contains("not stopped") ? null
                : "That service didn't stop. It may be bound by another app.";
    }

    /** Switches the service off for good until it is switched on again (the app can't start it). */
    static boolean block(Service s, boolean block) {
        return Shell.run("pm " + (block ? "disable" : "enable") + " --user " + s.user + " " + Shell.quote(s.component())).ok();
    }
}
