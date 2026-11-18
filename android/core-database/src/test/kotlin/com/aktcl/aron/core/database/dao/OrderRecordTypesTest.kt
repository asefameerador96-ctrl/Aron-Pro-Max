package com.aktcl.aron.core.database.dao

import com.aktcl.aron.contract.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderRecordTypesTest {
    @Test fun theSqlListIsTheKotlinList() =
        assertEquals(ORDER_RECORD_TYPES.map { "'$it'" }, ORDER_RECORD_TYPES_SQL.split(",").map { it.trim() })

    @Test fun everyContractRecordTypeThatVoidsAMemoIsInTheOrder() {
        val voids = RecordType.entries.filter { "void" in it.wire }.map { it.wire }
        assertTrue("the contract has a void record type", voids.isNotEmpty())
        voids.forEach { assertTrue("$it must be in ORDER_RECORD_TYPES (an acknowledged void takes its memo out of the server figure)", it in ORDER_RECORD_TYPES) }
    }

    @Test fun memosRedemptionsAndProgrammeRecordsAreThere() =
        listOf("memo", "redemption", "amo_record").forEach { assertTrue(it, it in ORDER_RECORD_TYPES) }
}
