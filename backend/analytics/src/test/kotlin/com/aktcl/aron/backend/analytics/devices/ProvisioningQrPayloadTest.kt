package com.aktcl.aron.backend.analytics.devices

import com.aktcl.aron.backend.platform.ResponseJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * N-014 (contract conformance): an optional provisioning extra that is not set is left out of the QR payload, in the
 * API answer (ResponseJson) and in `qr_text` (JSON), never sent as null (contract ProvisioningQrPayload: optional, not
 * nullable). A set extra is still sent.
 */
class ProvisioningQrPayloadTest {
    private val bare = ProvisioningQrPayload(
        component = "com.aktcl.aron.sr/com.aktcl.aron.dpc.AronDeviceAdminReceiver", downloadLocation = "https://example.com/aron-sr.apk",
        signatureChecksum = "A".repeat(43), extras = AdminExtras("t".repeat(43), "https://api.example.com", "dev", "sr", "dev"),
    )

    private fun nulls(o: JsonObject): List<String> = o.entries.flatMap { (k, v) -> if (v is JsonNull) listOf(k) else if (v is JsonObject) nulls(v).map { "$k.$it" } else emptyList() }

    @Test
    fun unsetOptionalExtrasAreLeftOutNotNull() {
        for (json in listOf(ResponseJson, JSON)) {
            val o = Json.parseToJsonElement(json.encodeToString(ProvisioningQrPayload.serializer(), bare)).jsonObject
            assertEquals(emptyList(), nulls(o))
            assertFalse("android.app.extra.PROVISIONING_WIFI_SSID" in o)
            assertFalse("aron.zone_code" in o["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]!!.jsonObject)
        }
    }

    @Test
    fun setExtrasAreSent() {
        val wifi = bare.copy(wifiSsid = "Depot", wifiSecurity = "WPA", wifiPassword = "secret-pass", extras = bare.extras.copy(zoneCode = "Z-MIR"))
        val o = Json.parseToJsonElement(JSON.encodeToString(ProvisioningQrPayload.serializer(), wifi)).jsonObject
        assertEquals("\"Depot\"", o["android.app.extra.PROVISIONING_WIFI_SSID"].toString())
        assertTrue("aron.zone_code" in o["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]!!.jsonObject)
    }
}
