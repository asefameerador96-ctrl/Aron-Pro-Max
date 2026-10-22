# Lane brief: android-map

Session model: **Sonnet** (docs/29 s3). Owns the inline map and distance on the SR Sale → outlet confirm
step (`docs/34-owner-directive-2026-10-22.md` s4.5, D-MAP-02) in `feature-outlet` (`VisitScreens.kt`,
`VisitFlow.kt`) and `app-sr` (`OutletMapActivity`, Maps wiring).

Read `docs/lanes/README.md` first, then docs/34 s4.5, `docs/05` (geo-validation), `docs/17` s10.5 (maps
client rules and budgets), `docs/ui-reference/sr/sale-select.md`, and the current geo verdict code
(`core-geo`, `VisitFlow` `geoDistanceM`, `geoRadiusM`).

- Show on the confirm step, without a tap: distance to the outlet (metres, Bengali digits in Bangla mode),
  the radius and the verdict (inside / outside → force sale with photo), and an inline map: Maps SDK **lite
  mode** `MapView` (static bitmap, no gestures) with the outlet pin, the radius circle and the SR position;
  loads only on this step; tile cache on. Offline or no tiles: a panel with the distance and a bearing arrow
  (compass from the fix to the outlet); the sale flow never waits for the map.
- Keep "Open in Maps" as a secondary link. `OutletMapActivity` (full interactive map) stays for the Map tile.
- Budgets: no continuous location; the fix used is the one the visit already takes; ≤ 1 MB per map session;
  measure the added APK size and keep the 30 MB per ABI gate (`tools/ci/apk-size-baseline.json` is never
  edited by a lane: if the gate trips, report to the lead).
- Tests: a Compose test for the offline panel and the online panel (map stubbed), the distance text in both
  languages, and the force-sale branch unchanged; a golden for the confirm step.
- Status file `docs/status/android-map.md`.
