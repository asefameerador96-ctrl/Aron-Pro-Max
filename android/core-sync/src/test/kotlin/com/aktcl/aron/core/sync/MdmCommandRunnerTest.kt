package com.aktcl.aron.core.sync

import com.aktcl.aron.core.sync.mdm.MdmCommandHttp
import com.aktcl.aron.core.sync.mdm.MdmCommandRunner
import com.aktcl.aron.core.sync.mdm.ServerKeyStore
import com.aktcl.aron.core.sync.mdm.ServerKeys
import com.aktcl.aron.core.sync.push.PushMessage
import com.aktcl.aron.core.sync.push.PushPullKind
import com.aktcl.aron.dpc.mdm.CommandGateway
import com.aktcl.aron.dpc.mdm.CommandLedger
import com.aktcl.aron.dpc.mdm.CommandProcessor
import com.aktcl.aron.dpc.mdm.DeviceCommand
import com.aktcl.aron.dpc.mdm.MdmPolicyStore
import com.aktcl.aron.dpc.mdm.signedText
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lane mdm app wiring: a queued command is pulled, verified, executed and acked; a rotated server key is refreshed once; `mdm_nudge` asks for the pull. */
class MdmCommandRunnerTest {
    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val now = Instant.parse("2026-10-07T05:00:00Z").toEpochMilli()
    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private fun xy(k: KeyPair): Pair<String, String> {
        val w = (k.public as ECPublicKey).w
        fun c(i: java.math.BigInteger) = b64.encodeToString(i.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it })
        return c(w.affineX) to c(w.affineY)
    }

    private fun signed(k: KeyPair, id: String, type: String): DeviceCommand {
        val c = DeviceCommand(id, type, JsonObject(emptyMap()), "2026-10-07T04:00:00Z", "2026-10-08T05:00:00Z", "x".repeat(86))
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(k.private); update(c.signedText(uuid)); sign() }
        var i = 3; val rl = der[i].toInt(); i++
        val r = der.copyOfRange(i, i + rl); i += rl + 1
        val s = der.copyOfRange(i + 1, i + 1 + der[i].toInt())
        fun fix(b: ByteArray): ByteArray { val t = b.dropWhile { it == 0.toByte() }.toByteArray(); return ByteArray(32 - t.size) + t }
        return c.copy(sig = b64.encodeToString(fix(r) + fix(s)))
    }

    private class Http(var items: List<DeviceCommand>, var keys: ServerKeys?) : MdmCommandHttp {
        val acks = mutableListOf<Pair<String, String>>(); var keyFetches = 0; var offline = false
        override fun pull(): List<DeviceCommand> { if (offline) throw java.io.IOException(); return items }
        override fun ack(commandId: String, state: String, result: JsonObject?) { acks += commandId to state }
        override fun serverKeys(): ServerKeys? { keyFetches++; return keys }
    }

    private class Store(var k: ServerKeys? = null) : ServerKeyStore { override fun load() = k; override fun save(k: ServerKeys) { this.k = k } }

    private class Gw : CommandGateway {
        val log = mutableListOf<String>()
        override fun isDeviceOwner() = true
        override fun lockNow(message: String?) { log += "lock" }
        override fun clearLockMessage() { log += "unlock" }
        override fun showMessage(textBn: String?, textEn: String?) { log += "msg" }
        override fun locateOnce() = CommandGateway.Fix(23.79, 90.41, 12.0, 1)
        override fun reboot() { log += "reboot" }
        override fun liftFactoryResetRestriction() { log += "lift" }
        override fun wipe() { log += "wipe" }
    }

    private fun runner(http: Http, store: Store, gw: Gw, dir: java.io.File = Files.createTempDirectory("mdm").toFile()) =
        MdmCommandRunner(http, store, { uuid }, { api, verifier, u -> CommandProcessor(api, gw, verifier, u, CommandLedger(dir), MdmPolicyStore(dir), { now }) })

    @Test
    fun aQueuedCommandIsPulledExecutedAndAckedOnce() {
        val k = keyPair(); val http = Http(listOf(signed(k, "c1", "lock")), ServerKeys(listOf(xy(k)))); val gw = Gw(); val dir = Files.createTempDirectory("mdm").toFile()
        val r = runner(http, Store(), gw, dir)
        assertTrue(r.run())
        assertEquals(listOf("lock"), gw.log); assertEquals(listOf("c1" to "acked"), http.acks)
        assertFalse(r.run())   // redelivered by the server: nothing runs twice
        assertEquals(listOf("lock"), gw.log)
    }

    @Test
    fun aRotatedServerKeyIsRefreshedOnceAndTheCommandStillRuns() {
        val old = keyPair(); val fresh = keyPair(); val http = Http(listOf(signed(fresh, "c2", "reboot")), ServerKeys(listOf(xy(fresh)))); val gw = Gw()
        val store = Store(ServerKeys(listOf(xy(old))))
        assertTrue(runner(http, store, gw).run())
        assertEquals(listOf("reboot"), gw.log); assertEquals(1, http.keyFetches); assertEquals(ServerKeys(listOf(xy(fresh))), store.k)
    }

    @Test
    fun aForgedCommandNeverRunsAndOfflineIsQuiet() {
        val k = keyPair(); val other = keyPair(); val http = Http(listOf(signed(other, "c3", "lock")), ServerKeys(listOf(xy(k)))); val gw = Gw()
        assertFalse(runner(http, Store(), gw).run()); assertTrue(gw.log.isEmpty()); assertTrue(http.acks.isEmpty())
        http.offline = true
        assertFalse(runner(http, Store(ServerKeys(listOf(xy(k)))), gw).run())
        assertFalse(MdmCommandRunner(Http(emptyList(), null), Store(), { null }, { _, _, _ -> error("no") }).run())   // not enrolled
    }

    @Test
    fun mdmNudgeAsksForTheCommandPullOnly() {
        val m = PushMessage.parse(mapOf("kind" to "mdm_nudge"))!!
        assertEquals(setOf(PushPullKind.MDM), m.pulls); assertEquals(null, m.notice)
    }
}
