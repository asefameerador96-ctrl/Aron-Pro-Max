// android:app-sr: the ARON SR app, applicationId com.aktcl.aron.sr (docs/23 s8, docs/24 s2.1).
// Lane: android-core owns the shell and build wiring; the SR app lane owns the feature content. Each field app
// embeds android:dpc and is its own device owner (docs/24 s10.1).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// FCM config comes from the GOOGLE_SERVICES_JSON secret at build time (written to this folder by CI, git-ignored).
// Without it the app builds and runs with push disabled (FCM only nudges pulls, docs/24 s4.7, s13.6).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// API origin (docs/24 s3.1): scheme and host only, no path; the contract paths start with /v1. Validated here so a wrong
// value fails the build instead of the field app. Plain HTTP is accepted only for loopback or the emulator host (debug).
val apiBaseUrl: String = ((findProperty("aron.apiBaseUrl") as String?) ?: "https://api.aron-dev.invalid").trim().trimEnd('/')
check(
    Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$").matches(apiBaseUrl) ||
        Regex("^http://(localhost|127\\.0\\.0\\.1|10\\.0\\.2\\.2)(:[0-9]{1,5})?$").matches(apiBaseUrl),
) { "aron.apiBaseUrl must be a bare origin such as https://api.example.com (docs/24 s3.1), got: $apiBaseUrl" }

// F-SYS-044: a test copy under a fourth package id (for example -Paron.applicationIdSuffix=.copy gives
// com.aktcl.aron.sr.copy) proves the apps share no authority, permission or file. Field builds never set it.
val testCopySuffix: String? = (findProperty("aron.applicationIdSuffix") as String?)?.trim()?.takeIf { it.isNotEmpty() }
check(testCopySuffix == null || Regex("^\\.[a-z][a-z0-9_]{0,20}$").matches(testCopySuffix)) {
    "aron.applicationIdSuffix must look like .copy, got: $testCopySuffix"
}

val playIntegrityProjectNumber: Long = ((findProperty("aron.playIntegrityProjectNumber") as String?)?.trim()?.ifEmpty { null } ?: "0").toLong()

// A release build never talks plain HTTP (docs/24 s5.8): fail it here instead of crashing the app at launch.
val apiBaseUrlIsHttps = apiBaseUrl.startsWith("https://")
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    // Copied into a local so the action captures a Boolean, not the build script (configuration cache).
    val https = apiBaseUrlIsHttps
    doFirst { check(https) { "release builds need an https aron.apiBaseUrl (docs/24 s5.8)" } }
}

android {
    namespace = "com.aktcl.aron.sr"
    defaultConfig {
        applicationId = "com.aktcl.aron.sr"
        versionCode = (findProperty("aron.versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("aron.versionName") as String?) ?: "0.1.0"
        buildConfigField("String", "ARON_ROLE", "\"SR\"")
        buildConfigField("String", "API_BASE_URL", "\"" + apiBaseUrl + "\"")
        // Play Integrity (docs/24 s8.7): the Google Cloud project number, not a secret; 0 means not configured and the
        // status report carries the not_configured marker (R12).
        buildConfigField("long", "PLAY_INTEGRITY_PROJECT_NUMBER", playIntegrityProjectNumber.toString() + "L")
        if (testCopySuffix != null) applicationIdSuffix = testCopySuffix
    }
    buildFeatures { compose = true }
    // Only the two app languages ship (docs/24 s5.6); drops library translations and keeps the APK lean (s5.7).
    androidResources { localeFilters += listOf("en", "bn") }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":android:core-common"))
    implementation(project(":android:core-ui"))
    implementation(project(":android:core-database"))
    implementation(project(":android:core-network"))
    implementation(project(":android:core-session"))
    implementation(project(":android:core-sync"))
    implementation(libs.androidx.work.runtime.ktx) // Configuration.Provider for the sync worker factory
    implementation(project(":android:core-geo"))
    implementation(project(":android:core-printing"))
    implementation(project(":android:core-system"))
    implementation(project(":android:core-media"))
    implementation(project(":android:dpc"))
    implementation(project(":android:feature-auth"))
    implementation(project(":android:feature-home"))
    implementation(project(":android:feature-attendance"))
    implementation(project(":android:feature-stock"))
    implementation(project(":android:feature-sale"))
    implementation(project(":android:feature-memo"))
    implementation(project(":android:feature-dayclose"))
    implementation(project(":android:feature-outlet"))
    implementation(project(":android:feature-tasks"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.maps.compose) // N-041: loaded only by OutletMapActivity, which starts only on tap
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
