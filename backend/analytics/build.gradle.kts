// backend:analytics. Lane: backend. Owner and scope: docs/24-build-spec.md s1, s2.1, s6.2.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":backend:platform"))
    implementation(libs.logback.classic)
    implementation(libs.fastexcel)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":db"))
    testImplementation(testFixtures(project(":backend:platform")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.bouncycastle.bcpkix)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    environment("ARON_TEST_PG_URL", System.getenv("ARON_TEST_PG_URL") ?: "")
    // D-QA-CI-HANG-01: no single test (or @BeforeAll/@AfterAll) may run longer than 5 minutes; an overrun fails by name
    // instead of eating the job's time. The slowest analytics class takes 4.4 minutes in all (22 tests, migrations included).
    systemProperty("junit.jupiter.execution.timeout.default", "5m")

    // D-LEAD-CI-ANALYTICS-SHARD-01: CI runs this suite on two runners. ARON_ANALYTICS_SHARD=a runs exactly the classes
    // listed below; =b runs every other class, as an EXCLUDE of the same list, so the two shards are complementary by
    // construction: no class can fall between them and a class added anywhere lands in b. Unset runs everything, as
    // before (local runs and every other task). Any other value fails the build instead of running a wrong subset.
    // Groups balanced from the TEST-CLASS END timings of run 40755248363 (about 18 minutes of class time each).
    val shard = System.getenv("ARON_ANALYTICS_SHARD")?.trim()?.lowercase().orEmpty()
    if (shard.isNotEmpty()) {
        val shardA = listOf(
            "AggregationTest", "AnalyticsModuleTest", "AsWasBrandScopeTest", "CheckerF058DefectsTest",
            "CheckerMemoGapsAdminVoidTest", "CheckerMemoGapsPartialScopeTest", "DailyTrackingTest", "DashboardTest",
            "EntrySourceTest", "ExportJobsTest", "GeoValidationTest", "MemoGapsTest", "NationalBasisPointsTest",
            "NationalDashboardTest", "NationalMapsTest", "OpsApiTest", "ReportAreaContractTest", "ReportBatchBTest",
            "ReportBatchCTest", "ReportPrintMarkTest", "SuccessfulLoginTest", "SupportReportAccessTest",
            "devices.ProvisioningQrPayloadTest",
        ).map { "com.aktcl.aron.backend.analytics.$it" }
        filter {
            when (shard) {
                "a" -> shardA.forEach { includeTestsMatching(it) }
                "b" -> shardA.forEach { excludeTestsMatching(it) }
                else -> throw GradleException("ARON_ANALYTICS_SHARD must be 'a', 'b' or unset; it is '$shard'")
            }
        }
    }
}
