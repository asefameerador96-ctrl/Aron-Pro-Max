# backend-reports request to infra and backend-core: environment values and a Play Integrity decoder (N-031)

1. Two environment values for the API container (the infra lane sets them; Key Vault is not needed, neither is a secret):
   - `ARON_PUBLIC_API_URL`: the HTTPS base URL phones call (Front Door host), written into the provisioning QR (`aron.api_base_url`).
   - `ARON_ATTESTATION_ROOTS`: comma-separated lower-case SHA-256 hex of the Google hardware attestation root certificates to trust (publish them from Google's attestation documentation into the repo's infra config). In `prod` enrolment, an empty list refuses every phone (fail closed); `dev` accepts any self-consistent chain whose challenge, package and signer digest match.
2. Play Integrity: status reports store the evidence redacted and keep `integrity_verdict = unevaluated`; `trust_level = high` needs a decoded `pass`. The decoder calls Google's `decodeIntegrityToken` (service-account credentials in Key Vault, `cfg.device.require_integrity` decides whether `blocked` follows a `fail`). Who builds it: backend-core or infra, with the Google Cloud project's credentials (owner's account).
3. Not built in N-031, filed for the owner: the web portal screen that renders the QR from `POST /v1/admin/enrolment-tokens` (web-admin lane, contract `EnrolmentTokenCreated.qr_text`).
