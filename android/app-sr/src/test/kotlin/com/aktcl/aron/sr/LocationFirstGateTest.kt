package com.aktcl.aron.sr

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** SR manual 03 (SRM-A-04): the precise-location system prompt comes right after login, once, before the notice and the permission list. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LocationFirstGateTest {
    @get:Rule val compose: ComposeTestRule = hostComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val launched = ArrayList<List<String>>()
    private var scenario: ActivityScenario<ComponentActivity>? = null

    @org.junit.After fun close() { scenario?.close() }

    @Test fun firstAskIsPureAndOnlyForANeverAskedUngrantedPhone() {
        assertTrue(FirstLocationAsk.firstAskNeeded(fineGranted = false, alreadyAsked = false))
        assertFalse(FirstLocationAsk.firstAskNeeded(fineGranted = true, alreadyAsked = false))
        assertFalse(FirstLocationAsk.firstAskNeeded(fineGranted = false, alreadyAsked = true))
        assertFalse(FirstLocationAsk.firstAskNeeded(fineGranted = true, alreadyAsked = true))
    }

    private fun owner(answer: Boolean, hold: Boolean = false) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                @Suppress("UNCHECKED_CAST")
                val names = (input as Array<String>).toList()
                launched += names
                if (hold) return // the sheet is still open
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, names.associateWith { answer } as O)
            }
        }
    }

    private fun setUp(answer: Boolean = false, hold: Boolean = false, granted: Boolean = false, asked: Boolean = false) {
        if (granted) shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        app.getSharedPreferences("aron-permissions", Context.MODE_PRIVATE).edit().putBoolean("PRECISE_LOCATION", asked).commit()
        shadowOf(app.packageManager).addActivityIfNotPresent(android.content.ComponentName(app, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { a ->
            a.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner(answer, hold)) { LocationFirstGate { Text("next-step") } } }
        }
    }

    @Test fun theSheetIsAskedFirstWithPreciseAndApproximateTogetherAndNeverTheMicrophone() {
        setUp(hold = true)
        compose.waitUntil(10_000) { launched.isNotEmpty() }
        assertEquals(listOf(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)), launched)
        compose.onNodeWithText("next-step").assertDoesNotExist() // nothing else shows while the sheet is open
    }

    @Test fun aDenialNeverLocksTheAppAndIsRememberedAsAsked() {
        setUp(answer = false)
        compose.waitUntil(10_000) { launched.isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("next-step").assertExists()
        assertTrue(app.getSharedPreferences("aron-permissions", Context.MODE_PRIVATE).getBoolean("PRECISE_LOCATION", false))
        assertEquals(1, launched.size)
    }

    @Test fun aGrantedPhoneSkipsTheSheet() {
        setUp(granted = true)
        compose.waitForIdle()
        assertTrue(launched.isEmpty())
        compose.onNodeWithText("next-step").assertExists()
    }

    @Test fun aPhoneAskedBeforeIsNotAskedAgain() {
        setUp(asked = true)
        compose.waitForIdle()
        assertTrue(launched.isEmpty())
        compose.onNodeWithText("next-step").assertExists()
    }
}
