package com.aktcl.aron.backend.notify

import com.aktcl.aron.backend.platform.ConfigPullNotifier
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ServerConfig
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.random.Random

/**
 * The `config_pull` push of a late-sale policy change (X4a ask 11, D-TSO-MAN-57, D-TSO-MAN-62): ONE data message per live phone of
 * the given users, kind `config_pull`, `urgent` (the phone pulls the CONFIG delta outside its daily cap), with the `reason` for
 * the logs; never business data. `pull_after_s` is a random 0..`cfg.ops.config_pull_spread_s` (default 20, widened by config at
 * fleet size) so the phones do not pull in the same second. Switched by `cfg.ops.push_enabled` like every push.
 */
class ConfigPullPush(
    private val db: Database,
    private val config: ServerConfig,
    private val push: PushNotifier,
    private val random: Random = Random.Default,
) : ConfigPullNotifier {
    private val log = LoggerFactory.getLogger("aron.push")

    override fun configPull(reason: String, userIds: Collection<Long>) {
        if (userIds.isEmpty() || !runCatching { config.bool("cfg.ops.push_enabled") }.getOrDefault(false)) return
        val id = UUID.randomUUID().toString()
        val spread = spreadSeconds()
        val tokens = db.jdbi.withHandle<List<Pair<Long, String>>, Exception> { h -> push.liveTokens(h, userIds.toSet()) }
        if (tokens.isEmpty()) return
        // One message per phone token; the broadcast queue is bounded and refuses visibly. Each token draws its own delay.
        push.broadcast(tokens) { _ ->
            mapOf("kind" to "config_pull", "reason" to reason, "notification_id" to id, "pull_after_s" to random.nextInt(0, spread + 1).toString(), "urgent" to "true")
        }
        log.info("config_pull queued reason=$reason users=${userIds.size} tokens=${tokens.size} spread_s=$spread")
    }

    /** The configured window, clamped to the registry bounds; an unreadable value falls back to the default. */
    private fun spreadSeconds(): Int = runCatching { config.int("cfg.ops.config_pull_spread_s") }.getOrDefault(DEFAULT_SPREAD_S).coerceIn(0, MAX_SPREAD_S)

    companion object {
        const val DEFAULT_SPREAD_S = 20
        const val MAX_SPREAD_S = 900
    }
}
