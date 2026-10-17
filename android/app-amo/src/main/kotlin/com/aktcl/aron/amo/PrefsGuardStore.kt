package com.aktcl.aron.amo

import android.content.Context
import com.aktcl.aron.feature.stock.StockLoad

/** Re-save guard of the stock screen kept across a kill and relaunch. */
class PrefsGuardStore(context: Context, userId: Long) : StockLoad.GuardStore {
    private val prefs = context.getSharedPreferences("aron-stock-guard-$userId", Context.MODE_PRIVATE)
    override fun load(): Pair<String, Long>? = prefs.getString("sig", null)?.let { it to prefs.getLong("at", 0L) }
    override fun save(signature: String, atMs: Long) { prefs.edit().putString("sig", signature).putLong("at", atMs).apply() }
}
