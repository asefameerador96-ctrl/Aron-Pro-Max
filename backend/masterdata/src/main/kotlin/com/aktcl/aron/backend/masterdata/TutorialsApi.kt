package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/** Contract `TutorialItem`. */
@Serializable
data class TutorialItemDto(val tutorial_id: Long, val kind: String, val title_en: String, val title_bn: String?, val url: String, val bytes: Long?, val duration_s: Int?, val sort: Int)

@Serializable
data class TutorialListDto(val items: List<TutorialItemDto>)

class TutorialsDeps(val db: Database, val blob: BlobSasIssuer, val guard: AuthGuardDeps)

/**
 * F-API-027 `GET /v1/tutorials` (listTutorials): the active videos and manuals published for the caller's role, ordered by
 * `sort`. A role no tutorial is published for gets an empty list; the role comes from the token only.
 */
fun Route.tutorialRoutes(d: TutorialsDeps) {
    authenticated(d.guard) {
        get("/tutorials") {
            val role = call.principal.role.wire
            val items = d.db.jdbi.withHandle<List<TutorialItemDto>, Exception> { h ->
                h.createQuery(
                    "SELECT t.id, t.kind, t.title_en, t.title_bn, t.sort, a.blob_path, a.bytes FROM app.tutorial t JOIN app.admin_asset a ON a.asset_id = t.asset_id " +
                        "WHERE t.status = 'active' AND :role = ANY(t.roles) ORDER BY t.sort, t.id LIMIT 100",
                ).bind("role", role).map { rs, _ ->
                    TutorialItemDto(rs.getLong("id"), rs.getString("kind"), rs.getString("title_en"), rs.getString("title_bn"), d.blob.readUrl(rs.getString("blob_path")), rs.getLong("bytes"), null, rs.getInt("sort"))
                }.list()
            }
            call.respond(TutorialListDto(items))
        }
    }
}
