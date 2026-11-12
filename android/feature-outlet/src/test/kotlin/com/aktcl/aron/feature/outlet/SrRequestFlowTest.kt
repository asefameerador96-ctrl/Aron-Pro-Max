package com.aktcl.aron.feature.outlet

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** SRM-B-32 to 45: cluster-first forms with a searchable picker, the close details card, the success dialog, the clusters list and its create dialog. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrRequestFlowTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, content: @androidx.compose.runtime.Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } } }
        compose.waitForIdle()
    }

    private fun outlet(id: Long, name: String, cluster: Long, clusterName: String) = OutletEntity(
        id, 1, "37009$id", name, null, name.lowercase(), "Owner $id", "0171100000$id", 23.78, 90.40, true, null, null, cluster, clusterName, "grocery", null, null,
        "active", "outlet", "outlet", 100, 50, id.toInt(), 0, null, "[]", false,
    )

    private val clusters = listOf(ClusterOption(1, "111 No Road"), ClusterOption(2, "Lakepar"), ClusterOption(3, "Pink City"))
    private val outlets = listOf(outlet(10, "Mojammel Store", 1, "111 No Road"), outlet(11, "Rahim Traders", 2, "Lakepar"), outlet(12, "Alo Store", 2, "Lakepar"))

    @Test fun filterIsCaseInsensitiveOverTheWholeLabel() {
        val items = listOf(PickItem("1", "111 No Road"), PickItem("2", "Lakepar"), PickItem("3", "Pink City Road"))
        assertEquals(listOf("2"), filterPickItems(items, " lake ").map { it.key })
        assertEquals(listOf("1", "3"), filterPickItems(items, "ROAD").map { it.key })
        assertEquals(3, filterPickItems(items, "").size)
        assertTrue(filterPickItems(items, "zzz").isEmpty())
    }

    @Test fun newShopShowsTheClusterCardFirstThenSearchesAndPicks() {
        var form by mutableStateOf(OutletRequestForm(OutletRequestKind.NEW))
        show(AppLanguage.BN) {
            SrOutletRequestContent(form, emptySet(), clusters, outlets, GeoPhotoState(), saving = false, savedDialog = false, onChange = { form = it }, onPickOutlet = {}, onShutter = {}, onSave = {}, onSavedOk = {})
        }
        compose.onNodeWithTag(SearchPickerTags.field(SrRequestTags.CLUSTER)).assertExists()
        compose.onNodeWithText("ক্লাস্টার নির্বাচন করুন").assertExists()
        compose.onNodeWithTag(SearchPickerTags.field(SrRequestTags.CLUSTER)).performClick()
        compose.onNodeWithText("জায়গা সার্চ করুন").assertExists()
        compose.onNodeWithTag(SearchPickerTags.DIALOG_SEARCH).performTextInput("lake")
        compose.onNodeWithTag(SearchPickerTags.item("1")).assertDoesNotExist()
        compose.onNodeWithTag(SearchPickerTags.DIALOG_CLEAR).performClick()
        compose.onNodeWithTag(SearchPickerTags.item("1")).assertExists()
        compose.onNodeWithTag(SearchPickerTags.item("2")).performClick()
        assertEquals(2L, form.clusterId)
        compose.onNodeWithText("Lakepar").assertExists()
    }

    @Test fun closeNarrowsRetailersByClusterAndShowsTheDetailsCardBeforeSave() {
        var form by mutableStateOf(OutletRequestForm(OutletRequestKind.CLOSE))
        show(AppLanguage.BN) {
            SrOutletRequestContent(
                form, emptySet(), clusters, outlets, GeoPhotoState(), saving = false, savedDialog = false, onChange = { form = it },
                onPickOutlet = { form = form.copy(outletId = it.outletId, openDueMtk = it.openDueMtk) }, onShutter = {}, onSave = {}, onSavedOk = {},
            )
        }
        compose.onNodeWithTag(SrRequestTags.DETAILS).assertDoesNotExist()
        compose.onNodeWithTag(SearchPickerTags.field(SrRequestTags.CLUSTER)).performClick()
        compose.onNodeWithTag(SearchPickerTags.item("2")).performClick() // Lakepar
        compose.onNodeWithTag(SearchPickerTags.field(SrRequestTags.RETAILER)).performClick()
        compose.onNodeWithTag(SearchPickerTags.item("10")).assertDoesNotExist() // Mojammel is in 111 No Road
        compose.onNodeWithTag(SearchPickerTags.item("11")).performClick()
        assertEquals(11L, form.outletId)
        compose.onNodeWithTag(SrRequestTags.DETAILS).assertExists()
        compose.onNodeWithText("দোকানের নাম:").assertExists(); compose.onNodeWithText("দোকানের মালিকের নাম:").assertExists(); compose.onNodeWithText("মোবাইল নম্বর:").assertExists()
        compose.onNodeWithText("Owner 11").assertExists()
    }

    @Test fun saveIsOffWhileSavingSoADoubleTapFilesOneRequestAndSuccessShowsBeforeClosing() {
        var saves = 0; var ok = 0
        var saving by mutableStateOf(false); var dialog by mutableStateOf(false)
        show(AppLanguage.EN) {
            SrOutletRequestContent(
                OutletRequestForm(OutletRequestKind.NEW, clusterId = 1), emptySet(), clusters, outlets, GeoPhotoState(), saving = saving, savedDialog = dialog,
                onChange = {}, onPickOutlet = {}, onShutter = {}, onSave = { saves++; saving = true; dialog = true }, onSavedOk = { ok++ },
            )
        }
        compose.onNodeWithTag(RequestTags.SAVE).assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RequestTags.SAVE).assertIsNotEnabled()
        assertEquals(1, saves)
        assertEquals("the form has not closed yet", 0, ok)
        compose.onNodeWithText("Saved. The request is pending verification.").assertExists()
        compose.onNodeWithText("OK").performClick()
        assertEquals(1, ok)
    }

    @Test fun changeInformationStartsOnTheOutletDropDownAndShowsThePrefilledCluster() {
        var form by mutableStateOf(OutletRequestForm(OutletRequestKind.INFO))
        show(AppLanguage.EN) {
            SrOutletRequestContent(
                form, emptySet(), clusters, outlets, GeoPhotoState(), saving = false, savedDialog = false, onChange = { form = it },
                onPickOutlet = { o -> form = OutletRequestForm(OutletRequestKind.INFO, outletId = o.outletId, clusterId = o.clusterId, currentClusterId = o.clusterId, name = o.name, ownerName = o.ownerName, mobile = o.contactNumber) },
                onShutter = {}, onSave = {}, onSavedOk = {},
            )
        }
        compose.onNodeWithTag(SearchPickerTags.field(SrRequestTags.CLUSTER)).assertDoesNotExist() // nothing until a shop is chosen
        compose.onNodeWithTag(SrRequestTags.ALPHA).assertExists()
        compose.onNodeWithTag(SrRequestTags.letter("A")).performClick() // the alphabet bar narrows the drop-down
        compose.onNodeWithTag(SearchPickerTags.field(SrRequestTags.OUTLET)).performClick()
        compose.onNodeWithTag(SearchPickerTags.item("11")).assertDoesNotExist()
        compose.onNodeWithTag(SearchPickerTags.item("12")).performClick() // Alo Store
        compose.onNodeWithTag(SearchPickerTags.field(SrRequestTags.CLUSTER)).assertExists()
        assertTrue(compose.onAllNodesWithText("Lakepar").fetchSemanticsNodes().isNotEmpty()) // the prefilled cluster
        compose.onNodeWithTag(RequestTags.NAME).assertExists()
    }

    @Test fun clustersListShowsNameAndRouteAndCreateStoresOneRecordPerOpening() {
        val stored = mutableListOf<Pair<String, String>>()
        val existing = listOf(SrCluster(1, null, "111 No Road", "Agora GoliDaily", false), SrCluster(null, "u9", "Old Pending", "Agora GoliDaily", true))
        show(AppLanguage.BN) {
            ClustersContent(existing, onCreate = { name, uuid -> SrClusters.validate(name, existing) ?: run { stored += uuid to name; null } })
        }
        compose.onNodeWithText("111 No Road").assertExists()
        compose.onAllNodesWithText("Route: Agora GoliDaily").assertCountEquals(2)
        compose.onNodeWithText("Waiting to be sent").assertDoesNotExist() // BN app: the pending tag is Bangla
        compose.onNodeWithText("পাঠানোর অপেক্ষায়").assertExists()
        compose.onNodeWithTag(ClustersTags.FAB).performClick()
        compose.onNodeWithTag(ClustersTags.NAME).assertExists()
        compose.onNodeWithTag(ClustersTags.NAME).performTextInput("111 no road") // duplicate (any case) is refused with words
        compose.onNodeWithTag(ClustersTags.SUBMIT).performClick()
        compose.waitForIdle()
        assertTrue(stored.isEmpty())
        compose.onNodeWithText("এই ক্লাস্টার আপনার রুটে আগেই আছে।").assertExists()
        compose.onNodeWithTag(ClustersTags.NAME).performTextInput("2")
        compose.onNodeWithTag(ClustersTags.SUBMIT).performClick()
        assertEquals(1, stored.size)
    }

    @Test fun clusterNamesAreValidatedAndTheListMergesPendingOnes() {
        val real = listOf(SrCluster(1, null, "Lakepar", "R", false))
        assertEquals(ClusterNameError.EMPTY, SrClusters.validate("  ", real))
        assertEquals(ClusterNameError.TOO_SHORT, SrClusters.validate("a", real))
        assertEquals(ClusterNameError.TOO_LONG, SrClusters.validate("x".repeat(81), real))
        assertEquals(ClusterNameError.DUPLICATE, SrClusters.validate(" LAKEPAR ", real))
        assertNull(SrClusters.validate("Pink City", real))
        val listed = SrClusters.list(outlets, "Agora GoliDaily", listOf(PendingCluster("u1", "Pink City", 1, "", "t"), PendingCluster("u2", "lakepar", 1, "", "t")))
        assertEquals(listOf("111 No Road", "Lakepar", "Pink City"), listed.map { it.name }) // a pending twin of a real cluster is not listed twice
        assertTrue(listed.last().pending && listed.last().routeName == "Agora GoliDaily")
        assertEquals("only confirmed clusters can take a new shop", listOf(1L, 2L), SrClusters.selectable(listed).map { it.clusterId })
    }

    @Test fun routeAddCarriesTheClusterAndTheTitleFollowsTheKind() {
        val draft = OutletRequestDraft("00000000-0000-4000-8000-000000000001", OutletRequestKind.ROUTE_ADD, 10, null, null, null, 2, null, "on my way", null, emptyList(), null)
        val fix = FixReading("ok", 23.78, 90.40, 10.0, false)
        val meta = com.aktcl.aron.core.database.entity.CaptureMeta("2026-11-12", "2026-11-12T04:00:00.000Z", 1, 1, 0, true, 1, null, "b", false, 1)
        val entity = draft.toEntities(meta, fix, "00000000-0000-4000-8000-000000000002").first
        assertTrue(entity.proposedJson, entity.proposedJson.contains("\"cluster_id\":2"))
        assertEquals(R.string.out_req_close, srRequestTitle(OutletRequestKind.CLOSE))
        assertEquals(R.string.out_req_new, srRequestTitle(OutletRequestKind.NEW))
        assertEquals(R.string.out_req_info, srRequestTitle(OutletRequestKind.INFO))
    }
}
