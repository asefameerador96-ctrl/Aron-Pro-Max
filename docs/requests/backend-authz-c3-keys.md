# backend-authz: two security keys one ADMIN can loosen alone (Q3, Q4)

Status: proposed, not built (lead 2026-10-18: report, do not change; waits on the hardening decision). Owner when approved: backend-authz.

## Finding
- `cfg.auth.mfa_required_roles` (docs/24 s9.5 row, risk C2) and `cfg.price.max_change_pct` (C2, bounds 1..100) are registry class 2 (`app.cfg_key.risk_class = 2`, `restrictive_dir = none`).
- C2 means one ADMIN schedules the change and it applies after `cfg.sys.c2_delay_min`; no second person (docs/24 s8.6 needs a second approver only for C3).
- So one ADMIN alone (or one stolen ADMIN session that passed MFA) can schedule `cfg.auth.mfa_required_roles = []` (MFA off for every admin role) or `cfg.price.max_change_pct = 100` (the price maker-checker rail of D24-75 is then never triggered, so later price batches publish without a checker). Both take away a control that exists to stop exactly one person.
- `RiskClassifier.classify` (backend/config ConfigValidator.kt) escalates only the radius rules, three value-triggered keys and "C3 at D and wider".

## Proposed change (pick one)
A. **Registry, migration (db lane):** `UPDATE app.cfg_key SET risk_class = 3 ... WHERE key IN ('cfg.auth.mfa_required_roles', 'cfg.price.max_change_pct')` plus `restrictive_dir` (`mfa_required_roles`: a superset is restrictive; `max_change_pct`: down). Every change, also a tightening, then needs a second SUPERADMIN. Simple, but a tightening (adding SUPPORT back) also waits for a second person. Needs a migration number (V0084 to V0087 are reserved for the held hardening branch) and a docs/24 s9.5 edit (two rows C2 -> C3).
B. **Value-triggered, code only (recommended):** in `RiskClassifier.classify`, as for `cfg.media.photo_max_kb`:
   - `cfg.auth.mfa_required_roles`: C3 when the new list drops any role the node resolves to now (`before` minus `after` non-empty); adding roles stays C2.
   - `cfg.price.max_change_pct`: C3 when the new value is above the resolved value (a loosening); lowering stays C2.
   No migration; docs/24 s9.5 gets "C2; C3 when loosened" in the two rows (the drift test reads the risk column, so the docs row and the registry keep class 2 and the rule text goes into `risk_rule` only if the drift test needs it: check `ConfigRegistryDriftTest` first).
   Also add both keys to the break-glass "restrictive" set so a break-glass may restore them.

## Tests (either option)
- One ADMIN schedules `mfa_required_roles=[]` -> change is `pending_approval`, never applied after the C2 delay; a second SUPERADMIN approves -> applied; the same ADMIN approving -> `ERR_CFG_SELF_APPROVAL`.
- `max_change_pct` 15 -> 100 the same; 15 -> 10 stays C2 (applies after the delay).
- Adding SUPPORT back to the list stays C2 (option B).
