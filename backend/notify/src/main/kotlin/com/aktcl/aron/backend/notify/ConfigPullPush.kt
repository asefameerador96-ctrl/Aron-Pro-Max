package com.aktcl.aron.backend.notify

import com.aktcl.aron.backend.platform.ConfigPullNotifier
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ServerConfig
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.random.Random

/**
 * The `config_pull` push of a late-sale policy change (X4a ask 11, D-TSO-MAN-57): ONE data message per live phone of the
 * affected scope, kind `config_pull`, `urgent` (the phone pulls the CONFIG delta outside its daily cap, spread over 0 to 20 s),
 * with the `reason` for the logs; never business data. Switched by `cfg.ops.push_enabled` like every push. A scope the server
 * cannot place on the geography (a role, a user, a class) widens to everyone: a superset is safe, a miss is not.
 */
class ConfigPullPush(
    private val db: Database,
    private val config: ServerConfig,
    private val push: PushNotifier,
    private val random: Random = Random.Default,
) : ConfigPullNotifier {
    private val log = LoggerFactory.getLogger("aron.push")

    override fun configPull(reason: String, scopes: List<Pair<String, Long>>) {
        if (!runCatching { config.bool("cfg.ops.push_enabled") }.getOrDefault(false)) return
        val id = UUID.randomUUID().toString()
        val tokens = db.jdbi.withHandle<List<Pair<Long, String>>, Exception> { h -> push.liveTokens(h, usersOf(h, scopes)) }
        if (tokens.isEmpty()) return
        // One message per phone token, however many scopes name its user; the broadcast queue is bounded and refuses visibly.
        push.broadcast(tokens) { _ ->
            mapOf("kind" to "config_pull", "reason" to reason, "notification_id" to id, "pull_after_s" to random.nextInt(0, URGENT_MAX_S + 1).toString(), "urgent" to "true")
        }
        log.info("config_pull queued reason=$reason scopes=${scopes.size} tokens=${tokens.size}")
    }

    private fun usersOf(h: Handle, scopes: List<Pair<String, Long>>): Set<Long> {
        if (scopes.any { (type, _) -> type !in GEO }) return all(h)
        if (scopes.any { (type, _) -> type == "global" }) return all(h)
        val users = mutableSetOf<Long>()
        for ((type, sid) in scopes) {
            val where = when (type) {
                "zone" -> "z.id = :id"
                "territory" -> "z.territory_id = :id"
                "division" -> "t.division_id = :id"
                "wing" -> "dv.wing_id = :id"
                "route" -> "z.id = (SELECT zone_id FROM app.route WHERE id = :id)"
                else -> return all(h)
            }
            val zones = h.createQuery("SELECT z.id FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division dv ON dv.id = t.division_id WHERE $where")
                .bind("id", sid).mapTo(Long::class.java).list()
            if (zones.isEmpty()) continue
            users += h.createQuery(
                """
                SELECT u.id FROM app.app_user u WHERE u.status = 'active' AND (u.home_zone_id = ANY(:z) OR EXISTS (
                  SELECT 1 FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id
                  WHERE a.user_id = u.id AND r.zone_id = ANY(:z) AND a.valid_from <= current_date AND (a.valid_to IS NULL OR a.valid_to > current_date)))
                """.trimIndent(),
            ).bindArray("z", Long::class.javaObjectType, zones).mapTo(Long::class.java).list()
        }
        return users
    }

    private fun all(h: Handle): Set<Long> = h.createQuery("SELECT id FROM app.app_user WHERE status = 'active'").mapTo(Long::class.java).set()

    private companion object {
        const val URGENT_MAX_S = 20
        val GEO = setOf("global", "wing", "division", "territory", "zone", "route")
    }
}
