package com.aktcl.aron.sr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.feature.sale.domain.EditReason
import com.aktcl.aron.feature.sale.domain.QcEntry
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P-SR1b (lead's lows): `beginEdit` on a visit whose sale is QC-locked is refused BEFORE anything is prefilled: the old memo's payment is never written into the locked draft,
 * the draft carries no edit marker, and no stock of the old memo is given back into its catalog. The host then tells the rep (the locked text).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrBeginEditLockedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var f: SrLockFixture
    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher); f = SrLockFixture(context, latePolicy = null) }
    @After fun tearDown() { try { f.close() } finally { Dispatchers.resetMain() } }

    @Test fun anEditOfAMemoIsRefusedWhileTheVisitsSaleIsQcLockedAndNothingIsPrefilled() = runBlocking {
        val v = f.openCall(); val memo = f.creditMemo(v); f.stockedCatalog()
        f.day.visitSession.restore(v); f.day.sale.prepare()
        val flow = f.day.sale.flow
        flow.start(f.day.sale.saleVisit(v)); flow.setQuantity(105, 2, QtyUnit.DOZEN)
        flow.setQc(QcEntry(105, "damaged_crushed", "MFC", 1)); flow.completeQc()
        assertTrue(flow.state.value.draft!!.qcCompleted)
        val before = flow.state.value
        assertFalse("refused", f.day.sale.beginEdit(memo, EditReason.WRONG_SKU))
        assertEquals("no prefill, no payment, no edit marker", before.draft, flow.state.value.draft)
        assertNull(flow.state.value.draft!!.edit)
        assertNull("the old memo's payment is not in the locked draft", flow.state.value.draft!!.paidMtk)
        assertEquals("no give-back in the catalog", before.catalog, flow.state.value.catalog)
    }

    @Test fun anEditOfAMemoOpensWhenTheSaleIsNotLocked() = runBlocking {
        val v = f.openCall(); val memo = f.creditMemo(v); f.stockedCatalog()
        f.day.visitSession.restore(v); f.day.sale.closeCall(); f.day.sale.prepare()
        assertTrue(f.day.sale.beginEdit(memo, EditReason.WRONG_SKU))
        assertEquals(memo, f.day.sale.flow.state.value.draft!!.edit!!.supersedesMemoUuid)
    }
}
