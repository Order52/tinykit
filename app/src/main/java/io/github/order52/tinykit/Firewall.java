package io.github.order52.tinykit;

import android.os.IBinder;

/**
 * Blocks or allows apps' internet through Android's own per-app firewall, the kernel rule Data
 * Saver and Battery Saver use. TinyKit starts it with app_process as the shell user via Shizuku,
 * sets the rules and exits. Nothing runs afterwards: the kernel enforces them.
 *
 * Args: block UID... | allow UID... | query UID...  (query prints the blocked UIDs)
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
                StringBuilder blocked = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    int rule = (Integer) cm.getClass().getMethod("getUidFirewallRule", int.class, int.class)
                            .invoke(cm, CHAIN, Integer.parseInt(args[i]));
                    if (rule == RULE_DENY) blocked.append(args[i]).append(' ');
                }
                System.out.println(blocked.toString().trim());
                return;
            }
            boolean block = cmd.equals("block");
            if (!block && !cmd.equals("allow")) throw new IllegalArgumentException("unknown command " + cmd);
            // Switching the chain on is harmless to repeat.
            cm.getClass().getMethod("setFirewallChainEnabled", int.class, boolean.class).invoke(cm, CHAIN, true);
            for (int i = 1; i < args.length; i++) {
                cm.getClass().getMethod("setUidFirewallRule", int.class, int.class, int.class)
                        .invoke(cm, CHAIN, Integer.parseInt(args[i]), block ? RULE_DENY : RULE_DEFAULT);
            }
            System.out.println("OK");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.out.println("This Android doesn't support blocking apps: " + cause);
            System.exit(1);
        }
    }
}
