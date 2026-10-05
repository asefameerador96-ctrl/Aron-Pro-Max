// android:app-sr: the ARON SR app, applicationId com.aktcl.aron.sr (docs/23 s8, docs/24 s2.1).
// Lane: android (SR app agent). Each field app embeds android:dpc and is its own device owner (docs/24 s10.1).
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

android {
    namespace = "com.aktcl.aron.sr"
    defaultConfig {
        applicationId = "com.aktcl.aron.sr"
        versionCode = (findProperty("aron.versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("aron.versionName") as String?) ?: "0.1.0"
        buildConfigField("String", "ARON_ROLE", "\"SR\"")
        buildConfigField("String", "API_BASE_URL", "\"" + ((findProperty("aron.apiBaseUrl") as String?) ?: "https://api.aron-dev.invalid") + "\"")
    }
    buildFeatures { compose = true }
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
    implementation(project(":android:core-geo"))
    implementation(project(":android:core-printing"))
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
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
}
