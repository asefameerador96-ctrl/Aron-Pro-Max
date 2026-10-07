# Request to web-dashboard and infra (from backend-core, 2026-10-07): signed browser IP from the BFF (AUD-SEC-02 item 3)

The API keys a web login's lockout by `username|-|ipClass(remote address)`. The BFF forwards no client IP, so every
web login shares the BFF's IP class: 10 wrong passwords for one username lock that user for everyone (now capped at
2 h, docs/21 s2.4). To key the web lockout by the browser's IP class, the API needs the browser IP from the BFF in a
header the API can trust.

## Ask
- **web-dashboard:** on `POST /v1/auth/login` (and change-password), send `X-Aron-Client-Ip: <ip>` and
  `X-Aron-Client-Ip-Sig: <base64url HMAC-SHA256(key, ip + "\n" + request X-Request-Id)>`, the IP taken from
  `X-Azure-ClientIP` (Front Door) or the socket, never from a browser-supplied header.
- **infra:** one Key Vault secret `aron-bff-ip-hmac` (32 random bytes) mounted into web and api as
  `ARON_BFF_IP_HMAC_KEY_FILE`. No value in the repo or in logs.
- backend-core then trusts the header only when the signature verifies; until then the web lockout stays per
  username (BFF class) with the 2 h cap, and the global web login bucket (120 per minute per replica) and the
  separate web hash pool protect the phones.
