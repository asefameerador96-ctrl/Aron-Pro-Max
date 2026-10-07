package com.aktcl.aron.core.geo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/** The phone's fix enums are the contract's, and core-geo never streams locations or reads a cached one. */
class FixModelContractTest {
    @Suppress("UNCHECKED_CAST")
    private val schemas: Map<String, Any?> by lazy {
        val root = File(System.getProperty("aron.openapi")!!).inputStream().use { Load(LoadSettings.builder().build()).loadFromInputStream(it) } as Map<String, Any?>
        (root["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumOf(vararg path: String): Set<String> =
        (path.fold(schemas as Any?) { acc, k -> (acc as Map<String, Any?>)[k] } as Map<String, Any?>)["enum"].let { (it as List<String>).toSet() }

    @Test fun enumsMatchTheContract() {
        assertEquals(enumOf("FixPurpose"), FixPurpose.entries.map { it.wire }.toSet())
        assertEquals(enumOf("LocationProvider"), FixProvider.entries.map { it.wire }.toSet())
        assertEquals(enumOf("GeoFix", "properties", "fix_status"), FixStatus.entries.map { it.wire }.toSet())
        assertEquals(enumOf("GeoFix", "properties", "request_priority"), FixPriority.entries.map { it.wire }.toSet())
    }

    @Test fun takenFixCarriesEveryGeoFixMemberExceptRadio() {
        @Suppress("UNCHECKED_CAST")
        val members = ((schemas["GeoFix"] as Map<String, Any?>)["properties"] as Map<String, Any?>).keys
        val camel = TakenFix::class.java.declaredFields.map { it.name }.toSet()
        fun snakeToCamel(s: String) = s.split('_').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar(Char::uppercase) }.joinToString("")
        val missing = members.filter { m -> m != "radio" && snakeToCamel(m) !in camel && "${snakeToCamel(m)}Json" !in camel }
        assertTrue("TakenFix lacks $missing", missing.isEmpty())
    }

    @Test fun noStreamAndNoLastKnownLocationInCoreGeo() {
        val forbidden = Regex("""requestLocationUpdates|getLastLocation|lastLocation|getLastKnownLocation|lastKnownLocation|requestSingleUpdate""")
        // The platform fallback below API 30 needs one single update and removes its listener on return.
        val allowed = mapOf("AndroidLocation.kt" to setOf("requestSingleUpdate"))
        val hits = File(System.getProperty("aron.coreGeoSrc")!!).walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .flatMap { line -> forbidden.findAll(line).map { it.value }.toList() }
                .filter { it !in (allowed[f.name] ?: emptySet()) }.map { "${f.name}: $it" }
        }.toList()
        assertTrue("forbidden location calls: $hits", hits.isEmpty())
    }
}
