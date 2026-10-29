// android:feature-tickets. Lane: tso-manual A2. The role-agnostic support tickets module (build contract
// docs/requests/tso-manual-build-2026-10.md s5 A2; D-TSO-MAN-17..25): host ports, states, the five screens, en and bn
// strings. The TSO app wires it now (app-tso TicketsHost.kt); SR and AMO later.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aktcl.aron.feature.tickets"
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(project(":android:core-ui"))
    implementation(project(":android:core-common")) // TicketMath, Percent (shared:rules), ClientIds, LocaleDigits
    api(project(":shared:contract")) // the queue port carries SupportTicketPayload / SupportTicketEventPayload
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
