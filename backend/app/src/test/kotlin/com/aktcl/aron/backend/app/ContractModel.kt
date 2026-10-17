package com.aktcl.aron.backend.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonNodePath
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.PathType
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.io.File
import java.util.IdentityHashMap
import java.util.UUID

/**
 * N-014: contract/openapi.yaml (OpenAPI 3.1, so its schemas are JSON Schema 2020-12) as a test oracle. Operations with
 * their parameters, request schema and responses ($ref to components resolved); [validate] checks a JSON instance
 * against any request or response schema of the contract with the networknt validator (format assertions on), refs into
 * components resolved in the contract itself; [sample] builds a small instance of a schema for a request.
 */
class ContractModel private constructor(private val doc: ObjectNode) {
    class Parameter(val name: String, val where: String, val required: Boolean, val schema: JsonNode)
    class Response(val content: Map<String, JsonNode?>)
    class Operation(val method: String, val path: String, val operationId: String, val public: Boolean, val parameters: List<Parameter>, val requestSchema: JsonNode?, val responses: Map<String, Response>) {
        val key: String get() = "$method ${shape(path)}"
    }

    private val operations = LinkedHashMap<String, Operation>()
    private val synthetic = IdentityHashMap<JsonNode, String>()
    private val compiled = HashMap<String, JsonSchema>()
    /** The whole contract as one schema, so every component is compiled once and shared by all the sub-schemas. */
    private lateinit var root: JsonSchema
    private val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()

    init {
        val registry = doc.with("components").with("schemas")
        fun register(schema: JsonNode?) {
            if (schema == null || synthetic.containsKey(schema)) return
            val name = "__conformance_${synthetic.size}"
            synthetic[schema] = name
            registry.set<JsonNode>(name, schema.deepCopy())
        }
        val paths = doc.path("paths")
        for ((path, item) in paths.properties()) {
            val common = item.path("parameters").map(::param)
            for ((m, op) in item.properties()) {
                if (m !in METHODS) continue
                val params = (common + op.path("parameters").map(::param)).associateBy { it.where + ":" + it.name }.values.toList()
                val body = deref(op.path("requestBody")).path("content").let { c -> c.path("application/json").takeIf { !it.isMissingNode } ?: c.elements().asSequence().firstOrNull() }?.get("schema")
                register(body)
                val responses = deref(op.path("responses")).properties().associate { (code, r0) ->
                    val r = deref(r0)
                    code to Response(r.path("content").properties().associate { (type, media) -> type.lowercase() to media.get("schema") }.onEach { register(it.value) })
                }
                val security = op.get("security")
                val public = security != null && (security.isEmpty || security.any { it.isObject && it.isEmpty })
                operations["${m.uppercase()} ${shape(path)}"] = Operation(m.uppercase(), path, op.path("operationId").asText(), public, params, body, responses)
            }
        }
        val text = json.writeValueAsString(doc)
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { b -> b.schemaLoaders { l -> l.schemas(mapOf(BASE to text)) } }
        root = factory.getSchema(SchemaLocation.of(BASE), config)
    }

    fun operation(method: String, path: String): Operation? = operations["${method.uppercase()} ${shape(path)}"]

    val allOperations: Collection<Operation> get() = operations.values

    /** Validation messages of [instance] against [schema] (a request or response schema node of this contract). */
    fun validate(schema: JsonNode, instance: JsonNode): List<String> = messages(schema, instance).map { it.message }

    /**
     * The distinct errors of [instance] as stable keys `<instance path with indices as *> <keyword>[ <property>]`,
     * sorted: a baseline of these fails on any NEW error in an operation already known to deviate.
     */
    fun errorKeys(schema: JsonNode, instance: JsonNode): List<String> = messages(schema, instance).map { m ->
        val path = m.instanceLocation.toString().replace(Regex("/\\d+(?=/|$)"), "/*").ifEmpty { "/" }
        val prop = m.property?.let { " $it" } ?: ""
        "$path ${m.type}$prop"
    }.distinct().sorted()

    private fun messages(schema: JsonNode, instance: JsonNode): List<com.networknt.schema.ValidationMessage> {
        val name = synthetic[schema] ?: error("not a request or response schema of the contract")
        val s = compiled.getOrPut(name) { root.getSubSchema(JsonNodePath(PathType.JSON_POINTER).append("components").append("schemas").append(name)) }
        return s.validate(instance).toList()
    }

    /** Every 2xx JSON response schema of the contract, with its operation key and status. */
    fun successSchemas(): List<Triple<String, String, JsonNode>> = operations.values.flatMap { op ->
        op.responses.filterKeys { it.startsWith("2") }.flatMap { (st, r) -> r.content.filterKeys { it.endsWith("json") }.values.filterNotNull().map { Triple(op.key, st, it) } }
    }

    private fun param(p: JsonNode): Parameter {
        val r = deref(p)
        return Parameter(r.path("name").asText(), r.path("in").asText(), r.path("required").asBoolean(false), r.path("schema"))
    }

    private fun deref(n: JsonNode): JsonNode {
        var cur = n
        repeat(8) {
            val ref = cur.get("\$ref")?.asText() ?: return cur
            cur = doc.at(ref.removePrefix("#"))
        }
        return cur
    }

    // ---- request samples -------------------------------------------------------------------------------------------

    fun sampleText(schema: JsonNode, name: String): String {
        val s = sample(schema)
        return when {
            name == "business_date" || name == "date" -> TODAY
            s.isTextual -> s.asText()
            else -> s.toString()
        }
    }

    /**
     * A small instance of [schema]: required properties only, plus every optional property whose name passes
     * [optional]. Dates and timestamps are the harness clock's day (contract examples are often in its past).
     */
    fun sample(schema: JsonNode, depth: Int = 0, optional: ((String) -> Boolean)? = null): JsonNode {
        val f = JsonNodeFactory.instance
        val s = deref(schema)
        if (depth > 12) return f.nullNode()
        s.get("const")?.let { return it }
        when (s.path("format").asText()) {
            "date" -> return f.textNode(TODAY)
            "date-time" -> return f.textNode(TODAY + "T04:00:00Z")
        }
        s.get("examples")?.takeIf { it.isArray && !it.isEmpty }?.let { return it[0] }
        s.get("example")?.let { return it }
        s.get("default")?.let { return it }
        s.get("enum")?.takeIf { it.isArray && !it.isEmpty }?.let { e -> return e.firstOrNull { !it.isNull } ?: e[0] }
        s.get("allOf")?.let { parts ->
            val merged = f.objectNode()
            parts.forEach { p -> (sample(p, depth + 1, optional) as? ObjectNode)?.let { merged.setAll<JsonNode>(it) } }
            if (s.has("properties") || s.has("required")) merged.setAll<JsonNode>(objectSample(s, depth, optional) as ObjectNode)
            return merged
        }
        (s.get("oneOf") ?: s.get("anyOf"))?.let { alts -> return sample(alts.first { deref(it).path("type").asText() != "null" } ?: alts[0], depth + 1, optional) }
        val type = s.get("type")?.let { t -> if (t.isArray) t.map { it.asText() }.firstOrNull { it != "null" } ?: "null" else t.asText() }
            ?: if (s.has("properties") || s.has("required")) "object" else "string"
        return when (type) {
            "object" -> objectSample(s, depth, optional)
            "array" -> f.arrayNode().also { a -> repeat(maxOf(1, s.path("minItems").asInt(0)).coerceAtMost(maxOf(1, s.path("maxItems").asInt(1)))) { a.add(sample(s.path("items"), depth + 1, optional)) } }
            "integer" -> f.numberNode(bound(s).toLong())
            "number" -> f.numberNode(bound(s))
            "boolean" -> f.booleanNode(false)
            "null" -> f.nullNode()
            else -> f.textNode(stringSample(s))
        }
    }

    private fun bound(s: JsonNode): Double {
        val min = s.get("minimum")?.asDouble() ?: s.get("exclusiveMinimum")?.asDouble()?.plus(1) ?: 1.0
        val max = s.get("maximum")?.asDouble() ?: s.get("exclusiveMaximum")?.asDouble()?.minus(1)
        return if (max != null && min > max) max else min
    }

    private fun objectSample(s: JsonNode, depth: Int, optional: ((String) -> Boolean)?): JsonNode {
        val o = JsonNodeFactory.instance.objectNode()
        val props = s.path("properties")
        val names = s.path("required").map { it.asText() }.toMutableList()
        val minProps = s.path("minProperties").asInt(0)
        props.fieldNames().asSequence().filter { it !in names }.take(maxOf(0, minProps - names.size)).forEach { names += it }
        if (optional != null) props.fieldNames().asSequence().filter { it !in names && optional(it) }.forEach { names += it }
        for (n in names) o.set<JsonNode>(n, props.get(n)?.let { sample(it, depth + 1, optional) } ?: JsonNodeFactory.instance.textNode("x"))
        return o
    }

    private fun stringSample(s: JsonNode): String {
        val min = s.path("minLength").asInt(1).coerceAtLeast(1)
        val max = s.path("maxLength").asInt(Int.MAX_VALUE)
        return when (s.path("format").asText()) {
            "uuid" -> UUID.randomUUID().toString()
            "date" -> TODAY
            "date-time" -> TODAY + "T04:00:00Z"
            "email" -> "qa@example.com"
            "uri" -> "https://example.com/qa"
            else -> s.get("pattern")?.asText()?.let { regexSample(it, min) } ?: "a".repeat(min.coerceAtMost(max))
        }
    }

    /**
     * A shortest string matching [pattern] (the subset the contract uses: anchors, literals, escapes, classes, groups with
     * alternatives, quantifiers), padded by repeating the last repeatable atom up to [minLength]; null when unsure.
     */
    private fun regexSample(pattern: String, minLength: Int): String? {
        var i = 0
        fun cls(): Char { // [..]: the first member
            i++ // [
            val negate = pattern[i] == '^'
            if (negate) i++
            var first: Char? = null
            while (pattern[i] != ']') {
                val c = if (pattern[i] == '\\') { i++; esc(pattern[i]) } else pattern[i]
                if (first == null) first = c
                i++
            }
            i++ // ]
            return if (negate) 'a' else first!!
        }
        lateinit var alt: () -> String
        fun atom(): String = when (val c = pattern[i]) {
            '[' -> cls().toString()
            '(' -> { i++; if (pattern.startsWith("?:", i)) i += 2; alt().also { i++ } }
            '\\' -> { i++; esc(pattern[i++]).toString() }
            '.' -> { i++; "a" }
            else -> { i++; c.toString() }
        }
        fun seq(): String {
            val out = StringBuilder()
            while (i < pattern.length && pattern[i] != '|' && pattern[i] != ')') {
                if (pattern[i] == '^' || pattern[i] == '$') { i++; continue }
                val a = atom()
                val (lo, _) = when {
                    i < pattern.length && pattern[i] == '{' -> { val end = pattern.indexOf('}', i); val q = pattern.substring(i + 1, end).split(','); i = end + 1; q[0].toInt() to q.getOrNull(1) }
                    i < pattern.length && pattern[i] in "*?" -> { i++; 0 to null }
                    i < pattern.length && pattern[i] == '+' -> { i++; 1 to null }
                    else -> 1 to "1"
                }
                repeat(lo) { out.append(a) }
            }
            return out.toString()
        }
        alt = {
            val first = seq()
            while (i < pattern.length && pattern[i] == '|') { i++; seq() }
            first
        }
        val base = runCatching { alt() }.getOrNull() ?: return null
        val rx = Regex(pattern)
        if (!rx.containsMatchIn(base)) return null
        // Pad to the minimum length when the pattern allows it (most patterns end in a repeatable class).
        var padded = base
        while (padded.length < minLength && rx.containsMatchIn(padded + padded.last())) padded += padded.last()
        return padded
    }

    private fun esc(c: Char): Char = when (c) { 'd' -> '0'; 'w' -> 'a'; 's' -> ' '; else -> c }

    companion object {
        const val BASE = "https://aron.invalid/contract/openapi.json"
        /** The harness clock's business date (ApiWorld). */
        const val TODAY = ApiWorld.BUSINESS_DATE
        val json = ObjectMapper()
        private val METHODS = setOf("get", "put", "post", "delete", "patch", "head", "options")

        fun load(file: File): ContractModel {
            val tree = org.snakeyaml.engine.v2.api.Load(org.snakeyaml.engine.v2.api.LoadSettings.builder().setCodePointLimit(64 * 1024 * 1024).build()).loadFromString(file.readText())
            return ContractModel(json.valueToTree(tree))
        }

        fun shape(path: String): String = path.replace(Regex("\\{[^}]*}"), "{}").trimEnd('/').ifEmpty { "/" }
    }
}
