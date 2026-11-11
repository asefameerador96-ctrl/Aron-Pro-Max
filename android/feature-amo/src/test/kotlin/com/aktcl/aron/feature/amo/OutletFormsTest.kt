package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.outlet.RequestError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A4 amo-outlet rows 46 to 64: the pure lists, the menu, the form body, the dialogs and the Update Base geometry. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OutletFormsTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage = AppLanguage.BN, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language, amo = true) { content() } } }
        compose.waitForIdle()
    }

    private fun outlet(id: Long, name: String, cluster: Long, clusterName: String, phone: String? = null, status: String = "active") = OutletEntity(
        outletId = id, routeId = 1, code = "C$id", name = name, nameBn = null, nameSortKey = com.aktcl.aron.rules.TextRules.nameSortKey(name),
        ownerName = "x", contactNumber = phone, lat = 23.79, lng = 90.40, locationConfirmed = true, provisionalLat = null,
        provisionalLng = null, clusterId = cluster, clusterName = clusterName, channel = "grocery", subChannelId = null, geoClass = null,
        status = status, priceType = "outlet", outletKind = "regular", radiusM = 100, maxAccuracyM = 50, visitSequence = null,
        openDueMtk = 0, openDueAsOf = null, programmeFlagsJson = "[]", pendingRequest = false,
    )

    // ---- 46: menu
    @Test fun menuShowsTheEightTilesAndTheZeroBadgesOfTheVendor() {
        var verify: VerifyGroup? = null; var op: AmoOutletOp? = null
        show { AmoOutletMenuContent(mapOf(VerifyGroup.NEW to 2), { verify = it }, { op = it }) }
        compose.onNodeWithText("এস আর থেকে আসা অনুরোধ গুলো যাচাই করুন").assertExists()
        compose.onNodeWithText("দোকান সংক্রান্ত অপারেশন্স").assertExists()
        compose.onNodeWithText("২").assertExists() // the New badge, locale digits
        compose.onNodeWithTag(OutletMenuTags.badge(VerifyGroup.CLOSE), useUnmergedTree = true).assertExists() // the vendor draws 0 too
        compose.onNodeWithTag(OutletMenuTags.verify(VerifyGroup.CLOSE)).performClick(); assertEquals(VerifyGroup.CLOSE, verify)
        AmoOutletOp.entries.forEach { compose.onNodeWithTag(OutletMenuTags.op(it)).performClick(); assertEquals(it, op) }
    }

    @Test fun verificationGroupsPartitionEveryRequestType() {
        listOf("new", "close", "info", "cluster", "location", "route_add").forEach { t -> assertEquals(1, VerifyGroup.entries.count { it.matches(t) }) }
        assertEquals(VerifyGroup.NEW, VerifyGroup.of("new")); assertEquals(VerifyGroup.CLOSE, VerifyGroup.of("close")); assertEquals(VerifyGroup.CHANGE, VerifyGroup.of("info"))
    }

    // ---- lists
    @Test fun clustersAreDistinctByNameAndLettersAreUpperCaseOnlyThosePresent() {
        val outlets = listOf(outlet(1, "billal Store", 5, "Kacha Bazar"), outlet(2, "Babu Store", 5, "Kacha Bazar"), outlet(3, "Sujon Store", 6, "Madrasa Road"), outlet(4, "nine", 7, "Third Lane"), outlet(5, "closed", 6, "Madrasa Road", status = "closed"))
        assertEquals(listOf("Kacha Bazar", "Madrasa Road", "Third Lane"), AmoOutletLists.clusters(outlets).map { it.label })
        assertEquals(listOf("B", "N", "S"), AmoOutletLists.letters(outlets, null)) // case-insensitive, upper case (R3); the closed shop is not offered
        assertEquals(listOf("B"), AmoOutletLists.letters(outlets, 5))
        assertEquals(2, AmoOutletLists.retailers(outlets, 5, "").size)
        assertEquals(listOf("Babu Store (C2-Kacha Bazar)", "billal Store (C1-Kacha Bazar)").sorted(), AmoOutletLists.retailers(outlets, 5, "B").map { it.label }.sorted())
    }

    @Test fun phonesKeepTheLeadingZero() {
        assertEquals("01624788895", AmoOutletLists.phone("01624788895"))
        assertEquals("01832714554", AmoOutletLists.phone("1832714554")) // R3: the vendor prints 10 digits, ours the full number
        assertEquals(null, AmoOutletLists.phone(null)); assertEquals(null, AmoOutletLists.phone(" "))
    }

    @Test fun sharedValidatorErrorsLandUnderTheirFields() {
        assertEquals(AmoFormError.NAME, AmoOutletLists.fieldError(RequestError.NAME_INVALID))
        assertEquals(AmoFormError.GEO_PHOTO, AmoOutletLists.fieldError(RequestError.MOCK_FIX))
        assertEquals(null, AmoOutletLists.fieldError(RequestError.DUES_BLOCK_CLOSE))
    }

    @Test fun verifyingANewOutletNeedsTheRecapturedGeoAndPhoto() {
        assertEquals(setOf(VerifyError.GEO_PHOTO_REQUIRED), OutletVerify.validate("new", true, 4, "Urban", captured = false))
        assertTrue(OutletVerify.validate("new", false, null, null, captured = false).isEmpty()) // Cancel (reject) needs no capture
        assertTrue(OutletVerify.validate("close", true, null, null, captured = false).isEmpty())
        assertEquals("unknown_route", OutletVerify.decisionProblem("unknown_route")); assertEquals("cluster", OutletVerify.decisionProblem("schema_invalid"))
        assertEquals("scope", OutletVerify.decisionProblem("scope_out_of_reach")); assertEquals("other", OutletVerify.decisionProblem(null)); assertEquals("other", OutletVerify.decisionProblem("something_else"))
        // a changed route needs a cluster pick (the old cluster cannot belong to the new route)
        assertEquals(setOf(VerifyError.CLUSTER_REQUIRED), OutletVerify.validate("info", true, null, null, clusterMissing = true))
        assertTrue(OutletVerify.validate("info", true, null, null, clusterMissing = false).isEmpty())
        assertTrue(OutletVerify.validate("info", false, null, null, clusterMissing = true).isEmpty()) // a reject needs nothing
    }

    // ---- 47 to 61: the form body and the dialogs
    @Test fun formBodyShowsItemsInOrderAndSaveAndCancelCallBack() {
        var saves = 0; var cancels = 0
        show {
            AmoOutletFormBody(
                items = listOf(
                    AmoFormItem.Select("route", null, "একটি রুট সিলেক্ট করুন", listOf(AmoOption(1, "Savar BazarDaily")), 1, {}),
                    AmoFormItem.Field("name", "দোকানের নাম", "Ashraf Store", {}),
                    AmoFormItem.ReadOnly("mobile", "মোবাইল নম্বর", "01772369852"),
                ),
                onSave = { saves++ }, onCancel = { cancels++ },
            )
        }
        compose.onNodeWithText("Savar BazarDaily").assertExists()
        compose.onNodeWithText("দোকানের নাম").assertExists()
        compose.onNodeWithText("মোবাইল নম্বর:").assertExists()
        compose.onNodeWithText("01772369852").assertExists()
        compose.onNodeWithText("সংরক্ষণ").assertExists(); compose.onNodeWithText("বাতিল").assertExists()
        compose.onNodeWithTag(OutletFormTags.SAVE).performClick(); assertEquals(1, saves)
        compose.onNodeWithTag(OutletFormTags.CANCEL).performClick(); assertEquals(1, cancels)
    }

    @Test fun saveStaysOffWhileBusyOrNotEnabled() {
        show { AmoOutletFormBody(items = emptyList(), onSave = {}, saveEnabled = false) }
        compose.onNodeWithTag(OutletFormTags.SAVE).assertIsNotEnabled()
    }

    @Test fun letterBarListsAllThenOnlyThePresentLetters() {
        var picked: String? = null
        show(AppLanguage.EN) { AmoLetterBar(AmoFormItem.Letters(listOf("A", "B", "S"), "") { picked = it }) }
        compose.onNodeWithText("All").assertExists(); compose.onNodeWithTag(OutletFormTags.letter("B")).performClick(); assertEquals("B", picked)
        compose.onNodeWithTag(OutletFormTags.letter("Z")).assertDoesNotExist()
    }

    @Test fun successDialogsShowTheVendorMessageAndOkIsTheOnlyWayOut() {
        var oks = 0
        show { AmoSuccessDialog("ডেটা সফলভাবে সংরক্ষিত হয়েছে।", "ওকে") { oks++ } }
        compose.onNodeWithText("ডেটা সফলভাবে সংরক্ষিত হয়েছে।").assertExists()
        compose.onNodeWithTag(OutletFormTags.OK).assertIsEnabled().performClick(); assertEquals(1, oks)
    }

    @Test fun bothLocalesCarryTheSuccessTextsInTheirOwnLanguage() {
        show(AppLanguage.EN) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.amo_out_updated)) }
        compose.onNodeWithText("Data Updated Successfully").assertExists()
    }

    // ---- 64: Update Base geometry
    @Test fun aDropBeyondTheCapLandsOnTheCircleEdgeAndInsideStaysPut() {
        val lat = 23.7925; val lng = 90.4078
        val far = UpdateBasePoint.move(lat, lng, 300, 400) // 500 m away
        val c = UpdateBasePoint.clamp(lat, lng, far.first, far.second, 100)
        val d = com.aktcl.aron.rules.Geo.haversineM(lat, lng, c.first, c.second)
        assertEquals(100.0, d, 1.0)
        val near = UpdateBasePoint.move(lat, lng, 30, 40)
        assertEquals(near, UpdateBasePoint.clamp(lat, lng, near.first, near.second, 100))
        assertEquals(lat to lng, UpdateBasePoint.clamp(lat, lng, lat, lng, 100))
    }
}

