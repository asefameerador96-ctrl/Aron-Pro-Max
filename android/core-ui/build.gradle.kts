// android:core-ui: Compose theme, bundled Bengali and Latin fonts, per-app language, shared UI strings, and the build
// gate against hard-coded user-visible text (docs/24 s1.2, s5.6; F-SYS-018). Lane: android-core.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aktcl.aron.core.ui"
    buildFeatures { compose = true }
    testOptions {
        unitTests.all {
            // HardcodedStringScanTest reads the app and feature sources; declaring them as inputs makes the gate re-run
            // (never "up to date") whenever any of those files changes.
            val androidRoot = rootProject.layout.projectDirectory.dir("android")
            it.systemProperty("aron.androidRoot", androidRoot.asFile.absolutePath)
            it.inputs.files(
                rootProject.fileTree("android") {
                    include("app-*/src/**", "feature-*/src/**", "core-ui/src/**", "core-printing/src/**", "core-sync/src/**", "dpc/src/**")
                    exclude("**/src/test/**", "**/src/androidTest/**")
                },
            ).withPropertyName("scannedSources").withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    api(project(":android:core-common"))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
