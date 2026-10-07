// android:core-ui-testing: the shared Robolectric Compose screen check (AUD-TP-5). Test scope only: feature modules add it as
// `testImplementation(project(":android:core-ui-testing"))`; it is never packaged into an APK. Lane: android-core-ui.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aktcl.aron.core.uitesting"
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.test.junit4)
    implementation(libs.bundles.compose)
    api(project(":android:core-ui"))
    api(project(":android:core-common"))
    api(libs.junit4)
}
