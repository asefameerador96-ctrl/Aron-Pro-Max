// backend:platform: server infrastructure shared by every backend module (Ktor plugins, problem+json,
// principal and scope context, DB pool and transactions, clock, config loading, audit writer, observability).
// Lane: backend (auth-and-scope agent). docs/24-build-spec.md s2.2, s5.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":shared:contract"))
    api(project(":shared:rules"))
    api(platform(libs.ktor.bom))
    api(libs.bundles.ktor.server)
    api(libs.bundles.db)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
    implementation(libs.logback.classic)

    testImplementation(project(":db"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    // Integration tests need PostgreSQL 16: ARON_TEST_PG_URL (CI service container or the local server), else
    // Testcontainers when Docker is present. See docs/24 s13.3.
    environment("ARON_TEST_PG_URL", System.getenv("ARON_TEST_PG_URL") ?: "")
}
