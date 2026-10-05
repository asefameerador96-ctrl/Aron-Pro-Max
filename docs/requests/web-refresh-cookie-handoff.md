# Request: confirm how the web BFF receives the refresh token (lane web-dashboard, 2026-10-05)

**Observation.** For `client: web` the contract says `refresh_token` is `null` in the login/mfa bodies and the token is
"set as the `aron_rt` cookie" (`Path=/v1/auth/refresh`, HttpOnly, Secure, SameSite=Strict). The web BFF is a server, not a
browser, so it reads the `Set-Cookie: aron_rt=...` header of the API response and re-issues its own `aron_rt` cookie on the
web origin (Path `/`), then replays it as a `Cookie: aron_rt=...` header on `POST /v1/auth/refresh`.

**Ask.** Confirm this hand-off is the intended one (the BFF is the cookie jar for the browser). If the backend prefers, allow
the BFF (server-to-server) to receive the token in the body; the BFF accepts either (`extractRefreshToken` in
`web/src/lib/auth/service.ts` prefers the cookie header and falls back to the body).

**Local stub.** Implemented and tested against the mock as described. No contract change needed if confirmed.
