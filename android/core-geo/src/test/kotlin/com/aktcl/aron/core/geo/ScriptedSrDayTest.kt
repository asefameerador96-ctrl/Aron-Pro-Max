package com.aktcl.aron.core.geo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.rules.BusinessDate
import com.aktcl.aron.rules.FixInput
import com.aktcl.aron.rules.GeoAction
import com.aktcl.aron.rules.GeoPolicy
import com.aktcl.aron.rules.GeoVerdicts
import com.aktcl.aron.rules.MockPolicy
import com.aktcl.aron.rules.NoLocationPolicy
import com.aktcl.aron.rules.OutletGeo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * N-021 acceptance on the host: a scripted 60-outlet SR day takes one fix per event (never a stream), stays within the
 * docs/24 s5.7 budget of 80 fixes, and the mock flag of every fix is stored in `geo_fix`. The battery trace on the
 * Galaxy A06 is the device half (docs/status/device-checks.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScriptedSrDayTest {
    private lateinit var db: AronDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun aSixtyOutletDayTakesAtMost80FixesAndStoresTheMockFlagOfEach() = runTest {
        val clock = FakeClock(wallMs = java.time.Instant.parse("2026-10-07T03:00:00Z").toEpochMilli()) // 09:00 Dhaka
        val source = FakeSource(clock, busyMs = 4_000)
        val ledger = MemoryFixLedger()
        val manager = FixManager(source, FakeAccess(), { honestDevice }, clock, ledger)
        val policy = { refreshes: Int -> GeoPolicy(MockPolicy.BLOCK_SALE, NoLocationPolicy.FORCE_SALE_REQUIRED, false, refreshes, 3) }

        // Outlets 100 m apart on a line; the rep stands 20 m from each, except: outlets 7, 19, 33 the first fix lands
        // 180 m away (one refresh fixes it), outlet 41 never gets closer (three refreshes then force sale), and at
        // outlets 12 and 50 a fake-GPS app answers.
        val outlets = (0 until 60).map { i -> 23.7800 + i * 0.0009 to 90.4100 }
        val stored = mutableListOf<Pair<String, TakenFix>>()
        suspend fun fix(purpose: FixPurpose, owner: String, refreshCount: Int = 0, cycle: String? = null): TakenFix {
            val f = manager.take(purpose, cycle, refreshCount)
            val uuid = ClientIds.newUuid()
            db.captureDao().insertFix(f.toEntity(uuid, owner))
            stored += uuid to f
            return f
        }

        fix(FixPurpose.ATTENDANCE_IN, ClientIds.newUuid())
        var outletIndex = 0
        var attempt = 0
        source.next = {
            val (lat, lng) = outlets[outletIndex]
            when {
                outletIndex in setOf(12, 50) -> source.located(lat, lng, accuracy = 5.0, mock = true)
                outletIndex == 41 -> source.located(lat + 0.002, lng, accuracy = 15.0)
                outletIndex in setOf(7, 19, 33) && attempt == 0 -> source.located(lat + 0.0016, lng, accuracy = 25.0)
                else -> source.located(lat + 0.00018, lng, accuracy = 12.0)
            }
        }
        val mockedOutlets = mutableListOf<Int>()
        for (i in outlets.indices) {
            outletIndex = i
            attempt = 0
            clock.advance(5 * 60_000) // walking and selling between outlets
            val visit = ClientIds.newUuid()
            var f = fix(FixPurpose.VISIT_OPEN, visit, cycle = "outlet-list")
            val (olat, olng) = outlets[i]
            fun verdict(t: TakenFix, refreshes: Int) = GeoVerdicts.verdict(
                FixInput(t.isOk, t.lat ?: 0.0, t.lng ?: 0.0, t.accuracyM, t.isMock), OutletGeo(true, olat, olng), 100, 100, policy(refreshes),
            )
            var v = verdict(f, 0)
            while (v.action == GeoAction.REFRESH_OFFERED) {
                attempt++
                f = fix(FixPurpose.REFRESH, ClientIds.newUuid(), refreshCount = attempt, cycle = "outlet-list")
                v = verdict(f, attempt)
            }
            if (f.isMock) mockedOutlets += i
        }
        fix(FixPurpose.ATTENDANCE_OUT, ClientIds.newUuid())

        val day = BusinessDate.of(clock.wallMs).toString()
        assertEquals(source.requests, ledger.fixes(day))
        assertTrue("fixes taken: ${source.requests}", source.requests <= 80)
        assertEquals(2 + 60 + 3 + 3, source.requests) // in, 60 opens, 3 single refreshes, 3 refreshes at outlet 41, out
        assertEquals(listOf(12, 50), mockedOutlets)

        // Every fix is in geo_fix with the mock flag it was taken with.
        assertEquals(stored.size, db.query("SELECT COUNT(*) FROM geo_fix", null).use { it.moveToFirst(); it.getInt(0) })
        for ((uuid, f) in stored) assertEquals(f.isMock, db.captureDao().fix(uuid)!!.isMock)
        assertEquals(2, db.query("SELECT COUNT(*) FROM geo_fix WHERE is_mock = 1", null).use { it.moveToFirst(); it.getInt(0) })
    }
}

/** The capture's mapping of a [TakenFix] to its `geo_fix` row (the field order of the contract `GeoFix`). */
fun TakenFix.toEntity(clientUuid: String, owner: String) = GeoFixEntity(
    clientUuid = clientUuid, ownerClientUuid = owner, purpose = purpose.wire, fixStatus = fixStatus.wire,
    lat = lat, lng = lng, accuracyM = accuracyM, altitudeM = altitudeM, verticalAccuracyM = verticalAccuracyM,
    speedMps = speedMps, bearingDeg = bearingDeg, provider = provider.wire, fixTime = fixTime,
    fixElapsedRealtimeMs = fixElapsedRealtimeMs, fixAgeMs = fixAgeMs, timeToFixMs = timeToFixMs,
    requestPriority = requestPriority.wire, isMock = isMock, reused = reused, refreshCount = refreshCount,
    gnssJson = gnssJson, deviceOwner = device.deviceOwner, devOptionsEnabled = device.devOptionsEnabled,
    adbEnabled = device.adbEnabled, autoTimeEnabled = device.autoTimeEnabled, mockAppPresent = device.mockAppPresent,
    integrityRef = device.integrityRef,
)
