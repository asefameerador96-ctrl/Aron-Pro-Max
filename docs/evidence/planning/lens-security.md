# Lens: Security, Privacy and Anti-abuse

Date: 2026-10-04. Author: security/privacy specialist for the Aron rebuild. Inputs read in full: CLAUDE.md, PROJECT-CONTEXT.md, README.md, docs/01–13 and docs/22, db/schema.sql, db/seed/README.md and the sku_catalog.csv header, plan/seed-findings.md. Cross-referenced (IDs only, not re-derived): plan/lens-features.md (F-IDs), plan/lens-data.md (M-40 credentials, M-41 device/OTP, M-43 audit, M-45 config), plan/lens-sync.md (D-sync-01 per-user local DB, D-sync-03 logout semantics, `batch_uuid` replay), plan/lens-scale.md (D-scale-4 batch replay, D-scale-8 refresh-token daily login, G-scale-04 carrier NAT).

This lens is PLANNING. It adds what the spec lacks on authentication, authorization, PII, transport/storage, API hardening, fraud beyond GPS, audit, supply chain, OWASP mapping and the security test plan. Test gate IDs from this lens use the `T-<phase>-7n` range (70–79) so they do not collide with the data (01–19), sync (20–39) and scale (51–59) lenses. Decisions are `D-sec-nn`, gaps `G-sec-nn`, config keys `cfg.<area>.<name>` (areas owned here: `cfg.auth`, `cfg.sec`, `cfg.pii`, `cfg.fraud`; shared keys are named with their owner).

## 0. Summary of conclusions

1. **The auth model is "password rarely, refresh daily, device proves itself".** Password login happens on first use, after a server-side password change, or after 90 days. The daily login (bundle download) is a refresh-token exchange (D-scale-8) with rotation, reuse detection and a device-held key (Android Keystore) that signs each refresh, so a refresh token copied off a phone is useless on another one. Access tokens are 60-minute ES256 JWTs carrying `role`, the user's **top scope nodes** and a `scope_version`; the server expands reach itself (R6 of CLAUDE.md #4).
2. **Offline login is a local verifier, not a cached token.** The SR unlocks the app with no network against an Argon2id verifier written at the last online login, valid while the refresh token is unexpired and the last online auth is within `cfg.auth.offline_unlock_max_days` (14). The local DB key is device-bound (Keystore), never password-derived, so the sync engine can still upload user A's rows while user B works (D-sync-01). The sync lens's PBKDF2-100k verifier is below the OWASP floor (G-sec-07).
3. **Scope is enforced twice.** Primary: a request-scoped `ScopeContext` and a `scoped()` repository helper that every read and write must go through (lint rule, no raw query builder in controllers). Secondary: PostgreSQL row-level security on the transactional and PII tables keyed off `SET LOCAL app.user_id`, so a missed `scoped()` call returns zero rows instead of the nation. Every endpoint has a two-user scope-leak test (T-0-75) and an IDOR sweep (T-4-74).
4. **PII in practice is phone and owner name (docs/22 P-12), not NID/TIN.** NID/TIN/trade licence are envelope-encrypted (AES-256-GCM, DEK wrapped by Key Vault) because they are rarely read and never searched. Phone and owner name stay plaintext in `app.outlet` behind Postgres grants, a masked view for BI, redaction in logs, and an export log; whether phone must also be encrypted is a legal-review outcome (G-sec-04). Bangladesh's data-protection and localisation position is **unknown and must be confirmed** before wave 1 (G-sec-01); there is no Azure region in Bangladesh.
5. **Fraud controls are server-side signals, not client blocks.** Nineteen named schemes (fake sales, after-the-fact edits, ghost outlets, dues skimming, loyalty abuse, back-dated clocks, photo reuse, stock leakage, CPR inflation, …) each have a computable signal, a threshold key, a `risk_signal` row and a supervisor surface (AMO/TSO "Exceptions" screen, web Exceptions report). Nothing is auto-deleted: a paper memo exists and money changed hands. A **trusted capture time** (server anchor + monotonic elapsed) defeats clock back-dating, which GPS checks never see.
6. **Audit is append-only and leaves the database.** `app.audit_log` and `cfg.config_change_audit` are REVOKE-protected and trigger-protected against UPDATE/DELETE, hash-chained, and exported daily to an immutable (WORM) Blob container. Critical config keys (`cfg.geo.radius_m`, mock policy, token TTLs, loyalty rates) are maker-checker: the author cannot approve.
7. **Supply chain is boring on purpose.** OIDC from GitHub to Azure (no stored cloud secrets), actions pinned by SHA, lockfiles, CodeQL + Semgrep + Dependabot + OSV for pub, Trivy on images, cosign-signed images in ACR, SBOM per release, APK upload key separate from the signing key, Flutter `--obfuscate --split-debug-info`.
8. **Security gates per phase.** SAST/dependency/secret/IaC scans block CI from Phase 0; the gstack `/cso`-style audit gates pilot (end of Phase 2); an external pen test of API + web + Android gates wave 1 (Phase 7 entry), with no open high/critical.

Blockers: G-sec-01 (data-protection law and residency), G-sec-02 (device binding model is also a security decision), G-sec-03 (Apsis credential migration: hashes or forced reset).

## 1. Threat model

### 1.1 Actors and what they want

| # | Actor | Capability | Goal | Primary controls (section) |
|---|---|---|---|---|
| A1 | SR (insider, low skill, high motivation) | Owns the phone for the day; can install fake-GPS, change the clock, root the phone; knows the workflow | Hit targets without selling: fake sales, fake visits, back-dating, ghost outlets; skim dues and loyalty | 6 (fraud signals), 5.4 (trusted time), 1.4 (device integrity), docs/05 |
| A2 | AMO/TSO (insider, approves things) | Verifies outlets, issues OTPs, final-submits, assigns tasks | Approve a friend's ghost outlet; issue an OTP to bind an unauthorised phone; reopen days | 2.3 (separation of duties), 7 (audit), 6 FS-10/FS-11 |
| A3 | Admin / analyst (insider, high privilege) | Master data, config, targets, PII reports, DB access | Change radius or targets quietly; export the retailer phone list; alter history | 2.3 (admin split, maker-checker), 3 (PII grants, export log), 7 (immutable audit), 8 (no shared DB creds) |
| A4 | Thief / finder of a lost phone | Physical access to an unlocked or locked shared phone | Read outlet phone numbers and sales; sync bogus data as the SR | 1.5 (device revoke), 3.5 (on-device encryption, no backup), 1.3 (device key) |
| A5 | Opportunistic internet attacker | Internet access to Front Door; credential stuffing; scanning | Account takeover; data scrape via IDOR; DoS at 08:00 | 1.2 (rate limits, lockout), 2.2 (scope/RLS), 5 (hardening), scale lens 2.3 (WAF) |
| A6 | Supply-chain attacker | Compromised npm/pub package, GitHub action, CI secret | Backdoor the APK or API; steal Key Vault secrets | 8 |
| A7 | Former vendor / departing staff | Knows the old system; may hold old credentials, API docs | Reuse credentials; social-engineer OTPs | 1.2 (rotate everything imported), G-sec-03, CLAUDE.md guardrail |
| A8 | Carrier NAT (not malicious, dangerous) | Thousands of SRs share one IP at 08:00 | Trip per-IP limits and lock out a region | 5.1 (per-device limits; WAF per-IP thresholds high) |

### 1.2 Assets and classification

| Asset | Class | Why |
|---|---|---|
| Credentials, refresh tokens, OTPs, device keys, JWT signing keys, SAS signing identity | Secret | Direct account/system takeover |
| Outlet NID / TIN / trade licence | Sensitive personal (national identifiers) | Identity theft; legally sensitive; today unused (P-12) |
| Outlet owner name, phone, address; SR/AMO/TSO name, phone | Personal | 735k retailer phone numbers are a marketable list; employee data |
| GPS fixes per visit, attendance fixes, team-location map | Personal (employee location) | Employee monitoring; needs notice/consent policy |
| Sales, dues, loyalty, targets, prices (5 price types incl. distributor/NTO) | Commercially confidential | Competitor intelligence; retailer disputes |
| Photos (outlet, force-sale, gift hand-over) | Personal (faces, shop fronts) + evidence | Fraud evidence; must be tamper-evident |
| Audit and config history | Integrity-critical | The only proof of who changed what |

## 2. Authentication

### 2.1 Token design

| Item | Decision | Value / rule | Why |
|---|---|---|---|
| Access token | JWT, ES256 (P-256), `kid` header | TTL `cfg.auth.access_ttl_min` = 60 (bounds 15–120). Web admin console: 15 | Short enough that revocation lag is bounded; long enough that a trickle sync every few minutes does not refresh constantly (R4) |
| Claims | `iss`=`aron-api`, `aud`=`aron`, `sub`=user_id, `usr`=username, `rol`=role, `dev`=device_id (null on web), `scv`=scope_version, `sct`=[{t,i}] top scope nodes (≤ 16 entries), `cfv`=config_version at issue, `pii`=bool (`app_user.pii_access`), `perm`=[permission codes] (admin only), `jti`, `iat`, `exp` | Size ≈ 450–700 B | Top nodes, not the expanded route list: a WM's 1,100 routes do not belong in a header; the server expands (2.1) |
| Refresh token | Opaque, 256-bit CSPRNG, base64url; stored as SHA-256 hash (`app.refresh_token.token_hash`, M-40) | TTL sliding `cfg.auth.refresh_ttl_days` = 30 (7–60); absolute cap `cfg.auth.refresh_absolute_max_days` = 90 since last password auth | Sliding covers Eid breaks; the absolute cap forces a password every quarter |
| Rotation | Every `POST /auth/refresh` returns a new refresh token; the used one gets `replaced_by_id`, `replaced_at` | Family: `family_id` set at password login; all tokens in a chain share it | Standard rotation |
| Reuse detection | Presenting a token with `replaced_at` set **and** older than `cfg.auth.refresh_reuse_grace_s` (60) → revoke the whole `family_id`, write `security_event(kind=refresh_reuse)`, raise risk signal FS-18 | Within the grace window the server returns the **stored** response of the replacement (same pattern as `batch_uuid` replay) | Mobile networks lose responses; a device that never received its new token must not be locked out, but a copied token used a minute later must |
| Device proof (DPoP-style) | At bind, the app registers an EC P-256 public key generated in **Android Keystore** (StrongBox when present). Each `/auth/refresh` and `/auth/bind-device` carries `X-Device-Proof` = ES256 signature over `sha256(refresh_token) ‖ server_nonce_bucket(5 min) ‖ device_uuid` | Fallback: if the Keystore is unavailable (rare on Android 7+), bind with `device.hw_key=false` and raise the device's risk weight | A refresh token exfiltrated from a rooted phone cannot be used from a laptop or another phone |
| Signing keys | In Key Vault; API reads via Managed Identity; two keys active (`kid` current + previous) during rotation; rotate every 90 days; JWKS at `/.well-known/jwks.json` (public keys only) | Key rotation drill T-7-71 | No private key on disk or in env |
| Revocation (fast path) | Redis: `rev_user:<id>` = min_iat, `rev_dev:<id>` = min_iat, `scope_ver:<id>` | O(1) check per request. **Fail-open** for `/sync/*`, `/auth/refresh`, `/day/*` if Redis is unreachable (field availability wins for ≤ 60 min, the access TTL); **fail-closed** for `/admin/*`, `/reports/*` with PII, `/auth/bind-device` | A Redis outage must not stop selling; it may stop admin |
| Revocation (slow path) | `refresh_token.revoked_at`; all revocations also audited | Admin "force logout user", "revoke device", password change, scope change | |
| Scope change | `app_user.scope_version` bumped on any `user_scope`/`route_assignment`/role change; token with lower `scv` → 401 `scope_changed`; client refreshes and gets a new token | The client's 401 handling already exists (lens-sync 401 path) | A transferred SR cannot keep syncing under the old zone |

### 2.2 Password authentication

| Rule | Value | Config key | Notes |
|---|---|---|---|
| Hash | Argon2id, m=64 MiB, t=3, p=1 (server) | fixed; re-hash on login if parameters changed | ~60–90 ms on D8ds_v5 vCPU. Scale lens: at most a few logins/s because daily login is a refresh (D-scale-8) |
| Password policy (web, docs/09) | ≥ 12 chars, mixed case + digit, not one of last 10, not within 24 h of last change | `cfg.auth.pw_min_len` 12, `cfg.auth.pw_history` 10, `cfg.auth.pw_min_age_h` 24 | Already in lens-data M-40 `password_history` |
| Password policy (field roles) | **unknown; confirm with the business.** ASSUMPTION: the same 12-char policy would cause a support storm on wave day among 8,500 SRs who share phones and type in Bangla keyboards. Proposal: `cfg.auth.pw_policy_by_role`: field roles ≥ 8 chars with a deny-list of top 10k passwords and the username; supervisors/admin the full docs/09 policy | `cfg.auth.pw_policy_by_role` | Compensating controls for field: device binding + OTP, per-username lockout, refresh-based daily login |
| Breached-password check | Deny-list of 10k most common + username + `aktcl`/`aron` substrings | bundled list, no external call | No Have-I-Been-Pwned call (data egress, latency) |
| Lockout | 10 failed attempts per username in 15 min → lock 15 min, doubling to max 2 h; counter in `user_credential.failed_attempts/locked_until` (M-40) and Redis | `cfg.auth.lockout_attempts` 10, `cfg.auth.lockout_min` 15 | Temporary, not permanent: a permanent lock is a DoS lever on a shared phone |
| Uniform errors | `invalid_credentials` for unknown user and wrong password; same response time (hash a dummy) | — | No username enumeration; the username scheme (`sr334001`) is already guessable, so lockout + rate limit carry the weight |
| Admin reset | Admin/TSO (scope-bound) sets a temporary password → `must_change=true`; the temporary password is shown once, never stored in clear, expires in 24 h | `cfg.auth.temp_pw_ttl_h` 24 | Audited (7) |
| First login after cutover | See G-sec-03: import Apsis hashes if the algorithm is verifiable (bcrypt/argon2/PBKDF2 with known parameters) and verify-then-rehash on first login; otherwise forced reset via TSO-issued temporary password per SR on wave day | — | "Same logins where possible" (CLAUDE.md #6) means same **usernames**; passwords only if hashes migrate |

### 2.3 Device binding with TSO OTP

State machine for a (user, device) pair:

```
unbound ──login(password) ok──▶ bind_required (token has dev=null; only /auth/bind-device, /auth/logout allowed)
bind_required ──POST /auth/bind-device {deviceUuid, otp, publicKeyJwk, deviceInfo, integrity?} ok──▶ active
active ──TSO/admin suspend──▶ suspended (refresh/sync refused with 403 device_suspended; local capture continues; reactivation allowed)
active|suspended ──TSO/admin revoke (lost/stolen)──▶ revoked (refresh refused; on next contact the app wipes THIS user's local data after showing why; no reactivation; a new bind needs a new OTP)
active ──unbind by user count policy──▶ unbound (oldest binding when a user exceeds cfg.auth.max_devices_per_user)
```

| Element | Rule |
|---|---|
| OTP | 6 digits, CSPRNG; stored as HMAC-SHA256(server pepper, code) in `app.device_otp.code_hash` (M-41). TTL `cfg.auth.otp_ttl_min` 30 (10–120). `attempts` max `cfg.auth.otp_max_attempts` 5 then status `expired`. One active OTP per `for_user_id` (issuing a new one revokes the old) |
| Who can issue | TSO for users whose scope is inside the TSO's territory; admin with `security_admin` permission for anyone. Issuance is audited with issuer, target user, and later the device model/OS that consumed it (F-ADM-022) |
| What binds | The OTP binds **this user ↔ this device**. Limits: `cfg.auth.max_devices_per_user` 2, `cfg.auth.max_users_per_device` 3 (lens-sync values; **G-sec-02**: the business must confirm the model) |
| Device identity | `device_uuid` v4 generated on first run, stored in `flutter_secure_storage` (Keystore-backed). Not `ANDROID_ID`, not IMEI (restricted since Android 10, and PII-adjacent) |
| Device record | M-41 columns + `public_key_jwk`, `hw_key boolean`, `key_attestation jsonb` (Keystore attestation chain when available), `integrity_last jsonb` |
| Social engineering | An attacker who talks a TSO into an OTP still needs the SR's password (bind requires a password-authenticated `bind_required` token). The TSO panel shows the consuming device's model and the time; a bind from an unexpected model is a risk signal FS-19 |
| Wave-day throughput | 1,000 new devices in a morning = 1,000 OTPs issued by ~35 TSOs. Admin **bulk pre-issue** per zone (printed list, 24 h TTL, one per user) is allowed with the longer TTL `cfg.auth.otp_bulk_ttl_h` 24 and is itself audited |

### 2.4 Device integrity signals (weighted, never a hard block alone)

Collected at login/bundle (F-SYS-031) and stored in `device_integrity_log`: mock-location app installed/`isMocked` seen, developer options on, USB debugging on, root hints (su binary, Magisk packages, writable /system), `Build.TAGS=test-keys`, emulator hints, app signature mismatch, Play Integrity verdict (`MEETS_DEVICE_INTEGRITY` / `BASIC` / none; **unknown whether the fleet has Google Play services on every phone, confirm**), time-zone not Asia/Dhaka, auto-time disabled. Each contributes a weight to `device.risk_score` (`cfg.geo.integrity_weight` family, owned by the geo/cfg lens). Policy `cfg.geo.mock_policy` ∈ {flag, block_geo_valid, block_sale}, default `block_geo_valid` (a mocked fix can never be geo-valid; the sale proceeds as a force sale with a photo). Hard block of selling on integrity alone is a business decision (docs/05); default off.

### 2.5 Shared-device and logout semantics

| Situation | Behaviour | Security note |
|---|---|---|
| SR/AMO logout | Ends the UI session; keeps that user's local DB (`aron_u<id>.db`) and refresh token so the engine can still flush pending rows (D-sync-03) | The refresh token stays on the device, in Keystore-backed secure storage, bound to the device key; the local DB is encrypted with a device-bound key (3.5). Acceptable residual risk: another bound user of the same phone cannot read it through the app (separate DB, separate key per user: `dbkey_u<id>` wrapped by the Keystore master key) |
| Second user logs in | Separate DB, separate verifier, separate refresh token, separate memo counters | No cross-contamination; `cfg.auth.max_users_per_device` enforced server-side at bind |
| TSO logout | Wipes all local app data (docs/08), refusing while rows are pending unless PDA-to-Support ran (D-sync-03) | Wipe = delete DB files + secure-storage entries for that user + media queue files; `SQLCipher` key destruction makes the deletion cryptographically final even if file blocks remain |
| App lock on resume | Default: no re-authentication during the day (parity with the current app, ASSUMPTION). Configurable `cfg.auth.app_lock_idle_min` (0 = off; 30 for supervisors with PII) | Requiring a password per outlet would slow selling; the device is the trust anchor |
| Admin "force logout" | Revokes refresh family + `rev_user` min_iat; the device sees 401 at next online call, keeps capturing, shows "needs re-login" (F-SYS-002) | Never a kill switch for already-captured data |
| Lost/stolen phone | TSO: suspend → revoke. On `revoked`, the app's next contact gets 403 `device_revoked`, uploads **nothing**, wipes that user's local data and secure-storage entries. Offline forever: the Keystore key is behind the phone's lock screen (if set) and the data class is Personal, not Sensitive | Recommend MDM-less fleet policy: screen lock mandatory on shared phones (company policy, not app) — **unknown whether enforceable; confirm** |

### 2.6 Offline login (cached credential verification)

Requirement: the SR opens the app in a dead zone and sells (CLAUDE.md #1).

| Step | Design |
|---|---|
| Write | On every successful **online** password login: `salt_dev` = 16 B CSPRNG; `verifier = Argon2id(password, salt_dev, m=19 MiB, t=2, p=1)` (OWASP minimum; 0.2–0.6 s on a 2 GB phone, measured in T-1-71); store `{salt_dev, verifier, written_at, refresh_expires_at, user_id}` in `flutter_secure_storage` under `verifier_u<id>`. Overwrite on every online login so a server-side password change propagates at the next online login |
| Unlock offline | Allowed iff verifier exists **and** `now < refresh_expires_at` (local copy) **and** `now − last_online_auth ≤ cfg.auth.offline_unlock_max_days` (14, 1–30) **and** offline failed attempts < `cfg.auth.offline_lockout_attempts` (10). Failed attempt counter in secure storage; cool-down 15 min doubling. Success resets the counter |
| What it unlocks | UI + local DB for that user. Local reads/writes need no token. The sync engine refreshes when online; a 401 there → `needs_relogin` banner, capture continues |
| Why not password-derived DB key | The engine must upload user A's rows while B is logged in (D-sync-01). The DB key is therefore a random 256-bit key wrapped by the Keystore master key, not derived from the password. The verifier gates the UI only. Stated residual risk: a rooted, unlocked phone exposes that day's local data (Personal class) |
| Server-side change while offline | Password change or revoke takes effect at the next online refresh (401 → forced online login → verifier rewritten or deleted). Bounded by `access_ttl` for API calls, by `offline_unlock_max_days` for the UI |
| Clock games | The expiry checks use the device clock, which A1 can set back. Mitigation: store `last_online_auth_monotonic` (elapsedRealtime + boot_id) alongside wall time; if wall time went **backwards** relative to the last recorded wall time, treat the unlock window as expired after 24 h and raise FS-13 at next sync |

### 2.7 Web authentication (Next.js)

| Item | Rule |
|---|---|
| Transport of tokens | Access token in memory only; refresh token in an `HttpOnly; Secure; SameSite=Strict; Path=/auth/refresh` cookie. API calls use `Authorization: Bearer`, so CSRF cannot target data endpoints; `/auth/refresh` checks `Origin`/`Sec-Fetch-Site` and the cookie only |
| Lifetimes | `cfg.auth.web_access_ttl_min` 15; `cfg.auth.web_refresh_ttl_h` 24 (sliding) with absolute 7 d; idle timeout `cfg.auth.web_idle_min` 60 (admin console 30) |
| MFA | TOTP (RFC 6238) mandatory for `admin`; `cfg.auth.mfa_required_roles` default `[admin]`, recommended `[admin, top, wm]`. Recovery codes (10, single use). **Unknown**: whether AKTCL has Microsoft Entra ID for staff; if yes, Entra ID SSO (OIDC) for web roles `dmo/wm/top/admin` replaces local passwords + TOTP (G-sec-10) |
| Session binding | Refresh token hashed in `app.refresh_token` with `device_id = null`, `ua_hash`, `ip` (for the audit viewer, not for enforcement; carrier NAT makes IP binding wrong) |
| Headers | HSTS (1 year, preload after pilot), CSP (`default-src 'self'`; Next.js nonce for inline), `X-Content-Type-Options`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` (geolocation=self for the map), `frame-ancestors 'none'` |
| Reports with PII | Require `pii` claim and re-authentication within 15 min (`cfg.pii.reauth_min`) before an export containing phone/owner columns; every export logged in `report_export_log` (M-43) |

### 2.8 Auth endpoints (additions to docs/09)

| Endpoint | Change |
|---|---|
| `POST /auth/login` | Body `{username, password, deviceUuid?, integrity?}`; response adds `bindRequired`, `scopeVersion`, `configVersion`, `serverTime`. Rate-limited per username and per device (5.1) |
| `POST /auth/refresh` | Body `{refreshToken}` + header `X-Device-Proof` (field) or cookie (web); rotation + grace replay (2.1) |
| `POST /auth/bind-device` | Body adds `publicKeyJwk`, `keyAttestation?`, `deviceInfo{model, manufacturer, os, appVersion, abi, ram}` |
| `POST /auth/logout` | Revokes the presented refresh token only (not the family) so other bound users and the engine are unaffected |
| `POST /auth/logout-all` (web) / `POST /admin/users/:id/force-logout` | Family + `rev_user` |
| `POST /admin/devices/:id/suspend|revoke|reactivate` | Audited; scope-bound for TSO |
| `GET /.well-known/jwks.json` | Public keys for web/BFF verification |
| `POST /auth/mfa/enrol`, `/auth/mfa/verify` | Web only |

## 3. Authorization

### 3.1 Scope resolution (server-side, from `user_scope`)

| Piece | Design |
|---|---|
| Closure table | `app.geo_closure(anc_type, anc_id, desc_type, desc_id)` maintained by triggers on the geography tables (wing→division→territory→house→zone→route). Rows ≈ 11,336 routes × 6 ancestor levels ≈ 70k; trivial |
| Per-user reach | `app.user_route_reach(user_id, route_id, scope_version)` materialised on every `user_scope`/`route_assignment` change (and nightly for `valid_from/valid_to` roll-overs). SR reach = routes with an active `route_assignment` (today ± cover days, owned by the features lens); AMO = routes of the zone; TSO = territory; DMO = division; WM = wing; `top`/`admin` = all |
| Cache | Redis `reach:<user_id>:<scv>` as a sorted int array (a wing ≈ 1,100 route ids ≈ 9 KB); TTL 24 h; invalidated by `scope_version` bump |
| Non-geo scope | Product scope (Q14, TSO "Digonto"): `user_product_scope(user_id, category_id)` optional; default all categories. Program scope none |
| Enforcement objects | Transactional rows carry `route_id` (seed finding #3) so reach is a direct join. `outlet.route_id`; `attendance.user_id` → the user's own rows plus subordinate users via `user_route_reach ∩ route_assignment`; `app_user` visibility = users whose reach ⊂ caller's reach |

### 3.2 Row-level enforcement pattern in the API (NestJS)

1. `AuthGuard` verifies the JWT (signature, `exp`, `aud`, `iss`, `kid` in JWKS), checks `rev_user`/`rev_dev`/`scope_ver` (fail-open/closed per 2.1), builds `Principal{user_id, role, device_id, scv, perms, pii}`.
2. `ScopeInterceptor` opens one DB transaction per request (required under PgBouncer transaction pooling) and executes `SET LOCAL app.user_id = $1; SET LOCAL app.role = $2; SET LOCAL app.pii = $3` — the RLS context.
3. `ScopeContext` (request-scoped provider) exposes `reachRouteIds()`, `reachZoneIds()`, `assertRouteInReach(route_id)`, `assertOutletInReach(outlet_id)`, `assertUserInReach(user_id)`.
4. Every repository method takes `ScopeContext` and uses the `scoped(qb)` helper which appends `route_id = ANY($reach)` (or the zone/territory equivalent for `dw.agg_*`). An ESLint rule (`no-unscoped-query`) fails CI when a `Kysely`/`Prisma` query on a scoped table is built outside a `*Repository` class or without `scoped()`. 
5. Writes: the server **ignores** any `user_id`/`device_id` in device payloads and stamps them from the `Principal`. A record whose `outlet_id`/`route_id` is outside reach goes to `sync_quarantine` with `reason=scope_out_of_reach` (F-SYS-014), never silently accepted or dropped. Approvals: `verified_by`/`approved_by` come from the Principal; self-approval of own request is refused (`approver == requester → 409 separation_of_duties`).
6. **Defence in depth: PostgreSQL RLS** (`ENABLE ROW LEVEL SECURITY` + `FORCE`) on `app.outlet`, `app.visit`, `app.memo`, `app.memo_line` (via memo), `app.due_collection`, `app.loyalty_ledger`, `app.redemption`, `app.attendance`, `app.outlet_change_request`, `app.task`, `app.call_assessment`, `app.distribution_check`, `dw.dim_outlet_pii`. Policy template: `USING (route_id IN (SELECT route_id FROM app.user_route_reach WHERE user_id = current_setting('app.user_id', true)::bigint))`. Role `app_api` is subject to RLS; roles `app_job` (aggregation, importer) and `app_migrate` have `BYPASSRLS`. Performance gate T-4-70: RLS adds ≤ 10 % to p95 on the batch ingest and bundle paths, else RLS is kept on PII tables only and the app-level pattern remains the sole control on the rest (decision recorded).
7. Read models in `dw` are scoped by `geo_key` through the same closure; the web never receives or sends scope IDs beyond the filter **within** its reach (filter values are validated against reach, else 403).

### 3.3 Permission matrix (RBAC on top of scope)

Permissions are codes; roles map to sets. Admin is split into permission bundles so no single account holds everything.

| Permission | sr | amo | tso | dmo | wm | top | admin bundles |
|---|---|---|---|---|---|---|---|
| `field.capture` (visits, memos, dues, stock, attendance) | Y | Y | — | — | — | — | — |
| `outlet.request` (new/close/info) | Y | Y | — | — | — | — | — |
| `outlet.verify` | — | Y (zone) | — | — | — | — | — |
| `outlet.approve` | — | — | Y? (**unknown**: docs/06 says "web approval"; confirm who) | — | — | — | `master_data` |
| `day.sales_submit` | own route | own zone | — | — | — | — | — |
| `day.final_submit` | — | — | Y (territory) | — | — | — | — |
| `day.reopen` | — | — | — | — | — | — | `ops_admin` (audited, reason required) |
| `task.assign` | — | Y | Y | — | — | — | — |
| `otp.issue` | — | — | Y (territory) | — | — | — | `security_admin` |
| `device.suspend/revoke` | — | — | Y (territory) | — | — | — | `security_admin` |
| `leave.apply` / `leave.approve` | — | — | apply | approve | — | — | — |
| `target.set` / `target.revise.approve_L<n>` | — | — | ? | ? | ? | ? | `master_data`; levels per Q12 |
| `report.read` (scoped) | — | Y | Y | Y | Y | Y | all bundles |
| `report.export_pii` | — | — | `pii` flag | `pii` flag | `pii` flag | `pii` flag | `pii_officer` |
| `master.write` (geography, products, prices, routes, assignments, outlets) | — | — | — | — | — | — | `master_data` |
| `user.write`, `scope.write` | — | — | — | — | — | — | `security_admin` |
| `config.write` | — | — | — | — | — | — | `config_editor` |
| `config.approve` (critical keys) | — | — | — | — | — | — | `config_approver` (≠ editor of that change) |
| `finance.adjust` (dues write-off, loyalty adjustment) | — | — | — | — | — | — | `finance_admin` (maker) + `finance_approver` above `cfg.fraud.adjust_approval_mtk` (50,000 Tk) |
| `release.manage` (APK, min_version, waves) | — | — | — | — | — | — | `release_mgr` |
| `import.run` | — | — | — | — | — | — | `importer` (staging-first) |
| `audit.read` | — | — | — | — | — | — | `security_admin`, `config_approver`, `pii_officer` |

Rules: (a) a user holds one role plus zero or more admin bundles; (b) `config_editor` and `config_approver` may be the same **person** only for non-critical keys, and never for the same change; (c) break-glass: one `superadmin` account whose credentials live in Key Vault, MFA, used only through an audited "break-glass" flow that alerts the security owner; (d) no shared admin accounts; (e) service accounts (`app_api`, `app_job`, `app_migrate`, `bi_reader`) are Postgres roles authenticated with Entra ID Managed Identity, not passwords.

### 3.4 Object-level checks (IDOR)

Every `GET /x/:id`, `PATCH`, and every foreign key in a write body (`against_memo_id`, `supersedes_memo_id`, `outlet_id`, `assignee_id`, `task_id`) is resolved through `ScopeContext.assert*` before use. Memo edit: `supersedes_memo_id` must belong to the same `user_id` (or the same zone for AMO) and the same `outlet_id`; otherwise `rejected(reason=supersedes_foreign)`. Due collection: `against_memo_id` must be a memo of that outlet with `due_minor > 0`. T-4-74 sweeps every endpoint with another user's ids and expects 403/404 and zero rows.

## 4. PII and privacy

### 4.1 Inventory and treatment

| Field(s) | Where | Class | Who legitimately needs it | Protection |
|---|---|---|---|---|
| `outlet.nid`, `tin`, `trade_license` | `app.outlet`, `dw.dim_outlet_pii` | Sensitive | Admin `master_data` when editing; nobody in the field (P-12: unused today) | **Envelope encryption** (4.2); never in bundles; never in `dw.dim_outlet`; export requires `pii_officer` + reason |
| `outlet.contact_number`, `owner_name` | same | Personal | SR/AMO for their route (bundle label "name (code-phone-cluster)"); TSO/web for retailer browse; de-dupe | Plaintext in `app.outlet` behind grants; `phone_hash` = HMAC-SHA256(pepper, E.164 normalised) for matching; masked view `dw.v_outlet_masked` (`01*****123`); bundle carries phone **only for outlets in the SR's reach**; export logged |
| `outlet.address` | same | Personal | TSO/web | Same as phone; filled for 13 outlets today |
| `app_user.phone`, `full_name`, `email`, `employee_code` | `app.app_user` | Personal (employee) | Supervisors in scope, admin | Grants; `dim_user` without phone |
| `visit.lat/lng/accuracy/mock`, `attendance.*_lat/lng`, `device_integrity_log` | transactions | Personal (employee location) | AMO/TSO in scope (Team Location shows last synced fixes only); fraud job | Scoped reads; **notice**: in-app Bangla/English notice at first login explaining that location is recorded at check-in/out, outlet open, force sale and outlet capture only (never continuously) and is used for route verification; acceptance stored (`user_consent(user_id, policy_version, accepted_at)`) |
| Photos | Blob `photos/`, `support/` | Personal + evidence | SR (own), AMO/TSO in scope, approval panel | Private containers; API-issued read SAS 15 min scoped to one blob; EXIF stripped on device (GPS lives in the record, not the file); perceptual hash stored for dedupe (6) |
| Passwords, refresh tokens, OTPs, device keys | `app.user_credential`, `refresh_token`, `device_otp`, Keystore | Secret | nobody | Hashes only; HMAC with pepper for OTP; keys never leave Keystore/Key Vault |
| "PDA to Support" uploads (full local DB + logs) | Blob `support/` | Personal (whole day of outlets) | Support engineer | Encrypted client-side with a per-upload key wrapped by the API's public key (or at minimum a private container with no listing); 30-day retention; access via `support_upload` row + audit; download requires `security_admin` or `support` bundle |
| Apsis dump (raw files) | import staging | Personal + possibly credentials | Importer | Stored in a dedicated locked Blob container with immutability off and **30-day deletion after reconciliation**; any credentials/tokens discovered are catalogued and **rotated**, never reused (CLAUDE.md guardrail); PII columns go only into `app.*`/`dw.dim_outlet_pii`, never into `stg` copies that analysts can read |

### 4.2 Column protection options and the decision

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| A. `pgcrypto` `pgp_sym_encrypt` in SQL | Simple | Key must reach the DB session (in SQL text, so in logs/`pg_stat_statements`); no per-row AAD | Reject |
| B. Application-level envelope encryption: per-column DEK (AES-256-GCM), DEK wrapped by a Key Vault key (RSA-OAEP or AES-KW via Key Vault `wrapKey`), AAD = `outlet_id ‖ column` | Keys never in SQL; rotation = re-wrap DEK; ciphertext opaque to DBAs, backups, replicas | Cannot search/sort; code path for every read | **Adopt for `nid`, `tin`, `trade_license`** (never searched) |
| C. Tokenisation vault (separate service) | Strong separation | A second system to run at 8,500-user scale | Reject (over-engineering for three unused columns) |
| D. Plaintext + Postgres grants + TDE + masked views + export log | Searchable, zero code cost, bundle generation trivial | A DB dump or replica leak exposes 735k phones | **Adopt for `contact_number`, `owner_name`, `address` pending legal review (G-sec-04)**; if the review says "sensitive", switch to B with `phone_hash` for search (design already allows it: the bundle generator is the only hot reader) |

Encryption detail for B: table `app.pii_key(id, key_vault_key_id, wrapped_dek bytea, created_at, retired_at)`; ciphertext stored as `bytea` = `key_id(2) ‖ nonce(12) ‖ ct ‖ tag(16)`; DEK cached in API memory for ≤ 1 h; rotation job re-wraps (not re-encrypts) on Key Vault key rotation; `pii_access` is also enforced by RLS (`current_setting('app.pii')`).

### 4.3 Redaction in logs, telemetry and analytics

| Layer | Rule |
|---|---|
| API structured logs (pino) | Allow-list of fields per log event; `redact` paths `req.body.*.contact_number`, `*.owner_name`, `*.nid`, `*.password`, `*.refreshToken`, `authorization` header, `X-Device-Proof`; a final regex scrubber for BD phone shapes (`(\+?88)?01[3-9]\d{8}`) and 10/13/17-digit numeric runs; ids, `memo_no`, `client_uuid` allowed (lens-data 5.2) |
| App Insights / Sentry | `beforeSend` scrubber on device and server; no user names (ids only); sampling per scale lens; daily cap |
| Device logs | Same scrubber; logs rotate at 5 MB; included in PDA-to-Support upload (encrypted) |
| Postgres | `log_statement = 'ddl'`, `log_min_duration_statement` for slow queries only; `pgaudit` for role/DDL/`admin` role sessions; parameters are not logged (`log_parameter_max_length = 0`) |
| `dw`/BI | `dim_outlet_pii` separate with grants; `bi_reader` has none; `v_outlet_masked`; Power BI connects through the read replica only (lens-data 5.2) |
| Analytics events | No free-text fields from users; event names + ids |

### 4.4 Bangladesh legal and regulatory considerations (state of knowledge, 2026-10-04)

What I believe and how sure I am; **everything here must be confirmed by AKTCL legal counsel before wave 1 (G-sec-01)**:

| Topic | Belief | Confidence | Design consequence |
|---|---|---|---|
| General data-protection statute | Bangladesh has had successive drafts of a Personal Data Protection Act/Ordinance (2022–2025 drafts; an Ordinance may have been promulgated in 2025) with data-controller duties, consent, breach notification and a regulator; the exact in-force text, dates and penalties are **uncertain** to me | Low–medium | Build for the strict case: lawful-basis record, notice, consent log, DSAR (access/correction/erasure) tooling for retailers and employees, breach runbook (4.6) |
| Data localisation | Drafts contained localisation requirements for "sensitive"/"user-generated" data with government exemptions; whether the in-force text requires in-country storage of retailer PII is **unknown** | Low | There is no Azure region in Bangladesh (scale lens: Southeast Asia or Central India). If localisation applies: options are (a) classify PII fields and keep them in an in-country mirror (e.g. Azure Stack HCI / local DC) with only pseudonymised data in Azure, or (b) an exemption/registration. Decide before wave 1; the schema split (`dim_outlet_pii`, envelope encryption) keeps option (a) feasible without redesign |
| Cyber-security law | The Digital Security Act 2018 was replaced by the Cyber Security Act 2023, itself replaced by a Cyber Security/Protection Ordinance in 2025; these are criminal-law instruments on unauthorised access, not data-protection codes | Medium | Unauthorised access by insiders is a crime; the audit trail (7) and access logs matter as evidence |
| Employee location monitoring | No specific statute known; labour law (2006, amended) is silent on GPS; good practice = notice + proportionality | Medium | Location only at defined events (docs/05); notice + consent record; AMO/TSO see last synced fixes, no continuous track; retention of raw fixes limited (4.5) |
| Telecom/SMS | SMS to retailers (optional due-collection receipts, 6 FS-07) needs an approved A2P sender and consent; BTRC rules | Medium | If adopted, opt-in per outlet (`outlet.sms_opt_in`) and a stop keyword |
| National ID (NID) | NID data is governed by the Election Commission/NID Wing rules; storing NID numbers of retailers has legal sensitivity | Medium | Envelope-encrypt; do not build features on NID (P-12) |
| Tobacco control | Advertising restrictions on tobacco (Smoking and Tobacco Products Usage (Control) Act 2005/2013) may touch the AV/KV content and POSM features | Low (out of this lens) | Flag to the business; content lives in `campaign_content` and can be disabled |
| Cross-border transfer | If a localisation or transfer rule exists, Azure Southeast Asia (Singapore) is a transfer | Low | Record the transfer basis; consider Central India only if legal prefers (no technical difference) |

### 4.5 Retention and deletion

| Data | Retention | Deletion path |
|---|---|---|
| Transactions, aggregates, audit | Indefinite (business + evidence), per data lens | Never hard-deleted |
| Raw GPS fixes on `visit`/`attendance` | Keep lat/lng (needed for history and fraud); after `cfg.pii.gps_precision_after_days` (730) round to 3 decimals (~100 m) in `dw` facts; `app` rows archived per data-lens partitions | Job |
| Photos | Evidence 3 years (`cfg.pii.photo_retention_days` 1095) Hot→Cool→Cold, then delete unless under dispute hold | Blob lifecycle + `legal_hold` flag |
| `device_integrity_log`, `security_event` | 2 years | Partition drop |
| `support/` uploads | 30 days | Lifecycle |
| Refresh tokens | 30 days after expiry/revocation (forensics) | Job |
| Retailer erasure request | Outlet → `status=closed`, PII columns nulled/encrypted-key-destroyed, `owner_name` → `'[erased]'`, `phone_hash` kept for de-dupe only if legal allows; transactions keep `outlet_id` | Admin action, audited |
| Employee leaving | `app_user.status=disabled`, devices revoked, tokens revoked, PII retained per HR policy (unknown) | Admin |

### 4.6 Breach and incident response (minimum)

Runbooks: (1) lost/stolen device, (2) credential leak (user, admin, service), (3) suspected spoofing/fraud ring in a zone, (4) PII export misuse, (5) key compromise (JWT, DEK, SAS identity), (6) vulnerable dependency with a public exploit. Each names: detection signal, who decides, first 60-minute actions, evidence to preserve (audit export, Key Vault logs, App Insights), notification duty (legal to confirm the regulator/affected-person rule under 4.4), and the post-incident entry in `DECISIONS.md`. Tabletop exercise before wave 1 (T-7-72).

## 5. Transport, storage and platform security

### 5.1 Network and transport

| Control | Decision |
|---|---|
| TLS | Front Door managed certificate (DigiCert chain, trusted on Android 7.0+); TLS 1.2 minimum; HSTS. **No certificate pinning** in the app (a rotation or CA change would strand offline devices for a release cycle); instead the app verifies the hostname and refuses user-added CAs via Android network security config (`<trust-anchors><certificates src="system"/></trust-anchors>`), which already defeats casual MITM proxies on rooted phones |
| Cleartext | `usesCleartextTraffic=false`; API base URL is the only network constant in the APK |
| Origin exposure | ACA internal ingress, Front Door Private Link origin (Premium at full fleet; Standard with origin IP allow-list + `X-Azure-FDID` header check during pilot) |
| WAF | Managed rule set in Detection mode for the first pilot week, then Prevention; custom per-IP rate limits only as a DDoS backstop (≥ 20,000 / 5 min on `/sync/*`, G-scale-04); geo-filter: allow BD + the ops team's countries, log the rest (do not block outright: roaming SIMs exist) |
| Postgres | Private endpoint only; `require_secure_transport=on`; Entra ID authentication for all app roles via Managed Identity (no DB passwords in Key Vault at all); `azure_pg_admin` reserved for break-glass; Azure Policy denies public network access in prod |
| Redis | Azure Managed Redis with Entra ID auth, TLS, private endpoint |
| Blob | Private endpoints; `allowBlobPublicAccess=false`; shared-key access **disabled** (`allowSharedKeyAccess=false`) so only Entra/user-delegation SAS works; soft delete 14 d + versioning on `photos/`; immutable (time-based retention) container `audit-export/` |
| Internal calls | ACA → Postgres/Redis/Blob/Key Vault over private endpoints with Managed Identity; no service-to-service secrets |

### 5.2 Secrets and keys

| Item | Where | Access | Rotation |
|---|---|---|---|
| JWT signing keys (EC P-256) | Key Vault keys (`sign` only; non-exportable) | API MI: `sign`, `get public`; web: JWKS | 90 d, two active |
| PII DEK wrapping key | Key Vault key (`wrapKey/unwrapKey`) | API MI | 365 d; re-wrap job |
| OTP/phone-hash pepper | Key Vault secret | API MI | Pepper rotation requires re-hash; `phone_hash` has `pepper_version` column; rotate yearly or on compromise |
| SAS issuance | Storage Blob Data Contributor on the API MI; user-delegation keys | API MI | Automatic (7 d key life) |
| FCM / push credentials (if `cfg.ops.push_enabled`) | Key Vault secret | API MI | Vendor-driven |
| APK signing key | Play App Signing (Google holds the app signing key) **or** Key Vault-stored keystore for the side-load channel; **upload key** separate, in GitHub environment secret for `prod` only, protected by required reviewers | Release pipeline | Upload key yearly; app signing key never |
| GitHub → Azure | OIDC federated credentials per environment (dev/staging/prod), no client secrets | Workflow identity | n/a |
| Key Vault itself | RBAC (not access policies), soft delete + purge protection, private endpoint, diagnostic logs to Log Analytics 1 y | — | — |

### 5.3 Media (photo) path

1. Device compresses (≤ `cfg.media.photo_max_kb`), strips EXIF, computes `sha256` + perceptual hash (`phash64`), stores both on the record.
2. `POST /media/upload-url {client_uuid, purpose, sha256, bytes}` → server checks reach, that the referenced record exists or is in the same batch, per-device daily quota `cfg.media.max_photos_per_device_day` (200), and returns a **user-delegation SAS**: write-only (`c`/`w`), 15 min, pinned to `photos/{business_date}/{device_uuid}/{client_uuid}.jpg`, `Content-Type: image/jpeg` enforced by a stored-policy-free check at worker time.
3. Worker (Event Grid `BlobCreated`) verifies size ≤ `cfg.media.max_bytes`, content sniff = JPEG, `sha256` matches the record; mismatch → `photo_state=invalid` + FS-16. Blob metadata records `device_id`, `user_id`.
4. Reads: `GET /media/:id/url` returns a read SAS (15 min, single blob) only if the record is in reach; web images use that URL (never a public container).
5. Lifecycle Hot→Cool→Cold per scale lens; legal hold flag prevents deletion.

### 5.4 Trusted capture time (anti-back-dating; the control GPS cannot provide)

Problem: an SR sets the phone clock back to yesterday (or to the last day of the month) and captures sales that land on a favourable `business_date`.

Design: every bundle/sync response carries `server_time`. The app stores an **anchor** `{server_time, elapsedRealtime, boot_id}` in secure storage. Each record stores `captured_at_device` (wall), `captured_elapsed_ms`, `boot_id`. Server on ingest computes `captured_at_trusted = anchor.server_time + (captured_elapsed_ms − anchor.elapsed)` when `boot_id` matches the anchor's; otherwise (reboot since the anchor) it falls back to `captured_at_device`, bounded by `[last_server_contact, receipt_time]`, and flags `time_untrusted=true`. `business_date` is derived from `captured_at_trusted`. If `|captured_at_device − captured_at_trusted| > cfg.sync.max_clock_skew_min` (10): record kept, `clock_skew_flag=true`, FS-13. A record whose trusted time is later than its receipt time or earlier than `receipt − cfg.sync.max_backdate_days` is quarantined. The app also shows "set the phone's date/time" when skew > 10 min (scale lens 4.12).

## 6. API hardening

### 6.1 Rate limits (per device/user in Redis sliding window; WAF per-IP only as backstop)

| Endpoint | Key | Limit | Burst | On exceed |
|---|---|---|---|---|
| `POST /auth/login` | username | 10 / 15 min | — | 429 + lockout counter (2.2) |
| `POST /auth/login` | device_uuid (unauth'd, from body) | 30 / 15 min | — | 429 `Retry-After` |
| `POST /auth/refresh` | device_id | 20 / h | 5 | 429 |
| `POST /auth/bind-device` | device_uuid | 5 / h | — | 429; OTP attempts counted separately |
| `GET /sync/bundle` | device_id | 12 / h (full), 60 / h (delta/304) | — | 429 `Retry-After` 300 |
| `POST /sync/batch` | device_id | `cfg.api.rate_limit_per_device_per_min` 120 | 20 | 429 `Retry-After` 5–60 s jittered (scale lens) |
| `POST /media/upload-url` | device_id | 300 / day | 20 / min | 429 |
| `POST /day/*` | user_id | 30 / h | — | 429 |
| `GET /app/home`, `/reports/*`, `/dashboard/*` | user_id | 60 / min | 10 | 429 |
| `/reports/*?format=xlsx` | user_id | 20 / h | — | 429; large exports queued |
| `/admin/*` writes | user_id | 60 / min | — | 429 |
| any | device_id or user_id concurrent in-flight | 4 | — | 429 |
| Global | per replica in-flight batches | 64 (scale lens) | — | 429/503 |

Limits are `cfg.api.*` keys (server-only delivery) so launch-day tuning needs no deploy. Headers `RateLimit-Limit/Remaining/Reset` returned. Device-keyed limits use the authenticated `dev` claim; unauthenticated endpoints use the body's `deviceUuid` plus the username; IP is **not** a key (A8).

### 6.2 Payload and parsing limits

| Limit | Value | Key |
|---|---|---|
| `POST /sync/batch` compressed body | ≤ 1 MiB | `cfg.sync.batch_max_kb` |
| Decompressed body | ≤ 8 MiB and ratio ≤ 20:1 (gzip-bomb guard; streaming inflate with a hard cap) | `cfg.sync.batch_max_kb_raw` |
| Rows per batch | ≤ 500 | `cfg.sync.batch_max_rows` |
| Memo lines per memo | ≤ 60 (docs/22: max 40 observed) | `cfg.sale.max_lines_per_memo` |
| JSON depth / keys | depth ≤ 8; unknown keys rejected (`strict()` schemas) | fixed |
| String lengths | names ≤ 120, text/reason ≤ 1,000, `edit_reason` from enum | shared schema |
| Request timeout | 30 s server-side; 60 s client | fixed |
| Photo | ≤ 300 KB | `cfg.media.max_bytes` |
| Report rows | ≤ 50,000 synchronous; else async export | `cfg.api.report_max_rows` |

### 6.3 Idempotency and replay, seen from security

| Concern | Rule |
|---|---|
| `batch_uuid` replay cache | Keyed by `(device_id, batch_uuid)`, not `batch_uuid` alone; a different device presenting the same UUID gets a fresh ingest, never another device's stored response. Stored response contains no PII |
| Same `client_uuid`, different payload | Conflict (data lens DQ). Additionally: if the registered row belongs to a **different user**, respond `rejected(reason=conflict)` with no further detail and write `security_event(kind=uuid_collision_cross_user)`; two in a day for one device → FS-18 |
| Row ownership | `user_id`, `device_id`, `sync_batch_id` stamped from the Principal; a batch may only contain rows for the authenticated user (the engine uploads A's rows under A's token, never under B's) |
| Business date window | `business_date ∈ [today − cfg.sync.max_backdate_days (7), today + 1]` (Dhaka) else quarantine |
| Monotonic edits | `supersedes_memo_id` chain depth ≤ `cfg.memo.max_edits` (3); edit after `qc_entry` exists or after `day_state ≥ sales_submitted` → `rejected(reason=edit_window_closed)` and FS-02 |
| Replay of signed requests | `X-Device-Proof` includes a 5-minute nonce bucket; the server keeps `(device_id, proof_hash)` for 10 min in Redis to refuse exact replays |

### 6.4 Input validation from `/packages`

- One `zod` schema set in `/packages/contract` is the source of truth for the API, the web and (via generated JSON Schema → Dart `freezed`/`json_serializable` with validation) the app. Enums (`force_reason`, `edit_reason`, `task_type`, `price_type`…) are generated from `cfg` code lists where they are admin-editable, with the bundle carrying the live list and the server validating against the version the record claims (`config_version`).
- Numeric rules enforced server-side regardless of the client: `qty > 0` and `qty ≤ cfg.sale.max_line_qty_base` soft ceiling (flag, not reject; docs/22 P-16), `qty % pack_size == 0` when `unit=stick` (P-04; flag if not), `gross = Σ qty×unit_price`, `net = gross − discount`, `paid + due = net`, `paid ≥ 0`, `due ≥ 0`, `is_credit ⇔ due > 0`; `target ≥ 0` at entry (the −37,500 % bug).
- **Server price recomputation**: for every line the server looks up `sku_price(price_type=outlet, valid on business_date)` and the offer rules in force at `config_version`; a difference from the client's `unit_price`/`discount` does not reject (the paper memo exists) but sets `memo.price_mismatch=true` and feeds the Price Compliance count the AMO reconciliation screen already lists (docs/07).
- Coordinates: `lat ∈ [20.5, 26.7]`, `lng ∈ [88.0, 92.7]` (Bangladesh bounding box) else `geo_out_of_country` flag; `accuracy_m ∈ (0, 5000]`.
- IDs: all device ids are UUID v4 (regex + version nibble); server ids are `bigint` and must be in reach.
- Text: NFC-normalised, control characters stripped, length-bounded; no HTML rendering of user text anywhere (web escapes by default; printed memo renderer is text-only).
- Error envelope `{message, success:false, code}`; no stack traces, no SQL, no internal ids beyond the request id; `request_id` in every response and log.
- Security headers via `helmet`; CORS allow-list = the web origin(s) only; no wildcard; apps do not need CORS.

## 7. Anti-fraud beyond GPS

### 7.1 Principles

1. The server computes every signal from stored facts; the client never self-certifies more than the raw inputs.
2. Flag and surface; never auto-delete or auto-reverse a sale, due or point. Corrections are new rows with reasons (docs/02).
3. Every signal has a formula, a threshold config key (`cfg.fraud.*`, scope global/wing/zone), a severity and a surface (AMO/TSO "Exceptions" screen, web Exceptions report, risk score on the SR card).
4. Supervisors can mark a signal `reviewed / dismissed / confirmed` with a note; confirmations feed the SR's rolling `risk_score` and the Supervisory Module (F-ADM-025, G-feat-31).

### 7.2 Storage

```sql
CREATE TABLE app.risk_signal (
  id bigint GENERATED ALWAYS AS IDENTITY, at timestamptz NOT NULL DEFAULT now(), business_date date NOT NULL,
  kind text NOT NULL,                         -- FS-01 … FS-19
  severity smallint NOT NULL,                 -- 1 info, 2 low, 3 medium, 4 high
  subject_type text NOT NULL, subject_id bigint NOT NULL,   -- user | outlet | device | memo | visit | route | zone
  user_id bigint, route_id bigint, zone_id bigint,          -- for scoping (RLS/closure)
  evidence jsonb NOT NULL,                    -- the numbers that fired it (ids, counts, distances, thresholds)
  score numeric(6,2) NOT NULL, config_version int NOT NULL,
  status text NOT NULL DEFAULT 'open',        -- open | reviewed | dismissed | confirmed
  reviewed_by bigint, reviewed_at timestamptz, review_note text,
  PRIMARY KEY (id, business_date)) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.risk_signal (zone_id, business_date, status);
CREATE INDEX ON app.risk_signal (subject_type, subject_id, business_date);
CREATE TABLE app.security_event (id bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT now(), kind text NOT NULL, user_id bigint, device_id bigint, ip inet, request_id text, detail jsonb);
CREATE TABLE app.user_risk (user_id bigint PRIMARY KEY, score_30d numeric(6,2) NOT NULL DEFAULT 0, confirmed_30d int NOT NULL DEFAULT 0, open_signals int NOT NULL DEFAULT 0, updated_at timestamptz);
```

`dw.fact_risk_signal` mirrors it for reports. Signals are computed by the aggregation worker after each batch lands (cheap per-record checks) and by a nightly job (pattern checks over the day/month).

### 7.3 Fraud scheme catalogue

| ID | Scheme | Server-side signal (formula) | Threshold key (default) | Severity | Surface / action |
|---|---|---|---|---|---|
| FS-01 | **Fake sales to hit STD/memo targets** (phantom memos to real outlets, often on credit, later "collected" or left as dues) | Per SR per day: credit share `Σ due_minor / Σ net_minor` vs the SR's own 60-day median; month-end spike: last-3-day STD / month daily mean; outlets whose memo count jumps > 3× their 90-day mean; sales to outlets with `location_unconfirmed` or `status=closed` | `cfg.fraud.credit_share_x` (2.5), `cfg.fraud.month_end_spike_x` (2.0) | 3 | AMO Exceptions; web "Sales anomaly" report; month-end section |
| FS-02 | **Memo edits after the fact** (sell, print, then edit down or re-assign) | Any `supersedes_memo_id` where the original has `qc_entry` or `day_state ≥ sales_submitted` (rejected + flagged); edits with net reduction > `x`% ; edit count per SR per month; edit `geo_validated_server=false` | `cfg.fraud.edit_reduction_pct` (30), `cfg.fraud.edits_per_month` (10) | 3–4 | Rejected at ingest when outside the window; otherwise AMO Exceptions; Discount/Edit report |
| FS-03 | **Ghost / duplicate outlets** (new-outlet requests to inflate outlet count or CPR) | At request: `phone_hash` match with an existing outlet; name trigram similarity ≥ 0.6 within 100 m (PostGIS `ST_DWithin`); requester's new-outlet rate vs zone mean; new outlets with zero sales in 30 days after approval | `cfg.fraud.dup_name_sim` (0.6), `cfg.fraud.dup_radius_m` (100) | 3 | "Possible duplicate" badge in the AMO verification screen and web Approval Panel (cannot approve without choosing "merge" or "not a duplicate" + note) |
| FS-04 | **Dues skimming** (collect cash, never record; or record then edit the memo to erase the due) | Outlet due age > N days; dues of an outlet that keep growing while the outlet keeps buying for cash elsewhere; `due_collection` followed by a superseding memo lowering `due_minor`; collections exactly equal to round numbers vs the outstanding amount | `cfg.fraud.due_age_days` (30), `cfg.credit.max_due_minor` | 3 | TSO Exceptions; Dues ageing report; optional retailer SMS receipt on credit sale and collection (FS-07) |
| FS-05 | **Dues manipulation by admin** (write-offs) | Any `finance.adjust` above `cfg.fraud.adjust_approval_mtk` without approver; adjustments per outlet per quarter | (50,000 Tk) | 4 | Maker-checker; audit report |
| FS-06 | **Loyalty point abuse** (points earned on fake sales; redemption to a friendly outlet; cash-back pocketed) | Points are **computed server-side** from facts, never accepted from the client; redemption > server balance → `rejected(reason=insufficient_points)` (offline race across two devices is the honest case → the SR sees "needs attention"); redemptions per outlet per month > 1; cash-back redemptions where the outlet's own memos were all credit; gift photo missing after `cfg.loyalty.photo_due_days` (7) | `cfg.fraud.redemptions_per_outlet_month` (1) | 3 | Campaign Gift Redemption report; AMO Exceptions |
| FS-07 | **Retailer-confirmed money** (improvement, optional) | SMS receipt to `outlet.contact_number` on credit sale and on due collection (amount, memo no, balance) | `cfg.fraud.sms_receipts` (off) | — | Business decision: cost ≈ 1.2 lakh SMS/day at peak if all memos; restrict to credit + collections (≈ 10–20k/day, **unknown**; confirm). Needs consent (4.4) |
| FS-08 | **Clean-GPS fakery** (GPS not mocked but the SR stands in the market and ticks every shop) | Route-day: all visits within one 55 m cell (docs/22 P-10); inter-visit time < 90 s for ≥ 5 consecutive visits; zero accuracy variance; geo-valid rate 100 % with `accuracy_m` identical | `cfg.geo.min_fixes_for_jitter`, `cfg.fraud.min_visit_gap_s` (90) | 3 | Per-route "suspicious location" count (docs/05) |
| FS-09 | **Force-sale abuse** (always "internet problem") | Force-sale share per SR per week vs zone median; force sales on outlets that other SRs geo-validate fine; same force-sale photo hash reused | `cfg.fraud.force_share_x` (2.0) | 2–3 | AMO Exceptions; By-Route Geo Capture report |
| FS-10 | **Approver collusion** | Same AMO verifies > x % of a zone's new outlets that later go dormant; AMO verifying own SR-mode requests (blocked); TSO reopening days > n/month | `cfg.fraud.dormant_after_approval_days` (30) | 3 | DMO/web report |
| FS-11 | **OTP / device misuse** (binding an unauthorised phone) | Binds from a device model never seen in the zone; one device bound to users of different zones; more than `max_users_per_device`; bind at odd hours | — | 3 | TSO Device panel; security_event |
| FS-12 | **Attendance fraud** (check-in from home) | Check-in fix > `cfg.fraud.checkin_radius_m` (2,000) from the route's outlet centroid; check-in then no visit for > 2 h; check-out at exactly 17:00 with last visit hours earlier | (2,000) | 2 | GIGO report |
| FS-13 | **Clock back-dating** | `clock_skew_flag` or `time_untrusted` on records (5.4); wall time moving backwards between syncs | `cfg.sync.max_clock_skew_min` (10) | 3 | Sync-health + Exceptions |
| FS-14 | **CPR inflation with zero-sale calls** | Zero-sale share per SR per day vs zone median; zero-sale visits with dwell < 30 s; outlets with 100 % zero-sale for 4 weeks still "visited" | `cfg.fraud.zero_sale_share_x` (2.0), `cfg.kpi.count_abandoned_visits` | 2 | Strike-rate report footnote; AMO Exceptions |
| FS-15 | **Memo splitting** (one sale split into many memos to hit memo targets) | Same outlet, same SR, ≥ 3 memos within 15 min on one day; memos with 1 line and identical SKU across consecutive memos | `cfg.fraud.split_window_min` (15) | 2 | Memo report |
| FS-16 | **Photo reuse / tampering** | `sha256` duplicates across records; `phash64` Hamming distance ≤ 6 across different outlets/days; photo `sha256` mismatch at upload; photo captured > 10 min before/after the visit | `cfg.fraud.phash_distance` (6) | 3 | Approval panel + Exceptions |
| FS-17 | **Stock leakage** (issued stock not sold, not returned) | Per SR per day per SKU: `issued − Σ sold − returned ≠ 0` beyond tolerance; chronic negative (sold more than issued = sales not from this stock) | `cfg.fraud.stock_tolerance_units` (0) | 2–3 | Stock report; distributor reconciliation |
| FS-18 | **Credential / token abuse** | Refresh-token reuse outside grace (2.1); logins from > 2 devices in a day; cross-user `client_uuid` collisions; proof-signature failures | — | 4 | security_event → security owner alert; auto family revoke |
| FS-19 | **Discount / offer abuse** | Offer applied without the qualifying lines or DRP packs (server rule recompute, 6.4); `price_mismatch` rate per SR; discounts to the same outlet repeatedly at the cap | `cfg.fraud.price_mismatch_rate` (0.05) | 2–3 | Discount Report; Price Compliance |

### 7.4 Supervisor surfaces

| Surface | Role | Content |
|---|---|---|
| **Exceptions** tile (AMO app, TSO drawer) | AMO (zone), TSO (territory) | Open signals grouped by SR → kind; one-tap review/dismiss/confirm with note; offline-readable (delivered in the bundle for the supervisor's scope, ≤ 200 rows), actions sync like any record (`risk_review` client_uuid) |
| SR card risk badge | AMO/TSO Team Performance | `user_risk.score_30d` band (green/amber/red) |
| Web **Exceptions report** | all web roles in scope | Filters: date, kind, severity, status; drill to evidence; export (no PII beyond outlet code/name) |
| Web **Sales anomaly / Dues ageing / Edit log / Photo duplicates** | TSO+ | Report set additions to docs/09 |
| Sync-health | ops | security_event counts; FS-13/18 trend |

## 8. Audit logging

### 8.1 What is logged

| Event family | Table | Fields beyond M-43 |
|---|---|---|
| Master data CRUD (geography, products, prices, sales plan, routes, assignments, users, scope, outlets, classifications) | `app.audit_log` | `before/after/diff`, `request_id`, `actor_role`, `via` |
| Config changes | `cfg.config_change_audit` (M-45) | `approved_by` mandatory for critical keys; `config_version`; ack reach (data lens) |
| Targets and revisions | `app.audit_log` + `target_version` | approval level, approver |
| Approvals (outlet requests, leave, revisions) | `app.audit_log` | requester, verifier, approver (three different users where the flow has three steps) |
| Day control (final submit, reopen, Data Entry backfill) | `app.audit_log` | reason text mandatory on reopen/backfill |
| Security (login success/failure, refresh, reuse, bind, suspend, revoke, OTP issue/consume, MFA, force logout, password change/reset, break-glass) | `app.security_event` + `app.audit_log` for admin actions | `ip`, `ua_hash`, `device_id` |
| PII access (retailer detail view with phone, exports with PII, support upload download) | `app.report_export_log`, `app.audit_log(action='pii_read')` | `includes_pii`, filters, row_count |
| Finance adjustments (dues, loyalty) | `app.audit_log` | maker, checker, amount |
| Risk signal reviews | `app.risk_signal` status columns | — |
| Device-side activity | `app.activity_log` (batched in sync, F-SYS-024) | not security-grade (client-asserted) |

### 8.2 Immutability

1. Roles: `app_api` has `INSERT` + `SELECT` only on `app.audit_log`, `cfg.config_change_audit`, `app.security_event`, `app.report_export_log`; `UPDATE/DELETE/TRUNCATE` revoked from every role including `app_job`. Only `app_migrate` (CI, migrations) owns the tables; migrations never touch audit rows.
2. Trigger `audit_no_rewrite` BEFORE UPDATE OR DELETE raises an exception; partition detachment for archival is allowed only via the archival job after export (step 4).
3. Hash chain: `prev_hash bytea`, `row_hash = sha256(prev_hash ‖ canonical_json(row))` set by the insert trigger; a daily job verifies the chain and writes the day's head hash to `audit_export/`.
4. Daily export: NDJSON of the previous Dhaka day's audit rows to Blob container `audit-export/` configured with **immutable storage, time-based retention** (7 years, ASSUMPTION; legal to confirm) and legal-hold capability; the export's sha256 is written to the Log Analytics workspace (separate retention) so the chain is verifiable from outside Postgres.
5. Platform layer: Azure Activity Log, Key Vault, Postgres (`pgaudit` on `admin` role and DDL), Front Door WAF logs → Log Analytics, 1-year retention, with the workspace's own RBAC (no `app_api` access).
6. Viewer: F-ADM-034 reads only; filters by entity/actor/date; export is itself logged.

## 9. Mobile app hardening (Flutter / Android host)

| Control | Setting |
|---|---|
| Backups | `android:allowBackup="false"`, `fullBackupContent` none; Auto Backup off so the encrypted DB and secure storage never land in a Google account |
| Components | No exported activities/services/receivers beyond the launcher; no deep links; WorkManager jobs internal; FileProvider only for the printer/photo flows and `grantUriPermissions` scoped (android-intent-security skill applies at review) |
| Local DB | SQLCipher (Drift `sqlcipher_flutter_libs`) with a per-user 256-bit key wrapped by the Keystore master key (`flutter_secure_storage` with `encryptedSharedPreferences=true`, StrongBox when available) |
| Secure storage contents | refresh token, verifier, DB keys, device_uuid, time anchor, device key alias (private key itself never leaves Keystore) |
| Code | `flutter build --release --obfuscate --split-debug-info`, R8 enabled, `debuggable=false`; symbol files kept in the release pipeline for crash de-obfuscation (r8-analyzer skill) |
| Tamper | App verifies its own signing certificate hash at start (weight into integrity score); mismatch is a signal, not a block |
| Root / mock | As 2.4 (signals). No hard block by default |
| Network | Network security config: system CAs only, cleartext off; API host pinned by hostname only |
| Screens | `FLAG_SECURE` off by default (SRs screenshot memos for retailers today? **unknown**); on for supervisors' retailer-detail screens with phone numbers (`cfg.sec.flag_secure_pii_screens` true) |
| Clipboard | No PII copy buttons; phone dial via intent only |
| Permissions | location (while-in-use only; never background), camera, Bluetooth; **microphone: do not request** unless Q15 confirms voice recording is a real feature and consent is designed (G-sec-09); storage via scoped storage only |
| Logging | Release builds log at `warn+`; the scrubber of 4.3 applies; logs rotate |
| Updates | In-app updater downloads only from the Blob/Front Door URL in `app_release` with `sha256` verified before install; min-version gate never blocks offline capture or uploads (scale lens) |
| APK integrity | Per-ABI splits signed with the same key; the side-load channel publishes sha256 and the release page is the only distribution point communicated to TSOs |

## 10. Secrets, CI/CD and supply-chain hygiene

| Area | Rule |
|---|---|
| Repository | Branch protection on `main`: PR required, 1 reviewer (2 for `/infra`, `/db/migrations`, `/api/src/auth`, `/api/src/scope`), status checks required, signed commits recommended, force-push blocked; CODEOWNERS for the sensitive paths |
| Secrets in git | GitHub secret scanning + push protection on; `gitleaks` pre-commit and CI; `.env*` ignored; no secrets in the APK (verified by `apkanalyzer` + string grep in CI, T-1-74) |
| Cloud credentials | GitHub OIDC → Azure federated credentials per environment; `prod` environment requires a reviewer and is limited to the `main` branch; no long-lived service principal secrets |
| Dependencies (Node) | `npm ci` with lockfile; Dependabot (weekly, grouped) + `npm audit --audit-level=high` in CI; allow-list of registries; `ignore-scripts=true` in `.npmrc` with explicit opt-in per package; `socket.dev`/`npq`-style install-time checks optional |
| Dependencies (Dart) | `pubspec.lock` committed; OSV-Scanner on the lockfile in CI; `dart pub outdated --mode=null-safety` weekly; plugins reviewed for native code (printer, geolocator, secure storage, sqlcipher) and pinned |
| Actions | Third-party actions pinned by **commit SHA**; `permissions:` least privilege per job (`contents: read` default); no `pull_request_target` with checkout of PR code |
| SAST | CodeQL (JS/TS) + Semgrep (OWASP + custom rules: `no-unscoped-query`, no `console.log` of request bodies, no `pgp_sym_*`) blocking on high |
| IaC | Bicep in `/infra`; `PSRule for Azure` + `checkov` in CI; Azure Policy in prod (deny public network, require TLS 1.2, require MI); Microsoft Defender for Cloud on the subscription (Defender for Containers, Storage, Key Vault, DB) |
| Containers | Distroless/Alpine base pinned by digest; Trivy scan blocking critical; images signed with `cosign` (keyless, OIDC) and ACR admission check via ACA `--image` digest pin (not tag); SBOM (CycloneDX) per image stored with the release |
| Web | `next` version pinned; CSP as 2.7; no third-party scripts; map tiles self-hosted or from one allow-listed origin |
| Environments | dev/staging/prod isolated subscriptions-or-resource-groups with separate Key Vaults, Postgres, Blob, Redis; prod data never copied to dev (synthetic/anonymised seed only; the importer has an `--anonymise` mode for staging) |
| Release | Tagged releases; `CHANGELOG`; API and app versions compatible by `schema_version` in the contract; APK signing in a protected job; release notes include SBOM link and scan summary |
| People | Security owner named in `DECISIONS.md`; quarterly access review of GitHub, Azure RBAC, admin bundles, `pii_access` flags |

## 11. OWASP mappings

### 11.1 OWASP Top 10 (2021)

| Risk | Where it bites Aron | Controls (section) |
|---|---|---|
| A01 Broken Access Control | Scope leaks (the current build's biggest flaw), IDOR on memos/outlets, self-approval | 3.1–3.4, RLS, T-0-75, T-4-74 |
| A02 Cryptographic Failures | PII at rest, tokens, OTPs, local DB on shared phones | 2.1, 4.2, 5.2, 9 |
| A03 Injection | SQL via query builders, JSON, report filters, printed memo text | Parameterised Kysely/Prisma only; `zod` strict; no dynamic SQL in reports (named queries); Semgrep |
| A04 Insecure Design | Client-asserted geo/price/points; client-sent scope | Server recompute (6.4, 7.3 FS-06), server scope (3) |
| A05 Security Misconfiguration | Public Blob, open Postgres, verbose errors, debug APK | 5.1, 6.4 errors, 9, IaC policy |
| A06 Vulnerable Components | npm/pub/plugins | 10 |
| A07 Identification & Auth Failures | Credential stuffing on `sr334001`-style usernames, token theft, OTP abuse | 2.1–2.6, 6.1 |
| A08 Software & Data Integrity | CI compromise, unsigned APK side-loading, unverified updater | 10, 9 updates |
| A09 Logging & Monitoring Failures | Silent fraud, silent config changes, PII in logs | 7, 8, 4.3, scale-lens alerts |
| A10 SSRF | Report export, media URL handling, webhooks | No user-supplied URLs fetched server-side; blob paths constructed server-side only |

### 11.2 OWASP Mobile Top 10 (2024) and API Security Top 10 (2023), briefly

| Item | Control |
|---|---|
| M1 Improper credential usage | No secrets in APK; refresh token device-bound (2.1) |
| M2 Inadequate supply chain | 10 |
| M3 Insecure auth/authz | 2, 3; offline unlock bounded (2.6) |
| M4 Insufficient input/output validation | Shared schemas on device too (6.4) |
| M5 Insecure communication | 5.1 |
| M6 Inadequate privacy controls | 4; location only at events; notice/consent |
| M7 Insufficient binary protections | Obfuscation, R8, signature check (9) |
| M8 Security misconfiguration | allowBackup false, exported components off (9) |
| M9 Insecure data storage | SQLCipher + Keystore; no external storage (9) |
| M10 Insufficient cryptography | AES-GCM, Argon2id, ES256, no home-grown crypto |
| API1 BOLA / API3 Property-level auth | 3.2 step 5, 3.4; PII fields stripped by role in serialisers (`pii` claim) |
| API4 Unrestricted resource consumption | 6.1, 6.2 |
| API5 Broken function-level auth | Permission matrix 3.3; guards per route; default-deny |
| API6 Unrestricted access to sensitive business flows | Rate limits on bind/OTP/redemption; FS catalogue |
| API8 Security misconfiguration / API9 Inventory | One OpenAPI generated from `/packages`; unused endpoints removed; versioned `/v1` |

## 12. Security test plan per phase

Gate IDs `T-<phase>-7n`. "Block" = CI/phase exit blocks on failure.

| Gate | Phase | Test | Pass criterion | Block |
|---|---|---|---|---|
| T-0-70 | 0 | SAST: CodeQL + Semgrep on `/api`, `/web`, `/packages` | 0 high/critical | Y |
| T-0-71 | 0 | Dependency scanning: Dependabot + `npm audit`, OSV-Scanner on `pubspec.lock` | 0 critical; high triaged within 7 d | Y (critical) |
| T-0-72 | 0 | Secret scanning: GitHub push protection + gitleaks on history | 0 findings | Y |
| T-0-73 | 0 | IaC scan: PSRule for Azure + checkov on `/infra`; Azure Policy assignments present in staging/prod | 0 high | Y |
| T-0-74 | 0 | Auth negative suite: expired/garbage/alg=none/wrong-`kid`/wrong-`aud` JWTs; refresh rotation; reuse inside and outside grace; lockout; uniform error timing (±20 ms) | all rejected/behave as 2.1–2.2 | Y |
| T-0-75 | 0 | Scope-leak harness: for every endpoint, user A (zone X) vs user B (zone Y) with seeded data; property test over random scope trees | B never sees A's rows; RLS alone (with `scoped()` stubbed out) also returns 0 rows | Y |
| T-0-76 | 0 | `no-unscoped-query` lint; Postgres role grants snapshot test (`app_api` cannot UPDATE audit tables) | lint clean; grant diff empty | Y |
| T-1-70 | 1 | Device binding: OTP TTL/attempts; bind without password token refused; `X-Device-Proof` from another key refused; refresh from a second device with a copied token refused and family revoked | as specified | Y |
| T-1-71 | 1 | Offline unlock on a 2 GB Android 7/8 device: Argon2id verifier time ≤ 1.0 s; unlock works in airplane mode; 10 failures → cool-down; expired window → online required; password change online → old verifier fails at next online login | all | Y |
| T-1-72 | 1 | Batch forgery: payload `user_id` of another user ignored; outlet outside reach → quarantine; `business_date` −8 d → quarantine; `client_uuid` of another user's row → conflict + security_event; gzip bomb 100:1 → 413 within 50 ms | all | Y |
| T-1-73 | 1 | Rate-limit conformance (6.1) with k6 per device key; carrier-NAT simulation (500 devices, 1 IP) never trips the WAF | 429s only per device | Y |
| T-1-74 | 1 | APK config check in CI: `allowBackup=false`, `debuggable=false`, cleartext off, exported components list, no secrets strings, obfuscation on, per-ABI splits | all | Y |
| T-1-75 | 1 | Log redaction: inject phone/NID/password/token into every request path; grep App Insights + pino output | 0 leaks | Y |
| T-1-76 | 1 | Trusted time: device clock set −1 day and +1 day; records land on the correct `business_date`, `clock_skew_flag` set, FS-13 raised | all | Y |
| T-2-70 | 2 | Memo edit rules on server: edit after QC, after Sales Submit, outside geofence, chain depth 4 → rejected with the right reason; valid edit accepted and supersedes correctly | all | Y |
| T-2-71 | 2 | Price recompute: tampered `unit_price`/discount → accepted with `price_mismatch`; totals inconsistent (`net ≠ gross − discount`) → rejected | all | Y |
| T-2-72 | 2 | Photo pipeline: EXIF stripped; sha256 mismatch → invalid; duplicate photo across outlets → FS-16; SAS write-only/15 min/pinned path (attempt to PUT elsewhere → 403) | all | Y |
| T-2-73 | 2 | **`/cso`-style security audit** (gstack `/cso`) of API, web, app and infra before pilot | 0 high; mediums have owners and dates | Y (pilot) |
| T-2-74 | 2 | DAST: OWASP ZAP baseline + authenticated scan against staging | 0 high | Y |
| T-2-75 | 2 | Anti-spoofing: mocked fix never `geo_validated_server`; integrity signals stored; `cfg.geo.mock_policy` transitions behave | all | Y |
| T-3-70 | 3 | Separation of duties: AMO cannot verify outside zone or own requests; TSO cannot final-submit another territory; approver ≠ requester enforced | all | Y |
| T-3-71 | 3 | Device lifecycle: suspend blocks sync not capture; revoke → next contact wipes that user's data only; reactivate works; all audited | all | Y |
| T-3-72 | 3 | Final submit once per zone/day; reopen requires `ops_admin` + reason; audit chain verifies | all | Y |
| T-3-73 | 3 | Exceptions screen: seeded FS-01/02/03/08/09 scenarios appear to the right AMO/TSO only; review actions sync idempotently | all | Y |
| T-4-70 | 4 | RLS performance: p95 of `/sync/batch` and `/sync/bundle` with RLS on vs off | ≤ +10 %; else fall back per 3.2 step 6 and record the decision | Y |
| T-4-71 | 4 | `bi_reader` cannot select `dim_outlet_pii`; `v_outlet_masked` masks; replica only reachable via private endpoint | all | Y |
| T-4-72 | 4 | PII exports require `pii` claim + re-auth; every export logged with `includes_pii`; viewer shows it | all | Y |
| T-4-73 | 4 | Web: CSP/HSTS/headers (Mozilla Observatory ≥ A); CSRF on `/auth/refresh` from a foreign origin refused; idle timeout; MFA enforced for admin | all | Y |
| T-4-74 | 4 | IDOR sweep: every GET/PATCH by id with foreign ids, every FK in write bodies | 403/404, 0 rows | Y |
| T-5-70 | 5 | Loyalty: redemption over server balance rejected; points never accepted from client; adjustment above threshold needs approver | all | Y |
| T-5-71 | 5 | Fraud job on a synthetic dataset with 50 planted schemes across FS-01..19 | recall ≥ 90 %; false-positive rate on clean SRs ≤ 5 % per day | Y |
| T-5-72 | 5 | Dues: collection > outstanding rejected; collection against foreign memo rejected; ageing report correct | all | Y |
| T-6-70 | 6 | Config console: out-of-bounds radius rejected; critical key requires a different approver; change audited; `config_version` bumps; ack reach reported | all | Y |
| T-6-71 | 6 | Audit immutability: UPDATE/DELETE on audit tables fail for every role; hash chain verifies; daily export lands in the immutable container and cannot be deleted | all | Y |
| T-6-72 | 6 | Admin bundles: a `config_editor` cannot write users; break-glass use alerts within 5 min | all | Y |
| T-7-70 | 7 | **External penetration test** (API, web, Android app incl. local storage, infra) by an independent firm, scoped to staging at prod SKU with anonymised data | 0 open high/critical before wave 1; retest of fixes | Y (wave 1) |
| T-7-71 | 7 | Key rotation drill: JWT key rollover with no field logouts; DEK re-wrap; pepper version bump path tested | all | Y |
| T-7-72 | 7 | Incident tabletop: lost device, admin credential leak, spoofing ring; runbooks executed against staging; audit evidence exported | completed; actions logged | Y |
| T-7-73 | 7 | DR restore includes Key Vault keys (soft-delete recovery), audit chain continuity across restore | all | Y |
| T-7-74 | 7 | Apsis dump handling: credential discovery report produced; any live credential rotated; raw dump deleted 30 d after reconciliation; PII only in `app`/`dim_outlet_pii` | all | Y |
| T-7-75 | 7 | Wave-day auth storm: 1,000 binds + 9,850 refreshes in 60 min on staging at prod SKU; OTP panel bulk issue | 0 lockouts of legitimate users; p95 refresh < 300 ms | Y |

## 13. Configuration keys owned by this lens

Delivery: `token` = claims; `bundle` = shipped to devices; `server` = server-only. Critical = maker-checker required.

| Key | Default | Bounds / type | Scope levels | Delivery | Critical |
|---|---|---|---|---|---|
| `cfg.auth.access_ttl_min` | 60 | 15–120 int | global, role | server | Y |
| `cfg.auth.refresh_ttl_days` | 30 | 7–60 | global, role | server | Y |
| `cfg.auth.refresh_absolute_max_days` | 90 | 30–365 | global | server | Y |
| `cfg.auth.refresh_reuse_grace_s` | 60 | 0–300 | global | server | N |
| `cfg.auth.web_access_ttl_min` / `web_refresh_ttl_h` / `web_idle_min` | 15 / 24 / 60 | int | global, role | server | N |
| `cfg.auth.mfa_required_roles` | `[admin]` | list of roles | global | server | Y |
| `cfg.auth.pw_min_len` / `pw_history` / `pw_min_age_h` | 12 / 10 / 24 | int | global, role | server | Y |
| `cfg.auth.pw_policy_by_role` | see 2.2 | json | global | server | Y |
| `cfg.auth.lockout_attempts` / `lockout_min` | 10 / 15 | int | global | server | N |
| `cfg.auth.temp_pw_ttl_h` | 24 | 1–72 | global | server | N |
| `cfg.auth.otp_ttl_min` / `otp_max_attempts` / `otp_bulk_ttl_h` | 30 / 5 / 24 | int | global | server | N |
| `cfg.auth.max_devices_per_user` / `max_users_per_device` | 2 / 3 (G-sec-02) | int | global, role | server | Y |
| `cfg.auth.offline_unlock_max_days` | 14 | 1–30 | global, role | bundle | Y |
| `cfg.auth.offline_lockout_attempts` | 10 | 3–20 | global | bundle | N |
| `cfg.auth.app_lock_idle_min` | 0 (off); 30 for supervisors | 0–480 | global, role | bundle | N |
| `cfg.sec.redis_fail_open_paths` | `[/sync, /auth/refresh, /day]` | list | global | server | Y |
| `cfg.sec.flag_secure_pii_screens` | true | bool | global | bundle | N |
| `cfg.pii.roles_allowed` (shared with data lens) | `[admin, tso]` + `pii_access` flag | list | global | server | Y |
| `cfg.pii.reauth_min` | 15 | 0–60 | global | server | N |
| `cfg.pii.gps_precision_after_days` | 730 | int | global | server | N |
| `cfg.pii.photo_retention_days` | 1095 | int | global | server | Y |
| `cfg.api.rate_limit_per_device_per_min` (shared with scale lens) and the other rows of 6.1 as `cfg.api.rl.<endpoint>` | per 6.1 | int | global | server | N |
| `cfg.api.report_max_rows` | 50,000 | int | global | server | N |
| `cfg.media.max_photos_per_device_day` | 200 | int | global, role | bundle | N |
| `cfg.memo.max_edits` | 3 | 1–5 | global, zone | bundle | N |
| `cfg.sale.max_lines_per_memo` | 60 | int | global | bundle | N |
| `cfg.fraud.*` thresholds listed in 7.3 (`credit_share_x`, `month_end_spike_x`, `edit_reduction_pct`, `edits_per_month`, `dup_name_sim`, `dup_radius_m`, `due_age_days`, `adjust_approval_mtk`, `redemptions_per_outlet_month`, `min_visit_gap_s`, `force_share_x`, `dormant_after_approval_days`, `checkin_radius_m`, `zero_sale_share_x`, `split_window_min`, `phash_distance`, `stock_tolerance_units`, `price_mismatch_rate`, `sms_receipts`) | as 7.3 | numeric/bool | global, wing, zone | server | `adjust_approval_mtk`, `sms_receipts` Y; rest N |
| `cfg.geo.mock_policy` (owned by geo/cfg lens) | `block_geo_valid` | enum | global, territory, zone | bundle | Y |

## 14. Decisions proposed (for DECISIONS.md)

| ID | Decision | Reason |
|---|---|---|
| D-sec-01 | Access JWT ES256 60 min; opaque rotated refresh 30 d sliding / 90 d absolute; reuse detection with 60 s grace; Redis revocation fail-open for field paths, fail-closed for admin | Field availability (R5) vs admin safety; Q4 of docs/13 |
| D-sec-02 | Device-held EC key (Android Keystore) signs every refresh and bind (`X-Device-Proof`); fallback flagged `hw_key=false` | Shared, rootable phones; token exfiltration is the realistic theft |
| D-sec-03 | Offline unlock by Argon2id verifier (m=19 MiB, t=2) bounded by refresh expiry and 14-day window; local DB key device-bound, not password-derived | CLAUDE.md #1 and D-sync-01 together |
| D-sec-04 | Scope enforced by `ScopeContext`/`scoped()` (primary) **and** Postgres RLS (secondary), with a 10 % p95 budget for RLS | CLAUDE.md #4; defence in depth against the one missed query |
| D-sec-05 | Admin role split into permission bundles (`master_data`, `security_admin`, `config_editor`, `config_approver`, `finance_admin/approver`, `release_mgr`, `importer`, `pii_officer`, `ops_admin`); maker-checker on critical config and finance adjustments | Insider risk A2/A3; R6 audit trail |
| D-sec-06 | Envelope-encrypt NID/TIN/trade licence; phone/owner/address plaintext behind grants, masking and export logging pending legal review | P-12 reality; bundle needs phone in clear |
| D-sec-07 | No certificate pinning; system CAs only; TLS 1.2+; Front Door managed certs | Rotation safety for an offline fleet |
| D-sec-08 | Fraud = server-side `risk_signal` catalogue (FS-01..19), surfaced to AMO/TSO/web; nothing auto-reversed | docs/05 "report, don't just block" generalised |
| D-sec-09 | Trusted capture time from server anchor + monotonic clock; `business_date` derived from it | Clock back-dating is invisible to GPS checks |
| D-sec-10 | Audit tables append-only (REVOKE + trigger + hash chain) with daily export to immutable Blob | R6; evidence quality |
| D-sec-11 | Web: BFF cookie refresh + in-memory access; TOTP MFA for admin; Entra ID SSO if AKTCL has it | Standard; G-sec-10 |
| D-sec-12 | Photos uploaded by user-delegation SAS pinned to one path; EXIF stripped; sha256 + pHash stored | Evidence integrity; A1 photo reuse |
| D-sec-13 | Field password policy relaxed vs web policy with compensating controls (binding, lockout, refresh-based login) — **pending business confirmation** | Wave-day support load |
| D-sec-14 | Microphone permission removed unless Q15 confirms the feature and consent is designed | Privacy by default |

## 15. Gap list

| ID | Severity | Where | Gap | Proposed resolution / owner |
|---|---|---|---|---|
| G-sec-01 | blocker | docs/02 hosting, docs/13 Q3, 4.4 | Bangladesh data-protection and data-localisation obligations are unknown; no Azure region in Bangladesh; PII and employee location data would be stored in Singapore or India | AKTCL legal counsel opinion before wave 1; design keeps PII separable (`dim_outlet_pii`, envelope encryption) so an in-country mirror remains possible; record lawful basis and transfer basis in DECISIONS.md |
| G-sec-02 | blocker | docs/06 bind, docs/11 shared ownership, G-sync-01 | Device-binding model (users per device, devices per user, remote unbind, lost-phone wipe) is a security decision as much as a sync one; also whether shared phones have a screen lock | Business confirms `cfg.auth.max_users_per_device`/`max_devices_per_user` and the suspend/revoke/wipe semantics of 2.3/2.5 before Phase 1 DoD |
| G-sec-03 | blocker | docs/11 "same logins where possible", docs/13 Q18 | Whether Apsis password hashes (algorithm, parameters) are in the dump decides between seamless login and a forced reset of 8,500 passwords on wave day; any credentials/tokens in the dump must be rotated, never reused | Add to the dump request: hash algorithm + parameters; build both paths (verify-then-rehash; TSO temporary passwords); T-7-74 |
| G-sec-04 | major | docs/03 PII note, docs/09 rules, 4.2 | Phone/owner name protection level (plaintext with grants vs encrypted) is undecided and depends on G-sec-01; 735k phone numbers are the real exposure | Decide after legal review; code path for option B already planned (`phone_hash` for search) |
| G-sec-05 | major | docs/02 auth, docs/13 Q4 | Token lifetimes, rotation, revocation, device proof and offline login were unspecified | Section 2; D-sec-01..03; T-0-74, T-1-70, T-1-71 |
| G-sec-06 | major | docs/09 scope rule, schema | No enforcement pattern or defence in depth for scope; a single unscoped query would reproduce the current build's biggest flaw | Section 3.2; RLS; `no-unscoped-query` lint; T-0-75, T-4-74 |
| G-sec-07 | major | lens-sync §offline unlock | Proposed PBKDF2-HMAC-SHA256 at 100k iterations for the offline verifier is below the OWASP floor (600k) and weaker than Argon2id; no offline attempt limit or window was defined | D-sec-03; T-1-71 |
| G-sec-08 | major | docs/05, docs/10 | Fraud controls beyond GPS (fake sales, edits, ghost outlets, dues, loyalty, clock) are absent from the spec | Section 7 catalogue; `risk_signal`; Exceptions surfaces; T-5-71 |
| G-sec-09 | major | docs/06 permissions, docs/13 Q15 | Microphone permission and a `voicerecording` page exist in the current build; recording retailers without consent is a privacy and legal exposure | D-sec-14; if kept, consent capture + retention design |
| G-sec-10 | major | docs/09 web, docs/13 Q16 | Web roles (DMO/WM/Top/admin) have no MFA or SSO design; admin is a single undifferentiated role | 2.7 MFA; 3.3 admin bundles; confirm Entra ID availability |
| G-sec-11 | major | docs/03 audit, schema `activity_log` | Audit is a client-asserted `activity_log`; no immutable server-side audit, no PII export log, no security event log | Section 8; M-43 extensions; T-6-71 |
| G-sec-12 | major | docs/04 clock-skew test, docs/07 business date | Clock back-dating by the SR is undetected; `business_date` from device time is attacker-controlled | 5.4 trusted time; FS-13; T-1-76 |
| G-sec-13 | major | docs/09 `/media/upload` | Multipart upload through the API with no path pinning, size check or content verification; photo evidence not tamper-evident | 5.3; D-sec-12; T-2-72 |
| G-sec-14 | major | docs/02 "no PII in logs" | No redaction mechanism specified; PDA-to-Support uploads a whole local DB of retailer phones to support with no protection or retention | 4.3; 4.1 support row; T-1-75 |
| G-sec-15 | major | docs/09 rate limits absent; G-scale-04 | No per-device/user limits, payload caps or gzip-bomb protection; per-IP limits would lock out carriers | 6.1–6.2; T-1-73 |
| G-sec-16 | major | docs/06 memo edit, docs/13 Q11 | Edit window and post-final-submit changes are enforced on device only; who may reopen a day and under what audit is open | 6.3 monotonic edits; `ops_admin` reopen with reason; T-2-70, T-3-72 |
| G-sec-17 | minor | docs/06 Astha/Diamond League | Points accepted from the client would allow fabrication; redemption balance race across devices unhandled | FS-06: server-computed points; reject over-balance; T-5-70 |
| G-sec-18 | minor | docs/06 outlet requests, docs/07 verification | No duplicate-outlet detection at verification/approval | FS-03 badge in AMO screen and Approval Panel; `phone_hash`, trigram + PostGIS |
| G-sec-19 | minor | docs/02 CI/CD | No supply-chain controls (action pinning, OIDC, image signing, SBOM, APK key separation) | Section 10; T-0-70..73 |
| G-sec-20 | minor | docs/09 Credentials page | Password policy applies to web; field-role policy unknown; wave-day reset load unaddressed | D-sec-13; `cfg.auth.pw_policy_by_role`; business confirms |
| G-sec-21 | minor | docs/05 integrity signals | Play Integrity assumes Google Play services on every fleet phone; unknown for the shared low-end fleet | Confirm device census; weight-only usage so absence is not a block |
| G-sec-22 | minor | docs/04 "Team Location", docs/05 | Employee location monitoring has no notice/consent or retention rule | 4.1 consent record, 4.5 precision reduction after 2 years; policy text by HR/legal |
| G-sec-23 | minor | docs/11 pilot/parallel run | During the parallel run the new app also holds Apsis-migrated PII on pilot devices; coexistence with the old app on one phone doubles exposure | Pilot devices get the same hardening (9); pilot data purge on exit; note in cutover runbook |

## 16. Phase mapping (what this lens adds to docs/12)

| Phase | Security deliverables | Gates |
|---|---|---|
| 0 | `/packages` contract with strict schemas; `user_credential`, `password_history`, `refresh_token`, `device_otp`, `audit_log`, `security_event`, `pii_key` tables; JWT (ES256, Key Vault), refresh rotation + reuse detection, lockout, uniform errors; `ScopeContext`/`scoped()` + RLS policies + lint; Postgres roles via Entra MI; OIDC pipelines; SAST/deps/secrets/IaC scans; Azure Policy; private endpoints; redaction middleware | T-0-70..76 |
| 1 | Device binding with OTP + Keystore proof; offline verifier; SQLCipher per-user DB; secure storage; trusted time anchor; per-device rate limits (Redis or in-process for pilot); batch forgery checks; APK hardening checks; SAS media path skeleton | T-1-70..76 |
| 2 | Memo edit rules server-side; price recompute + `price_mismatch`; photo sha256/pHash + EXIF strip; mock policy + integrity weights; first `risk_signal` kinds (FS-02, FS-08, FS-09, FS-13, FS-16); `/cso` audit; DAST | T-2-70..75 |
| 3 | Separation of duties in approvals; device suspend/revoke/wipe; final-submit/reopen audit; AMO/TSO Exceptions screen; OTP panel with bulk issue | T-3-70..73 |
| 4 | Web BFF auth, MFA, CSP; PII claim + re-auth + export log; `bi_reader` grants and masked views; IDOR sweep; RLS performance decision | T-4-70..74 |
| 5 | Server-side loyalty points and redemption checks; dues rules; full FS catalogue + nightly fraud job; Exceptions/Dues ageing/Photo duplicate reports; optional SMS receipts (decision) | T-5-70..72 |
| 6 | Config console maker-checker + bounds; admin bundles; audit viewer; immutable export; break-glass | T-6-70..72 |
| 7 | External pen test; key rotation drill; incident tabletop; DR incl. Key Vault; Apsis dump credential handling; wave-day auth storm test | T-7-70..75 |
