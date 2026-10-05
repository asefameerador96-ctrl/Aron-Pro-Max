// android:core-sync. Lane: android. Owner agent and scope: docs/24-build-spec.md s2.2.
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
    implementation(project(":android:core-session"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}
