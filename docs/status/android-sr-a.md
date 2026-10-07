# Status: lane android-sr-a (2026-10-07)

## Done
Nothing: every one of the 35 rows has an unmet dependency owned by another lane. See `docs/requests/android-sr-a-blocked.md`.

## Blocked (all rows)
First unmet dependencies: N-023, N-021, F-SYS-006, F-SYS-008, F-SYS-049, F-SYS-023 and the other android-core/backend rows listed in the request.

## Next three rows when unblocked
F-SR-008 (Home header), F-SR-009 (tile grid), F-SR-014 (stock load), then F-SR-016 and F-SR-017.

## Traps noted
- android-sr-b depends on my F-SR-014 and F-SR-017 (see `docs/requests/android-sr-b-*`); interface to agree in `feature-outlet`.
- Android SDK is installed by `tools/android-sdk.sh` (`sdk.dir=/opt/android-sdk`); Maven Central may need the mirror init script.
