import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing comes from keystore.properties (not committed). Without it the
// release APK is signed with the local debug key so it still installs.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.order52.tinykit"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.order52.tinykit"
        minSdk = 24
        targetSdk = 34
        versionCode = 18
        versionName = "1.13.0"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Lint runs in CI and fails on errors. These are on purpose: the UI is English only and built
    // in code, and TinyKit reaches hidden system APIs by reflection and /sdcard paths as the shell.
    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += listOf("SetTextI18n", "SdCardPath", "PrivateApi", "DiscouragedApi", "HardwareIds",
            "DefaultLocale", "ClickableViewAccessibility", "WebViewApiAvailability", "DataExtractionRules",
            "OldTargetApi", "ObsoleteSdkInt", "UnusedAttribute", "InlinedApi")
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
