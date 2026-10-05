// android:core-network. Lane: android. Owner agent and scope: docs/24-build-spec.md s2.2.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aktcl.aron.core.network"
}

dependencies {
    implementation(project(":android:core-common"))
    api(project(":shared:contract"))
    api(platform(libs.okhttp.bom))
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
}
