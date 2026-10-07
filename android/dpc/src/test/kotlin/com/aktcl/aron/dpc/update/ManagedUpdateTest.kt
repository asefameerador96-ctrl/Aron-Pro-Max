package com.aktcl.aron.dpc.update

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

class ManagedUpdateTest {
    private val apkBytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
    private val cert = "ab".repeat(32)
    private val me = InstalledApp("com.aktcl.aron.sr", "sr", 103, "arm64-v8a", cert)

    private fun release(code: Long = 104, sha256: String = sha, size: Long = apkBytes.size.toLong(), signer: String = cert, url: String = "https://cdn.aron.example/sr-104.apk",
                        flavour: String = "sr", abi: String = "arm64-v8a", status: String = "published") =
        AppReleaseDto(7, flavour, "1.0.4", code, abi, sha256, size, url, signer, status, 100, null, null, "2026-10-07T05:00:00.000Z", null, 1)

    private fun check(r: AppReleaseDto? = release(), available: Boolean = true, wifiOnly: Boolean? = true) =
        UpdateCheckDto(available, false, 100, r, "silent", wifiOnly)

    private class Rig(val dir: File, bytes: ByteArray) {
        var downloads = 0
        var served = bytes
        var installs = mutableListOf<File>()
        var installOk = true
        var wifi = true
        var quiet = true
        val lifts = mutableListOf<Boolean>()
        val updater = ManagedUpdater(dir,
            { _, target -> downloads++; target.writeBytes(served); true },
            { apk, _ -> installs += apk; installOk },
            { wifi }, { quiet }, { lifts += it })
    }

    private fun rig() = Rig(Files.createTempDirectory("upd").toFile(), apkBytes)

    @Test fun aNewerSameSignerReleaseInstallsSilentlyWithTheRestrictionLiftedOnlyForIt() = runTest {
        val r = rig()
        assertEquals(UpdateOutcome.Committed(104), r.updater.run(check(), me))
        assertEquals(1, r.installs.size)
        assertEquals(listOf(true), r.lifts) // restored by the result receiver / MY_PACKAGE_REPLACED
    }

    @Test fun aWrongChecksumIsRefusedAndTheFileDeleted() = runTest {
        val r = rig()
        r.served = apkBytes.copyOf().also { it[100] = (it[100] + 1).toByte() }
        assertEquals(UpdateOutcome.Refused(ManagedUpdater.CHECKSUM_MISMATCH), r.updater.run(check(), me))
        assertTrue(r.installs.isEmpty())
        assertTrue(r.lifts.isEmpty())
        assertTrue(r.dir.listFiles()!!.isEmpty())
    }

    @Test fun aTruncatedDownloadIsRefused() = runTest {
        val r = rig()
        r.served = apkBytes.copyOf(1000)
        assertEquals(UpdateOutcome.Refused(ManagedUpdater.CHECKSUM_MISMATCH), r.updater.run(check(), me))
    }

    @Test fun plannerRefusesWhatCouldNeverBeAnInPlaceUpdate() {
        assertEquals(UpdatePlan.Refused("signer_mismatch"), UpdatePlanner.plan(check(release(signer = "cd".repeat(32))), me))
        assertEquals(UpdatePlan.Refused("flavour_mismatch"), UpdatePlanner.plan(check(release(flavour = "amo")), me))
        assertEquals(UpdatePlan.Refused("abi_mismatch"), UpdatePlanner.plan(check(release(abi = "armeabi-v7a")), me))
        assertEquals(UpdatePlan.Refused("checksum_malformed"), UpdatePlanner.plan(check(release(sha256 = "XYZ")), me))
        assertEquals(UpdatePlan.Refused("url_not_https"), UpdatePlanner.plan(check(release(url = "http://cdn/x.apk")), me))
        assertEquals(UpdatePlan.Refused("size_invalid"), UpdatePlanner.plan(check(release(size = 0)), me))
        assertEquals(UpdatePlan.Refused("not_published"), UpdatePlanner.plan(check(release(status = "blocked")), me))
        assertEquals(UpdatePlan.NoUpdate, UpdatePlanner.plan(check(release(code = 103)), me))
        assertEquals(UpdatePlan.NoUpdate, UpdatePlanner.plan(check(release(code = 90)), me)) // never a downgrade
        assertEquals(UpdatePlan.NoUpdate, UpdatePlanner.plan(check(null), me))
        assertEquals(UpdatePlan.NoUpdate, UpdatePlanner.plan(check(available = false), me))
        assertTrue(UpdatePlanner.plan(check(release(abi = "universal")), me) is UpdatePlan.Install)
    }

    @Test fun waitsForWifiAndForAQuietMomentKeepingTheVerifiedFile() = runTest {
        val r = rig()
        r.wifi = false
        assertEquals(UpdateOutcome.Deferred("waiting_for_wifi"), r.updater.run(check(), me))
        assertEquals(0, r.downloads)
        r.wifi = true; r.quiet = false
        assertEquals(UpdateOutcome.Deferred("busy"), r.updater.run(check(), me))
        r.quiet = true
        assertEquals(UpdateOutcome.Committed(104), r.updater.run(check(), me))
        assertEquals(1, r.downloads) // the verified file was reused
        assertTrue(r.updater.run(check(wifiOnly = false), me.copy(versionCode = 104)) == UpdateOutcome.NothingToDo)
    }

    @Test fun aFailedInstallSessionPutsTheRestrictionBack() = runTest {
        val r = rig()
        r.installOk = false
        assertEquals(UpdateOutcome.Deferred("install_session_failed"), r.updater.run(check(), me))
        assertEquals(listOf(true, false), r.lifts)
    }

    @Test fun aStaleDownloadOfAnotherVersionIsRemoved() = runTest {
        val r = rig()
        File(r.dir.apply { mkdirs() }, "99.apk").writeBytes(byteArrayOf(1, 2, 3))
        r.updater.run(check(), me)
        assertFalse(File(r.dir, "99.apk").exists())
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun dtoMembersMatchTheContract() {
        val root = File(System.getProperty("aron.openapi")!!).inputStream().use { Load(LoadSettings.builder().build()).loadFromInputStream(it) } as Map<String, Any?>
        val schemas = (root["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>
        fun props(n: String) = ((schemas[n] as Map<String, Any?>)["properties"] as Map<String, Any?>).keys
        val j = Json { encodeDefaults = true; explicitNulls = true }
        assertEquals(props("AppRelease"), Json.parseToJsonElement(j.encodeToString(AppReleaseDto.serializer(), release())).jsonObject.keys)
        assertEquals(props("UpdateCheck"), Json.parseToJsonElement(j.encodeToString(UpdateCheckDto.serializer(), check())).jsonObject.keys)
    }
}
