package com.aktcl.aron.core.printing.template

/**
 * The templates built into the app (version 1), stored as JSON in `src/main/resources/aron/print/` (Java
 * resources, read the same way on the JVM and on the phone). They print when the bundle carries no template
 * for a kind or its template fails validation. Layout follows the on-screen memo (docs/ui-reference/sr/memo.md:
 * items table SKU / quantity / price, discount table SKU / quantity / value, total discount, total QC, grand
 * total) until the sponsor's photographed printout is compared (N-020).
 */
object EmbeddedTemplates {
    const val VERSION = 1

    val KINDS = listOf("cash_memo", "credit_memo", "offer_memo", "drp_memo", "zero_memo", "edited_memo", "stock_slip", "day_summary", "void_slip", "due_receipt")

    private val cache = HashMap<String, String>()

    /** Contract `PrintTemplate.kind` values, mapped to the embedded template JSON that prints them. */
    fun forKind(kind: String): String = synchronized(cache) {
        cache.getOrPut(fileOf(kind)) {
            val name = "/aron/print/${fileOf(kind)}.json"
            EmbeddedTemplates::class.java.getResourceAsStream(name)?.use { String(it.readBytes(), Charsets.UTF_8) }
                ?: error("embedded template $name missing from the build")
        }
    }

    private fun fileOf(kind: String): String = when (kind) {
        "cash_memo", "credit_memo", "offer_memo", "drp_memo", "zero_memo", "edited_memo" -> "memo"
        "stock_slip", "day_summary", "void_slip", "due_receipt" -> kind
        else -> throw IllegalArgumentException("no template kind $kind")
    }
}
