package com.aktcl.aron.dpc.policy

import android.os.UserManager
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DevicePolicyContractTest {
    @Suppress("UNCHECKED_CAST")
    private val schemas: Map<String, Any?> by lazy {
        val root = File(System.getProperty("aron.openapi")!!).inputStream().use { Load(LoadSettings.builder().build()).loadFromInputStream(it) } as Map<String, Any?>
        (root["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun props(vararg path: String): Map<String, Any?> =
        (path.fold(schemas as Any?) { acc, k -> (acc as Map<String, Any?>)[k] } as Map<String, Any?>)["properties"] as Map<String, Any?>

    @Test fun theSpecExampleRoundTripsWithEveryContractMember() {
        val p = policyFixture()
        val encoded = Json.parseToJsonElement(p.encode()).jsonObject
        assertEquals(props("DevicePolicy").keys, encoded.keys)
        for (nested in listOf("user_restrictions", "global_settings", "self_protection", "location", "status_report", "integrity")) {
            assertEquals(nested, props("DevicePolicy", "properties", nested).keys, (encoded[nested] as JsonObject).keys)
        }
        assertEquals(props("AppControlPolicy").keys, (encoded["app_control"] as JsonObject).keys)
        assertEquals(props("BlockingSchedule").keys, (encoded["schedule"] as JsonObject).keys)
        assertEquals(p, DevicePolicy.parse(p.encode()))
    }

    @Test fun contractRestrictionsMapToTheUserManagerKeys() {
        val gw = FakeGateway(sdkInt = 36)
        val a = PolicyApplier(gw)
        assertEquals(listOf(UserManager.DISALLOW_DEBUGGING_FEATURES), a.androidKeys("no_debugging_features"))
        assertEquals(listOf(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY), a.androidKeys("no_install_unknown_sources"))
        assertEquals(listOf(UserManager.DISALLOW_INSTALL_APPS), a.androidKeys("no_install_apps"))
        assertEquals(listOf(UserManager.DISALLOW_FACTORY_RESET), a.androidKeys("no_factory_reset"))
        assertEquals(listOf(UserManager.DISALLOW_SAFE_BOOT), a.androidKeys("no_safe_boot"))
        assertEquals(listOf(UserManager.DISALLOW_ADD_USER), a.androidKeys("no_add_user"))
        assertEquals(listOf(UserManager.DISALLOW_CONFIG_DATE_TIME), a.androidKeys("no_config_date_time"))
        assertEquals(listOf(UserManager.DISALLOW_USB_FILE_TRANSFER), a.androidKeys("no_usb_file_transfer"))
        assertEquals(listOf(UserManager.DISALLOW_CONFIG_LOCATION), a.androidKeys("no_config_location"))
        assertEquals(android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED, DpmGateway.GRANT_GRANTED)
        assertEquals(android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED, DpmGateway.GRANT_DENIED)
        assertEquals(android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT, DpmGateway.GRANT_DEFAULT)
    }
}
