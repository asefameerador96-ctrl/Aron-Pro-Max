package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.printing.doc.MemoPrint
import com.aktcl.aron.core.printing.flow.CommittedMemo

/** Reads the just-committed memo back as the print model (stored mtk columns only; production: `PrintMapping` over Room rows). */
fun interface MemoPrintSource { suspend fun load(memoUuid: String): MemoPrint }

/**
 * The `commit` step of android-print's `SaveAndPrint` (docs/requests/android-print-integration.md s2): it is the existing
 * memo save (one Room transaction with the outbox) and never depends on the printer. A repeat call after a landed commit
 * returns the same memo (SaleFlow remembers it), so a retry after "save failed" cannot write a second memo.
 */
class SaleCommitStep(
    private val flow: SaleFlow,
    private val source: MemoPrintSource,
    private val editFix: () -> GeoFixEntity? = { null },
) {
    suspend operator fun invoke(): CommittedMemo {
        val done = flow.commit(editFix())
        return CommittedMemo(done.memoUuid, source.load(done.memoUuid))
    }
}
