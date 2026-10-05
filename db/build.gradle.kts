// db: forward-only SQL migrations (Flyway) and seed data. Lane: db. docs/24-build-spec.md s12, s13.
// db/migrations/V<NNNN>__<snake_name>.sql is packaged at classpath:db/migration for backend:app and the tests.
// db/seed/*.sql is development and test data, never packaged into the production image.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

tasks.processResources {
    from(layout.projectDirectory.dir("migrations")) { into("db/migration") }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

val migrationsDir: String = layout.projectDirectory.dir("migrations").asFile.absolutePath
tasks.withType<Test>().configureEach {
    inputs.dir(migrationsDir).withPropertyName("migrations").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("aron.migrations", migrationsDir)
}
