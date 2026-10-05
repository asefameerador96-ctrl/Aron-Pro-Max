// backend:app: assembles every backend module into ONE deployable (container image). Role chosen at start:
// ARON_ROLE=api (Ktor HTTP, /v1) or ARON_ROLE=worker (aggregation and scheduled jobs). Lane: backend.
// docs/24-build-spec.md s2.1, s6.2.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.aktcl.aron.backend.app.MainKt")
    applicationName = "aron-backend"
}

dependencies {
    implementation(project(":backend:platform"))
    implementation(project(":backend:auth"))
    implementation(project(":backend:sync"))
    implementation(project(":backend:masterdata"))
    implementation(project(":backend:config"))
    implementation(project(":backend:analytics"))
    implementation(project(":backend:notify"))
    implementation(project(":backend:media"))
    runtimeOnly(project(":db"))
    implementation(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    environment("ARON_TEST_PG_URL", System.getenv("ARON_TEST_PG_URL") ?: "")
    systemProperty("aron.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
}
