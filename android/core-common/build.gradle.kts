// android:core-common. Lane: android. Owner agent and scope: docs/24-build-spec.md s2.2.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aktcl.aron.core.common"
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.datetime)
    api(project(":shared:rules"))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}
