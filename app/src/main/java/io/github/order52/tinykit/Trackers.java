package io.github.order52.tinykit;

/**
 * Names the ad, analytics and tracking libraries whose parts show up in an app's component list.
 * A component whose class starts with one of these prefixes belongs to that library.
 */
final class Trackers {
    private static final String[] LIST = {
            "com.google.firebase.analytics|Google Analytics",
            "com.google.android.gms.measurement|Google Analytics",
            "com.google.android.gms.analytics|Google Analytics",
            "com.google.android.gms.ads|Google Ads",
            "com.google.firebase.crashlytics|Crashlytics",
            "com.crashlytics|Crashlytics",
            "io.fabric|Fabric",
            "com.facebook.ads|Facebook Ads",
            "com.facebook.appevents|Facebook Analytics",
            "com.applovin|AppLovin",
            "com.unity3d.ads|Unity Ads",
            "com.unity3d.services|Unity Ads",
            "com.adjust.sdk|Adjust",
            "io.branch|Branch",
            "com.appsflyer|AppsFlyer",
            "com.onesignal|OneSignal",
            "com.flurry|Flurry",
            "com.mixpanel|Mixpanel",
            "com.amplitude|Amplitude",
            "com.segment|Segment",
            "com.chartboost|Chartboost",
            "com.vungle|Vungle",
            "com.ironsource|ironSource",
            "com.startapp|StartApp",
            "com.inmobi|InMobi",
            "com.mopub|MoPub",
            "com.bugsnag|Bugsnag",
            "io.sentry|Sentry",
            "com.microsoft.appcenter|App Center",
            "com.kochava|Kochava",
            "com.tapjoy|Tapjoy",
            "com.smaato|Smaato",
            "com.yandex.metrica|Yandex Metrica",
            "com.yandex.mobile.ads|Yandex Ads",
            "com.tencent.bugly|Bugly",
            "com.umeng|Umeng",
            "com.taboola|Taboola",
            "com.outbrain|Outbrain",
            "net.pubnative|PubNative",
            "com.bytedance.sdk|Pangle",
            "com.mbridge|Mintegral",
            "com.fyber|Fyber",
            "com.my.target|MyTarget",
            "com.singular|Singular",
            "com.clevertap|CleverTap",
            "com.moengage|MoEngage",
            "com.braze|Braze",
            "com.appboy|Braze",
            "com.leanplum|Leanplum",
            "com.urbanairship|Airship",
            "com.localytics|Localytics",
            "com.heapanalytics|Heap",
    };

    private Trackers() {
    }

    /** The library a component class belongs to, or null if it isn't a known tracker. */
    static String find(String className) {
        for (String entry : LIST) {
            int bar = entry.indexOf('|');
            if (className.startsWith(entry.substring(0, bar))) return entry.substring(bar + 1);
        }
        return null;
    }
}
