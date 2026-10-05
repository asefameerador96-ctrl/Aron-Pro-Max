// android:core-network: OkHttp client, gzip, auth headers and refresh-on-401, X-Device-Proof hook, problem parsing,
// X-Aron-Api edge detection (docs/24 s1.2, s3, D24-06). Lane: android-core.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aktcl.aron.core.network"
    testOptions {
        unitTests.all {
            // Contract fixtures and member-name checks read the one contract file (docs/26 s1: never invent a field).
            it.systemProperty("aron.openapi", rootProject.layout.projectDirectory.file("contract/openapi.yaml").asFile.absolutePath)
        }
    }
}

dependencies {
    implementation(project(":android:core-common"))
    api(project(":shared:contract"))
    api(platform(libs.okhttp.bom))
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.snakeyaml.engine)
}
