// android:core-map: the shared map component and the online-only reverse geocoder (N-053, F-SYS-074; docs/17 s10.5,
// D-08). Lane: android-core (built for android-amo and android-tso to reuse). The Maps SDK classes load only when a
// map screen is composed; nothing here starts a sensor, a service or a timer.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aktcl.aron.core.map"
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":android:core-common"))
    implementation(project(":android:core-ui"))
    implementation(libs.maps.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
