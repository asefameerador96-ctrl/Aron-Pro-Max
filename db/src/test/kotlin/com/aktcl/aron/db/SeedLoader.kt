package com.aktcl.aron.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Loads the development and test seed (db/seed/NN_*.sql, in file-name order) into a migrated database, each file in
 * its own transaction. The files are idempotent, so loading twice changes nothing (row N-008). Never used in
 * production: the seed lives outside the packaged migrations.
 *
 * Command line: `ARON_SEED_DB_URL=jdbc:postgresql://host:5432/db?user=..&password=.. ./gradlew :db:seed`
 * (the same files also load with psql: `for f in db/seed/0*.sql; do psql "$URL" -v ON_ERROR_STOP=1 -f "$f"; done`).
 */
object SeedLoader {
    fun files(dir: File = File(System.getProperty("aron.seed"))): List<File> =
        dir.listFiles { f -> f.isFile && Regex("^\\d{2}_[a-z0-9_]+\\.sql$").matches(f.name) }!!.sortedBy { it.name }

    fun load(c: Connection, dir: File = File(System.getProperty("aron.seed"))) {
        val auto = c.autoCommit
        c.autoCommit = false
        try {
            files(dir).forEach { f ->
                c.createStatement().use { it.execute(f.readText()) }
                c.commit()
            }
        } catch (e: Exception) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = auto
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val url = args.firstOrNull() ?: System.getenv("ARON_SEED_DB_URL")
            ?: error("set ARON_SEED_DB_URL (JDBC URL of a migrated development database)")
        DriverManager.getConnection(url).use { load(it) }
        println("seed loaded: " + files().joinToString { it.name })
    }
}
