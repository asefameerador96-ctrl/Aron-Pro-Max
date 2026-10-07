# Request: data void maker-checker is not in the contract (to: lead)

docs/24 s8.6 lists "data void" among the actions that need a second person, but `DataVoidRequest` (contract v1.2) has one step and no approver field, and `route_day_void_barrier.approved_by` is nullable.

Built (F-API-048): the single-step void exactly as the contract has it, roles TSO (own reach), ADMIN, SUPERADMIN (docs/21 `day.void`), audited with the reason, refused after Final Submit (409 ERR_DAY_ALREADY_FINAL_SUBMITTED), idempotent by `client_uuid`, `approved_by` left NULL.

Decision needed from the lead: keep single-step for the pilot (the audit row carries who and why), or add a contract item (a pending void plus an approval call, like the price publish second approver). I will build whichever is ruled; no work is blocked on it.
