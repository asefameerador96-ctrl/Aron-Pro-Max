// android:dpc: device-owner policy engine embedded in each field app (docs/24 s10). Lane: android
// (device-owner policy agent).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aktcl.aron.dpc"
    testOptions {
        unitTests.all {
            it.systemProperty("aron.openapi", rootProject.layout.projectDirectory.file("contract/openapi.yaml").asFile.absolutePath)
        }
    }
}

dependencies {
    implementation(project(":android:core-common"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.snakeyaml.engine)
    testImplementation(libs.kotlinx.coroutines.test)
}
