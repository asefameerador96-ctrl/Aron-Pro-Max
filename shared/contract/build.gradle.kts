// shared:contract: the Kotlin mirror of contract/openapi.yaml (DTOs, enums, problem codes) used by the phone and
// the server. Lane: contract/shared. contract/openapi.yaml is the source of truth; the jvmTest drift tests fail
// the build when an enum here diverges from it, and SpecCrossCheckTest fails it when docs/24-build-spec.md and the
// contract disagree (docs/24-build-spec.md s13.2, Appendix B), and BacklogCoverageTest fails it when a docs/25 backlog
// row needs a contract element that is missing (docs/24-build-spec-verification.md s10).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.snakeyaml.engine)
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

val openApiPath: String = rootProject.layout.projectDirectory.file("contract/openapi.yaml").asFile.absolutePath
val buildSpecPath: String = rootProject.layout.projectDirectory.file("docs/24-build-spec.md").asFile.absolutePath
val verificationPath: String = rootProject.layout.projectDirectory.file("docs/24-build-spec-verification.md").asFile.absolutePath
val backlogPath: String = rootProject.layout.projectDirectory.file("docs/25-build-backlog.csv").asFile.absolutePath
tasks.named<Test>("jvmTest") {
    inputs.file(openApiPath).withPropertyName("openapi").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(buildSpecPath).withPropertyName("buildSpec").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(verificationPath).withPropertyName("verification").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(backlogPath).withPropertyName("backlog").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("aron.openapi", openApiPath)
    systemProperty("aron.spec", buildSpecPath)
    systemProperty("aron.verification", verificationPath)
    systemProperty("aron.backlog", backlogPath)
}
