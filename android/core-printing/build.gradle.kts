// android:core-printing. Lane: android. Owner agent and scope: docs/24-build-spec.md s2.2.
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
