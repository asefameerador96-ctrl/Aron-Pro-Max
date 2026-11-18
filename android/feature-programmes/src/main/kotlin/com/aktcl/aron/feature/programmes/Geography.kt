package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One level of the user's own zone chain (`user.geography.*`): `{id, code, name, name_bn}`. */
data class GeoNode(val id: Long?, val code: String?, val name: String, val nameBn: String?) {
    fun text(locale: UiLocale): String = if (locale == UiLocale.BN) nameBn?.takeIf { it.isNotBlank() } ?: name else name
}

/** The user's own Wing, Division, Territory and Zone (bundle `user.geography`, once per bundle; null members while the server does not send them). The Astha shop card shows them. */
data class Geography(val wing: GeoNode?, val division: GeoNode?, val territory: GeoNode?, val zone: GeoNode?) {
    companion object {
        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /** From the stored `user` section; null when the section has no `geography` object (the server has not sent it yet): the card then shows a dash. */
        fun parse(userJson: String?): Geography? {
            val u = userJson?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
            val g = u["geography"] as? JsonObject ?: return null
            fun node(k: String): GeoNode? {
                val o = g[k] as? JsonObject ?: return null
                fun str(x: String) = (o[x] as? JsonPrimitive)?.contentOrNull
                return GeoNode((o["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(), str("code"), str("name") ?: return null, str("name_bn"))
            }
            return Geography(node("wing"), node("division"), node("territory"), node("zone"))
        }
    }
}
