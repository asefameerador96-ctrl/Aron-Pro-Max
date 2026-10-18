package com.aktcl.aron.uiscreenshots

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aktcl.aron.core.ui.PackshotProvider
import com.aktcl.aron.core.ui.PackshotSku
import java.io.File

/**
 * The dev pack pictures (android/dev-packshots, PARITY-SPEC s2.6) for the goldens: the same files and index the dev APK
 * bundles, served from memory so the first frame already shows them (deterministic captures). Fixture codes that are not
 * seed codes ("MAXR10" of the sale fixtures, "S1" of the stock fixtures, both "MaxR 10S") alias to the seed code.
 */
object TestPackshots : PackshotProvider {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "android/dev-packshots/dev-packshots") }.firstOrNull { it.isDirectory }
        ?: generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "dev-packshots/dev-packshots") }.first { it.isDirectory }

    private val aliases = mapOf("maxr10" to "maxr-10s", "s1" to "maxr-10s")

    private val index: Map<String, String> by lazy {
        File(root, "index.txt").readLines().map { it.substringBefore('#').trim() }.filter { '=' in it }
            .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
    }

    private val decoded = HashMap<String, Bitmap?>()

    override fun cached(sku: PackshotSku): Bitmap? = synchronized(decoded) {
        val code = sku.code.lowercase().let { aliases[it] ?: it }
        val art = index[code] ?: return null
        decoded.getOrPut(art) { BitmapFactory.decodeFile(File(root, "$art.webp").path) }
    }

    override suspend fun load(sku: PackshotSku): Bitmap? = cached(sku)
}
