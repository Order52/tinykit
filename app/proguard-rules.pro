# Shell runs commands through Shizuku.newProcess, which is private and called by reflection.
-keepclassmembers class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

# Started by name with app_process (as the shell user) to read saved Wi-Fi networks.
-keep class io.github.order52.tinykit.WifiDump {
    public static void main(java.lang.String[]);
}

# Started by name with app_process (as the shell user) to toggle all-sensors privacy.
-keep class io.github.order52.tinykit.Sensors { public static void main(java.lang.String[]); }
