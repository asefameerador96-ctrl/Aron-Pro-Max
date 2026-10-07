package com.aktcl.aron.backend.media

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.contract.Role
import io.mockk.mockk
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * F-API-057: one write-only URL per photo, pinned to photos/{date}/{token device}/{media uuid}.jpg, 15 minutes,
 * the same path on a repeat; `already_uploaded` false when the server holds no stored blob; bad items are 400.
 */
class MediaSasTest {
    private val now = Instant.parse("2027-01-03T04:00:00Z")
    private val device = "00000000-0000-4000-8000-000000000001"
    private val phone = AronPrincipal(7, "sr1001", Role.SR, 1, com.aktcl.aron.backend.platform.Audience.API, 3, device, "sr", emptyList(), false, listOf("pwd"), "j", now)
    private val issued = mutableListOf<Triple<String, Long, Instant>>()
    private val sas = PhotoSasIssuer { path, max, until -> issued += Triple(path, max, until); "https://blob.invalid/media/$path?sig=x" }
    private val sha = "a".repeat(64)

    private fun item(uuid: String, bytes: Long = 120_000, s: String = sha) = MediaSasItem(uuid, "outlet_capture", s, bytes, "2027-01-03", "image/jpeg")

    @Test
    fun eachPhotoGetsItsOwnPinnedPathFor15MinutesAndTheSamePathOnARepeat() {
        FreshDb.create().use { fresh ->
            val m = MediaSas(MediaDeps(fresh.db, sas, mockk(relaxed = true), AronClock { now }))
            val a = "11111111-1111-4111-8111-111111111111"; val b = "22222222-2222-4222-8222-222222222222"
            val r = m.grant(phone, MediaSasRequest(listOf(item(a), item(b))))
            assertEquals(listOf("photos/2027-01-03/$device/$a.jpg", "photos/2027-01-03/$device/$b.jpg"), r.items.map { it.blob_path })
            assertEquals(listOf(120_000L, 120_000L), issued.map { it.second }, "each URL is capped at the declared size")
            assertEquals(now.plusSeconds(900), issued.first().third)
            assertEquals("2027-01-03T04:15:00.000Z", r.items.first().expires_at)
            assertEquals(listOf("PUT", "PUT"), r.items.map { it.method })
            assertEquals(listOf(false, false), r.items.map { it.already_uploaded })
            assertEquals(r.items.map { it.blob_path }, m.grant(phone, MediaSasRequest(listOf(item(a), item(b)))).items.map { it.blob_path }, "a repeat gets the same paths")
        }
    }

    @Test
    fun malformedItemsAreRefused() {
        val m = MediaSas(MediaDeps(mockk(relaxed = true), sas, mockk(relaxed = true), AronClock { now }))
        val ok = "11111111-1111-4111-8111-111111111111"
        for (bad in listOf(
            emptyList(), List(11) { item("1111111$it-1111-4111-8111-111111111111".take(36)) }, listOf(item("NOT-A-UUID")),
            listOf(item(ok, bytes = 307_201)), listOf(item(ok, s = "A".repeat(64))), listOf(item(ok), item(ok)),
            listOf(item(ok).copy(mime = "image/png")), listOf(item(ok).copy(purpose = "selfie")), listOf(item(ok).copy(business_date = "2027-1-3")),
        )) {
            val e = assertFailsWith<ApiProblem> { m.grant(phone, MediaSasRequest(bad)) }
            assertEquals("ERR_VALIDATION", e.code.name, bad.toString())
        }
        assertEquals(0, issued.size, "no URL is minted for a refused request")
        assertFailsWith<ApiProblem> { m.grant(phone.copy(deviceUuid = null), MediaSasRequest(listOf(item(ok)))) }
    }
}
