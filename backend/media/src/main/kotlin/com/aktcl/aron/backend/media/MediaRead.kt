package com.aktcl.aron.backend.media

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Read-only URL for one photo blob (Azure user-delegation SAS in production), valid until `expiresAt`. */
fun interface PhotoReadIssuer {
    fun readSas(blobPath: String, expiresAt: Instant): String
}

/** Where blob storage is not configured (local runs, CI image smoke): 503, as the SAS endpoint. */
val UnconfiguredPhotoReadIssuer = PhotoReadIssuer { _, _ ->
    throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "blob storage is not configured in this environment")
}

/** Contract `MediaReadUrl`. */
@Serializable
data class MediaReadUrl(val url: String, val expires_at: String)

/**
 * GET /v1/media/{media_uuid}/read-url (contract getMediaReadUrl; docs/21 s5.3 and the data table: "SR (own), AMO and
 * TSO in scope"; docs/18: supervisors get a read SAS issued by the API, 5 min). The photo's reach is its captor's:
 * the caller's own photo always; for a field SR only that (`ownRecordsOnly`); for anyone else, national reach, or the
 * captor held a route on the photo's business date that is in the caller's routes or zones, or, for a captor who held no route
 * that date (an AMO's own photos), the captor's home zone is in the caller's zones (an SR who moved zone keeps its old
 * photos with the old zone's supervisors). Reach is the caller's on today's Dhaka date, from the
 * token, never from the request. Order: unknown or voided 404, out of reach 403, blob not stored yet 404. The URL is a
 * bearer secret: it is never logged, and the answer is `no-store`.
 */
internal class MediaRead(private val d: MediaDeps) {
    fun readUrl(p: AronPrincipal, rawUuid: String?): MediaReadUrl {
        val uuid = rawUuid?.takeIf { UUID_V4.matches(it) }
            ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "media_uuid is not a UUID", errors = listOf(FieldError("path.media_uuid", "invalid_value")))
        val reachResolver = d.reach ?: throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "photo reads are not wired")
        data class Row(val owner: Long, val date: LocalDate, val path: String, val status: String)
        val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
        val (row, inReach) = d.db.readJdbi.withHandle<Pair<Row, Boolean>, Exception> { h ->
            val row = h.createQuery("SELECT user_id, business_date, blob_path, status FROM app.media WHERE client_uuid = :u AND voided_at IS NULL")
                .bind("u", UUID.fromString(uuid))
                .map { rs, _ -> Row(rs.getLong(1), rs.getObject(2, LocalDate::class.java), rs.getString(3), rs.getString(4)) }
                .findOne().orElseThrow { ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such photo") }
            if (row.owner == p.userId) return@withHandle row to true
            val reach = reachResolver.reach(p.userId, p.role, p.scopeVersion, today)
            val ok = when {
                reach.ownRecordsOnly -> false
                reach.national -> true
                reach.routeIds.isEmpty() && reach.zoneIds.isEmpty() -> false
                else -> h.createQuery(
                    """
                    SELECT EXISTS (
                             SELECT 1 FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id
                              WHERE a.user_id = :owner AND a.valid_from <= :bd AND (a.valid_to IS NULL OR a.valid_to > :bd)
                                AND (a.route_id = ANY(:routes) OR r.zone_id = ANY(:zones)))
                        OR (NOT EXISTS (SELECT 1 FROM app.route_assignment a
                                         WHERE a.user_id = :owner AND a.valid_from <= :bd AND (a.valid_to IS NULL OR a.valid_to > :bd))
                            AND EXISTS (SELECT 1 FROM app.app_user u WHERE u.id = :owner AND u.home_zone_id = ANY(:zones)))
                    """.trimIndent(),
                ).bind("owner", row.owner).bind("bd", row.date)
                    .bindArray("routes", Long::class.javaObjectType, reach.routeIds.toList())
                    .bindArray("zones", Long::class.javaObjectType, reach.zoneIds.toList())
                    .mapTo(Boolean::class.javaObjectType).one()
            }
            row to ok
        }
        if (!inReach) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "the photo is outside your reach")
        if (row.status != "stored") throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "the photo has not reached storage yet")
        val expires = d.clock.now().plusSeconds(VALID_S)
        val url = try { d.reader.readSas(row.path, expires) } catch (e: ApiProblem) { throw e } catch (e: Exception) {
            throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "photo storage unavailable (${e.javaClass.simpleName})")
        }
        return MediaReadUrl(url, expires.wire())
    }

    private companion object {
        const val VALID_S = 5 * 60L
        val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
