// backend:notify. Lane: backend. Owner and scope: docs/24-build-spec.md s1, s2.1, s6.2.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":backend:platform"))
    implementation(libs.firebase.admin)  // FCM HTTP v1 sender (docs/24 s2.2); off without ARON_FCM_SERVICE_ACCOUNT_JSON

    // tso-manual B1: InboxTest runs against a fresh migrated PostgreSQL (FreshDb), as the masterdata tests do.
    testImplementation(project(":db"))
    testImplementation(testFixtures(project(":backend:platform")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// tso-manual B1: integration tests need PostgreSQL 16 (ARON_TEST_PG_URL), docs/24 s2.5.
tasks.withType<Test>().configureEach {
    environment("ARON_TEST_PG_URL", System.getenv("ARON_TEST_PG_URL") ?: "")
}
