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
}
