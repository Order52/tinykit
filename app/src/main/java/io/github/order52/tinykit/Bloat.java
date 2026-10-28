package io.github.order52.tinykit;

import java.util.HashMap;
import java.util.Map;

/**
 * What TinyKit knows about system apps, so the debloater can say whether removing one is safe,
 * like Canta does. A short built-in list (exact package names, or a name ending in "*" for a whole
 * family); anything not listed gets a cautious default for its kind.
 */
final class Bloat {
    static final int SAFE = 0, ADVANCED = 1, EXPERT = 2, UNSAFE = 3;
    static final String[] LEVELS = {"Recommended", "Advanced", "Expert", "Unsafe"};
    static final String[] MEANING = {
            "Safe to remove. Nothing else depends on it.",
            "Removing it can switch off a feature you may use. Read the description first.",
            "Other things depend on it. Only remove it if you know what it does.",
            "Android needs this app. Removing it can stop the phone from starting."};

    static final class Info {
        final int level;
        final String note;

        Info(int level, String note) {
            this.level = level;
            this.note = note;
        }
    }

    // level (0-3), then the description. Exact names first, "*" entries match a whole prefix.
    private static final String[] LIST = {
            // Android itself
            "android|3|Android system",
            "com.android.systemui|3|Status bar, quick panel and lock screen",
            "com.android.settings|3|Settings",
            "com.android.shell|3|The shell Shizuku and adb use",
            "com.android.phone|3|Calls and mobile network",
            "com.android.server.telecom|3|Call manager",
            "com.android.providers.*|3|Stores contacts, media, settings and downloads",
            "com.android.permissioncontroller|3|Permission screens",
            "com.google.android.permissioncontroller|3|Permission screens",
            "com.android.packageinstaller|3|Installs and removes apps",
            "com.google.android.packageinstaller|3|Installs and removes apps",
            "com.android.bluetooth|3|Bluetooth",
            "com.android.keychain|3|Certificates and keys",
            "com.android.externalstorage|3|Access to storage",
            "com.android.inputdevices|3|Keyboards and input devices",
            "com.android.location.fused|3|Location",
            "com.android.networkstack*|3|Network connection checks",
            "com.android.cellbroadcastreceiver*|2|Emergency alerts",
            "com.android.se|3|Secure element, used for payments",
            "com.android.vpndialogs|3|VPN permission prompt",
            "com.android.wifi.resources|3|Wi-Fi",
            "com.google.android.gsf|3|Google services framework",
            "moe.shizuku.privileged.api|3|Shizuku, which TinyKit needs",
            "com.google.android.webview|3|Shows web pages inside apps",
            "com.android.webview|3|Shows web pages inside apps",
            "com.google.android.trichromelibrary*|3|Needed by Chrome and WebView",
            "com.android.nfc|1|NFC, tap to pay and tags",
            "com.android.mms.service|2|Sending and receiving MMS",
            "com.android.carrierconfig|2|Carrier settings",
            "com.android.managedprovisioning|2|Work profiles and device setup",
            "com.android.documentsui|2|File picker used by every app",
            "com.android.statementservice|2|Verifies app links",
            "com.android.proxyhandler|2|Proxy settings",
            "com.android.captiveportallogin|2|Wi-Fi sign-in pages",
            "com.android.smspush|2|WAP push messages",
            "com.android.ons|2|Mobile network switching",
            "com.android.mtp|2|Transferring files to a computer",
            "com.google.android.setupwizard|2|First-time phone setup",
            "com.google.android.gms|2|Google Play services: sign-in, push notifications, maps, location",
            "com.android.vending|2|Google Play Store",
            "com.google.android.ext.services|1|Android extras: suggestions and sharing",
            // Plain Android apps
            "com.android.egg|0|Hidden Android game",
            "com.android.dreams.*|0|Screen savers",
            "com.android.bookmarkprovider|0|Old browser bookmarks",
            "com.android.protips|0|Tips",
            "com.android.htmlviewer|0|Opens saved web pages",
            "com.android.wallpaper.livepicker|0|Live wallpaper picker",
            "com.android.quicksearchbox|0|Old search box",
            "com.android.email|0|Email app",
            "com.android.dynsystem|0|Dynamic system updates",
            "com.android.traceur|0|System tracing for developers",
            "com.android.stk|1|SIM card menu from your carrier",
            "com.android.printspooler|1|Printing",
            "com.android.bips|1|Printing",
            "com.android.musicfx|1|Sound effects",
            "com.android.calendar|1|Calendar",
            "com.android.deskclock|1|Clock and alarms",
            "com.android.calculator2|1|Calculator",
            "com.android.camera2|1|Camera",
            "com.android.gallery3d|1|Gallery",
            "com.android.exchange|1|Exchange email accounts",
            "com.android.emergency|1|Emergency information",
            "com.android.storagemanager|1|Storage clean-up",
            "com.android.wallpaperbackup|1|Backs up your wallpaper",
            "com.android.companiondevicemanager|1|Pairing with watches and accessories",
            "com.android.hotspot2.osulogin|1|Wi-Fi hotspot sign-up",
            "com.android.carrierdefaultapp|1|Carrier setup",
            "com.android.simappdialog|1|SIM setup prompts",
            "com.android.localtransport|1|Backup",
            "com.android.backupconfirm|1|Backup",
            "com.android.sharedstoragebackup|1|Backup",
            "com.android.apps.tag|1|NFC tag reader",
            "com.android.pacprocessor|1|Proxy auto-config",
            "com.android.healthconnect.controller|1|Health Connect",
            "com.android.hotwordenrollment.*|1|Voice match for \"Hey Google\"",
            // Google apps
            "com.facebook.system|0|Facebook, installed by the maker",
            "com.facebook.appmanager|0|Facebook, installed by the maker",
            "com.facebook.services|0|Facebook, installed by the maker",
            "com.google.android.feedback|0|Sends feedback to Google",
            "com.google.android.printservice.recommendation|0|Suggests print plug-ins",
            "com.google.android.apps.work.oobconfig|0|Work setup",
            "com.google.android.projection.gearhead|0|Android Auto",
            "com.google.android.apps.wellbeing|0|Digital Wellbeing",
            "com.google.android.videos|0|Google TV",
            "com.google.android.music|0|Google Play Music",
            "com.google.android.apps.youtube.music|0|YouTube Music",
            "com.google.android.youtube|0|YouTube",
            "com.google.android.apps.tachyon|0|Google Meet",
            "com.google.android.apps.magazines|0|Google News",
            "com.google.android.apps.podcasts|0|Google Podcasts",
            "com.google.android.apps.books|0|Google Play Books",
            "com.google.android.apps.docs|0|Google Drive",
            "com.google.android.apps.docs.editors.*|0|Google Docs, Sheets and Slides",
            "com.google.android.apps.photos|0|Google Photos",
            "com.google.android.apps.maps|0|Google Maps",
            "com.google.android.gm|0|Gmail",
            "com.google.android.keep|0|Google Keep",
            "com.google.android.apps.subscriptions.red|0|Google One",
            "com.google.android.apps.googleassistant|1|Google Assistant",
            "com.google.android.googlequicksearchbox|1|Google app and Assistant",
            "com.google.android.apps.nbu.files|1|Files by Google",
            "com.google.android.calendar|1|Google Calendar",
            "com.google.android.contacts|1|Contacts",
            "com.google.android.deskclock|1|Clock and alarms",
            "com.google.android.apps.walletnfcrel|1|Google Wallet and tap to pay",
            "com.google.android.partnersetup|1|Setup for the maker's apps",
            "com.google.android.onetimeinitializer|1|One-time setup after an update",
            "com.google.android.syncadapters.*|1|Syncs contacts and calendar",
            "com.google.android.tts|1|Speech for TalkBack and reading aloud",
            "com.google.android.marvin.talkback|1|TalkBack screen reader",
            "com.google.android.apps.turbo|1|Device Health Services: battery tips",
            "com.google.android.as|1|Device Personalization Services",
            "com.google.ar.core|1|Augmented reality support",
            // Other makers' extras
            "com.amazon.*|0|Amazon app, installed by the maker",
            "com.netflix.partner.activation|0|Netflix setup",
            "com.microsoft.appmanager|0|Link to Windows",
            "com.microsoft.office.*|0|Microsoft Office",
            "com.microsoft.skydrive|0|OneDrive",
            "com.linkedin.android|0|LinkedIn",
            "com.spotify.music|0|Spotify",
            // Samsung
            "com.samsung.android.bixby.agent|0|Bixby",
            "com.samsung.android.bixby.service|0|Bixby",
            "com.samsung.android.bixbyvision.framework|0|Bixby Vision",
            "com.samsung.android.visionintelligence|0|Bixby Vision",
            "com.samsung.android.app.spage|0|Samsung Free",
            "com.samsung.android.arzone|0|AR Zone",
            "com.samsung.android.aremoji|0|AR Emoji",
            "com.samsung.android.stickercenter|0|AR Emoji stickers",
            "com.samsung.android.app.tips|0|Samsung tips",
            "com.samsung.android.kidsinstaller|0|Samsung Kids",
            "com.samsung.android.app.watchmanagerstub|0|Galaxy Watch setup",
            "com.samsung.android.rubin.app|0|Customization Service",
            "com.samsung.android.email.provider|0|Samsung Email",
            "com.sec.android.app.sbrowser|0|Samsung Internet",
            "com.samsung.android.game.gamehome|1|Game Launcher",
            "com.samsung.android.game.gametools|1|Game Tools",
            "com.samsung.android.app.routines|1|Modes and Routines",
            "com.samsung.android.app.notes|1|Samsung Notes",
            "com.samsung.android.calendar|1|Samsung Calendar",
            "com.sec.android.app.samsungapps|1|Galaxy Store",
            "com.samsung.android.themestore|1|Galaxy Themes",
            "com.sec.android.app.shealth|1|Samsung Health",
            "com.samsung.android.spay*|1|Samsung Pay",
            "com.samsung.android.app.galaxyfinder|1|Finder search",
            "com.samsung.android.fmm|1|Find My Mobile",
            "com.samsung.android.lool|1|Device care",
            "com.sec.android.easyMover|1|Smart Switch",
            "com.samsung.android.knox.*|2|Samsung Knox security",
            // Xiaomi
            "com.miui.analytics|0|Sends usage data",
            "com.miui.msa.global|0|Ads in system apps",
            "com.miui.systemAdSolution|0|Ads in system apps",
            "com.miui.bugreport|0|Bug reports",
            "com.miui.hybrid|0|Quick apps",
            "com.miui.miservice|0|Services and feedback",
            "com.miui.android.fashiongallery|0|Wallpaper carousel",
            "com.xiaomi.mipicks|0|GetApps store",
            "com.mi.globalbrowser|0|Mi Browser",
            "com.xiaomi.gamecenter.sdk.service|0|Game services",
            "com.miui.videoplayer|1|Mi Video",
            "com.miui.player|1|Mi Music",
            "com.miui.yellowpage|1|Caller ID and yellow pages",
            "com.miui.weather2|1|Weather",
            "com.miui.cleaner|1|Cleaner",
            "com.miui.securitycenter|2|Security app",
            "com.xiaomi.xmsf|2|Xiaomi push services",
            "com.miui.home|3|Home screen",
            "com.miui.system|3|MIUI framework",
            "com.sec.android.app.launcher|3|One UI Home",
            "com.huawei.android.launcher|3|Home screen",
            "com.android.launcher3|2|Home screen",
            "com.google.android.apps.nexuslauncher|2|Pixel Launcher",
    };

    private static final Map<String, Info> EXACT = new HashMap<>();
    private static final Map<String, Info> PREFIX = new HashMap<>();

    static {
        for (String line : LIST) {
            String[] f = line.split("\\|", 3);
            Info info = new Info(Integer.parseInt(f[1]), f[2]);
            if (f[0].endsWith("*")) PREFIX.put(f[0].substring(0, f[0].length() - 1), info);
            else EXACT.put(f[0], info);
        }
    }

    private Bloat() {
    }

    /** What to show for a package. {@code system} is false for apps the user installed. */
    static Info info(String pkg, boolean system) {
        Info exact = EXACT.get(pkg);
        if (exact != null) return exact;
        Info best = null;
        int length = -1;
        for (Map.Entry<String, Info> e : PREFIX.entrySet()) {
            if (pkg.startsWith(e.getKey()) && e.getKey().length() > length) {
                best = e.getValue();
                length = e.getKey().length();
            }
        }
        if (best != null) return best;
        if (!system) return new Info(SAFE, "An app you installed");
        if (pkg.startsWith("com.android.") || pkg.startsWith("android.")) {
            return new Info(EXPERT, "Part of Android, not in TinyKit's list");
        }
        return new Info(ADVANCED, "A system app not in TinyKit's list");
    }
}
