package com.aktcl.aron.feature.tso

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.localizedDigits
import org.json.JSONObject

/** Zone names by id, read from the scope tree (`GET /v1/admin/geo/zone`, only the zones in the TSO's reach); empty until the read lands. */
val LocalZoneNames = compositionLocalOf<Map<Long, String>> { emptyMap() }

/** The label of a zone: its name, else "Zone <id>" (the read failed or the zone is not in it). */
@Composable
fun zoneName(id: Long): String = LocalZoneNames.current[id] ?: localizedDigits(stringResource(R.string.tso_mc_zone, id.toString()))

/** Reads the `GeoNodePage` of the zone level into id -> name (the Bangla name when [bangla] and the node has one); bad rows are dropped. */
object ZoneNamesJson {
    fun parse(json: String?, bangla: Boolean): Map<Long, String> = runCatching {
        val items = JSONObject(json ?: return emptyMap()).optJSONArray("items") ?: return emptyMap()
        buildMap {
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val id = o.optLong("id", 0L).takeIf { it > 0 } ?: continue
                val name = (if (bangla) o.optString("name_bn", "").takeIf { it.isNotBlank() && !o.isNull("name_bn") } else null) ?: o.optString("name", "").takeIf { it.isNotBlank() } ?: continue
                put(id, name)
            }
        }
    }.getOrDefault(emptyMap())
}
