// backend:notify. Lane: backend. Owner and scope: docs/24-build-spec.md s1, s2.1, s6.2.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":backend:platform"))
    implementation(libs.firebase.admin)  // FCM HTTP v1 sender (docs/24 s2.2); off without ARON_FCM_SERVICE_ACCOUNT_JSON

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform.launcher)
}
