# Status: lane android-amo (wave 2, started 2026-10-17)

## Done
- F-AMO-001 (home tile resolution and Outlet badge). Interpretation: the "17/13" counts destinations (Settings and Astha included); Astha is deferred (docs/27) so a full AMO shows 16; an SS user (no subordinate SR in the supervisor section, MQ-19 assumed) shows 13.

## In progress
- F-AMO-003 attendance: building `AmoDay` in app-amo (lean day object: capture, reference, meta provider, fix source, attendance flow). Decision: AMO gets its own thin wiring in app-amo; SR screens/flows come from the feature modules. SrDay/SrSaleKit stay in app-sr (SR lanes idle; extraction into a shared module would be a cross-lane refactor, revisit at F-AMO-006).

## Next three rows
F-AMO-003, F-AMO-005, F-AMO-013 (then Day-4 list in id order).

## Traps
- Local Gradle works online: `echo sdk.dir=/opt/android-sdk > local.properties`, `./gradlew :android:feature-amo:testDebugUnitTest -q`.
- AMO app lacks MediaShell/ContentShell/SystemShell/printer wiring that app-sr has; rows needing photos/printing must add them.
