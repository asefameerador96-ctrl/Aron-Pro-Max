// android:core-session: login, encrypted token store (Android Keystore), offline unlock (Argon2id), refresh on 401,
// device uuid and per-user sessions on shared phones (docs/24 s1.2, s5.3, s8.1). Lane: android-core.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aktcl.aron.core.session"
    testOptions {
        unitTests.all {
            it.systemProperty("aron.openapi", rootProject.layout.projectDirectory.file("contract/openapi.yaml").asFile.absolutePath)
        }
    }
}

dependencies {
    implementation(project(":android:core-common"))
    api(project(":android:core-network"))
    implementation(libs.argon2kt)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.snakeyaml.engine)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
