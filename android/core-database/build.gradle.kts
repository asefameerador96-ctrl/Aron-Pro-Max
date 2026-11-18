// android:core-database: the per-user Room database (reference cache of the day bundle, SR-day captures, outbox), its
// schema JSON and migration tests (docs/24 s1.2, s4, s5.2). Lane: android-core.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aktcl.aron.core.database"
    // core-printing's PrintLedger contract runs against RoomPrintLedger (RoomPrintLedgerContractTest; lane android-print).
    sourceSets {
        getByName("test").kotlin.directories.add("../core-printing/src/testContract/kotlin")
    }
    testOptions {
        unitTests.all {
            it.systemProperty("aron.openapi", rootProject.layout.projectDirectory.file("contract/openapi.yaml").asFile.absolutePath)
        }
    }
}

// The exported schemas are unit-test assets so MigrationTestHelper can open every version under Robolectric too.
androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach { it.sources.assets?.addStaticSourceDirectory("$projectDir/schemas") }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":android:core-common"))
    api(project(":android:core-printing")) // RoomPrintLedger implements core-printing's PrintLedger
    implementation(project(":shared:contract"))
    implementation(libs.kotlinx.serialization.json)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.snakeyaml.engine)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// D-AGATE-29: EveryWriterIsGatedTest scans the writers of core-database, core-sync and the three apps. They read .kt/.xml SOURCE files at run time, which Gradle cannot see, so those files are declared as inputs of every unit test task of this module:
// the up-to-date check and the build cache can never answer for a changed source file (a structural test that can pass from cache is not a gate).
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree("android") {
            include("core-database/src/main/**", "core-sync/src/main/**", "app-sr/src/main/**", "app-amo/src/main/**", "app-tso/src/main/**")
        },
    ).withPropertyName("scannedSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
