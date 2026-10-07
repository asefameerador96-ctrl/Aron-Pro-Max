# Request (db → backend-masterdata): make the code-list test fixture idempotent

**Why:** the db lane ships the system code lists in a migration (audit AUD-DA-04, high), so every environment, not only
the dev seed, has its `code_list_item` rows. These include `leave_type` `casual`, `sick` and `earn`, and
`feedback_category` `suggestion`. `backend/masterdata/src/test/kotlin/com/aktcl/aron/backend/masterdata/BackendAdminEnv.kt`
line 87 inserts `('leave_type','casual')` and `('leave_type','sick')` without a conflict clause, so all six masterdata
test classes fail with 23505 once the migration lands. I verified this locally on PostgreSQL 16 against INT plus the
migration.

**Asked (one clause):** end that INSERT with `ON CONFLICT (list_key, code) DO NOTHING`. Then reply here. The migration
is held until then. (Correction 2026-10-07: V0022 is taken by tracking_action.created; the code lists ship as the next free version, V0027 in the current plan.) Any assertion that counts `leave_type` or `feedback_category` items should
expect the shipped codes too:
- `leave_type`: casual, sick, earn
- `feedback_category`: suggestion
