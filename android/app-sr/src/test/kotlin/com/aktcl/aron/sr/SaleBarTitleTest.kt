package com.aktcl.aron.sr

import com.aktcl.aron.feature.sale.ui.SaleStage
import org.junit.Assert.assertEquals
import org.junit.Test

/** R1 (lead gate): the SR frame's bar reads "QC" on the tiles, "QC এন্ট্রি" on the entry page and "স্লাইড" on the Slide, and "বিক্রয়" elsewhere. */
class SaleBarTitleTest {
    @Test fun theBarFollowsTheSaleStage() {
        assertEquals(SaleBarTitle.SALE, saleBarTitle(SaleStage.Entry, false))
        assertEquals(SaleBarTitle.SALE, saleBarTitle(SaleStage.Credit, false))
        assertEquals(SaleBarTitle.SALE, saleBarTitle(SaleStage.Review, false))
        assertEquals(SaleBarTitle.SLIDE, saleBarTitle(SaleStage.Slide, false))
        assertEquals(SaleBarTitle.QC, saleBarTitle(SaleStage.Qc, false))
        assertEquals(SaleBarTitle.QC_ENTRY, saleBarTitle(SaleStage.Qc, true))
    }

    // an entry page left open by a stage change must not leak its title onto another stage
    @Test fun theQcEntryTitleIsOnlyForTheQcStage() {
        assertEquals(SaleBarTitle.SALE, saleBarTitle(SaleStage.Review, true))
        assertEquals(SaleBarTitle.SLIDE, saleBarTitle(SaleStage.Slide, true))
    }
}
