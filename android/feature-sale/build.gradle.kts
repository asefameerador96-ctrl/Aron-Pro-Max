// android:feature-sale. Lane: android. Owner and scope: docs/24-build-spec.md s1.2, s2.1, s5.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aktcl.aron.feature.sale"
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(project(":android:core-ui"))
    implementation(project(":android:core-common"))
    implementation(project(":android:core-database"))
    implementation(project(":android:core-printing"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
