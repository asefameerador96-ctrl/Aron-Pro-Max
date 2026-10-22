package com.aktcl.aron.backend.platform

import org.jdbi.v3.core.Handle

/**
 * D-ROLES-02 (row 2): an ADMIN user a test creates holds no module until granted (fail closed, as in production). Tests
 * whose ADMIN must keep today's full reach call this after inserting it; a no-op before the grant table exists.
 */
object AdminGrantsFixture {
    fun grantAllModules(h: Handle, username: String) {
        if (h.createQuery("SELECT to_regclass('app.user_module_grant') IS NOT NULL").mapTo(Boolean::class.java).one() != true) return
        h.createUpdate(
            """INSERT INTO app.user_module_grant (user_id, module, access)
               SELECT u.id, m, 'w' FROM app.app_user u, unnest(CAST(:mods AS text[])) AS m WHERE u.username = :n
               ON CONFLICT (user_id, module) DO UPDATE SET access = 'w'""",
        ).bindArray("mods", String::class.java, AdminModules.ALL).bind("n", username).execute()
    }
}
