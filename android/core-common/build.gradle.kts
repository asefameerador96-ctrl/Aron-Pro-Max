// android:core-common. Lane: android. Owner and scope: docs/24-build-spec.md s1.2, s2.1, s5.
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
