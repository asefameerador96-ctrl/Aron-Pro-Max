package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.VisitEntity

/** Idempotent per memo uuid, like the real counter must be: a retry of one memo gets its number again. */
class FakeNumbers(private val prefix: String = "sr334001-261005") : com.aktcl.aron.feature.sale.domain.MemoNumbers {
    private val byMemo = LinkedHashMap<String, String>()
    var failures = 0
    val consumed: Int get() = byMemo.size
    override suspend fun next(businessDate: String, memoUuid: String): String {
        if (failures > 0) { failures--; error("counter unavailable") }
        return byMemo.getOrPut(memoUuid) { "$prefix-%03d".format(byMemo.size + 1) }
    }
}

object Fx {
    const val DATE = "2026-10-05"
    val maxr = SaleSku(100, "MAXR10", "cigarette", "MaxR 10S", null, "stick", 10, 8_000, 1, "2026-09-01", stockBase = 400, drp = DrpRule(10))
    val aster = SaleSku(103, "ASTER", "lighter", "Aster", null, "piece", 1, 12_500, 1, "2026-09-01", stockBase = 25)
    val match = SaleSku(105, "FB", "match", "Flame Box", null, "dozen", 1, 28_000, 1, "2026-09-01")
    val odd = SaleSku(106, "ODD", "cigarette", "Odd", null, "stick", 20, 7_935, 1, "2026-09-01")
    val catalog = listOf(maxr, aster, match, odd).associateBy { it.skuId }

    fun draft(visit: String = ClientIds.newUuid()) =
        SaleDraft(visitUuid = visit, outletId = 50001, routeId = 10231, businessDate = DATE, memoUuid = ClientIds.newUuid())

    fun meta() = CaptureMeta(DATE, "2026-10-05T04:31:07.120Z", 9_120_331, 41, -1_250, true, 10231, null, "2026-10-05:3", false, 318)

    fun visit(uuid: String): Pair<VisitEntity, GeoFixEntity> {
        val f = GeoFixEntity(
            ClientIds.newUuid(), uuid, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false,
            deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        return VisitEntity(
            uuid, meta(), "sr_call", 50001, "2026-10-05T04:31:07.120Z", 1, true, null, f.clientUuid, "in_range", 18.4, 100, 50, "master",
            23.7937, 90.4042, "sale_allowed",
        ) to f
    }
}
