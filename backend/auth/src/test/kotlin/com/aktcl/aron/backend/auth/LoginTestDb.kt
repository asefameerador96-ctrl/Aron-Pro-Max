package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.FreshDb
import kotlinx.serialization.json.JsonElement

/** The same LoginTest cases against the JDBI stores on a fresh, migrated PostgreSQL 16 database. */
class LoginTestDb : LoginTest() {
    override fun fixture(overrides: Map<String, JsonElement>, hashConcurrency: Int, hashQueue: Int): AuthFixture =
        track(AuthFixture(overrides, hashConcurrency, hashQueue, FreshDb.create()))
}
