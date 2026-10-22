# Status: lane android-map

## State (2026-10-22, session 1)
D-MAP-02 built: the Sale outlet-confirm step shows distance, radius and verdict without a tap, with a Maps SDK lite-mode map.

| Item | State | Notes |
|---|---|---|
| Distance, radius, verdict, always | done | out of range (`NeedsDecision`) and in range (the start-call step). In range the distance comes from `VisitSession.geo`, filled from the fix the visit already took (`VisitFlow.commit`); no second sample. A visit restored after a relaunch has no fix, so it keeps the plain start prompt |
| Inline lite map | done | `core-map/OutletLiteMap` (lite mode, no gestures, pin + radius circle + SR dot, transparent until `onMapLoaded`, gives up after 10 s); host slot `rememberOutletMapSlot` in `app-sr/SrGeoMap.kt`; null when offline, no key or provider not drawable, so no Maps class loads and no tile is requested |
| Offline / no tiles | done | the existing `RangeView` canvas is always drawn under the map; plus a bearing arrow with a compass word (8 winds, Bangla and English) |
| Open in Maps | kept, secondary | `OutletMapActivity` unchanged, started only from the tap |
| Tests | done | feature-outlet `VisitGeoTest` (offline and online panel, map stubbed, Bangla and English, force-sale branch unchanged, bearing/compass), `VisitFlowTest` (geo from the visit's own fix, one read), core-map `OutletLiteMapTest`, `NoAlwaysOnMapTest` (inline map reachable only through the confirm-step slot); Roborazzi goldens `sell_confirm_*` in `SellShot` |
| APK size | measured | SR release, same base commit with and without the change: +8,812 bytes on every ABI (arm64-v8a 5,225,191 to 5,234,003). maps-compose was already in the SR APK. Far below the 30 MB gate. Not mine: the committed baseline is stale (armeabi-v7a reads +6 % against it before this change too); baseline file untouched |
| Device check | DEVICE-PENDING | D-MAP-02 in `docs/status/device-checks.md` (real Maps key, tiles, data use) |

## Decisions
- The in-range confirm step is the start-call step of `SaleHost`: the old "start call?" dialog is replaced by the page (outlet card, geo card with panel, Start call bar, No sale link) when the fix is known. A dismissed dialog used to abandon the visit; the page keeps the visit open like the Sale tile already does after Back.
- The map never gates the sale: it is an overlay on the offline picture, so a missing key, no tiles or a slow load shows the picture and the distance.
- Tile cache: lite mode uses the SDK's own cache, left on; no snapshot is stored for this view (a stored image would show an old SR position).

## Checker (Sonnet, T2) and what came of it
Fixed: the start step lost the "No" path that abandoned the visit (now a "Go back" link that abandons, F-SR-060); a force-sold visit read "in range" over "outside" (card follows the verdict); the inside/outside wording follows distance against radius; the AMO keeps its earlier range view and wording (`geoPanel` is on for the SR only). Left as is: the map also loads on the out-of-range step (same Sale confirm step, where the owner's complaint is); the scale label is hidden whenever a map slot exists, so with a key but no tiles the picture has no scale (cosmetic).

## AMO (lead's follow-up)
AMO showed a distance only out of range (Control Call check step, no map). It now uses the same panel and rules there (`geoPanel = true`, lite map via `AmoGeoMap.kt`; AMO tests green). In range the AMO has no confirm step: the check step advances at once to the call menu, so no distance shows there. Not changed, because a held step would alter the Control Call flow; say if you want it. The AMO's "Open in Maps" stays off (`OutletMapActivity` is SR-only).

## Next
Nothing planned. If the owner wants the map on the picker rows or on in-range outlets only after the call starts, say so.
