package com.aktcl.aron.backend.masterdata

/**
 * The shape rules of the master-data tables, in ONE place. The portal import (`OutletImportApi`), the user editor (`AdminUsers`) and the bulk loader
 * (`loader/`) all validate against these constants, so a value one of them accepts can never be refused by a table CHECK in another
 * (before this object the portal accepted route codes with a dot or a slash that `app.route.route_code_check` refuses).
 *
 * `MasterRulesTest` compares every constant here with the live CHECK constraint of the migrated schema, so a migration that changes a table rule fails
 * that test until this object follows it: the rule set cannot drift from the tables silently.
 */
internal object MasterRules {
    /** Wing, division, territory, house, zone and route codes: `app.<table>.code` CHECK `<table>_code_check` (the same text for all six). */
    val HIERARCHY_CODE = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$")

    /** `app.outlet.code` CHECK `outlet_code_check`. */
    val OUTLET_CODE = Regex("^[0-9A-Za-z][0-9A-Za-z._/-]{0,31}$")

    /** `app.app_user.username` of an SR or AMO: CHECK `app_user_memo_username` (V0197: 3 to 32 characters, the memo number embeds it). */
    val MEMO_USERNAME = Regex("^[a-z][a-z0-9]{2,31}$")

    /** The shortest memo username the INSTALLED app accepts (MemoNumbers.kt); shorter ones are held until the release gate opens (D-MD-24). */
    const val APP_MIN_USERNAME_LENGTH = 4

    /** Names (outlet, owner, route, cluster, hierarchy, user full name): CHECK `length(name) BETWEEN 1 AND 120` (owner: at most 120). */
    const val NAME_MAX = 120
    const val ADDRESS_MAX = 300
    const val CLUSTER_TYPE_MAX = 60
    /** `app.route.display_label` CHECK `route_display_label_check`. */
    const val ROUTE_LABEL_MAX = 60
    /** `app.app_user.employee_code` CHECK. */
    const val EMPLOYEE_CODE_MAX = 40
}
