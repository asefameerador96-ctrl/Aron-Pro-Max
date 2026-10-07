// Root build: plugin versions (apply false) and the conventions every module inherits.
// Owner: infra lane. Conventions live here, not in an included build, so there is exactly ONE Gradle build
// (docs/24-build-spec.md s2). Module build files only add their plugins and dependencies.

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.google.services) apply false
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun v(alias: String): String = catalog.findVersion(alias).get().requiredVersion

val jvmTargetVersion = v("jvm-target")            // "17"
val compileSdkVersion = v("android-compileSdk").toInt()
val targetSdkVersion = v("android-targetSdk").toInt()
val minSdkVersion = v("android-minSdk").toInt()

// Robolectric 4.17 with the Android 16 (API 36) runtime on JDK 21 reflects into java.base internals
// (FileDescriptor via jdk.internal.access.SharedSecrets); without these flags Room tests fail with
// "Failed to interact with raw FileDescriptor internals" (seen 2026-10-05, docs/24-build-spec-verification.md).
val robolectricJvmArgs = listOf(
    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
)

subprojects {
    group = "com.aktcl.aron"
    version = (findProperty("aron.version") as String?) ?: "0.1.0"

    // Every Kotlin compilation (JVM, KMP jvm target, Android built-in Kotlin) emits Java 17 bytecode.
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion))
        }
    }
    // Non-Android modules also compile against the Java 17 API (the JDK in use is 21). Android modules must not:
    // AGP sets the Android bootclasspath itself and refuses `--release`.
    val pinJdkApi: () -> Unit = {
        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions.freeCompilerArgs.add("-Xjdk-release=$jvmTargetVersion")
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(jvmTargetVersion.toInt())
        }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") { pinJdkApi() }
    // AUD-TP-6: no compiler warning survives in the correctness-critical production code (test sources stay lenient). A
    // module joins this list once its warnings are fixed (docs/requests/kotlin-warnings-as-errors.md lists them):
    // targets :shared:rules, :backend:auth, :backend:sync, :backend:platform.
    val warningsAsErrors = setOf<String>()
    if (path in warningsAsErrors) {
        tasks.withType<KotlinJvmCompile>().matching { !it.name.contains("Test") }.configureEach {
            compilerOptions.allWarningsAsErrors.set(true)
        }
    }
    plugins.withId("org.jetbrains.kotlin.multiplatform") { pinJdkApi() }

    // JVM and KMP modules test on the JUnit Platform (Jupiter 6). Android local tests stay on JUnit 4 (Robolectric).
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            jvm()
            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }

    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = compileSdkVersion
            defaultConfig {
                minSdk = minSdkVersion
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions {
                sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
                targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
            }
            testOptions {
                unitTests.isIncludeAndroidResources = true
                unitTests.isReturnDefaultValues = true
                unitTests.all { it.jvmArgs(robolectricJvmArgs) }
            }
            lint {
                abortOnError = true
                checkDependencies = false
            }
        }
    }

    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            compileSdk = compileSdkVersion
            defaultConfig {
                minSdk = minSdkVersion
                targetSdk = targetSdkVersion
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                // Secrets never live in the repository (docs/24 s13.6): the Maps key arrives from the
                // MAPS_ANDROID_KEY environment variable (a GitHub secret in CI) or ~/.gradle/gradle.properties.
                manifestPlaceholders["mapsApiKey"] =
                    (System.getenv("MAPS_ANDROID_KEY") ?: (findProperty("aron.mapsAndroidKey") as String?) ?: "")
            }
            compileOptions {
                sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
                targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
            }
            buildFeatures {
                buildConfig = true
            }
            packaging {
                resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
            }
            testOptions {
                unitTests.isIncludeAndroidResources = true
                unitTests.isReturnDefaultValues = true
                unitTests.all { it.jvmArgs(robolectricJvmArgs) }
            }
            lint {
                abortOnError = true
                checkReleaseBuilds = false
            }
        }
    }
}
