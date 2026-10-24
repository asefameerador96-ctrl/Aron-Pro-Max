package com.aktcl.aron.feature.sale.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-SALE-ZERO-PRICE-01: only a positive unit price makes a SKU offerable on the Sale screen. */
class SaleSkuOfferableTest {
    private fun sku(priceMtk: Long) = SaleSku(100, "MAXDB10", "bidi", "MaxDB 10S", null, "stick", 10, priceMtk, 1, "2026-09-01")

    @Test fun aZeroPriceIsNotOfferable() = assertFalse(sku(0).isOfferable)

    @Test fun aPositivePriceIsOfferable() { assertTrue(sku(1).isOfferable); assertTrue(sku(28_000).isOfferable) }
}
