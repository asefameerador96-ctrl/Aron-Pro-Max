// android:core-geo. Lane: android. Owner agent and scope: docs/24-build-spec.md s2.2.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aktcl.aron.core.geo"
}

dependencies {
    implementation(project(":android:core-common"))
    implementation(libs.play.services.location)
    implementation(libs.play.integrity)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}
