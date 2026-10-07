// android:core-printing. Lane: android-print. Owner and scope: docs/24-build-spec.md s1.2, s2.1, s5.5.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aktcl.aron.core.printing"
    sourceSets {
        // Print samples and the PBM codec are shared by the JVM goldens and the on-phone golden test.
        getByName("test").kotlin.directories.add("src/test/shared/kotlin")
        getByName("androidTest").kotlin.directories.add("src/test/shared/kotlin")
        // The phone test reads the same golden files and the fonts the app ships (core-ui), as assets.
        getByName("androidTest").assets.directories.add("src/test/resources/goldens")
        getByName("androidTest").assets.directories.add("../core-ui/src/main/res/font")
    }
}

dependencies {
    implementation(project(":android:core-common"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

android.testOptions.unitTests.all {
    // Golden images are rewritten only on request: ./gradlew ... -Paron.updateGoldens=true
    it.systemProperty("aron.updateGoldens", (project.findProperty("aron.updateGoldens") as String?) ?: "false")
}
