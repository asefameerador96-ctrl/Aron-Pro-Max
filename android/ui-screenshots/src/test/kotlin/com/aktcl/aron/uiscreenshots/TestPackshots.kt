package com.aktcl.aron.uiscreenshots

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aktcl.aron.core.ui.aron.BitmapPackshotProvider
import com.aktcl.aron.core.ui.aron.PackshotProvider
import java.io.File

/**
 * The dev pack pictures (android/dev-packshots, PARITY-SPEC s2.6) for the goldens: the same files and index the dev APK
 * bundles, served from memory so the first frame already shows them (deterministic captures). The fixtures' SKU ids map
 * to seed codes here as the app's local catalogue does: 1 and 100 are "MaxR 10S"; 200..205 (Stock) and 300..305 (Sale)
 * are the six seeded Maxim SKUs (ScreenshotTests.StockPackshots, SalePackshots). Every other id shows the silhouette.
 */
val TestPackshots: PackshotProvider = BitmapPackshotProvider({ id -> DevArt.picture(id) }, { id -> DevArt.picture(id) })

private object DevArt {
    private val codes = mapOf(
        "1" to "MaxR-10S", "100" to "MaxR-10S",
    ) + listOf("MaxR-10S", "MaxR-20S", "MaxB-10S", "MaxB-20S", "MaxWB-10S", "MaxWB-20S").flatMapIndexed { i, code -> listOf("${200 + i}" to code, "${300 + i}" to code) }

    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "android/dev-packshots/dev-packshots") }.firstOrNull { it.isDirectory }
            ?: generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "dev-packshots/dev-packshots") }.first { it.isDirectory }
    }

    private val index: Map<String, String> by lazy {
        File(root, "index.txt").readLines().map { it.substringBefore('#').trim() }.filter { '=' in it }
            .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
    }

    private val decoded = HashMap<String, Bitmap?>()

    fun picture(skuId: String): Bitmap? = synchronized(decoded) {
        val art = codes[skuId]?.let { index[it.lowercase()] } ?: return null
        decoded.getOrPut(art) { BitmapFactory.decodeFile(File(root, "$art.webp").path) }
    }
}
