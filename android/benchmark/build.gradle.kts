// android:benchmark: macrobenchmark cold start (gate 2.5 s, docs/04) and the baseline-profile generator (AUD-PERF-04).
// Lane: android-core. A test module: nothing here ships. It runs only on a device or emulator:
//   ./gradlew :android:benchmark:connectedBenchmarkAndroidTest                      (SR, the default; timing)
//   ./gradlew :android:benchmark:connectedBenchmarkAndroidTest -Paron.benchmarkApp=amo   (or tso)
//   ./gradlew :android:benchmark:connectedBenchmarkProfileAndroidTest ...              (the profile generator)
// The generator writes baseline-prof.txt into the device's test output; copy it to android/app-<app>/src/main/.
// Lab phones only: the variants are debug-signed, so they never install over a release-signed device-owner field app,
// and `am force-stop` clears the app's alarms and jobs (scheduled app blocking) until its next launch.
import com.android.build.api.dsl.TestExtension

plugins {
    id("com.android.test") // AGP is on the root classpath already (no version here)
}

val benchmarkApp: String = ((findProperty("aron.benchmarkApp") as String?) ?: "sr").trim()
check(benchmarkApp in setOf("sr", "amo", "tso")) { "aron.benchmarkApp must be sr, amo or tso, got: $benchmarkApp" }

extensions.configure<TestExtension> {
    namespace = "com.aktcl.aron.benchmark"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["aron.targetPackage"] = "com.aktcl.aron.$benchmarkApp"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        // Matches the app's `benchmark` build type: release code (R8, shrunk), debug-signed, profileable.
        create("benchmark") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks += listOf("release")
        }
        // For BaselineProfileGenerator: matches the app's non-obfuscated `benchmarkProfile` build type.
        create("benchmarkProfile") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks += listOf("benchmark", "release")
        }
    }
    targetProjectPath = ":android:app-$benchmarkApp"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
}
