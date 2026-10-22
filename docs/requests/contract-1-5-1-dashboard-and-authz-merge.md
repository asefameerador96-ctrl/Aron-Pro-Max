# Contract 1.5.1: the national dashboard (1.5.0) on top of backend-authz's 1.4.0 (lead-approved)

Lane backend-reports, 2026-10-22. Lead rulings: backend-authz 1.4.0, backend-reports 1.5.0, mdm 1.6.0 (messages of 2026-10-22).

- 1.5.0 (additive): the 14 national dashboard operations, percentages as integer basis points (`*_pct_bp`, ruling D-CONTRACT-DASH-01).
- This push merges INT with backend-authz's approved 1.4.0 (Role += NATIONAL/BRAND, `ERR_AUTH_PORTAL_NOT_ALLOWED`; see
  `docs/requests/contract-portal-levels.md`). Against this lane's previous green head (7d495a80, also 1.5.0) oasdiff reports those
  enum additions as breaking, and the gate needs `info.version` to differ from that base: hence 1.5.1. No wire change beyond
  the two approved items.
- Phones: unknown roles and problem codes are already tolerated (shared contract and web tests, commit e387c5a7).
