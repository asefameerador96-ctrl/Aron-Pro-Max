package com.aktcl.aron.core.sync.device

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.session.DeviceIdentity
import com.aktcl.aron.core.session.EnrolmentOutcome
import com.aktcl.aron.dpc.enrolment.DeviceFacts
import com.aktcl.aron.dpc.enrolment.DeviceInfoDto
import com.aktcl.aron.dpc.enrolment.EnrolDeviceResponse
import com.aktcl.aron.dpc.enrolment.Enrolment
import com.aktcl.aron.dpc.enrolment.EnrolmentKeys
import com.aktcl.aron.dpc.enrolment.EnrolmentState
import com.aktcl.aron.dpc.enrolment.EnrolmentStore
import com.aktcl.aron.dpc.enrolment.JwkEcPublic
import com.aktcl.aron.dpc.enrolment.ecJwk
import com.aktcl.aron.dpc.policy.DevicePolicies
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Enrolment wiring of the three apps (docs/24 s10.4): the token screen and the QR path against a fake server, the
 * device UUID taking the server's, refusals, and a kill mid-enrolment.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DeviceEnrolmentTest {
    private val server = MockWebServer()
    private val token = "Tk_" + "a".repeat(40)
    private val pkg = "com.aktcl.aron.sr"
    private lateinit var origin: String
    private lateinit var dir: File
    private lateinit var client: AronApiClient

    /** What the fake server answers next (null: enrol and echo the UUID). */
    private var answer: ((RecordedRequest) -> MockResponse)? = null
    private val bodies = mutableListOf<String>()
    private val deviceHeaders = mutableListOf<String?>()

    private class Keys : EnrolmentKeys {
        val present = mutableSetOf<String>()
        var created = 0
        override fun newAlias() = "aron-device-key-${created + 1}"
        override fun exists(alias: String) = alias in present
        override fun create(alias: String, challenge: ByteArray): Pair<JwkEcPublic, List<String>> {
            created++; present += alias
            return ecJwk(x = "x".repeat(43), y = "y".repeat(43)) to listOf("Y2VydA==")
        }
        override fun deleteAllExcept(keep: String) { present.retainAll(setOf(keep)) }
        override fun delete(alias: String) { present -= alias }
    }

    private class Facts(private val owner: Boolean, override val signingCertSha256Hex: String = "0".repeat(64)) : DeviceFacts {
        override val appPackage = "com.aktcl.aron.sr"
        override val appVersion = "1.0.0+1"
        override fun isDeviceOwner() = owner
        override fun deviceInfo() = DeviceInfoDto("samsung", "SM-A065F", 35, "15", "2026-09-01", "arm64-v8a", 4096, 64000)
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("X-Aron-Api", "1").addHeader("Content-Type", "application/json").body(body).build()

    private fun enrolled(uuid: String): MockResponse {
        val policy = DevicePolicies.parse(javaClass.getResource("/fixtures/enrol-policy.json")!!.readText())
        val r = EnrolDeviceResponse(17, uuid, "2026-10-07T05:00:00.000Z", "dev", "normal", policy, "2026-10-07T05:00:00.000Z")
        return json(201, DevicePolicies.json.encodeToString(EnrolDeviceResponse.serializer(), r))
    }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                bodies += body
                deviceHeaders += request.headers["X-Device-Id"]
                if (request.url.encodedPath != "/v1/devices/enrol" || request.method != "POST") return json(404, """{"status":404,"code":"ERR_NOT_FOUND"}""")
                answer?.let { return it(request) }
                return enrolled(Json.parseToJsonElement(body).jsonObject["device_uuid"]!!.jsonPrimitive.content)
            }
        }
        server.start()
        origin = server.url("/").toString().trimEnd('/')
        dir = Files.createTempDirectory("enrol").toFile()
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        client = AronApiClient(ApiOrigin.parse(origin, allowCleartextLoopback = true), ok, ClientIdentity("1.0.0+1") { identity.deviceUuid })
    }

    @After fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private val identity get() = DeviceIdentity(File(dir, "aron"))

    private fun enrolment(keys: Keys = Keys(), owner: Boolean = false, applied: MutableList<String> = mutableListOf(), facts: Facts = Facts(owner)) = DeviceEnrolment(
        EnrolmentStore(File(dir, "dpc")), keys, ApiEnrolmentTransport(client), facts, identity, origin,
    ) { r -> applied += r.policy.lockdownLevel }

    @Test fun aTypedTokenEnrolsThePhoneAndTheServerUuidBecomesTheDeviceIdentity() = runTest {
        val before = identity.deviceUuid
        val applied = mutableListOf<String>()
        val e = enrolment(applied = applied)
        assertFalse(e.enrolled())
        assertEquals(EnrolmentOutcome.Enrolled, e.submit("  $token\n"))
        assertTrue(e.enrolled())
        val sent = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(token, sent["enrolment_token"]!!.jsonPrimitive.content)
        assertEquals("false", sent["device_owner"]!!.jsonPrimitive.content)
        assertEquals("com.aktcl.aron.sr", sent["app_package"]!!.jsonPrimitive.content)
        val uuid = sent["device_uuid"]!!.jsonPrimitive.content
        // The phone keeps the UUID it already sends (a signed-in session stays valid); the server enrolled exactly it.
        assertEquals(before, uuid)
        assertEquals(uuid, identity.deviceUuid)
        assertEquals(uuid, deviceHeaders.single())
        assertTrue(applied.isEmpty()) // not device owner: no policy apply
        assertFalse(dir.walkTopDown().filter { it.isFile }.any { token in it.readText() }) // the token left the phone
        assertEquals(EnrolmentOutcome.Enrolled, e.submit(null)) // idempotent, no second call
        assertEquals(1, bodies.size)
    }

    @Test fun aDeviceOwnerPhoneAppliesThePolicyFromTheResponse() = runTest {
        val applied = mutableListOf<String>()
        assertEquals(EnrolmentOutcome.Enrolled, enrolment(owner = true, applied = applied).submit(token))
        assertEquals(1, applied.size)
    }

    @Test fun theQrStoredByTheProvisioningActivityIsEnrolledInTheSameProcess() = runTest {
        val e = enrolment(owner = true)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        e.install(scope).join() // app start: nothing pending yet
        assertTrue(bodies.isEmpty())
        assertSame(e.coordinator, Enrolment.coordinator(ApplicationProvider.getApplicationContext()))
        // What acceptProvisioningExtras does after the QR, in the process that is already running. (The fake server is
        // http on loopback, which the QR parser refuses, so the validated extras are built here.)
        e.coordinator.accept(com.aktcl.aron.dpc.enrolment.EnrolmentExtras(token, origin, "dev", "sr", "prod", null))
        Enrolment.accepted()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.join() }
        assertTrue(e.enrolled())
        assertEquals(Json.parseToJsonElement(bodies.single()).jsonObject["device_uuid"]!!.jsonPrimitive.content, identity.deviceUuid)
    }

    @Test fun aQrStoredWhileTheAppWasNotRunningIsEnrolledAtTheNextStart() = runTest {
        enrolment(owner = true).coordinator.accept(com.aktcl.aron.dpc.enrolment.EnrolmentExtras(token, origin, "dev", "sr", "prod", null))
        val e = enrolment(owner = true)
        e.install(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)).join()
        assertTrue(e.enrolled())
    }

    @Test fun aRevokedUuidIsDroppedWithTheTokenAndTheNextTokenGetsAFreshUuid() = runTest {
        answer = { json(403, """{"status":403,"code":"ERR_DEVICE_REVOKED"}""") }
        val e = enrolment()
        assertEquals(EnrolmentOutcome.Refused("ERR_DEVICE_REVOKED"), e.submit(token))
        assertFalse(e.pending())
        assertFalse(dir.walkTopDown().filter { it.isFile }.any { token in it.readText() })
        answer = null
        assertEquals(EnrolmentOutcome.Enrolled, e.submit("Tk_" + "c".repeat(40)))
        val first = Json.parseToJsonElement(bodies[0]).jsonObject["device_uuid"]!!.jsonPrimitive.content
        val second = Json.parseToJsonElement(bodies[1]).jsonObject["device_uuid"]!!.jsonPrimitive.content
        assertTrue(first != second)
        assertEquals(second, identity.deviceUuid)
    }

    @Test fun anUnreadableSigningCertificateSendsNothingAndDropsTheToken() = runTest {
        val e = enrolment(facts = Facts(owner = false, signingCertSha256Hex = ""))
        assertEquals(EnrolmentOutcome.Refused(DeviceEnrolment.ERR_APP_SIGNATURE_UNREADABLE), e.submit(token))
        assertTrue(bodies.isEmpty())
        assertFalse(e.pending())
    }

    @Test fun aRefusedTokenIsFinalAndShownWithItsCode() = runTest {
        answer = { json(403, """{"status":403,"code":"ERR_ENROLMENT_TOKEN_EXHAUSTED"}""") }
        val e = enrolment()
        assertEquals(EnrolmentOutcome.Refused("ERR_ENROLMENT_TOKEN_EXHAUSTED"), e.submit(token))
        assertFalse(e.pending())
        assertEquals("ERR_ENROLMENT_TOKEN_EXHAUSTED", e.refusedCode())
        assertFalse(e.enrolled())
        answer = null
        assertEquals(EnrolmentOutcome.Enrolled, e.submit("Tk_" + "b".repeat(40))) // a new token from the portal
        assertNull(e.refusedCode())
    }

    @Test fun aServerErrorOrNoNetworkKeepsTheTokenAndSaysWaiting() = runTest {
        answer = { json(503, """{"status":503,"code":"ERR_SERVICE_UNAVAILABLE"}""") }
        val e = enrolment()
        assertEquals(EnrolmentOutcome.Waiting(offline = false), e.submit(token))
        assertTrue(e.pending())
        server.close()
        assertEquals(EnrolmentOutcome.Waiting(offline = true), e.submit(null))
        assertTrue(e.pending())
    }

    @Test fun aKillMidEnrolmentResumesWithTheSameUuidAndKey() = runTest {
        val keys = Keys()
        answer = { json(502, """{"status":502}""") } // the server's answer is lost
        assertEquals(EnrolmentOutcome.Waiting(offline = false), enrolment(keys).submit(token))
        answer = null
        // The process dies; a new process builds everything from disk.
        val relaunched = enrolment(keys)
        assertTrue(relaunched.pending())
        assertEquals(EnrolmentOutcome.Enrolled, relaunched.submit(null))
        assertEquals(1, keys.created)
        assertEquals(bodies[0], bodies[1]) // the same request: the server recognises a repeat
    }

    @Test fun aKillBetweenTheDoneMarkerAndTheUuidRecordIsRepairedAtStart() = runTest {
        val store = EnrolmentStore(File(dir, "dpc"))
        val serverUuid = "5b0f3c0e-8d4c-4a51-9c63-1f1f2a8e7d10"
        val policy = DevicePolicies.parse(javaClass.getResource("/fixtures/enrol-policy.json")!!.readText())
        store.saveEnrolled(EnrolmentState.Enrolled(serverUuid, 17, "aron-device-key-1"), EnrolDeviceResponse(17, serverUuid, "t", "dev", "normal", policy, "t"))
        assertTrue(identity.deviceUuid != serverUuid)
        enrolment().install(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)).join()
        assertEquals(serverUuid, identity.deviceUuid)
    }

    @Test fun aQrForAnotherAppOrGarbageIsRefusedBeforeAnyCall() = runTest {
        assertEquals(EnrolmentOutcome.Unreadable("other_server"), enrolment().submit(qr("https://api.other.example")))
        assertEquals(EnrolmentOutcome.Unreadable("flavour_mismatch"), enrolment().submit(qr("https://api.other.example", flavour = "tso")))
        assertEquals(EnrolmentOutcome.Unreadable("token_malformed"), enrolment().submit("12345"))
        assertEquals(EnrolmentOutcome.Unreadable("qr_unreadable"), enrolment().submit("{not json"))
        assertTrue(bodies.isEmpty())
    }

    @Test fun aBareTokenAndTheQrTextAreBothRead() {
        val https = "https://api.aron.example"
        val fromQr = DeviceEnrolment.readInput(qr(https), pkg, https) as DeviceEnrolment.Input.Ok
        assertEquals(token, fromQr.extras.enrolmentToken)
        val prodQr = qr(https).replace("\"aron.lockdown_level\":\"dev\"", "\"aron.lockdown_level\":\"prod\"")
        assertEquals("prod", (DeviceEnrolment.readInput(prodQr, pkg, https) as DeviceEnrolment.Input.Ok).extras.lockdownLevel)
        val bare = DeviceEnrolment.readInput(token.chunked(10).joinToString(" "), pkg, "$https/") as DeviceEnrolment.Input.Ok
        assertEquals(token, bare.extras.enrolmentToken)
        assertEquals(https, bare.extras.apiBaseUrl)
        assertEquals(DeviceEnrolment.Input.Bad("other_server"), DeviceEnrolment.readInput(qr("https://api.other.example"), pkg, https))
        assertEquals("sr", bare.extras.flavour)
    }

    /** The portal's provisioning QR text (backend DeviceService.createToken). */
    private fun qr(api: String, flavour: String = "sr") = """
        {"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":"com.aktcl.aron.$flavour/com.aktcl.aron.dpc.AronDeviceAdminReceiver",
         "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE":{"aron.enrolment_token":"$token","aron.api_base_url":"$api","aron.env":"dev",
         "aron.flavour":"$flavour","aron.lockdown_level":"dev","aron.zone_code":""}}
    """.trimIndent()
}
