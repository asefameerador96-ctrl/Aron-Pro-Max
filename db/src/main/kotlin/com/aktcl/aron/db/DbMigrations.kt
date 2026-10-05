package com.aktcl.aron.db

/** Where Flyway finds the Aron migrations (docs/24 s12.1). */
object DbMigrations {
    const val LOCATION: String = "classpath:db/migration"

    /** File name rule: V + 4 digits + "__" + lower snake case + ".sql"; numbers are never reused. */
    val FILE_NAME: Regex = Regex("^V\\d{4}__[a-z0-9]+(_[a-z0-9]+)*\\.sql$")
}
