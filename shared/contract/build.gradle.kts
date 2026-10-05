// shared:contract: the Kotlin mirror of contract/openapi.yaml (DTOs, enums, problem codes) used by the phone and
// the server. Lane: contract/shared. contract/openapi.yaml is the source of truth; the jvmTest drift tests fail
// the build when an enum or schema here diverges from it (docs/24-build-spec.md s3.10).
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
tasks.named<Test>("jvmTest") {
    inputs.file(openApiPath).withPropertyName("openapi").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("aron.openapi", openApiPath)
}
