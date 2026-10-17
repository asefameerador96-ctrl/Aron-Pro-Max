# N-039 anti-spoofing acceptance report (qa) — DEVICE-PENDING

Acceptance: at least five well-known mock-location apps and one app-cloning tool, tried on the Galaxy A06, A07 and
Honor X5c Plus, before and after enrolment, each result reported. After enrolment each spoofing app cannot be
installed or switched on; where one runs, the visit is refused and flagged.

**How each cell is measured.** Use `tools/checks/day3-check.ps1 -SpoofApks ...` on the enrolled phone, plus the same
apps on a NOT enrolled phone (before enrolment):
- "install": `adb install -r` succeeded or was refused (INSTALL_FAILED_USER_RESTRICTED);
- "mock on": `appops get <pkg> android:mock_location` (allow / ignore / default) and whether the app could select
  itself as the mock location app;
- "visit": the SR app's verdict at an outlet with the fake fix on. Refused with "নকল লোকেশন ধরা পড়েছে" is a pass;
- "flag": the visit synced with `is_mock` true or a mock risk signal. The laptop session reads it on dev.

The mechanism was already proven on the Honor X5c Plus without third-party apps (D-05, 2026-10-17: a fused mock fix is
never geo-valid). This report adds the real apps once the owner installs them (Play needs a Google account on the
phone; APK mirrors are not trusted sources) or hands their APKs to the laptop session.

| App (package) | Kind | Phone | Before enrolment: install / mock on / visit | After enrolment: install / mock on / visit / flag |
|---|---|---|---|---|
| Fake GPS location (com.lexa.fakegps) | mock | Honor X5c Plus | | |
| Fake GPS (com.incorporateapps.fakegps.fre) | mock | Honor X5c Plus | | |
| Fake GPS Location Spoofer (com.blogspot.newapphorizons.fakegps) | mock | Honor X5c Plus | | |
| Fake GPS Joystick (com.theappninjas.fakegpsjoystick) | mock | Honor X5c Plus | | |
| GPS Emulator (com.rosteam.gpsemulator) | mock | Honor X5c Plus | | |
| Parallel Space (com.lbe.parallel.intl) | cloning | Honor X5c Plus | | |
| (same six) | | Galaxy A06 | not in hand | not in hand |
| (same six) | | Galaxy A07 | not in hand | not in hand |
