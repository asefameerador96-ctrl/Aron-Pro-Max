// android:core-sync. Lane: android. Owner and scope: docs/24-build-spec.md s1.2, s2.1, s4, s5.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aktcl.aron.core.sync"
}

dependencies {
    implementation(project(":android:core-common"))
    implementation(project(":android:core-database"))
    implementation(project(":android:core-network"))
    api(project(":shared:contract")) // SyncScheduler exposes SyncTrigger
    implementation(project(":android:core-session"))
    // Device status and integrity wiring (docs/requests/android-geo-dpc-wiring.md): the sync worker queues device_status.
    implementation(project(":android:core-geo"))
    implementation(project(":android:dpc"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver)
}
