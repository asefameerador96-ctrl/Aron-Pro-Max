package com.aktcl.aron.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Drift gate between contract/openapi.yaml (the source of truth) and the Kotlin mirror. A lane that changes one
 * side without the other fails `./gradlew :shared:contract:jvmTest` (docs/24 s1.4, s13.2).
 */
class ContractDriftTest {
    private fun assertMirrors(schema: String, wires: List<String>) =
        assertEquals(OpenApi.enumOf(schema), wires, "enum $schema in shared/contract differs from contract/openapi.yaml")

    @Test fun recordTypes() = assertMirrors("RecordType", RecordType.entries.map { it.wire })
    @Test fun roles() = assertMirrors("Role", Role.entries.map { it.wire })
    @Test fun ackStatuses() = assertMirrors("AckStatus", AckStatus.entries.map { it.wire })
    @Test fun dayStates() = assertMirrors("DayState", DayState.entries.map { it.wire })
    @Test fun syncTriggers() = assertMirrors("SyncTrigger", SyncTrigger.entries.map { it.wire })
    @Test fun appFlavours() = assertMirrors("AppFlavour", AppFlavour.entries.map { it.wire })
    @Test fun lockdownLevels() = assertMirrors("LockdownLevel", LockdownLevel.entries.map { it.wire })
    @Test fun deviceStates() = assertMirrors("DeviceState", DeviceState.entries.map { it.wire })
    @Test fun geoVerdicts() = assertMirrors("GeoVerdict", GeoVerdict.entries.map { it.wire })
    @Test fun fixPurposes() = assertMirrors("FixPurpose", FixPurpose.entries.map { it.wire })
    @Test fun riskSignalCodes() = assertMirrors("RiskSignalCode", RiskSignalCode.entries.map { it.wire })
    @Test fun reportKeys() = assertMirrors("ReportKey", ReportKey.entries.map { it.wire })
    @Test fun configScopeTypes() = assertMirrors("ConfigScopeType", ConfigScopeType.entries.map { it.wire })
    @Test fun recordOutcomeCodes() = assertMirrors("RecordOutcomeCode", RecordOutcomeCode.entries.map { it.wire })
    @Test fun problemCodes() = assertMirrors("ProblemCode", ProblemCode.entries.map { it.wire })
    @Test fun visitKinds() = assertMirrors("VisitKind", VisitKind.entries.map { it.wire })
    @Test fun visitOutcomes() = assertMirrors("VisitOutcome", VisitOutcome.entries.map { it.wire })
    @Test fun stockMovementKinds() = assertMirrors("StockMovementKind", StockMovementKind.entries.map { it.wire })
    @Test fun outletRequestTypes() = assertMirrors("OutletRequestType", OutletRequestType.entries.map { it.wire })
    @Test fun codeListKeys() = assertMirrors("CodeListKey", CodeListKey.entries.map { it.wire })
    @Test fun mediaPurposes() = assertMirrors("MediaPurpose", MediaPurpose.entries.map { it.wire })
    @Test fun bundleSectionNames() = assertMirrors("BundleSectionName", BundleSectionName.entries.map { it.wire })
    @Test fun programmeKinds() = assertMirrors("ProgrammeKind", ProgrammeKind.entries.map { it.wire })
    @Test fun contentKinds() = assertMirrors("ContentKind", ContentKind.entries.map { it.wire })

    @Test
    fun everyRecordTypeHasAPayloadMapping() {
        @Suppress("UNCHECKED_CAST")
        val mapping = (OpenApi.schema("SyncRecord")["discriminator"] as Map<String, Any?>)["mapping"] as Map<String, String>
        assertEquals(RecordType.entries.map { it.wire }.toSet(), mapping.keys)
    }

    @Test
    fun scopePrecedenceMatchesContractDescription() {
        // "Precedence (most specific wins) device 120 > user 110 > ..." in components.schemas.ConfigScopeType.
        val description = OpenApi.schema("ConfigScopeType")["description"] as String
        val stated = Regex("""([a-z_]+) (\d+)""").findAll(description).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(ConfigScopeType.entries.associate { it.wire to it.precedence }, stated)
    }

    @Test
    fun apiBasePathIsV1() {
        assertTrue(OpenApi.paths.keys.all { it.startsWith(ContractInfo.API_BASE_PATH + "/") }) {
            "every path in openapi.yaml must start with ${ContractInfo.API_BASE_PATH}/"
        }
    }
}

/** Lazily parsed contract/openapi.yaml (path from the `aron.openapi` system property set by Gradle). */
internal object OpenApi {
    val file: File by lazy { File(System.getProperty("aron.openapi") ?: error("system property aron.openapi is not set")) }
    val text: String by lazy { file.readText() }

    @Suppress("UNCHECKED_CAST")
    val doc: Map<String, Any?> by lazy {
        org.snakeyaml.engine.v2.api.Load(org.snakeyaml.engine.v2.api.LoadSettings.builder().build())
            .loadFromString(text) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    val paths: Map<String, Map<String, Any?>> by lazy { doc["paths"] as Map<String, Map<String, Any?>> }

    @Suppress("UNCHECKED_CAST")
    fun schema(name: String): Map<String, Any?> =
        ((doc["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>)[name] as Map<String, Any?>?
            ?: error("schema $name missing in openapi.yaml")

    @Suppress("UNCHECKED_CAST")
    fun enumOf(name: String): List<String> = (schema(name)["enum"] as List<Any?>).map { it.toString() }

    /** Every operation as "METHOD /path". */
    val operations: Set<String> by lazy {
        val methods = setOf("get", "post", "put", "patch", "delete", "head")
        paths.flatMap { (path, item) -> item.keys.filter { it in methods }.map { "${it.uppercase()} $path" } }.toSet()
    }
}
