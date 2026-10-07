// android:core-system: runtime-permission gates and rationale, update check, PDA to Support, language and logout rules
// (F-SYS-023, F-SYS-019, F-SYS-020, F-SYS-021, F-SYS-022). Lane: android-sys.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aktcl.aron.core.system"
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // The manifest audit reads every module's source manifest (no microphone, no background location).
            it.systemProperty("aron.androidRoot", rootProject.layout.projectDirectory.dir("android").asFile.absolutePath)
            // The audit reads the three apps' merged manifests, so every library's permissions are included.
            it.dependsOn(":android:app-sr:processDebugMainManifest", ":android:app-amo:processDebugMainManifest", ":android:app-tso:processDebugMainManifest")
        }
    }
}

dependencies {
    api(project(":android:core-common"))
    implementation(project(":android:core-ui"))
    implementation(project(":android:core-network"))
    implementation(project(":android:core-database"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)
}
