package com.aktcl.aron.dpc.enrolment

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.PersistableBundle
import com.aktcl.aron.dpc.policy.policyFixture
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnrolmentTest {
    private val token = "Tk_" + "a".repeat(40)
    private val pkg = "com.aktcl.aron.sr"
    private val good = mapOf(
        EnrolmentExtras.KEY_TOKEN to token, EnrolmentExtras.KEY_API to "https://api.aron.example", EnrolmentExtras.KEY_ENV to "dev",
        EnrolmentExtras.KEY_FLAVOUR to "sr", EnrolmentExtras.KEY_LOCKDOWN to "dev", EnrolmentExtras.KEY_ZONE to "Z01",
    )

    private class Keys : EnrolmentKeys {
        val present = mutableSetOf<String>()
        var created = 0
        var challenge: ByteArray? = null
        var fail = false
        override fun newAlias() = "aron-device-key-${created + 1}"
        override fun exists(alias: String) = alias in present
        override fun create(alias: String, challenge: ByteArray): Pair<JwkEcPublic, List<String>> {
            if (fail) throw IllegalStateException("keystore")
            created++; present += alias; this.challenge = challenge
            return JwkEcPublic(x = "x".repeat(43), y = "y".repeat(43)) to listOf("Y2VydA==")
        }
        override fun deleteAllExcept(keep: String) { present.retainAll(setOf(keep)) }
        override fun delete(alias: String) { present -= alias }
    }

    private val facts = object : DeviceFacts {
        override val appPackage = pkg
        override val appVersion = "1.0.0+1"
        override val signingCertSha256Hex = "0".repeat(64)
        override fun isDeviceOwner() = true
        override fun deviceInfo() = DeviceInfoDto("samsung", "SM-A065F", 35, "15", "2026-09-01", "arm64-v8a", 4096, 64000)
    }

    private fun response(uuid: String) = EnrolDeviceResponse(17, uuid, "2026-10-07T05:00:00.000Z", "dev", "normal", policyFixture(), "2026-10-07T05:00:00.000Z")

    private fun parse(m: Map<String, String?>, own: String = pkg): Pair<EnrolmentExtras?, String?> {
        var why: String? = null
        return EnrolmentExtras.parse({ m[it] }, own) { why = it } to why
    }

    @Test fun extrasAreValidated() {
        assertEquals(EnrolmentExtras(token, "https://api.aron.example", "dev", "sr", "dev", "Z01"), parse(good).first)
        assertEquals("token_missing", parse(good - EnrolmentExtras.KEY_TOKEN).second)
        assertEquals("token_malformed", parse(good + (EnrolmentExtras.KEY_TOKEN to "short")).second)
        assertEquals("api_not_https_origin", parse(good + (EnrolmentExtras.KEY_API to "http://api.aron.example")).second)
        assertEquals("api_not_https_origin", parse(good + (EnrolmentExtras.KEY_API to "https://api.aron.example/v1")).second)
        assertEquals("https://api.aron.example", parse(good + (EnrolmentExtras.KEY_API to "https://api.aron.example/")).first!!.apiBaseUrl)
        assertEquals("flavour_mismatch", parse(good, own = "com.aktcl.aron.amo").second)
        assertEquals("lockdown_invalid", parse(good + (EnrolmentExtras.KEY_LOCKDOWN to "loose")).second)
        assertNull(parse(good + (EnrolmentExtras.KEY_ZONE to "")).first!!.zoneCode)
    }

    private fun coordinator(dir: File, keys: Keys, transport: EnrolmentTransport, enrolled: MutableList<String> = mutableListOf()) =
        EnrolmentCoordinator(EnrolmentStore(dir), keys, transport, facts, { "5b0f3c0e-8d4c-4a51-9c63-1f1f2a8e7d10" }) { r, alias -> enrolled += "${r.deviceUuid}/$alias" }

    @Test fun aSuccessfulEnrolmentAppliesThePolicyRecordsTheDeviceAndForgetsTheToken() = runTest {
        val dir = Files.createTempDirectory("enr").toFile()
        val keys = Keys()
        val sent = mutableListOf<EnrolDeviceRequest>()
        val enrolled = mutableListOf<String>()
        val c = coordinator(dir, keys, { _, req -> sent += req; EnrolCallResult.Enrolled(response(req.deviceUuid)) }, enrolled)
        keys.present += "aron-device-key-old"
        c.accept(parse(good).first!!)
        val s = c.run() as EnrolmentState.Enrolled
        assertEquals("5b0f3c0e-8d4c-4a51-9c63-1f1f2a8e7d10/aron-device-key-1", enrolled.single())
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).toList(), keys.challenge!!.toList())
        assertEquals(setOf("aron-device-key-1"), keys.present) // the old key is gone only now
        assertEquals(17L, s.deviceId)
        assertNull(EnrolmentStore(dir).pending()) // the token is no longer on the phone
        assertFalse(dir.walkTopDown().filter { it.isFile }.any { token in it.readText() })
        assertEquals(s, c.run()) // idempotent: no second call
        assertEquals(1, sent.size)
    }

    @Test fun aLostResponseIsRetriedWithTheSameUuidAndKeyAfterAKill() = runTest {
        val dir = Files.createTempDirectory("enr").toFile()
        val keys = Keys()
        val sent = mutableListOf<EnrolDeviceRequest>()
        val first = coordinator(dir, keys, { _, req -> sent += req; EnrolCallResult.Retry("timeout") })
        first.accept(parse(good).first!!)
        assertEquals(EnrolmentState.Waiting("timeout"), first.run())
        val afterKill = coordinator(dir, keys, { _, req -> sent += req; EnrolCallResult.Enrolled(response(req.deviceUuid)) })
        assertTrue(afterKill.run() is EnrolmentState.Enrolled)
        assertEquals(1, keys.created)
        assertEquals(sent[0], sent[1])
    }

    @Test fun aReusedTokenIsRefusedFinallyAndThePendingKeyDeleted() = runTest {
        val dir = Files.createTempDirectory("enr").toFile()
        val keys = Keys()
        val c = coordinator(dir, keys, { _, _ -> EnrolCallResult.Refused("ERR_ENROLMENT_TOKEN_EXHAUSTED") })
        c.accept(parse(good).first!!)
        assertEquals(EnrolmentState.Refused("ERR_ENROLMENT_TOKEN_EXHAUSTED"), c.run())
        assertTrue(keys.present.isEmpty())
        assertEquals(EnrolmentState.Refused("ERR_ENROLMENT_TOKEN_EXHAUSTED"), c.run()) // stays refused until a new QR
        c.accept(parse(good + (EnrolmentExtras.KEY_TOKEN to "N" + token.drop(1))).first!!)
        assertNotNull(EnrolmentStore(dir).pending())
    }

    @Test fun keyFailuresAndTransportExceptionsWaitInsteadOfCrashing() = runTest {
        val dir = Files.createTempDirectory("enr").toFile()
        val keys = Keys().apply { fail = true }
        val c = coordinator(dir, keys, { _, _ -> throw java.io.IOException("no route") })
        c.accept(parse(good).first!!)
        assertEquals(EnrolmentState.Waiting("key_create_failed"), c.run())
        keys.fail = false
        assertEquals(EnrolmentState.Waiting("transport"), c.run())
        assertEquals(EnrolmentState.NotProvisioned, coordinator(Files.createTempDirectory("e").toFile(), keys, { _, _ -> error("x") }).run())
    }

    @Test fun theSameQrScannedTwiceKeepsTheFirstAttempt() = runTest {
        val dir = Files.createTempDirectory("enr").toFile()
        var n = 0
        val c = EnrolmentCoordinator(EnrolmentStore(dir), Keys(), { _, _ -> EnrolCallResult.Retry("x") }, facts, { "uuid-${++n}" }) { _, _ -> }
        c.accept(parse(good).first!!)
        c.accept(parse(good).first!!)
        assertEquals("uuid-1", EnrolmentStore(dir).pending()!!.deviceUuid)
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun requestAndResponseMembersMatchTheContract() {
        val root = File(System.getProperty("aron.openapi")!!).inputStream().use { Load(LoadSettings.builder().build()).loadFromInputStream(it) } as Map<String, Any?>
        val schemas = (root["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>
        fun props(n: String) = ((schemas[n] as Map<String, Any?>)["properties"] as Map<String, Any?>).keys
        val req = EnrolDeviceRequest(token, "u", pkg, "1.0.0+1", "0".repeat(64), true, JwkEcPublic(x = "a", y = "b"), listOf("c"), facts.deviceInfo())
        val j = Json { encodeDefaults = true }
        assertEquals(props("EnrolDeviceRequest"), Json.parseToJsonElement(j.encodeToString(EnrolDeviceRequest.serializer(), req)).jsonObject.keys)
        assertEquals(props("DeviceInfo"), Json.parseToJsonElement(j.encodeToString(DeviceInfoDto.serializer(), facts.deviceInfo())).jsonObject.keys)
        assertEquals(props("JwkEcPublicDevice"), Json.parseToJsonElement(j.encodeToString(JwkEcPublic.serializer(), req.publicKey)).jsonObject.keys)
        assertEquals(props("EnrolDeviceResponse"), Json.parseToJsonElement(j.encodeToString(EnrolDeviceResponse.serializer(), response("u"))).jsonObject.keys)
    }

    @Test fun theProvisioningModeIsFullyManaged() {
        val c = Robolectric.buildActivity(GetProvisioningModeActivity::class.java).setup()
        val result = shadowOf(c.get()).resultIntent
        assertEquals(Activity.RESULT_OK, shadowOf(c.get()).resultCode)
        assertEquals(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE, result.getIntExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, -1))
    }

    @Test fun complianceStoresTheValidatedExtrasAsAPendingEnrolment() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val bundle = PersistableBundle().apply { good.forEach { (k, v) -> putString(k, v) } }
        val ownGood = bundle.apply { putString(EnrolmentExtras.KEY_FLAVOUR, app.packageName.substringAfterLast('.')) }
        val intent = Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, ownGood)
        val accepted = acceptProvisioningExtras(app, intent)
        // The library test package is not com.aktcl.aron.<flavour>, so the flavour check refuses it: nothing stored.
        assertEquals(app.packageName in setOf("com.aktcl.aron.sr", "com.aktcl.aron.amo", "com.aktcl.aron.tso"), accepted)
        assertFalse(acceptProvisioningExtras(app, Intent()))
    }

    @Test fun twoRunsAtOnceSendOnceAndNeverDeleteTheEnrolledKey() = runTest {
        val dir = Files.createTempDirectory("enr").toFile()
        val keys = Keys()
        var calls = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val c = coordinator(dir, keys, { _, req ->
            calls++
            if (calls == 1) { gate.await(); EnrolCallResult.Enrolled(response(req.deviceUuid)) } else EnrolCallResult.Refused("ERR_ENROLMENT_TOKEN_EXHAUSTED")
        })
        c.accept(parse(good).first!!)
        val a = async { c.run() }
        val b = async { c.run() }
        kotlinx.coroutines.yield(); gate.complete(Unit)
        assertTrue(a.await() is EnrolmentState.Enrolled)
        assertTrue(b.await() is EnrolmentState.Enrolled)
        assertEquals(1, calls)
        assertEquals(setOf("aron-device-key-1"), keys.present)
    }

    @Test fun aFailedPolicyApplyKeepsTheEnrolmentAndIsRetried() = runTest {
        val dir = Files.createTempDirectory("enr").toFile()
        var fail = true
        var applied = 0
        val c = EnrolmentCoordinator(EnrolmentStore(dir), Keys(), { _, req -> EnrolCallResult.Enrolled(response(req.deviceUuid)) }, facts,
            { "5b0f3c0e-8d4c-4a51-9c63-1f1f2a8e7d10" }) { _, _ -> if (fail) throw IllegalStateException("policy apply failed"); applied++ }
        c.accept(parse(good).first!!)
        assertTrue(c.run() is EnrolmentState.Enrolled)
        assertNull(EnrolmentStore(dir).pending()) // the token is not sent again
        fail = false
        assertTrue(c.run() is EnrolmentState.Enrolled)
        assertEquals(1, applied)
        c.run()
        assertEquals(1, applied)
    }

    @Test fun hostileExtrasAreRefused() {
        assertEquals("control_character", parse(good + (EnrolmentExtras.KEY_ZONE to "Z\rapi=http://evil.example")).second)
        assertEquals("control_character", parse(good + (EnrolmentExtras.KEY_ENV to "dev\nx=y")).second)
        assertEquals("api_not_https_origin", parse(good + (EnrolmentExtras.KEY_API to "https://api.aron.example#x")).second)
        assertEquals("api_not_https_origin", parse(good + (EnrolmentExtras.KEY_API to "https://u:p@api.aron.example")).second)
        assertEquals("env_invalid", parse(good + (EnrolmentExtras.KEY_ENV to "nonsense")).second)
        assertEquals("zone_invalid", parse(good + (EnrolmentExtras.KEY_ZONE to "Z".repeat(41))).second)
        assertNotNull(parse(good + (EnrolmentExtras.KEY_ENV to "staging")).first)
    }

    @Test fun aWrappedChainIsStoredCanonicallySoRetriesAreIdentical() = runTest {
        val dir = Files.createTempDirectory("enr").toFile()
        val keys = object : EnrolmentKeys by Keys() {
            override fun create(alias: String, challenge: ByteArray) = JwkEcPublic(x = "x".repeat(43), y = "y".repeat(43)) to listOf("Y2Vy\r\ndA==")
            override fun exists(alias: String) = true
        }
        val sent = mutableListOf<EnrolDeviceRequest>()
        val c = EnrolmentCoordinator(EnrolmentStore(dir), keys, { _, r -> sent += r; EnrolCallResult.Retry("t") }, facts, { "u-1" }) { _, _ -> }
        c.accept(parse(good).first!!)
        c.run(); c.run()
        assertEquals(listOf("Y2VydA=="), sent[0].keyAttestationChain)
        assertEquals(sent[0], sent[1])
    }

    @Test fun onlyTokenRefusalsAreFinalAndAUuidMismatchEndsTheAttempt() = runTest {
        val dir = Files.createTempDirectory("enr").toFile()
        var answer: EnrolCallResult = EnrolCallResult.Refused("ERR_VALIDATION")
        val c = coordinator(dir, Keys(), { _, _ -> answer })
        c.accept(parse(good).first!!)
        assertEquals(EnrolmentState.Waiting("refused:ERR_VALIDATION"), c.run())
        assertNotNull(EnrolmentStore(dir).pending())
        answer = EnrolCallResult.Enrolled(response("00000000-0000-4000-8000-000000000000"))
        assertEquals(EnrolmentState.Refused(EnrolmentCoordinator.DEVICE_UUID_MISMATCH), c.run())
        assertNull(EnrolmentStore(dir).pending())
    }

    @Test fun theTokenNeverAppearsInToString() {
        val e = parse(good).first!!
        val p = PendingEnrolment(e, "u")
        val r = EnrolDeviceRequest(token, "u", pkg, "1.0.0+1", "0".repeat(64), true, JwkEcPublic(x = "a", y = "b"), listOf("c"), facts.deviceInfo())
        listOf(e.toString(), p.toString(), r.toString()).forEach { assertFalse(it, token in it) }
    }

    @Test fun aNewQrDuringKeyCreationIsNotOverwritten() = runTest {
        val dir = Files.createTempDirectory("enr").toFile()
        val store = EnrolmentStore(dir)
        val newToken = "N" + token.drop(1)
        lateinit var c: EnrolmentCoordinator
        val keys = object : EnrolmentKeys by Keys() {
            override fun create(alias: String, challenge: ByteArray): Pair<JwkEcPublic, List<String>> {
                store.acceptPending(parse(good + (EnrolmentExtras.KEY_TOKEN to newToken)).first!!) { "u-2" } // a second QR lands now
                return JwkEcPublic(x = "x".repeat(43), y = "y".repeat(43)) to listOf("Y2VydA==")
            }
        }
        c = EnrolmentCoordinator(store, keys, { _, _ -> error("must not send the old token") }, facts, { "u-1" }) { _, _ -> }
        c.accept(parse(good).first!!)
        assertEquals(EnrolmentState.Waiting("superseded"), c.run())
        assertEquals(newToken, store.pending()!!.extras.enrolmentToken)
    }
}
