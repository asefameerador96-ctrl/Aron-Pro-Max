// android:core-geo: on-demand location fixes, GNSS evidence, integrity tokens (docs/24 s5.5, s8.3, s11). Lane:
// android-geo-dpc.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aktcl.aron.core.geo"
    testOptions {
        unitTests.all {
            it.systemProperty("aron.openapi", rootProject.layout.projectDirectory.file("contract/openapi.yaml").asFile.absolutePath)
            it.systemProperty("aron.coreGeoSrc", layout.projectDirectory.dir("src/main").asFile.absolutePath)
        }
    }
}

dependencies {
    implementation(project(":android:core-common"))
    implementation(libs.play.services.location)
    implementation(libs.play.integrity)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.snakeyaml.engine)
    // The scripted-day test stores every fix in the real geo_fix table to prove the mock flag survives storage.
    testImplementation(project(":android:core-database"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
}
