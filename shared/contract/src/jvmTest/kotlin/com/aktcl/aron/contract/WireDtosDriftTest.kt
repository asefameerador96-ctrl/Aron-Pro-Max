package com.aktcl.aron.contract

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Compares every member of the wire DTOs (WireDtos.kt) with the fields of the contract slices
 * (contract/slices/schemas, generated from contract/openapi.yaml): name, required, nullable and JSON type.
 * A renamed, added, dropped, retyped or re-nullabled field on either side fails `:shared:contract:jvmTest`.
 */
@OptIn(ExperimentalSerializationApi::class)
class WireDtosDriftTest {
    private val slicesDir = File(System.getProperty("aron.slices") ?: error("system property aron.slices is not set"))

    @Suppress("UNCHECKED_CAST")
    private fun slice(name: String): Map<String, Any?> {
        val doc = org.snakeyaml.engine.v2.api.Load(org.snakeyaml.engine.v2.api.LoadSettings.builder().build())
            .loadFromString(File(slicesDir, "$name.yaml").readText()) as Map<String, Any?>
        return doc[name] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun members(s: Map<String, Any?>): Pair<Map<String, Map<String, Any?>>, Set<String>> {
        val parts = (s["allOf"] as List<Map<String, Any?>>?)?.map { if (it.containsKey("\$ref")) slice(refName(it)) else it } ?: listOf(s)
        val props = LinkedHashMap<String, Map<String, Any?>>()
        val req = HashSet<String>()
        for (p in parts) {
            (p["properties"] as Map<String, Map<String, Any?>>?)?.let(props::putAll)
            (p["required"] as List<String>?)?.let(req::addAll)
        }
        return props to req
    }

    private fun refName(s: Map<String, Any?>) = (s["\$ref"] as String).substringAfterLast('/')

    @Suppress("UNCHECKED_CAST")
    private fun isNullable(s: Map<String, Any?>): Boolean {
        val t = s["type"]
        if (t is List<*> && "null" in t) return true
        return (s["oneOf"] as List<Map<String, Any?>>?)?.any { it["type"] == "null" } == true
    }

    @Suppress("UNCHECKED_CAST")
    private fun nonNull(s: Map<String, Any?>): Map<String, Any?> {
        var r = s
        (s["oneOf"] as List<Map<String, Any?>>?)?.filter { it["type"] != "null" }?.singleOrNull()?.let { r = it }
        val t = r["type"]
        if (t is List<*>) r = r + ("type" to t.filter { it != "null" }.single())
        return r
    }

    /** The JSON kind the Kotlin side must have for a slice property schema, as a tag compared with [kindOf]. */
    @Suppress("UNCHECKED_CAST")
    private fun expectedKind(raw: Map<String, Any?>): String {
        val s = nonNull(raw)
        if (s.containsKey("\$ref")) {
            val n = refName(s)
            return when (n) {
                "Id", "Mtk", "MtkNonNegative" -> "LONG"
                "RadioEnvironment", "SyncRecord" -> "OBJECT"
                else -> {
                    val t = slice(n)
                    when (t["type"]) {
                        "string" -> "STRING"
                        "integer" -> if (t["format"] == "int64") "LONG" else "INT"
                        "number" -> "DOUBLE"
                        "object", null -> if (n in WireDtoNames.all) "CLASS:$n" else "ELEMENT"
                        else -> error("unhandled alias $n")
                    }
                }
            }
        }
        return when (s["type"]) {
            "string" -> "STRING"
            "boolean" -> "BOOLEAN"
            "integer" -> if (s["format"] == "int64") "LONG" else "INT"
            "number" -> "DOUBLE"
            "array" -> "LIST<" + expectedKind(s["items"] as Map<String, Any?>) + ">"
            "object" -> if (s.containsKey("additionalProperties") && !s.containsKey("properties"))
                "MAP<" + expectedKind(s["additionalProperties"] as Map<String, Any?>) + ">" else "NESTED_OR_OBJECT"
            else -> error("unhandled $s")
        }
    }

    private fun kindOf(d: SerialDescriptor): String = when {
        d.serialName.removeSuffix("?") == "kotlinx.serialization.json.JsonObject" -> "OBJECT"
        d.kind == PrimitiveKind.STRING -> "STRING"
        d.kind == PrimitiveKind.BOOLEAN -> "BOOLEAN"
        d.kind == PrimitiveKind.INT -> "INT"
        d.kind == PrimitiveKind.LONG -> "LONG"
        d.kind == PrimitiveKind.DOUBLE -> "DOUBLE"
        d.kind == StructureKind.LIST -> "LIST<" + kindOf(d.getElementDescriptor(0)) + ">"
        d.kind == StructureKind.MAP -> "MAP<" + kindOf(d.getElementDescriptor(1)) + ">"
        d.serialName == "kotlinx.serialization.json.JsonObject" -> "OBJECT"
        d.serialName.startsWith("kotlinx.serialization.json.Json") -> "ELEMENT"
        else -> "CLASS:" + d.serialName.removeSuffix("?").substringAfterLast('.')
    }

    private fun matches(expected: String, actual: String): Boolean = when {
        expected == actual -> true
        expected == "NESTED_OR_OBJECT" -> actual == "OBJECT" || actual.startsWith("CLASS:")
        expected.startsWith("LIST<") && actual.startsWith("LIST<") ->
            matches(expected.removePrefix("LIST<").removeSuffix(">"), actual.removePrefix("LIST<").removeSuffix(">"))
        expected.startsWith("MAP<") && actual.startsWith("MAP<") ->
            matches(expected.removePrefix("MAP<").removeSuffix(">"), actual.removePrefix("MAP<").removeSuffix(">"))
        // an unmirrored schema (TargetRow, Directive, ...) is carried as raw JSON on the phone
        expected == "ELEMENT" -> actual == "ELEMENT" || actual == "OBJECT"
        else -> false
    }

    private fun check(name: String, d: SerialDescriptor, sch: Map<String, Any?>) {
        val (props, req) = members(sch)
        val actualNames = (0 until d.elementsCount).map { d.getElementName(it) }
        assertEquals(props.keys.toSet(), actualNames.toSet(), "$name: member names differ from the contract")
        for (i in 0 until d.elementsCount) {
            val p = d.getElementName(i)
            val ps = props.getValue(p)
            val where = "$name.$p"
            val nul = isNullable(ps)
            val hasDefault = nonNull(ps).containsKey("default") && !nul
            if (p in req) {
                assertFalse(d.isElementOptional(i), "$where: required in the contract, must have no default")
                assertEquals(nul, d.getElementDescriptor(i).isNullable, "$where: nullability differs")
            } else {
                assertTrue(d.isElementOptional(i), "$where: optional in the contract, needs a default")
                // optional without a schema default is nullable (null = absent); with a default it follows the schema
                assertEquals(nul || !hasDefault, d.getElementDescriptor(i).isNullable, "$where: nullability differs")
            }
            val exp = expectedKind(ps)
            val act = kindOf(d.getElementDescriptor(i))
            assertTrue(matches(exp, act), "$where: contract says $exp, Kotlin has $act")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun nestedOf(ownerSchema: Map<String, Any?>, prop: String): Map<String, Any?> {
        val ps = members(ownerSchema).first.getValue(prop)
        return (if (ps["type"] == "array") ps["items"] else ps) as Map<String, Any?>
    }

    @Test
    fun everyWireDtoMatchesItsSlice() {
        for ((name, ser) in WireDtoNames.serializers) {
            check(name, ser.descriptor, slice(name))
        }
    }

    @Test
    fun nestedObjectsMatchTheirSlices() {
        check("BundleMeta.paged_sections", serializer<PagedSection>().descriptor, nestedOf(slice("BundleMeta"), "paged_sections"))
        check("SyncBatchResponse.summary", serializer<SyncBatchSummary>().descriptor, nestedOf(slice("SyncBatchResponse"), "summary"))
    }

    @Test
    fun everyRequestedSchemaHasADto() {
        val requested = listOf(
            "LoginRequest", "LoginResponse", "UserSummary", "ScopeSummary", "NodeRef", "LoginDevice", "RefreshRequest", "TokenPair",
            "LogoutRequest", "BundleMeta", "BundleUser", "Route", "RouteSnapshot", "BundleOutlet", "Sku", "SkuPrice", "OpenMemo",
            "RecordEnvelope", "GeoFix", "FixDeviceState", "GnssSummary", "DeviceGeoVerdict", "AttendanceEventPayload",
            "StockMovementPayload", "VisitPayload", "VisitClosePayload", "MemoPayload", "MemoLinePayload", "MemoDiscountPayload",
            "QcLinePayload", "SyncBatchRequest", "SyncBatchResponse", "RecordAck",
        )
        assertEquals(requested.toSet(), WireDtoNames.serializers.keys)
    }

    @Test
    fun wireNamesAreTheSnakeCaseSerialNames() {
        // a member whose SerialName were dropped would serialise camelCase: every element name must be snake_case
        for ((name, ser) in WireDtoNames.serializers) for (i in 0 until ser.descriptor.elementsCount) {
            assertTrue(Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*").matches(ser.descriptor.getElementName(i))) { "$name.${ser.descriptor.getElementName(i)}" }
        }
    }

    private val request = Json { explicitNulls = false; encodeDefaults = false }
    private val response = Json { ignoreUnknownKeys = true }

    @Test
    fun requestsOmitNullsAndResponsesIgnoreUnknownKeys() {
        val text = request.encodeToString(LoginRequest.serializer(), LoginRequest("u", "p", "app_sr", null))
        assertEquals("""{"username":"u","password":"p","client":"app_sr"}""", text)
        val back = response.decodeFromString(
            LogoutRequest.serializer(), """{"scope":"all","refresh_token":null,"brand_new_field":{"a":1}}""",
        )
        assertEquals(LogoutRequest(scope = "all", refreshToken = null), back)
    }

    @Test
    fun serialNameOfEveryElementIsDeclared() {
        // guards against hand edits of the generated file: each DTO property carries an explicit @SerialName
        val src = File(System.getProperty("aron.slices")).resolve("../../../shared/contract/src/commonMain/kotlin/com/aktcl/aron/contract/WireDtos.kt")
            .canonicalFile.readText()
        val props = Regex("""^\s+(?:@SerialName\("[^"]+"\) )?val \S+:""", RegexOption.MULTILINE).findAll(src).toList()
        assertTrue(props.isNotEmpty())
        assertTrue(props.all { it.value.contains("@SerialName") }, "every DTO member needs @SerialName")
        assertTrue(src.contains(SerialName::class.simpleName!!))
    }
}

/** Serializers of every wire DTO, keyed by the contract schema name. */
internal object WireDtoNames {
    val serializers: Map<String, KSerializer<*>> = mapOf(
        "LoginRequest" to serializer<LoginRequest>(), "LoginResponse" to serializer<LoginResponse>(),
        "UserSummary" to serializer<UserSummary>(), "ScopeSummary" to serializer<ScopeSummary>(),
        "NodeRef" to serializer<NodeRef>(), "LoginDevice" to serializer<LoginDevice>(),
        "RefreshRequest" to serializer<RefreshRequest>(), "TokenPair" to serializer<TokenPair>(),
        "LogoutRequest" to serializer<LogoutRequest>(), "BundleMeta" to serializer<BundleMeta>(),
        "BundleUser" to serializer<BundleUser>(), "Route" to serializer<Route>(),
        "RouteSnapshot" to serializer<RouteSnapshot>(), "BundleOutlet" to serializer<BundleOutlet>(),
        "Sku" to serializer<Sku>(), "SkuPrice" to serializer<SkuPrice>(), "OpenMemo" to serializer<OpenMemo>(),
        "RecordEnvelope" to serializer<RecordEnvelope>(), "GeoFix" to serializer<GeoFix>(),
        "FixDeviceState" to serializer<FixDeviceState>(), "GnssSummary" to serializer<GnssSummary>(),
        "DeviceGeoVerdict" to serializer<DeviceGeoVerdict>(), "AttendanceEventPayload" to serializer<AttendanceEventPayload>(),
        "StockMovementPayload" to serializer<StockMovementPayload>(), "VisitPayload" to serializer<VisitPayload>(),
        "VisitClosePayload" to serializer<VisitClosePayload>(), "MemoPayload" to serializer<MemoPayload>(),
        "MemoLinePayload" to serializer<MemoLinePayload>(), "MemoDiscountPayload" to serializer<MemoDiscountPayload>(),
        "QcLinePayload" to serializer<QcLinePayload>(), "SyncBatchRequest" to serializer<SyncBatchRequest>(),
        "SyncBatchResponse" to serializer<SyncBatchResponse>(), "RecordAck" to serializer<RecordAck>(),
    )
    val all: Set<String> = serializers.keys
}
