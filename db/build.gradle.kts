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
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val migrationsDir: String = layout.projectDirectory.dir("migrations").asFile.absolutePath
val seedDir: String = layout.projectDirectory.dir("seed").asFile.absolutePath
tasks.withType<Test>().configureEach {
    inputs.dir(migrationsDir).withPropertyName("migrations").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(seedDir).withPropertyName("seed").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("aron.migrations", migrationsDir)
    systemProperty("aron.seed", seedDir)
    // Integration tests need PostgreSQL 16 (docs/24 s2.5): ARON_TEST_PG_URL (CI service container or the local
    // server; the role needs CREATEDB because every test class gets its own throwaway database), else Testcontainers.
    environment("ARON_TEST_PG_URL", System.getenv("ARON_TEST_PG_URL") ?: "")
}

// Loads db/seed/*.sql into the development database named by ARON_SEED_DB_URL (row N-008). Never run in production.
tasks.register<JavaExec>("seed") {
    group = "aron"
    description = "Loads the idempotent development seed (db/seed) into ARON_SEED_DB_URL"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.aktcl.aron.db.SeedLoader")
    systemProperty("aron.seed", seedDir)
}
