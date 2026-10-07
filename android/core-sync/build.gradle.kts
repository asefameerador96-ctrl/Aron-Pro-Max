// android:core-sync. Lane: android. Owner and scope: docs/24-build-spec.md s1.2, s2.1, s5.
plugins {
    alias(libs.plugins.android.library)
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
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}
