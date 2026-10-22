package com.aktcl.aron.dpc.mdm

import com.aktcl.aron.dpc.blocking.InstalledApp
import com.aktcl.aron.dpc.blocking.SuspendGateway
import com.aktcl.aron.dpc.policy.policyFixture
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdmTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    // ---- schedule ----

    private val weekdays = MdmBlockWindow(listOf(1, 2, 3, 4, 5), "09:00", "18:00")
    private val night = MdmBlockWindow(listOf(5, 6), "22:00", "06:00") // Fri and Sat nights

    @Test fun weekdayWindowBoundariesInDhaka() {
        // 2026-10-07 is a Wednesday. 09:00 Dhaka = 03:00Z.
        assertFalse(MdmWindows.isActive(weekdays, ms("2026-10-07T02:59:00Z")))
        assertTrue(MdmWindows.isActive(weekdays, ms("2026-10-07T03:00:00Z")))
        assertTrue(MdmWindows.isActive(weekdays, ms("2026-10-07T11:59:00Z")))   // 17:59
        assertFalse(MdmWindows.isActive(weekdays, ms("2026-10-07T12:00:00Z")))  // 18:00 excluded
        // Saturday 10:00 Dhaka, not a listed day
        assertFalse(MdmWindows.isActive(weekdays, ms("2026-10-10T04:00:00Z")))
        // UTC Tuesday 20:00 is Wednesday 02:00 Dhaka: not in 09-18
        assertFalse(MdmWindows.isActive(weekdays, ms("2026-10-06T20:00:00Z")))
    }

    @Test fun overnightWindowRunsIntoNextDay() {
        assertFalse(MdmWindows.isActive(night, ms("2026-10-09T15:59:00Z"))) // Fri 21:59 Dhaka
        assertTrue(MdmWindows.isActive(night, ms("2026-10-09T16:00:00Z")))  // Fri 22:00
        assertTrue(MdmWindows.isActive(night, ms("2026-10-09T23:59:00Z")))  // Sat 05:59
        assertFalse(MdmWindows.isActive(night, ms("2026-10-10T00:00:00Z"))) // Sat 06:00
        assertTrue(MdmWindows.isActive(night, ms("2026-10-10T17:00:00Z")))  // Sat 23:00
        assertTrue(MdmWindows.isActive(night, ms("2026-10-10T20:00:00Z")))  // Sun 02:00, tail of Saturday
        assertFalse(MdmWindows.isActive(night, ms("2026-10-11T17:00:00Z"))) // Sun 23:00, Sunday not listed
        assertFalse(MdmWindows.isActive(night, ms("2026-10-08T17:00:00Z"))) // Thu 23:00
    }

    @Test fun nextBoundary() {
        val doc = MdmPolicyDoc(blockWindows = listOf(weekdays))
        assertEquals(ms("2026-10-07T12:00:00Z"), MdmWindows.nextBoundaryMs(doc, ms("2026-10-07T03:30:00Z")))
        // Friday 18:00 ends; next start is Monday 09:00 Dhaka = Mon 03:00Z
        assertEquals(ms("2026-10-12T03:00:00Z"), MdmWindows.nextBoundaryMs(doc, ms("2026-10-09T12:00:00Z")))
        assertNull(MdmWindows.nextBoundaryMs(MdmPolicyDoc(), 0))
    }

    private class Phone : SuspendGateway {
        override val ownPackage = "com.aktcl.aron.sr"
        val suspended = sortedSetOf<String>()
        override fun isDeviceOwner() = true
        override fun setSuspended(packages: List<String>, suspended: Boolean): List<String> { packages.forEach { if (suspended) this.suspended += it else this.suspended -= it }; return emptyList() }
        override fun installed() = listOf(
            InstalledApp("com.facebook.katana", false, true), InstalledApp("com.ludo.king", false, true), InstalledApp("com.whatsapp", false, true),
            InstalledApp("com.aktcl.aron.sr", false, true), InstalledApp("com.android.settings", true, true),
        )
    }

    private class Kiosk : KioskGateway {
        val log = mutableListOf<String>()
        override fun setLockTaskPackages(packages: List<String>) { log += "pk:$packages" }
        override fun startLockTask(pkg: String) { log += "start:$pkg" }
        override fun clearLockTask() { log += "clear" }
    }

    @Test fun applierSuspendsInWindowAndReleasesAfter() {
        val dir = Files.createTempDirectory("mdm").toFile()
        val store = MdmPolicyStore(dir)
        store.save(1, 1, MdmPolicyDoc(allowedPackages = listOf("com.whatsapp"), blockWindows = listOf(weekdays), kiosk = MdmKiosk(true, listOf("com.aktcl.aron.sr"))))
        val phone = Phone(); val kiosk = Kiosk()
        var now = ms("2026-10-07T05:00:00Z")
        val a = MdmPolicyApplier(phone, kiosk, store, dir, { now }, { policyFixture() })
        val o = a.evaluate()
        assertEquals(listOf("com.facebook.katana", "com.ludo.king"), o.suspended) // not whatsapp (allowed), not Aron, not system
        assertEquals(setOf("com.facebook.katana", "com.ludo.king"), phone.suspended)
        assertTrue(o.kioskOn)
        assertEquals(ms("2026-10-07T12:00:00Z"), o.nextBoundaryMs)
        now = ms("2026-10-07T12:00:00Z")
        a.evaluate()
        assertTrue(phone.suspended.isEmpty())
        // a fresh applier over the same files (kill and restart) still releases what an earlier run suspended
        now = ms("2026-10-08T05:00:00Z"); a.evaluate()
        assertEquals(2, phone.suspended.size)
        val b = MdmPolicyApplier(phone, kiosk, store, dir, { ms("2026-10-10T05:00:00Z") }, { null }) // Saturday
        b.evaluate()
        assertTrue(phone.suspended.isEmpty())
    }

    @Test fun applierNeverReleasesWhatAttendanceHolds() {
        val dir = Files.createTempDirectory("mdm").toFile()
        val store = MdmPolicyStore(dir)
        store.save(1, 1, MdmPolicyDoc(blockWindows = listOf(MdmBlockWindow(listOf(3), "09:00", "10:00", listOf("com.ludo.king")))))
        val phone = Phone()
        var now = ms("2026-10-07T03:30:00Z")
        val a = MdmPolicyApplier(phone, Kiosk(), store, dir, { now }, { null }, { setOf("com.ludo.king") })
        a.evaluate(); assertTrue("com.ludo.king" in phone.suspended)
        now = ms("2026-10-07T05:00:00Z"); a.evaluate()
        assertTrue("com.ludo.king" in phone.suspended) // the attendance engine still owns it
    }

    @Test fun locationModeFlag() {
        val dir = Files.createTempDirectory("mdm").toFile()
        val store = MdmPolicyStore(dir)
        val a = MdmPolicyApplier(Phone(), Kiosk(), store, dir, { 0 }, { null })
        assertEquals("on_demand", a.locationMode())
        store.save(1, 1, MdmPolicyDoc(locationSampling = MdmLocationSampling("interval", 30)))
        assertEquals("interval", a.locationMode()); assertEquals(30, a.locationIntervalMin())
    }

    @Test fun storeRefusesOlderVersion() {
        val store = MdmPolicyStore(Files.createTempDirectory("mdm").toFile())
        assertTrue(store.save(7, 3, MdmPolicyDoc()))
        assertFalse(store.save(7, 2, MdmPolicyDoc(allowedPackages = listOf("x.y"))))
        assertTrue(store.save(8, 1, MdmPolicyDoc()))
    }

    // ---- commands ----

    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val verifier = CommandVerifier.fromKey(kp.public)
    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val now0 = ms("2026-10-07T05:00:00Z")

    /** JCA gives DER; JWS wants R||S. */
    private fun sign(text: ByteArray): String {
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(text); sign() }
        var i = 3; val rl = der[i].toInt(); i++
        val r = der.copyOfRange(i, i + rl); i += rl + 1
        val sl = der[i].toInt(); i++
        val s = der.copyOfRange(i, i + sl)
        fun fix(b: ByteArray): ByteArray { val t = b.dropWhile { it == 0.toByte() }.toByteArray(); return ByteArray(32 - t.size) + t }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fix(r) + fix(s))
    }

    private fun cmd(id: String, type: String, params: JsonObject = JsonObject(emptyMap()), expires: String = "2026-10-08T05:00:00Z", forDevice: String = uuid, tamper: Boolean = false): DeviceCommand {
        val c = DeviceCommand(id, type, params, "2026-10-07T04:00:00Z", expires, "x".repeat(86))
        val sig = sign(c.signedText(forDevice))
        return c.copy(type = if (tamper) "reboot" else type, sig = sig)
    }

    private class Api(var items: List<DeviceCommand>) : CommandApi {
        var offline = false; var ackOffline = false
        val acks = mutableListOf<Triple<String, String, JsonObject?>>()
        override fun pull(): List<DeviceCommand> { if (offline) throw java.io.IOException(); return items }
        override fun ack(commandId: String, state: String, result: JsonObject?) { if (ackOffline) throw java.io.IOException(); acks += Triple(commandId, state, result) }
    }

    private class Gw : CommandGateway {
        val log = mutableListOf<String>()
        override fun isDeviceOwner() = true
        override fun lockNow(message: String?) { log += "lock:$message" }
        override fun clearLockMessage() { log += "unlock" }
        override fun showMessage(textBn: String?, textEn: String?) { log += "msg:$textBn|$textEn" }
        override fun locateOnce() = CommandGateway.Fix(23.79, 90.41, 12.0, 1)
        override fun reboot() { log += "reboot" }
        override fun liftFactoryResetRestriction() { log += "lift" }
        override fun wipe() { log += "wipe" }
    }

    private fun proc(api: Api, gw: Gw, dir: java.io.File = Files.createTempDirectory("cmd").toFile(), changed: () -> Unit = {}) =
        CommandProcessor(api, gw, verifier, uuid, CommandLedger(dir), MdmPolicyStore(dir), { now0 }, changed)

    @Test fun executesOnceAndAcks() {
        val c = cmd("a1", "lock", buildJsonObject { put("message", "call office") })
        val api = Api(listOf(c)); val gw = Gw(); val p = proc(api, gw)
        val r = p.sync()
        assertEquals(1, r.executed); assertEquals(listOf("lock:call office"), gw.log)
        assertEquals("acked", api.acks.single().second)
        val r2 = p.sync() // redelivered
        assertEquals(1, r2.duplicates); assertEquals(0, r2.executed); assertEquals(1, gw.log.size)
    }

    @Test fun rejectsBadSignatureWrongDeviceExpiredUnsigned() {
        val gw = Gw()
        val api = Api(listOf(
            cmd("b1", "lock", tamper = true), cmd("b2", "lock", forDevice = "99999999-2222-4333-8444-555555555555"),
            cmd("b3", "lock", expires = "2026-10-07T04:59:00Z"), cmd("b4", "lock").copy(sig = ""),
            cmd("b5", "wipe_all"),
        ))
        val r = proc(api, gw).sync()
        assertEquals(0, r.executed); assertTrue(gw.log.isEmpty()); assertTrue(api.acks.isEmpty())
        assertEquals(listOf("b1:bad_signature", "b2:bad_signature", "b3:expired", "b4:unsigned", "b5:unknown_type"), r.rejected)
    }

    @Test fun ackIsRetriedAfterOffline() {
        val api = Api(listOf(cmd("c1", "locate"))); val gw = Gw()
        val dir = Files.createTempDirectory("cmd").toFile()
        api.ackOffline = true
        val r = proc(api, gw, dir).sync()
        assertEquals(1, r.executed); assertEquals(1, r.acksPending); assertTrue(r.offline)
        api.ackOffline = false; api.items = emptyList()
        // a new processor over the same files (process killed) sends the stored result, does not run again
        val r2 = proc(api, gw, dir).sync()
        assertEquals(0, r2.acksPending)
        val ack = api.acks.single()
        assertEquals("acked", ack.second)
        assertEquals("23.79", ack.third!!["lat"].toString())
        api.offline = true
        assertTrue(proc(api, gw, dir).sync().offline)
    }

    @Test fun applyPolicyStoresAndNotifies() {
        val params = buildJsonObject {
            put("policy_id", 4); put("version", 2)
            putJsonObject("policy") {
                put("allowed_packages", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("com.whatsapp"))))
                put("block_windows", kotlinx.serialization.json.JsonArray(emptyList()))
                putJsonObject("kiosk") { put("enabled", false) }
                putJsonObject("location_sampling") { put("mode", "off") }
            }
        }
        val dir = Files.createTempDirectory("cmd").toFile()
        var n = 0
        val api = Api(listOf(cmd("d1", "apply_policy", params)))
        proc(api, Gw(), dir) { n++ }.sync()
        assertEquals(1, n)
        assertEquals("off", MdmPolicyStore(dir).load()!!.locationSampling.mode)
        assertEquals(4L to 2, MdmPolicyStore(dir).version())
        assertEquals("true", api.acks.single().third!!["applied"].toString())
    }

    @Test fun factoryResetLiftsRestrictionThenWipesAfterAck() {
        val api = Api(listOf(cmd("e1", "factory_reset"))); val gw = Gw()
        proc(api, gw).sync()
        assertEquals(listOf("lift", "wipe"), gw.log)
        assertEquals("acked", api.acks.single().second)
    }

    @Test fun emptyMessageFails() {
        val api = Api(listOf(cmd("f1", "message"))); val gw = Gw()
        proc(api, gw).sync()
        assertEquals("failed", api.acks.single().second)
        assertTrue(gw.log.isEmpty())
    }

    @Test fun nullPolicyIdClearsAndIsIdempotent() {
        val dir = Files.createTempDirectory("cmd").toFile()
        val store = MdmPolicyStore(dir)
        store.save(4, 2, MdmPolicyDoc(blockWindows = listOf(weekdays), kiosk = MdmKiosk(true, listOf("com.aktcl.aron.sr")), locationSampling = MdmLocationSampling("off")))
        val phone = Phone(); val kiosk = Kiosk()
        val applier = MdmPolicyApplier(phone, kiosk, store, dir, { ms("2026-10-07T05:00:00Z") }, { null }, { setOf("com.whatsapp") })
        applier.evaluate()
        assertEquals(3, phone.suspended.size) // whatsapp too: the attendance engine also holds it
        val clear = buildJsonObject { put("policy_id", kotlinx.serialization.json.JsonNull) }
        val api = Api(listOf(cmd("g1", "apply_policy", clear), cmd("g2", "apply_policy", clear)))
        proc(api, Gw(), dir) { applier.evaluate() }.sync()
        assertNull(store.load())
        assertEquals(setOf("com.whatsapp"), phone.suspended)
        assertEquals("clear", kiosk.log.last())
        assertEquals("on_demand", applier.locationMode())
        assertNull(applier.evaluate().nextBoundaryMs)
        assertEquals(listOf("acked", "acked"), api.acks.map { it.second })
        assertEquals("true", api.acks[0].third!!["cleared"].toString())
        assertEquals("true", api.acks[1].third!!["cleared"].toString()) // nothing stored: still acked
    }
}
