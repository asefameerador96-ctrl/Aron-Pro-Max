// android:dpc: device-owner policy engine embedded in each field app (docs/24 s10). Lane: android
// (device-owner policy agent).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aktcl.aron.dpc"
}

dependencies {
    implementation(project(":android:core-common"))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}
