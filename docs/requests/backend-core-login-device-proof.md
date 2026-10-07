# Request to the lead, for contract v1.4 (from backend-core, 2026-10-07): `X-Device-Proof` on phone login (AUD-SEC-02 item 2)

docs/21 s2.4 and D-102 say that "a bound device presenting a valid `X-Device-Proof` is never locked by foreign-source
failures". The contract, however, lists `X-Device-Proof` only on `auth/refresh`, `auth/bind-device`, `sync/batch` and
`devices/me/*` (docs/24 s3.1 header table), not on `POST /v1/auth/login`. The lockout key is (username, device_uuid),
and `device_uuid` comes from the body, so anyone who learns a phone's uuid can still lock that SR out of it for up to
2 h, in 15-minute steps.

## Ask (contract v1.4, do not invent before it lands)
- `POST /v1/auth/login`: optional request header `X-Device-Proof` for app clients. Proof string (s8.3 style):
  `aron-proof-v1\nlogin\n<device_uuid>\n<lower-case username>\n<time bucket>`.
- Server rule once it lands (backend-core): failures that carry a valid proof from the bound device count on
  `username|device|proof`; failures without one count on `username|device`. A lock on the second key never blocks
  a login that carries a valid proof.
- android-core: sign the login with the Keystore key when the phone has one.

Until then backend-core builds what needs no contract change: locks are capped at 2 h, keyed by (username, device),
never by IP class; enrolled phones have reserved hash capacity.
