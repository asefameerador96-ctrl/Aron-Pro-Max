package com.aktcl.aron.core.map

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SYS-074: the attendance address resolves online only, with the coordinates (and the last nearby address) as fallback. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AddressResolverTest {
    private val gulshan = 23.7925 to 90.4078
    private var online = true
    private val calls = mutableListOf<Pair<Double, Double>>()
    private var answer: suspend () -> String? = { "Road 11, Gulshan 1, Dhaka" }
    private val backend = GeocodeBackend { lat, lng, _ -> calls += lat to lng; answer() }
    private val store = InMemoryLastAddressStore()
    private fun resolver(timeoutMs: Long = 5_000) = AddressResolver({ online }, backend, { AppLanguage.BN }, store, timeoutMs)

    @Test
    fun offlineNeverCallsTheGeocoder() = runTest {
        online = false
        assertEquals(AddressResult.Fallback(null), resolver().resolve(gulshan.first, gulshan.second))
        assertTrue("offline costs no request", calls.isEmpty())
    }

    @Test
    fun onlineResolvesAndRemembersTheAddress() = runTest {
        assertEquals(AddressResult.Resolved("Road 11, Gulshan 1, Dhaka"), resolver().resolve(gulshan.first, gulshan.second))
        assertEquals(1, calls.size)
        assertEquals("Road 11, Gulshan 1, Dhaka", store.read()!!.text)
    }

    @Test
    fun offlineNearTheLastAddressShowsItButFarAwayDoesNot() = runTest {
        resolver().resolve(gulshan.first, gulshan.second)
        online = false
        // ~110 m north: near; ~2.2 km north: not near.
        assertEquals(AddressResult.Fallback("Road 11, Gulshan 1, Dhaka"), resolver().resolve(gulshan.first + 0.001, gulshan.second))
        assertEquals(AddressResult.Fallback(null), resolver().resolve(gulshan.first + 0.02, gulshan.second))
        assertEquals(1, calls.size)
    }

    @Test
    fun aHangingGeocoderTimesOutToTheFallback() = runTest {
        answer = { awaitCancellation() }
        assertEquals(AddressResult.Fallback(null), resolver(timeoutMs = 3_000).resolve(gulshan.first, gulshan.second))
    }

    @Test
    fun aThrowingOrBlankGeocoderAndAThrowingOnlineCheckFallBack() = runTest {
        answer = { error("grpc failed") }
        assertEquals(AddressResult.Fallback(null), resolver().resolve(gulshan.first, gulshan.second))
        answer = { "   " }
        assertEquals(AddressResult.Fallback(null), resolver().resolve(gulshan.first, gulshan.second))
        val broken = AddressResolver({ error("no connectivity service") }, backend, { AppLanguage.EN }, store)
        calls.clear()
        assertEquals(AddressResult.Fallback(null), broken.resolve(gulshan.first, gulshan.second))
        assertTrue(calls.isEmpty())
        assertNull(store.read())
    }

    @Test
    fun invalidCoordinatesAreNeverSent() = runTest {
        listOf(Double.NaN to 90.0, 91.0 to 90.0, 23.0 to 181.0, 23.0 to Double.POSITIVE_INFINITY).forEach { (lat, lng) ->
            assertEquals(AddressResult.Fallback(null), resolver().resolve(lat, lng))
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun theDisplayTextIsTheAddressOrTheCoordinatesWithTheLastKnownAddress() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("Road 11, Gulshan 1, Dhaka", resolver().displayText(context, gulshan.first, gulshan.second))
        online = false
        val text = resolver().displayText(context, 23.7935, 90.4078)!!
        assertTrue(text, text.startsWith("23.793500, 90.407800") && text.contains("Road 11, Gulshan 1, Dhaka"))
        assertNull(resolver().displayText(context, 22.3569, 91.7832)) // Chattogram: the flow keeps its coordinates
    }

    @Test
    fun theLastAddressSurvivesARelaunchAndIsKeptPerUser() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PrefsLastAddressStore(context, 7).write(KnownAddress(gulshan.first, gulshan.second, "Gulshan"))
        assertEquals(KnownAddress(gulshan.first, gulshan.second, "Gulshan"), PrefsLastAddressStore(context, 7).read())
        assertNull(PrefsLastAddressStore(context, 8).read())
    }
}
