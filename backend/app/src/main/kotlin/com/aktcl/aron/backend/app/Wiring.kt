package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.NIL_GENERATION
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.Settings
import io.ktor.server.routing.Route

/** The object graph of the API process; tests build their own with throwaway keys and in-memory stores. */
class Wiring(
    val clock: AronClock,
    val config: ServerConfig,
    val database: Database?,
    val generation: () -> String,
    val build: String,
    val mount: Route.() -> Unit,
) {
    companion object {
        fun production(s: Settings): Wiring {
            val db = Database.fromSettings(s)
            return Wiring(AronClock.SYSTEM, RegistryDefaults(s.env), db, { NIL_GENERATION }, s.build, mount = {})
        }
    }
}
