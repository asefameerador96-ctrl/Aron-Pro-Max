package com.aktcl.aron.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

/**
 * Drift gate between contract/openapi.yaml (the source of truth) and the Kotlin mirror. A lane that changes one
 * side without the other fails `./gradlew :shared:contract:jvmTest` (docs/24 s3.10, s13.2).
 */
class ContractDriftTest {
    @Suppress("UNCHECKED_CAST")
    private val schemas: Map<String, Any?> by lazy {
        val path = System.getProperty("aron.openapi") ?: error("system property aron.openapi is not set")
        val doc = Load(LoadSettings.builder().build()).loadFromString(File(path).readText()) as Map<String, Any?>
        (doc["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumOf(name: String): List<String> =
        ((schemas[name] as Map<String, Any?>?) ?: error("schema $name missing in openapi.yaml"))["enum"] as List<String>

    @Test
    fun recordTypesMatchOpenApi() = assertEquals(enumOf("RecordType"), RecordType.entries.map { it.wire })

    @Test
    fun rolesMatchOpenApi() = assertEquals(enumOf("Role"), Role.entries.map { it.wire })

    @Test
    fun ackStatusesMatchOpenApi() = assertEquals(enumOf("AckStatus"), AckStatus.entries.map { it.wire })

    @Test
    fun apiBasePathMatchesServers() {
        val path = System.getProperty("aron.openapi")!!
        val text = File(path).readText()
        assert(text.contains("/v1")) { "servers in openapi.yaml must end with ${ContractInfo.API_BASE_PATH}" }
    }
}
