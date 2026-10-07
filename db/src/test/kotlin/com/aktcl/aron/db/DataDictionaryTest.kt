package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/31 s3 item 8: every app and dw table, view and column carries a comment with an owner, a capture class, a
 * retention class and a PII class, and docs/data-dictionary.md is the rendering of the current schema. Regenerate with
 * `tools/data-dictionary/render.sh` (or `-Paron.writeDictionary=true`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataDictionaryTest {
    private lateinit var db: TestDatabase
    private val file = File(System.getProperty("aron.migrations")).parentFile.parentFile.resolve("docs/data-dictionary.md")

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
    }

    @AfterAll
    fun tearDown() = db.close()

    @Test
    fun everyTableViewAndColumnIsDocumented() = db.connect().use { c ->
        assertEquals(emptyList(), DataDictionary.problems(DataDictionary.relations(c)), "add COMMENT ON in the migration that created them")
    }

    @Test
    fun theCommittedDictionaryMatchesTheSchema() = db.connect().use { c ->
        val rendered = DataDictionary.render(DataDictionary.relations(c))
        if (System.getProperty("aron.writeDictionary") == "true") file.writeText(rendered)
        assertEquals(rendered, if (file.exists()) file.readText() else "", "docs/data-dictionary.md is stale: run tools/data-dictionary/render.sh")
    }
}
