package com.aktcl.aron.core.database

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File

/** Reads contract/openapi.yaml in tests so fixtures and member names come from the contract, not from memory. */
object ContractYaml {
    @Suppress("UNCHECKED_CAST")
    val root: Map<String, Any?> by lazy {
        val path = System.getProperty("aron.openapi") ?: error("system property aron.openapi is not set")
        File(path).inputStream().use { Load(LoadSettings.builder().build()).loadFromInputStream(it) } as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    fun node(vararg keys: String): Any? = keys.fold(root as Any?) { acc, k -> (acc as? Map<String, Any?>)?.get(k) }

    fun schema(name: String): Map<String, Any?> = map(node("components", "schemas", name)) ?: error("no schema $name")

    /** Property names of a schema, following `allOf` members. */
    fun propertyNames(name: String): Set<String> {
        val s = schema(name)
        val own = map(s["properties"])?.keys ?: emptySet()
        val inherited = (s["allOf"] as? List<*>)?.flatMap { part ->
            val m = map(part) ?: return@flatMap emptyList()
            val ref = m["\$ref"] as? String
            if (ref != null) propertyNames(ref.substringAfterLast('/')) else (map(m["properties"])?.keys ?: emptySet())
        } ?: emptyList()
        return own + inherited
    }

    @Suppress("UNCHECKED_CAST")
    fun requiredNames(name: String): Set<String> = ((schema(name)["required"] as? List<String>) ?: emptyList()).toSet()

    /** A named example of components.examples as JSON text. */
    fun componentExampleJson(name: String): String = toJson(node("components", "examples", name, "value")).toString()

    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is List<*> -> JsonArray(value.map { toJson(it) })
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun map(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>
}
