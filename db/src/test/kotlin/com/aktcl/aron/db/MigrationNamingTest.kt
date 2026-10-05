package com.aktcl.aron.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** CI gate for the db lane: every migration is correctly named and numbers are unique (docs/24 s13.2). */
class MigrationNamingTest {
    private val files: List<File> =
        File(System.getProperty("aron.migrations")).listFiles { f -> f.isFile && f.name.endsWith(".sql") }?.toList().orEmpty()

    @Test
    fun everyMigrationFollowsTheNamingRule() {
        files.forEach { assertTrue(DbMigrations.FILE_NAME.matches(it.name), "bad migration name: ${it.name}") }
    }

    @Test
    fun migrationNumbersAreUnique() {
        val numbers = files.map { it.name.substring(1, 5) }
        assertTrue(numbers.size == numbers.toSet().size, "duplicate migration numbers: $numbers")
    }
}
