# db → infra (via the lead): move the dev slice smoke's outlet to the Gulshan route

Status: open, 2026-10-22. Raised by lane db with the Gulshan/Banani dev seed (D-SEED-02, `db/seed/06_dev_gulshan_banani.sql`).

On the Azure dev database the new seed closes sr1001's Mirpur primaries and sets `MIR-SR-D`, `MIR-SR-3F`,
`MIR-SR-2F` inactive (owner: "keep the locations of Gulshan and Banani, not Mirpur"). `infra/sql/devseed-smoke-outlet.sql`
puts `SMOKE-SR-001` on `MIR-SR-D` (cluster "Mirpur 10 Bazar"), so the slice smoke would sell on a route sr1001 no
longer holds.

Ask (one file, infra-owned):
- `devseed-smoke-outlet.sql`: route `GUL-SR-2`, cluster `Gulshan 2 Circle`, zone of that route, address "Smoke Road,
  Gulshan 2, Dhaka", a pin near 23.7925 N, 90.4078 E; keep the code `SMOKE-SR-001` (it is idempotent by code: on dev
  the existing row needs an `UPDATE ... SET route_id, cluster_id, zone_id, lat, lng` too, plus a new
  `outlet_placement_history` row, or the bundle keeps it on MIR-SR-D).
- The Gulshan routes are 2F (Sat+Tue, Sun+Wed, Mon+Thu; none on Friday). If the smoke needs a *planned* route every
  day, it should pick today's planned route of sr1001 (one each day Saturday to Thursday) or treat Friday as "no
  route" — check how the smoke chooses its route-day.
- Promote this together with lane/db's seed candidate; until then keep the dev seed off or expect the smoke to fail.
