package io.github.order52.tinykit;

import android.os.IBinder;

import java.lang.reflect.Method;

/**
 * Blocks or allows apps' internet through Android's own per-app firewall, the kernel rule Data
 * Saver and Battery Saver use. TinyKit starts it with app_process as the shell user via Shizuku,
 * sets the rules and exits. Nothing runs afterwards: the kernel enforces them.
 *
 * Args: block UID... | allow UID... | query UID... | off | sync BLOCK_UID... -- ALLOW_UID...
 * (query prints the blocked UIDs; sync does both changes in this one run, which is what keeps
 * switching modes or restoring after a restart to a single start-up of this program)
 */
public final class Firewall {
    // FIREWALL_CHAIN_OEM_DENY_3 (Android 14+): a deny list for apps like this one. The system's own
    // chains (Doze, Data Saver, Battery Saver) are left alone.
    private static final int CHAIN = 9;
    private static final int RULE_DENY = 2;
    private static final int RULE_DEFAULT = 0;

    public static void main(String[] args) {
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "connectivity");
            Object cm = Class.forName("android.net.IConnectivityManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            String cmd = args.length > 0 ? args[0] : "";
            if (cmd.equals("query")) {
                Method get = cm.getClass().getMethod("getUidFirewallRule", int.class, int.class);
                StringBuilder blocked = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    if ((Integer) get.invoke(cm, CHAIN, Integer.parseInt(args[i])) == RULE_DENY) {
                        blocked.append(args[i]).append(' ');
                    }
                }
                System.out.println(blocked.toString().trim());
                return;
            }
            if (cmd.equals("off")) {
                // Switches TinyKit's chain off again; the rules were already cleared.
                cm.getClass().getMethod("setFirewallChainEnabled", int.class, boolean.class).invoke(cm, CHAIN, false);
                System.out.println("OK");
                return;
            }
            if (!cmd.equals("block") && !cmd.equals("allow") && !cmd.equals("sync")) {
                throw new IllegalArgumentException("unknown command " + cmd);
            }
            // Switching the chain on is harmless to repeat.
            cm.getClass().getMethod("setFirewallChainEnabled", int.class, boolean.class).invoke(cm, CHAIN, true);
            Method set = cm.getClass().getMethod("setUidFirewallRule", int.class, int.class, int.class);
            Method get = cm.getClass().getMethod("getUidFirewallRule", int.class, int.class);
            int rule = cmd.equals("allow") ? RULE_DEFAULT : RULE_DENY;
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--")) {
                    rule = RULE_DEFAULT;
                    continue;
                }
                int uid = Integer.parseInt(args[i]);
                // Android refuses to clear an app that never had a rule, so only touch the ones that do.
                if (rule == RULE_DEFAULT && (Integer) get.invoke(cm, CHAIN, uid) != RULE_DENY) continue;
                set.invoke(cm, CHAIN, uid, rule);
            }
            System.out.println("OK");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.out.println("This Android doesn't support blocking apps: " + cause);
            System.exit(1);
        }
    }
}
