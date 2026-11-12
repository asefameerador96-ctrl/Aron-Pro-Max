package com.aktcl.aron.sr

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.feature.outlet.OutletRequestKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * SRM-B-35: a rotation, a language switch or process death before OK keeps the request uuids and the "saved" state, so a re-save cannot
 * double the request. The recreate is the real mechanism: the saveable registry is saved and a fresh composition restores from it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RequestIdentityTest {
    @get:Rule val compose = hostComposeRule()

    @Test fun uuidsAndSavedStateSurviveARecreateAndANewKindIsANewForm() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        var made = 0
        var registry = SaveableStateRegistry(null) { true }
        var generation by mutableStateOf(0)
        var kind by mutableStateOf(OutletRequestKind.INFO)
        lateinit var identity: RequestIdentity
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    key(generation) { // a new generation is a new composition, as after a recreate
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { identity = rememberRequestIdentity(kind) { "uuid-${++made}" } }
                    }
                }
            }
            compose.waitForIdle()
            val request = identity.requestUuid; val cluster = identity.clusterRequestUuidState.value
            assertNotEquals(request, cluster)
            compose.runOnIdle { identity.savedState.value = true } // the save finished, the success dialog is up

            // rotation / language recreate / process death: save the registry, restore a fresh one from it, compose again
            compose.runOnIdle { registry = SaveableStateRegistry(registry.performSave()) { true }; generation++ }
            compose.waitForIdle()
            assertEquals("the request uuid is not minted again", request, identity.requestUuid)
            assertEquals("nor the cluster-change uuid", cluster, identity.clusterRequestUuidState.value)
            assertTrue("the saved state survives, so a re-entry shows saved and not an editable form", identity.savedState.value)

            // another kind is another form: new uuids, not saved
            compose.runOnIdle { kind = OutletRequestKind.CLOSE }
            compose.waitUntil(5_000) { identity.requestUuid != request } // the host rule runs queued frames on waitUntil polls, not on waitForIdle
            assertNotEquals(request, identity.requestUuid)
            assertEquals(false, identity.savedState.value)
        }
    }
}
