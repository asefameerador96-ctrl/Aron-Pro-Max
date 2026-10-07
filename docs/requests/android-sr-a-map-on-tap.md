# Request: N-041 map on the out-of-range screen needs a decision (android-sr-a, 2026-10-07)

N-041 wants the outlet pin, the radius circle and the phone's position, loaded only on tap, with a map failure never blocking Force Sale. Two facts block a straight build:
1. `gradle/libs.versions.toml` and docs/17 s10.5 say maps-compose is for the AMO and TSO flavours only, "never in the SR app" (APK size and data budget). N-041 reverses that for the SR app.
2. The Maps key is a manifest placeholder set for AMO and TSO in CI (`MAPS_ANDROID_KEY`); app-sr has none.

Options (my recommendation first):
- **A.** Add maps-compose to app-sr only behind a lazily loaded `MapActivity` (dynamic: loaded only on tap), manifest placeholder `mapsApiKey` for app-sr, an APK size check against docs/04, and the circle drawn with the Maps SDK. Needs: lead approval of the size cost, the CI secret placeholder for app-sr.
- **B.** No SDK: a `geo:` intent opens the installed Google Maps app with the outlet pin (no circle, no own position). Zero APK cost, but does not meet the acceptance text.

`VisitCheckContent` already has the `onMap` hook (shown only when set, never blocks Force Sale). Waiting for A or B.
