// android:core-session. Lane: android. Owner and scope: docs/24-build-spec.md s1.2, s2.1, s5.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aktcl.aron.core.session"
}

dependencies {
    implementation(project(":android:core-common"))
    implementation(project(":android:core-network"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.argon2kt)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}
