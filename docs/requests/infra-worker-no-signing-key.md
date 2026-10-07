# Request to infra (from backend-core, 2026-10-07): drop the JWT signing key from the worker (AUD-SEC-07)

`Settings` now requires `ARON_JWT_SIGNING_KEY` only for `ARON_ROLE=api` (lane/backend-core, `SettingsTest.theWorkerRunsWithoutTheSigningKeyAndTheApiDoesNot`); the worker and migrate roles start without it. Nothing in the worker signs or verifies tokens.

## Ask
- **infra:** remove the `aron-jwt-signing-key` secret reference (and its env var) from the worker container in `infra/apps.bicep`, so the exportable key lives only in api replicas. No other change needed.
- Optional, later (docs/21 rotation, T-7-71): a separate Key Vault secret for the refresh derivation key. Not built; the backend keeps deriving it from the signing key until asked.

## Also noted for infra (Opus checker on AUD-REL-07)
The API drain waits up to 15 s for in-flight calls, then Netty's quiet periods (about 9 s): about 24 s, inside Container Apps' default 30 s termination grace. If the grace is ever lowered, keep it at 30 s or more for the api app.
