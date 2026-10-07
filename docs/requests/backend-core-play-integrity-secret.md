# Request to infra (from backend-core, 2026-10-07): Play Integrity decode credentials for the API (N-027)

The API now decodes Play Integrity tokens server-side (`decodeIntegrityToken`, docs/24 s10.4 item 3). Without
credentials every verdict stays `unevaluated`, so with `cfg.device.require_integrity` on every phone's attendance and
sales are quarantined `device_integrity_failed` (never lost; released on a resend once the phone passes).

## Ask
1. Map an optional API secret `ARON_PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON` (Key Vault `aron-play-integrity-service-account`,
   same pattern as `ARON_FCM_SERVICE_ACCOUNT_JSON`; `_FILE` works too). If it is absent the API uses the FCM service
   account, so the cheapest path on the test account is: enable the **Play Integrity API** on the Firebase/Google project
   the FCM account belongs to and grant nothing else (the decode needs only the `playintegrity` scope).
2. Outbound HTTPS from the API to `playintegrity.googleapis.com` and `oauth2.googleapis.com` (already open if FCM works).

## Owner-only step (account action)
Play Integrity standard requests need the app's Google Cloud project number configured in the app and, for decoding,
the package linked to that project. If the owner's Play Console is not used (sideloaded APK), Google may refuse the
decode; the API then logs `play integrity decode failed` (never the token) and the verdict stays `unevaluated`.
Tomorrow's device check with integrity ON depends on this; until then keep `cfg.device.require_integrity` false in dev
or expect the quarantines above.
