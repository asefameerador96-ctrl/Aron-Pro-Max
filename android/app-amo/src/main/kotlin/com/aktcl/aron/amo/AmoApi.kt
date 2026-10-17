package com.aktcl.aron.amo

import android.content.Context
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.Grant

/** What an online read gave: fresh from the server, the last answer kept on this phone, or nothing. */
sealed interface AmoFetch {
    data class Fresh(val json: String) : AmoFetch
    data class Cached(val json: String) : AmoFetch
    data object Unavailable : AmoFetch
}

/**
 * The AMO's online reads (docs/24 s3: the zone's lists that are not in the bundle). Every read keeps its last answer per user on this
 * phone so the screen still shows something offline; nothing here is ever in the way of a sale and no token is logged.
 */
class AmoApi(private val client: AronApiClient, context: Context, userId: Long) {
    private val prefs = context.getSharedPreferences("amo_api_$userId", Context.MODE_PRIVATE)

    suspend fun outletRequest(uuid: String): AmoFetch = get("/v1/outlet-requests/$uuid", "req_$uuid")

    private suspend fun get(path: String, key: String): AmoFetch {
        val r = client.call(path = path, auth = CallAuth.Grant(Grant.FULL), build = { get() }, decode = { body, _ -> body })
        return when (r) {
            is ApiResult.Success -> { remember(key, r.value); AmoFetch.Fresh(r.value) }
            else -> cached(key)
        }
    }

    private fun cached(key: String): AmoFetch = prefs.getString(key, null)?.let { AmoFetch.Cached(it) } ?: AmoFetch.Unavailable

    private fun remember(key: String, json: String) {
        if (prefs.all.size >= MAX_ENTRIES) prefs.edit().clear().apply() // bounded: the lists are re-read when online
        prefs.edit().putString(key, json).apply()
    }

    private companion object { const val MAX_ENTRIES = 300 }
}
