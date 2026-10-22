package com.aktcl.aron.backend.analytics.mdm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The test copy of the MDM DDL must equal the block in the db request until the migration lands (then delete both). */
class MdmDdlSyncTest {
    @Test
    fun resourceEqualsTheRequestBlock() {
        val md = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "docs/requests/db-mdm-device-command.md") }.firstOrNull { it.isFile } ?: return
        val block = Regex("```sql\n(.*?)```", RegexOption.DOT_MATCHES_ALL).find(md.readText())!!.groupValues[1]
        val res = javaClass.classLoader.getResourceAsStream("mdm_ddl.sql")!!.bufferedReader().readText().substringAfter("\n")
        assertEquals(block, res)
    }
}
