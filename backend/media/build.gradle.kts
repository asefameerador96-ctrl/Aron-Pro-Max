// backend:media. Lane: backend. Owner and scope: docs/24-build-spec.md s1, s2.1, s6.2.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":backend:platform"))

    testImplementation(project(":db"))
    testImplementation(testFixtures(project(":backend:platform")))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    environment("ARON_TEST_PG_URL", System.getenv("ARON_TEST_PG_URL") ?: "")
    systemProperty("aron.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
}
