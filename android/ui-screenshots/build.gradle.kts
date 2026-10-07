// android:ui-screenshots: Roborazzi screenshot tests of Home, Sale entry, Review and Memo detail (N-023, docs/32 s2a item 8b).
// Test scope only: nothing here is packaged into an APK. Lane: android-core-ui.
// Modes: default = VERIFY against src/test/screenshots; -Proborazzi.record=true = RECORD into src/test/screenshots;
// -Paron.screenshots.recordTo=<dir> = RECORD into <dir> (CI bootstrap: build/test-results is in the uploaded artifact).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// BOOTSTRAP SWITCH: true records the goldens into build/test-results/screenshots-recorded. Must be false when committed for good.
val bootstrapRecord = true

android {
    namespace = "com.aktcl.aron.uiscreenshots"
    buildFeatures { compose = true }
    testOptions {
        unitTests.all {
            val goldens = layout.projectDirectory.dir("src/test/screenshots").asFile.absolutePath
            val recordTo = (project.findProperty("aron.screenshots.recordTo") as String?)
                ?: if (bootstrapRecord) layout.buildDirectory.dir("test-results/screenshots-recorded").get().asFile.absolutePath else null
            val recordGoldens = project.findProperty("roborazzi.record") == "true"
            it.systemProperty("aron.screenshots.goldens", goldens)
            if (recordTo != null) it.systemProperty("aron.screenshots.recordTo", recordTo)
            if (recordTo != null || recordGoldens) it.systemProperty("roborazzi.test.record", "true")
            else it.systemProperty("roborazzi.test.verify", "true")
        }
    }
}

dependencies {
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.bundles.compose)
    testImplementation(project(":android:core-ui"))
    testImplementation(project(":android:core-common"))
    testImplementation(project(":android:feature-home"))
    testImplementation(project(":android:feature-sale"))
    testImplementation(project(":android:feature-memo"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
