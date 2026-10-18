package com.aktcl.aron.backend.analytics

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * N-014 (qa contract conformance, section C): `GET /v1/reports` answered `area` "master" for retailer-list and
 * sku-list, outside the contract enum `ReportDefinition.area`. Every registered report uses a contract area, the same
 * one the web catalogue files it under (retailer-list in outlet, sku-list in sales).
 */
class ReportAreaContractTest {
    /** contract/openapi.yaml `ReportDefinition.area`. */
    private val contractAreas = setOf("sales", "field_force", "outlet", "day_control", "geo", "finance", "ops")

    @Test
    fun everyReportAreaIsInTheContractEnum() {
        val outside = ReportHandlers.all.map { it.definition }.filter { it.area !in contractAreas }.map { "${it.report_key}=${it.area}" }
        assertEquals(emptyList(), outside)
    }

    @Test
    fun masterListsUseTheAreaOfTheWebCatalogue() {
        val area = ReportHandlers.all.associate { it.definition.report_key to it.definition.area }
        assertEquals("outlet", area["retailer-list"])
        assertEquals("sales", area["sku-list"])
    }
}
