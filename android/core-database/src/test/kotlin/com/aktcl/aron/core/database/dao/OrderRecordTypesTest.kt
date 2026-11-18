package com.aktcl.aron.core.database.dao

import com.aktcl.aron.contract.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The acknowledgement-order lists are checked against the CONTRACT's record types, never against literals of this file. */
class OrderRecordTypesTest {
    private val wire = RecordType.entries.map { it.wire }.toSet()

    @Test fun theSqlListsAreTheKotlinLists() {
        assertEquals(ORDER_MEMO_RECORD_TYPES.map { "'$it'" }, ORDER_MEMO_RECORD_TYPES_SQL.split(",").map { it.trim() })
        assertEquals(ORDER_PROGRAMME_RECORD_TYPES.map { "'$it'" }, ORDER_PROGRAMME_RECORD_TYPES_SQL.split(",").map { it.trim() })
    }

    @Test fun everyListedTypeIsARealContractRecordType() =
        (ORDER_MEMO_RECORD_TYPES + ORDER_PROGRAMME_RECORD_TYPES).forEach { assertTrue("$it is not a contract record type (RecordType)", it in wire) }

    @Test fun theRulesReadTheseContractTypes() {
        assertEquals(listOf(RecordType.MEMO.wire, RecordType.MEMO_VOID.wire), ORDER_MEMO_RECORD_TYPES)
        assertEquals(listOf(RecordType.REDEMPTION.wire, RecordType.GIFT_CHOICE.wire), ORDER_PROGRAMME_RECORD_TYPES)
    }

    @Test fun everyContractRecordTypeThatVoidsOrIsAMemoOrAProgrammeRecordOfTheRulesIsListed() {
        // a void of any kind takes a record out of the server figure: it must be in the memo order the moment the contract has one
        wire.filter { "void" in it }.forEach { assertTrue("$it must be in ORDER_MEMO_RECORD_TYPES", it in ORDER_MEMO_RECORD_TYPES) }
        // the memo family itself and the record types the points and choice rules read
        listOf(RecordType.MEMO, RecordType.MEMO_VOID).forEach { assertTrue(it.wire in ORDER_MEMO_RECORD_TYPES) }
        listOf(RecordType.REDEMPTION, RecordType.GIFT_CHOICE).forEach { assertTrue(it.wire in ORDER_PROGRAMME_RECORD_TYPES) }
        // there is no `amo_record` WIRE type for a gift choice (the old list used one: the claimed fix did nothing)
        assertTrue("amo_record" !in ORDER_PROGRAMME_RECORD_TYPES)
    }
}
