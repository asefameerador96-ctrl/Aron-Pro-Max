# Request: "service zone" count and a final-submit completion figure for the home

Lane: web-dashboard. Needed by: the Final Submit tile of the home (`/`), to match the owner's reference (`docs/evidence/web-dashboard-reference-2026-10-17.md`).

## Need
The reference shows Total Zone, Total Service Zone, Remaining and one ring with "Total Final Submit" as a percentage. The home must show the same three counts and one completion ring.

## Gap
The dashboard summary and the final-submit read model carry submitted and remaining zones, but no count of *service zones* (the zones that actually have a service route today, as opposed to all zones in scope). Without it "Total Service Zone" cannot be computed on the client: the web would have to guess which zones are service zones, and a guessed figure on a money-adjacent tile is worse than none.

## Proposed shape
On the final-submit summary (same scope and business date as the rest of the tile) add three integers and one rate:
- `total_zones` (all zones in the caller's scope),
- `service_zones` (zones in scope with at least one active route on the business date),
- `submitted_service_zones` (service zones whose every route is final-submitted),
- `final_submit_pct` (0 to 100, 2 decimals, null when `service_zones` is 0).

Remaining is derived: `service_zones - submitted_service_zones`. Scope is the server's, as for every other tile.

## Stub
The tile keeps its current content (submitted and remaining zones, Submit %, Day completion %, per-zone chips). No ring and no "service zone" count are drawn until the field exists; nothing is estimated.

**State (2026-10-18, web-dashboard):** requested; the lead decides whether the backend lane takes it.
