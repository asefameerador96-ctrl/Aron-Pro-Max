# Request to web-dashboard (BFF) and infra (from backend-core, 2026-10-07): signed browser address on web logins (AUD-SEC-02 item 3)

The API sees every web login as coming from the BFF's own address: the BFF forwards no browser IP, and the API never
trusts `X-Forwarded-For` (the client writes it). Any lockout keyed by address would therefore lock **every** web user's
account together, and an attacker could lock out a SUPERADMIN with 10 bad passwords.

**What backend-core did (lane/backend-core, AUD-SEC-02):** web accounts are no longer hard-locked. Web failures are
counted and logged as an alert; the per-username throttle (10 per 15 min, 429) still applies. Phones keep the
(username, device) lock, capped at 2 h (docs/21 s2.4, D-102).

## Ask
1. **web-dashboard:** on `POST /api/bff/login` (and MFA verify), send the browser address to the API as
   `X-Aron-Client-IP: <ip>` with `X-Aron-Client-IP-Sig: base64url(HMAC-SHA-256(key, "<ip>\n<X-Request-Id>"))`.
2. **infra:** a Key Vault secret `aron-bff-ip-hmac-key` (32 random bytes) given to both the BFF and the API
   (`ARON_BFF_IP_HMAC_KEY`). In the test account it is created at pilot size like any other secret.
3. Then backend-core verifies the signature, keys web lockouts as `username|ipclass`, and turns the web lock back on.
   No contract change: these are BFF-to-API headers, not public contract members.
