# backend-core session 17, row 1: dashboards for house-scoped and route-only TSOs (lead, 2026-10-24)

**Ruling:** D-DASH-TSO-HOUSE-SCOPE-01 in `DECISIONS.md` (lead, 2026-10-24 16:20 UTC), from backend-core session 16's checker notes.

**Problem (pre-existing, now reachable):** since contract 1.7.0 a TSO may hold a `house` node (D-WEB-ADMIN-HOUSE-AMO-01, authz). `resolveScopedNode` in `backend/analytics` resolves wing, division, territory and zone only, so a TSO scoped by a house, or one holding only route assignments, receives 403 on every node-scoped dashboard default call (`GET /v1/dashboards/*` without an explicit node). It fails closed, so there is no leak; the user simply has no dashboard.

**Build (one slice, Opus checker, T1):**
1. `resolveScopedNode` (and whatever shares its level table) resolves a `house` node like the other geo levels; the node's zones come from `dw.dim_geo` under that house.
2. A route-only TSO (no geo node, one or more route assignments) gets as default node set the zones of the routes assigned on the business date (same source as `GET /v1/memos` reach; frozen at the business date like the memo flags view).
3. Explicit `node_id` queries keep the existing reach check (a node outside the caller's reach stays 403 ERR_OUT_OF_SCOPE; a BRAND caller stays 403 ERR_FORBIDDEN).
4. Tests: `ScopeLeakAcrossEndpointsTest` cases for a house-scoped TSO (sees own house, not the sibling house) and a route-only TSO (sees the zones of own routes only); `scope-cases.txt` rows; the default call for each returns 200 with the right nodes.
5. No contract change (the operations and shapes exist); docs/24 wording where the reach rule is described.

**Not in this slice:** the `dw.dim_geo` zone keying (db #11 note); the `getFlaggedMemos` description alignment (contract 1.9 queue row 3).
