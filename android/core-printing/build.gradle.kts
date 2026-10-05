// android:core-printing. Lane: android. Owner and scope: docs/24-build-spec.md s1.2, s2.1, s5.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aktcl.aron.core.printing"
}

dependencies {
    implementation(project(":android:core-common"))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}
