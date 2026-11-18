package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.FreshDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MasterRules] is the ONE rule set the portal import, the user editor and the bulk loader validate against. This test compares every constant with the
 * live CHECK constraint of the migrated schema, so a migration that changes a table rule fails here until the rule set follows it.
 */
class MasterRulesTest {
    private fun defs(fresh: FreshDb, table: String, constraint: String): String = fresh.db.jdbi.withHandle<String, Exception> { h ->
        h.createQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = CAST(:t AS regclass) AND conname = :c").bind("t", table).bind("c", constraint).mapTo(String::class.java).one()
    }

    /** The regular-expression literals of a constraint definition, in order. */
    private fun regexes(def: String): List<String> = Regex("~ '([^']*)'::text").findAll(def).map { it.groupValues[1] }.toList()

    @Test
    fun everyConstantEqualsTheLiveCheckConstraint() {
        FreshDb.create().use { fresh ->
            for (t in listOf("wing", "division", "territory", "house", "zone", "route")) {
                assertEquals(listOf(MasterRules.HIERARCHY_CODE.pattern), regexes(defs(fresh, "app.$t", "${t}_code_check")), "app.$t code")
            }
            assertEquals(listOf(MasterRules.OUTLET_CODE.pattern), regexes(defs(fresh, "app.outlet", "outlet_code_check")), "app.outlet code")
            assertEquals(MasterRules.MEMO_USERNAME.pattern, regexes(defs(fresh, "app.app_user", "app_user_memo_username")).single(), "SR/AMO username")
            assertTrue(defs(fresh, "app.outlet", "outlet_name_check").contains("<= ${MasterRules.NAME_MAX}"))
            assertTrue(defs(fresh, "app.outlet", "outlet_owner_name_check").contains("<= ${MasterRules.NAME_MAX}"))
            assertTrue(defs(fresh, "app.outlet", "outlet_address_check").contains("<= ${MasterRules.ADDRESS_MAX}"))
            assertTrue(defs(fresh, "app.cluster", "cluster_cluster_type_check").contains("<= ${MasterRules.CLUSTER_TYPE_MAX}"))
            assertTrue(defs(fresh, "app.cluster", "cluster_name_check").contains("<= ${MasterRules.NAME_MAX}"))
            assertTrue(defs(fresh, "app.route", "route_display_label_check").contains("<= ${MasterRules.ROUTE_LABEL_MAX}"))
            assertTrue(defs(fresh, "app.app_user", "app_user_employee_code_check").contains("<= ${MasterRules.EMPLOYEE_CODE_MAX}"))
        }
    }

    /** The portal import accepted a route code with a dot or a slash that the table refuses; it now answers `invalid` for it like the loader does. */
    @Test
    fun theRouteCodeRuleOfThePortalIsTheTablesRule() {
        assertTrue(!MasterRules.HIERARCHY_CODE.matches("R-1.2"))
        assertTrue(!MasterRules.HIERARCHY_CODE.matches("R/1"))
        assertTrue(MasterRules.HIERARCHY_CODE.matches("00123") && MasterRules.HIERARCHY_CODE.matches("MIR-SR-D"))
        assertTrue(MasterRules.OUTLET_CODE.matches("AB-100023") && MasterRules.OUTLET_CODE.matches("1234A56") && !MasterRules.OUTLET_CODE.matches("a b"))
    }
}
