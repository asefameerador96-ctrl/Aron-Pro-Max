// shared:rules: business rules that MUST give the same answer on the phone and the server: money and rounding,
// memo totals, discount lines, geofence maths, business date and trusted time, memo numbers, config resolution.
// Lane: contract/shared. docs/24-build-spec.md s7, s9, s11.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}
