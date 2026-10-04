# 21 — Security, Privacy and Anti-Abuse

Date 2026-10-04. Owner: security owner with the tech lead. Inputs: CLAUDE.md, docs/01 to 13, docs/22 (P-09, P-10, P-12, P-16), the four manual inventories and the manual delta register (G-man-017, 021, 024, 039, 062, 091 and s2.7), the verification files (device, outlets, web), the security lens, the fraud critic, and the decisions D-01 to D-269 as amended in doc 14. Where this document disagrees with the skeleton it still follows the skeleton and records the disagreement under "Open items".

**What this doc decides**

> 1. Authentication: password rarely, refresh daily, the device proves itself. ES256 access token (60 min, jittered), rotated opaque refresh token with reuse detection, a Keystore key that signs refresh, bind, every batch and every record family, a 4-digit reversible OTP read by the TSO, an Argon2id offline verifier, and an upload-only token so a shared phone keeps uploading without exposing another user's session (D-101 to D-105, D-68, D-471 to D-475; s2).
> 2. Authorisation: scope is derived on the server and enforced twice, by `ScopeContext` with `scoped()` and by PostgreSQL row-level security; write reach is judged as of the record's business date; admin authority is bounded permission bundles with maker-checker (D-106, D-470, D-490; s3).
> 3. PII: a class per field, a role-by-subject matrix, envelope encryption of NID, TIN and trade licence, masked lists with row budgets, sanitised and watermarked exports, log redaction, retention and the legal unknowns stated as such (D-107, D-108, D-120, D-121, D-482; s4).
> 4. Anti-abuse: server-side signals FS-01 to FS-33 instead of client blocks, a mock-flag invariant, radio-environment corroboration, plausibility rules with default thresholds, outlet-location drift controls, supervisor surfaces, and an SR who sees only the mock warning (D-96, D-109 to D-112, D-123, D-476 to D-481, D-487; s7).
> 5. Audit and supply chain: append-only hash-chained audit exported to WORM storage, PII-read and export logs, OIDC-only pinned CI, signed images, a reproducible APK published by a human, and a security gate in every phase from T-0-70 to T-7-77 (D-113, D-122, D-484, D-485; s8 to s10).

## 1 Threat model

The system holds three things attackers want: money-moving records (memos, dues, loyalty points) that 8,500 reps are paid on, 735k retailer phone numbers, and the location of 8,500 employees. The adversary that matters most is the insider (docs/05: reps already defeat the geofence with fake-GPS apps), so the controls are built on one rule: the device is hostile, the server recomputes everything it can and flags the rest.

### 1.1 Actors

| ID | Actor | Capability assumed | Goal | Controls |
| --- | --- | --- | --- | --- |
| SR | Dishonest SR on a shared stock phone | Installs Play-Store apps; changes clock, permissions and airplane mode; knows the workflow and the KPIs he is paid on; lends the phone | Hit STD, memo and CPR targets without selling; skim cash; keep stock | s7 signals, D-20 trusted time, docs/05 |
| SR+ | SR with a rooted phone or a technical helper | Magisk with DenyList, LSPosed or Frida hooks, a SQLite editor, mitmproxy with a system CA, curl with a stolen token, a GPS joystick, possibly an SDR | The same at scale; sells the method to peers | s2.6, s5.5, s7.2 |
| AMO | Careless or colluding AMO | Verifies outlet requests, dismisses exceptions, sells, assigns tasks, sets sub-channel at verification | Protect his SRs' numbers; favour a relative's shop | s7.6, s3.4 |
| TSO | Colluding or careless TSO | Reads OTPs, resets passwords, final-submits, edits radius within bounds, enters targets, approves outlets | Territory KPIs; cover for a ring; take over an SR identity | s2.5, s7.6 |
| ADM | Disgruntled admin holding permission bundles | Edits config, master data, targets; runs Data Entry; exports reports | Alter history, loosen controls, exfiltrate the retailer list | s3.5, s4.5, s8 |
| SUP | Support engineer | Opens PDA-to-Support bundles, replays a device file through the admin import | Curiosity; alter a replay | s5.4, D-480 |
| EXT | Outside attacker | Internet access to Front Door; credential stuffing; a stolen phone; a botnet of a few hundred IPs | Account takeover, scraping, disruption at 07:00 | s2.4, s6 |
| CI | Supply-chain attacker | Compromised npm or pub package, GitHub action, CI runner, the device-lab runner | Backdoor the APK or the API | s9 |

A carrier-grade NAT is not an actor but shapes every limit: thousands of SRs share one public IP, so no control in this document uses the source IP as a lockout or rate-limit key (D-116; s6).

### 1.2 Assets and classes

| Asset | Class | Why | Section |
| --- | --- | --- | --- |
| Passwords, refresh tokens, OTP values, device keys, JWT signing key, SAS identity, DEK wrapping key, peppers | Secret | Direct takeover | s2, s5 |
| Outlet NID, TIN, trade licence | Sensitive personal | Identity theft; today unused (docs/22 P-12: NID is the placeholder `123` for 589k outlets, blank for 145k, TIN and licence empty) | s4 |
| Outlet owner name, phone, address; SR, AMO, TSO name and phone | Personal | Phone and owner are 100 percent filled: the real exposure | s4 |
| GPS fixes, attendance fixes, Team Location | Personal (employee location) | Monitoring needs notice and proportionality | s4.7 |
| Sales, dues, loyalty, targets, prices incl. distributor and NTO | Commercially confidential | Competitor intelligence; retailer disputes | s3 |
| Photos (force sale, outlet, gift hand-over) | Personal and evidence | Fraud evidence; may show people | s5.3 |
| Audit, config history, risk signals | Integrity-critical | The only proof of who changed what | s8 |
| Apsis dump | Personal, may hold credentials | CLAUDE.md guardrail: data to migrate or rotate, never reused against a live service | s2.9, s4.8 |

### 1.3 Principles used by every later section

| # | Principle |
| --- | --- |
| 1 | The server recomputes geo verdict, prices, totals, points, business date and scope; the client self-certifies only raw inputs (D-117). |
| 2 | Flag and surface, never auto-reverse or auto-delete a sale, due or point: a printed memo exists and cash changed hands. Corrections are new rows (D-22, D-109). |
| 3 | Security never sits in the critical path of a sale: a failed refresh, a revoked token or a Redis outage stops reads and uploads, never capture (CLAUDE.md 1; doc 17 s7.2). |
| 4 | Every control that adds device work is measured against the docs/04 budgets (D-73): no new sensor, no new background service, no extra network round trip for security. |
| 5 | A control that can lock out 8,500 honest reps on a wave morning is a defect even if it stops an attacker (G-fraud-12). |
| 6 | Weights, not hard blocks, for unreliable signals (root hints, attestation, Play Integrity); a hard block needs a business decision (docs/05). |

Proved by: T-0-74, T-0-75, T-1-72.

## 2 Authentication

Model: password rarely, refresh daily, the device proves itself. The password is typed on first use, after refresh expiry, after a password change and after an explicit logout (a local check against the verifier, s2.7). The daily server leg is a refresh-token exchange with no password hash (D-scale-8 as carried by D-101), which is what keeps the first morning of a wave from becoming a hashing storm.

### 2.1 Endpoints (docs/09 plus additions)

All paths are under `/v1`. Mode uses the skeleton words: ONLINE-ONLY means the call needs the network and the app shows the state; QUEUED means the app can call later. A failed auth call never blocks local capture (principle 3).

| Endpoint | F-id | Mode | Sub-milestone | Gate | Behaviour |
| --- | --- | --- | --- | --- | --- |
| `POST /auth/login` | F-API-001 | ONLINE-ONLY | 0c | T-0-74 | `{username, password, deviceUuid, integrity?}`; adds `bindRequired`, `scopeVersion`, `configVersion`, `serverTime` to the response; rate-limited per username and device, never per IP |
| `POST /auth/refresh` | F-API-002 | ONLINE-ONLY (failure never blocks capture) | 0c | T-0-74, T-1-55 | Rotated opaque token with reuse detection; `X-Device-Proof` on devices, cookie on the web; `grant=full` or `grant=upload` (D-471) |
| `POST /auth/bind-device` | F-API-003 | ONLINE-ONLY | 1b | T-1-70 | `{deviceUuid, otp, publicKeyJwk, keyAttestation?, deviceInfo}`; needs a password-authenticated `bind_required` token |
| `POST /auth/change-password` | F-API-004 | ONLINE-ONLY | 0c | T-0-74, T-0-79 | Policy-checked; revokes every full-grant family of the user except the caller's |
| `POST /auth/logout` | F-API-031 | QUEUED | 1c (grants), 2e (logout screens) | T-1-78 | `scope=session` revokes the full grant; `scope=upload` revokes the upload grant after the outbox is empty (D-471) |
| `GET /.well-known/jwks.json` | none (platform) | CACHED | 0c | T-0-74 | Public keys only, for the web BFF and the verifier; cached at Front Door |
| `POST /auth/mfa/enrol`, `/auth/mfa/verify` | F-WEB-043 | ONLINE-ONLY | 4c | T-4-73 | TOTP, web only |
| `POST /admin/devices/:id/suspend`, `/revoke`, `/reactivate`; `POST /admin/users/:id/force-logout` | F-ADM-009 | ONLINE-ONLY | 3b | T-3-71 | Scope-bound for the TSO; always audited |
| `GET /admin/device-otp`, `POST /admin/device-otp` (re-issue, bulk pre-issue) | F-API-036, F-ADM-022, F-ADM-069 | ONLINE-ONLY | 0c (view), 7b (bulk) | T-3-78, T-7-75 | View-only for the TSO; issue actions for `ops_admin` and the bulk path |
| `POST /support/decrypt` | needs F-id (OI-21-12) | ONLINE-ONLY | 2e | T-2-78 | Support-bundle decryption through Key Vault (D-480) |

### 2.2 Access token

| Item | Decision | Value and key | Why |
| --- | --- | --- | --- |
| Format | JWT, ES256 (P-256) with `kid` | JWKS at `/.well-known/jwks.json`; two keys active during rotation; rotation every 90 days | D-101 |
| Signing | Key Vault EC P-256 root key, non-exportable, `sign` only; replicas sign access tokens with a delegated key (s2.2b, D-574) | The API holds no root private key. First-morning peak is 12.3 logins per second (doc 18 ST-03); at 25 signs per second (ASSUMPTION: logins plus refreshes in the worst 10 minutes) that is 250 operations per 10 s against 2,000 per 10 s published for HSM-protected EC keys per vault (Microsoft Learn, Key Vault service limits, read 2026-10-04) | Verification is local with the cached public key: no Key Vault call per request |
| Lifetime | 60 minutes for devices, 15 for the web, plus or minus 10 minutes of jitter at issue | `cfg.auth.access_ttl_min` 60, `cfg.auth.access_ttl_jitter_min` 10 | Spreads the hourly expiry wave (G-sre-06) |
| Refresh trigger | Piggybacked on a request made under 5 minutes before expiry; never a timer | `cfg.auth.min_refresh_interval_s` 300 | R4: no wake-ups for auth |
| Grace | A token expired by up to 60 s is accepted on `POST /sync/batch` only | fixed | A batch signed before expiry may travel slowly on 2G |
| Expiry clock | The device judges expiry on trusted time (anchor, doc 17 s5.5); a 401 carries `serverTime` so a skewed phone re-anchors instead of looping | T-1-55 | A phone 3 h fast must not refresh in a loop |
| Claims | `iss`, `aud` (`aron-api` or `aron-upload`), `sub` (user id), `usr`, `rol`, `dev` (null on web), `scv` (scope_version), `sct` (top scope nodes, at most 16 as `{type, id}`), `cfv` (config version), `pii` (boolean), `perm` (admin bundles only), `jti`, `iat`, `exp` | About 450 to 700 B (ASSUMPTION: lens estimate, measured in T-0-74) | Top nodes, not the expanded list: a wing is about 1,100 routes (11,336 routes over 10 wings), too big for a header; the server expands reach itself (s3.1) |

### 2.2b Key Vault dependency, delegated signing keys and the signing outage (D-574, G-qa-110)

Login and refresh each called Key Vault `sign`. The arithmetic was fine (25 signs a second against 2,000 per 10 s) but the same vault and the same limit also served PII DEK unwrap, OTP and support-bundle keys, and "running replicas keep working" is true for cached SECRETS, not for signing. A Key Vault outage or throttle longer than the access lifetime (60 minutes plus jitter) would leave every device unable to refresh, so uploads would stop at the 401 even though the data is safe on the phone, which defeats R5 for that period. The dependency and its failure mode are now stated and removed from the per-request path.

| Element | Design |
| --- | --- |
| Dependency (the failure mode, stated) | Without this section: Key Vault unavailable or throttled for more than about 60 minutes means no token can be minted or refreshed; devices hold valid tokens for at most 60 minutes, then every `/sync/batch` returns 401. With it: see the rows below |
| Delegated signing keys | Each API and `auth` replica generates an in-memory ES256 key pair at start and asks Key Vault ONCE to sign a delegation statement `{replica kid, public key, not_before, not_after (24 h)}` with the root key. Access tokens are signed locally by the replica key and carry the statement in the header (`dlg`). Verification stays local: verify the statement against the cached root public key, then the token against the delegated public key. Key Vault `sign` calls fall from about 25 a second to about 2 a replica a day. The delegation is renewed at 12 h, so a Key Vault outage of up to about 12 hours is invisible to minting and refresh. The private delegated key never leaves replica memory; a compromised replica is revoked by a `kid` deny-list in Redis and the statement expires in at most 24 h |
| Separate vaults | Signing lives in its own vault `kv-sign` (root key only); PII DEK wrapping, OTP, pepper and support-bundle keys live in `kv-pii`, so a throttle on DEK unwrap during a big export cannot starve signing and vice versa. DEKs are unwrapped once per replica per rotation and cached in memory for `cfg.sec.dek_cache_min` (60) |
| Upload grant | The upload-only grant (`aud = aron-upload`, s2.7) has an access lifetime of `cfg.auth.upload_access_ttl_min` (360 minutes, bound 60 to 1,440) because it can only post batches and media; this stretches the margin for uploads beyond the 12 h to a full day without widening the full grant |
| DR vault | A non-exportable key cannot be copied, and a Key Vault backup restores ONLY into a vault of the same subscription and the same Azure geography (Microsoft Learn, Key Vault backup and security worlds, read 2026-10-04). The DR design is therefore: a second vault `kv-sign-dr` in East Asia, in the same subscription and, ASSUMPTION to confirm against the Azure geography map, the same Asia Pacific geography as Southeast Asia, receives a backup of the root key after every rotation (a pipeline step with two-person approval) and is restored in the DR drill; if the two regions were NOT in one geography, the fallback is a SECOND root key published in the JWKS from day one with the DR replicas signing under it. Both options keep `kid`s in the JWKS so tokens from either root verify |
| Outage runbook | RB-48 (doc 18): confirm the vault status, check `delegation_expires_at` per replica in the ops workbook, do not restart replicas (a restart discards the in-memory delegated key and needs a fresh `sign`), raise the Sev, and tell support that uploads continue |
| Drill | T-4-173: Key Vault made unreachable for 90 minutes on staging during S2 load; minting, refresh and uploads continue; a replica restarted inside the window cannot sign and drops out of the minting pool while verification still works; the alert fires at the first failed `sign` |

### 2.3 Refresh token, rotation, revocation and the expiry wave

| Rule | Design |
| --- | --- |
| Format | Opaque, 256-bit CSPRNG, base64url; stored only as SHA-256 in `app.refresh_token` with `family_id`, `grant` (full or upload), `device_id`, `replaced_by_id`, `replaced_at` |
| Lifetime | Sliding 30 days (`cfg.auth.refresh_ttl_days`, bounds 7 to 90) so a nine-day Eid break does not log everyone out; absolute 90 days since the last password login (`cfg.auth.refresh_absolute_days`, 30 to 180) PLUS OR MINUS a per-family random jitter drawn at mint (`cfg.auth.refresh_absolute_jitter_days`, 15, so 75 to 105 days; D-518), so a wave cohort bound on one pre-bind day does not hit the absolute expiry on one calendar day; unknown; confirm with the business (Q4), proceed with these |
| Rotation | Every `POST /auth/refresh` returns a new token and marks the old one `replaced_at` |
| Reuse detection | A replaced token presented after `cfg.auth.refresh_grace_s` (60) revokes the whole family, writes `security_event(kind = refresh_reuse)` and raises FS-18. Within the grace window the server returns the stored response of the replacement: a phone that never received its new token (2G, a dropped response) must not be locked out. The stored response contains the new token, so it is AES-GCM encrypted in Postgres with a 120 s expiry and never held in Redis |
| Fast revocation | Redis `rev_user:<id>` and `rev_dev:<id>` hold a minimum `iat`; `scope_ver:<id>` holds the current scope version; one O(1) read per request |
| Redis outage | DECISION D-491: fail open for `/sync/*`, `/auth/refresh`, `/day/*`, `/config/*` (a revoked principal keeps working for at most the access lifetime, 60 minutes); fail closed for `/admin/*`, PII reports, `/auth/bind-device` and `/support/*`. A Redis outage may stop an admin, never a sale |
| Slow revocation | `refresh_token.revoked_at`; force-logout, device revoke, password change and scope change write it; all are audited |
| Never a kill switch for data | Revocation ends the session and refresh. It never deletes data and never blocks capture; pending rows follow s2.7 and D-474 |
| Scope change | `app_user.scope_version` is bumped on any change to `user_scope`, `route_assignment` or role, coalesced per user per 5 min (D-100); a token with a lower `scv` gets 401 `scope_changed`, the app refreshes and receives the new claims (G-cfg-12, `cfg.auth.scope_token_version_check`) |

Absolute-expiry wave (D-518, G-qa-42). With a fixed 90-day lifetime and no jitter a wave cohort of about 4,250 SRs (wave 4) would all need a password login on the same morning 90 days later, through the `auth` hash limiter (4 concurrent per replica), with SRs in dead zones unable to re-authenticate at all and offline unlock blocked for them (s2.7 requires an unexpired refresh token). The design:

| Element | Rule |
| --- | --- |
| Per-family random lifetime | the absolute expiry is `cfg.auth.refresh_absolute_days` plus a uniform draw in plus or minus `cfg.auth.refresh_absolute_jitter_days` (15), fixed at mint and stored with the family; 4,250 families then expire over 30 days, about 140 a day, instead of one |
| Warning and renewal | from day `cfg.auth.refresh_renew_warn_days` (75) the app shows a dismissible renewal prompt; renewal is by a password prompt online on any network (no forced logout, no wipe) or, for a device with `trust_level` normal, a hardware-backed key and no open risk signal, by device-key proof once per absolute cycle (a second consecutive renewal must use the password), which keeps the point of an absolute limit (periodic proof of the person) without a herd |
| Offline grace | after the absolute expiry an SR who never got the prompt (a dead zone for weeks) may still unlock and sell offline for `cfg.auth.offline_grace_after_absolute_expiry_days` (7) with UPLOAD-ONLY behaviour on the network side (the upload grant of s2.7 keeps working, the full grant does not); the first online contact shows the password prompt; beyond the grace the app blocks new capture and keeps uploading |
| Load | doc 18 s5.2 and ST-15: 140 password logins a day in steady state; the one-day case of 4,250 is the stress test S10 (T-4-162): login p95 at most 3 s, no lockout, offline unlock still working for 7 days after expiry |

Expiry-wave arithmetic. With jitter of plus or minus 10 minutes a cohort of 1,000 tokens issued in one second expires over 20 minutes, 0.8 refreshes per second per cohort. The gate is the SRE one: a cohort of 1,000 refreshes over 8 minutes or more, a device with a clock 3 h fast makes no refresh loop, and the 60 s grace holds on `/sync/batch` (T-1-55). Doc 18 owns the load, doc 17 s7.2 the device side.

### 2.4 Passwords, lockout and reset

| Rule | Value | Key | Note |
| --- | --- | --- | --- |
| Hash | Argon2id, m 64 MiB, t 3, p 1, with an HMAC-SHA-256 pepper from Key Vault (`pepper_version` stored; re-hash at login when parameters or pepper change) | fixed (D-472) | About 75 ms per hash on the reference vCPU (doc 18 ST-02: 87 CPU-s for 1,155 logins). ASSUMPTION: parameters are the OWASP-class minimum raised for server hardware; revisit if doc 18 sizing changes |
| Where | A dedicated `auth` app with `cfg.auth.hash_concurrency_per_replica` 4, 503 with `Retry-After` above it (D-126): 4 x 64 MiB = 256 MiB per replica; first-morning capacity is 4 / 0.075 = 53 hashes per second per replica against a 12.3 per second peak | `cfg.auth.hash_concurrency_per_replica` | Argon2id memory times concurrency is the failure mode (FM-02 in doc 18) |
| Web policy | At least 12 characters, lower and upper case and a digit, not one of the last 10, not within 24 h of the last change (docs/09; PARITY) | `cfg.auth.password_min_len` 12, `password_history_depth` 10, `password_min_age_h` 24 | The manual's example password is a vendor string (D-244); it goes on the deny-list and is never shown |
| Field roles | At least 8 characters with a deny-list (top 10,000 common passwords, the username, `aktcl`, `aron`, `apsis`, the manual example) | `cfg.auth.password_min_len` by ROLE, `password_denylist_enabled` | IMPROVEMENT; proposed because 8,500 reps type on small screens. unknown; confirm with the business (by 0c); the proceed-with default is 8 for field roles |
| Input handling | Usernames are case-insensitive (`citext`, G-man-091); Bengali digits are normalised in OTP and phone fields only, never in a password; usernames are Latin only | D-118, D-488 | The web labels the field "User ID" |
| Errors | `invalid_credentials` for unknown user and wrong password, with a dummy hash so timing matches within 20 ms | T-0-74 | The username scheme (`sr334001`) is guessable, so lockout and rate limits carry the weight |
| Lockout store | PostgreSQL is authoritative (`app.auth_attempt`: username, device_uuid, ip_class, window, failures, locked_until); Redis only accelerates | D-475 | Doc 18 leaves the lockout store to this document; a Redis loss must not unlock anyone |
| Lockout rule | 10 failures per (username, device) in 15 minutes locks that pair for 15 minutes, doubling to 2 h; a bound device presenting a valid `X-Device-Proof` is never locked by foreign-source failures | `cfg.auth.lockout_attempts` 10, `lockout_window_min` 15, `lockout_min` 15 | D-102. The attacker controls `deviceUuid`, so the per-pair counter alone is evadable |
| Step-up | More than 30 failures for one username across all devices in 15 minutes switches unbound-device attempts for that username to "password plus OTP in one call" for 60 minutes. Without a valid OTP the password is not evaluated, so there is no password oracle. The `ip_class` (/24) counters only trigger step-up and alerts, never a lock | `cfg.auth.lockout_key_mode` | Closes G-fraud-12: a wave-day attack on predictable usernames (`sr334001` to `sr342500`) cannot lock a bound device out |
| Fleet alert | More than 500 usernames with 10 or more failures in 10 minutes raises Sev1 and forces device-keyed lockout only | `cfg.sec.lockout_storm_usernames` 500 (new key, OI-21-12) | T-1-13 |
| Reset | A TSO (own scope) or support sets a temporary password: shown once, never stored in clear, 24 h expiry, `must_change`; no self-service reset on the web (PARITY, G-man-091); the TSO also unlocks | `cfg.auth.temp_password_ttl_h` 24 | F-TSO-023; closes G-feat-34 |
| Victim notice | A reset or change revokes the user's full-grant families, so the user's phone gets 401 `password_changed` at its next call and shows who reset it and when; SMS is optional because employee phones may be missing | `cfg.auth.notify_user_on_reset` | D-489. The upload grant survives, so pending sales still upload |
| New-device notice | A password success on an unknown device for a user who has an active bound device raises FS-11 evidence and shows a banner on the bound device at its next contact | D-489 | Closes the spraying blind spot: a correct password on a new phone is otherwise invisible to the victim |

Password spraying across 8,500 usernames is not stopped by a per-username counter. What stops it is that a success yields only a `bind_required` token and an OTP the attacker cannot read (s2.5); the fleet alert and FS-11 evidence make it visible.

### 2.5 Device binding and the OTP

State machine for a (user, device) pair:

| State | Entered by | Allowed | On the phone |
| --- | --- | --- | --- |
| unbound | first run | `POST /auth/login` only | Login screen |
| bind_required | password success on an unknown device | `/auth/bind-device`, `/auth/logout` | OTP screen "OTP যাচাইকরণ প্রক্রিয়া" (auth.otp.heading) |
| active | OTP verified, key registered | everything the role allows | Normal |
| suspended | TSO, `ops_admin` or `security_admin` suspends | capture and local reads; refresh and sync refused with 403 `device_suspended` | Banner with the support code; capture continues; rows wait |
| revoked | lost, stolen or retired | refresh refused; the grace upload of D-474 only | After the grace upload is acknowledged or its window ends the app wipes that user's data and secure-storage entries |
| replaced | the replace-device wizard (F-ADM-078, doc 19 s5.4b) marks the old device after the new one is approved | the upload-only grant of D-474 for `cfg.auth.revoked_device_grace_upload_h`; refresh and full sync refused | Banner "this phone was replaced"; the rows still on it upload as `source = revoked_device` and are parked for the zone TSO |
| unbound (by policy) | the oldest binding when a user exceeds `cfg.auth.max_devices_per_user` | the SAME upload-only grant as `revoked`, for `cfg.auth.revoked_device_grace_upload_h` (D-585: a policy unbind is treated exactly like a revoke, so a rep who borrows or swaps a phone while the old one holds the day's sales loses nothing); rows arrive as `source = revoked_device` and are parked for the zone TSO | As unbound, with the banner of `revoked` until the grace upload is acknowledged. With `cfg.auth.unbind_block_if_pending_rows` true the bind that WOULD displace a device with pending rows (the server knows its last `X-Pending-Rows`) is warned on the bind screen and the TSO sees the count on P11, and routes to the replace-device wizard instead of unbinding silently |

The OTP (PARITY for form, DEFAULT for mechanics; the creation rule is an inference, D-103, G-man-021):

| Element | Rule |
| --- | --- |
| Creation | The server creates it when an unknown device completes a password login (or, with `cfg.auth.reverify_on_new_version` true, a known device runs a newer build). The manual says only "provided by your TSO", so creation by the server is inferred; the sample Create Time values (January) next to April screenshots suggest a long-lived per-user code. unknown; confirm with the business (Q4, MQ-15); proceed with an expiring code |
| Form | 4 digits, CSPRNG, entered in four boxes; Bengali digits normalised |
| Storage | AES-256-GCM with a key wrapped in Key Vault, AAD = `otp_id` and `user_id`; reversible because the TSO must read it (R-34, D-165); never in a log |
| Lifetime | `cfg.auth.otp_ttl_min` 120 (wave scope 1,440), `cfg.auth.otp_max_attempts` 5 then `expired`, `cfg.auth.otp_max_active_per_user` 1 |
| Entropy budget | A random 4-digit code gives a guess probability of k / 10,000 after k tries: 5 tries per OTP is 0.05 percent. Two added caps (D-473): at most 3 OTP creations per user per hour (`cfg.auth.otp_issue_per_user_per_h`, new key, OI-21-12) and at most 10 failed OTP attempts per user per 24 h across all codes, after which binding is locked until a TSO or `ops_admin` clears it with an audit row. With the password known this bounds an attacker to 0.1 percent per day per user |
| Panel | View-only web page for the TSO: filters Wing to Zone, View, search, refresh; lists every SR of the selected zone (parity); the OTP column shows a value only while an unexpired OTP exists. Columns are the union of the eight in the SR manual and the five in the Web manual (F-TSO-022); each page view writes one aggregated `security_event(otp_view, row_count)`; the TSO sees only own-territory users (`cfg.auth.otp_visible_roles` [tso]) |
| Re-issue and bulk | Re-issue and bulk pre-issue per zone are IMPROVEMENTS (F-ADM-069), audited, with the longer wave TTL. WHO may issue is the authority matrix of doc 19 s5.1b (D-540, G-qa-71): the zone TSO for own zones, L1 support with the TSO's confirmation (or from the pre-approved bulk list of a pre-bind day), L2 support, `ops_admin` and `security_admin`; the first draft's `ops_admin` only left the helpdesk and the TSO unable to run the scripts of doc 20 s7.8 |
| Binding limits | Users per device 3, devices per user 2 (`cfg.auth.max_users_per_device`, `max_devices_per_user`); the binding model is MUST-CONFIRM by 1c (D-66) |
| AMO | The same gate applies to the AMO flavour; whether the live AMO app binds is unknown (MQ-15) |
| Re-verify after update | Off by default (D-80, DELIBERATE CHANGE): about 8,500 TSO lookups per release, 29 per TSO across 291 territories, and an offline day would break; parity (true) is available for the pilot |
| Unusual model | A bind from a device model never seen in the zone raises FS-11 |

Supervisor takeover chain (D-112, G-fraud-08). A TSO can reset an SR's password and read the OTP, then bind his own phone as that SR. Rule: a password reset plus OTP creation for the same user by the same actor within `cfg.sec.takeover_window_h` (24), or a bind from a `device_uuid` previously bound to the actor, puts the bind in `held` until a second person (DMO or `security_admin`) acknowledges it, and raises FS-21 severity 4 to the DMO and `security_admin`. The victim is told by the 401 reason and the new-device notice (s2.4).

Wave-day exemption (D-486, G-21-01). On a wave day every SR is reset (when the dump has no usable hashes, D-119) and bound by the same TSO inside 24 hours: 1,000 SRs across about 35 TSOs would all be held. The second person therefore acknowledges the wave cohort in advance: the DMO or `security_admin` approves the cohort list (one audited event naming the zone, the TSO and the user count) on the pre-bind day (D-126). Binds of users on an acknowledged cohort are not held; a user outside the cohort, a replacement of an already-bound device, or a device previously bound to the actor still is. This keeps D-112 intact: a second person is always involved.

Held-bind operations (D-586, G-qa-125). Outside a pre-acknowledged wave cohort, every new SR who is created and bound by the same TSO within 24 h, and every swapped phone, would otherwise wait for one of about 50 DMOs or the security team with no queue, no age alert, no SLA and no runbook, and T-6-42 would fail its own "replaces a device" step. The takeover rule itself is unchanged; its operation is specified in doc 19 s5.4b: (1) the replace-device wizard (F-ADM-078) contains the second person, because the DMO, `security_admin` or a delegate approves the replacement BEFORE the SR binds and the bind then arrives pre-approved and is not held; (2) every bind that is still held appears in the held-binds queue (F-ADM-079) with its age, a one-click release or reject, an SLA of `cfg.auth.held_bind_sla_min` (30 minutes in selling hours), a delegate (`cfg.auth.held_bind_delegate_roles`) when the DMO has not acted in 15 minutes, an alert at 45 minutes (SH-27) and read access for L1 and L2; (3) the maker of a wizard step is never its checker; (4) runbooks RB-45 (phone replaced) and RB-46 (bind held) in doc 18 s7.6. `device.mark_replaced` is the permission of step 1 and the state `replaced` above is its result; the phases of the device endpoints are aligned to 2e (suspend, revoke, replace, held-binds queue), with the full console at 6c.

### 2.6 Device key, record signatures and attestation

| Item | Rule |
| --- | --- |
| Key | At bind the app generates an EC P-256 key in Android Keystore (StrongBox when present) and registers the public key; no private key leaves the device; `hw_key` is false when no hardware-backed key exists |
| Proof on requests | `X-Device-Proof` is an ES256 signature: on refresh and bind over `sha256(refresh_token or otp) + nonce_bucket(5 min) + device_uuid` (concatenated), on `POST /sync/batch` over `sha256(body) + batch_uuid + X-Batch-Attempt` (concatenated). Refresh and bind proofs are checked against a 10-minute replay cache; a batch is idempotent by `(device_id, batch_uuid)` (D-62), so a replayed batch proof is harmless and needs no cache |
| Record signature | Each record family (a visit with its children, a memo with its lines) carries `sig = ES256(device_key, sha256(canonical_payload + client_uuid + captured_elapsed_ms + boot_id))` (concatenated), stored on the header row with `sig_status` (doc 16 s2: header record types only, children covered by the family signature). The canonical form is RFC 8785 style JSON generated by `/packages/contract` for both Dart and TypeScript and proved by golden fixtures in both languages (D-476). Cost: signing 5 ms or less per record on the reference device (T-1-11) |
| Modes | `cfg.sec.record_signature_mode`: `record` in the pilot (verify and log, never reject), `enforce` before wave 1 (PARK plus FS-18 on a missing or invalid signature, DQ-43). A device with no hardware key is accepted with `trust_level` low |
| What it buys | A SQLite edit, a curl-forged batch and a payload replay with fresh uuids all fail the signature (`client_uuid` is inside the digest). The attacker must run code inside the signed, attested app (residual RR-1) |
| Attestation | At bind the server verifies the Keystore attestation chain to the Google hardware-attestation roots, which are loaded from configuration so a root rotation needs no release (ASSUMPTION about rotation; verify in 1c), and compares `attestationApplicationId` (package and signing-certificate digest) with AKTCL's release signer. Genuine build: `trust_level` normal, `app_sig_attested` and `hw_backed` true. A repack, a foreign signer or an emulator: `trust_level` low. Never a hard block alone (D-105, D-479) |
| Effect of low trust | The weights of s7.2 are multiplied by 1.5 (ASSUMPTION, calibrated in the pilot) and the AMO and TSO see a "low-trust device" badge on the SR |
| Blocked versions | A row whose `X-App-Version` is in `blocked_versions` and whose trusted capture time is after the block's `effective_from` is parked (DQ-39); rows captured before the block upload normally (D-130) |
| Play Integrity | Off by default (`cfg.geo.play_integrity_enabled` false): it needs Google Play services on every phone, unknown for the shared low-end fleet (G-fraud-04 [G-sec-21]); when on it is one weight, absence is never a block |

Integrity signals collected at login and bundle (F-SYS-031) and weighted by `cfg.geo.integrity_weight` (s7.2): mock-location app installed or `isMocked` seen, developer options, USB debugging, root hints (su binary, Magisk package, writable `/system`, `test-keys`), emulator hints, app-signature mismatch, time zone other than Asia/Dhaka, auto-time off, clock-change broadcasts (`clock_changed_count`). Policy for rooted devices is `cfg.geo.rooted_policy` (default flag).

### 2.7 Offline unlock, shared phones and logout

Requirement: an SR opens the app in a dead zone and sells (CLAUDE.md 1). Doc 17 s7.2 to s7.4 own the device mechanics; this section owns the credential semantics.

| Element | Design |
| --- | --- |
| Verifier | Written at every online password login: `salt` 16 B, `V = Argon2id(password, salt, m 19 MiB, t 2, p 1)` split into a verifier hash and a wrapping key by HKDF with two labels, stored in secure storage under `verifier_u<id>`. Not PBKDF2 (R-34, G-sec-07). Time on a 2 GB device is 1.0 s or less (T-1-71) |
| Unlock window | Allowed while the refresh token is unexpired, or within `cfg.auth.offline_grace_after_absolute_expiry_days` (7) after its ABSOLUTE expiry (upload-only on the network side, s2.3, D-518), and the last online authentication is within `cfg.auth.offline_unlock_max_days` (7, range 1 to 14, D-265) and failed attempts are below `cfg.auth.offline_unlock_max_attempts` (10, doubling cool-down) |
| Clock games | The window is judged on trusted time; if the wall clock went backwards against the last recorded wall time the window ends after 24 h and the next sync raises FS-13 |
| DB key | A random per-user 256-bit SQLCipher key wrapped by the Keystore master key, not password-derived, because the engine must upload user A's rows while B works (D-67). The verifier gates the UI only |
| Two grants per user (D-471, G-fraud-19, OI-17-09) | The full grant (`aud aron-api`) and an upload grant (`aud aron-upload`) are separate refresh families issued together at password login. The full refresh token is stored twice: wrapped under the verifier-derived key (survives logout) and as a session copy in Keystore-backed storage (created at unlock, deleted at logout or idle lock, so a kill-and-relaunch mid-day resumes without the password). The upload grant is password-free and can call only `POST /sync/batch` for rows already in the outbox, `POST /media/sas`, `POST /auth/refresh` with `grant=upload` and `POST /auth/logout`; it cannot fetch a bundle, read data, bind or raise day events |
| Why | On a shared, rooted phone user B can read A's secure-storage entries and the shared device key. With the split, what B finds for A after A logs out is an upload-only credential, not a session that reads A's route and retailer phones. B can still push rows as A only by running code inside the signed app (RR-1) |
| Who uploads | While A's session is open the engine uses A's full grant. After logout, or when B is active, it uses the upload grant per user, one batch in flight device-wide (doc 17 s7.3) |
| Logout, SR and AMO | PARITY: the UI session ends and the local database stays. `POST /auth/logout scope=session` revokes the full grant now. The upload grant is revoked when the outbox and media queue are acknowledged (`scope=upload`); the server also expires an unused upload grant 7 days after its last use (`cfg.auth.upload_grant_idle_days`, new key, OI-21-12) |
| Logout, TSO | DELIBERATE CHANGE (D-69; resolves G-man-024; owner doc 17): refuse while any row is not `synced` or the media queue is not empty ("N items not yet sent", Sync now, Cancel); only a reconciled device wipes DB, caches, secure storage and tokens, and destroying the SQLCipher key makes the wipe final. The manual's dialog "Your all app data will removed." is kept as a bn and en pair with the grammar fixed (tso.logout.confirm) |
| App lock | No re-authentication on resume (parity); `cfg.auth.app_lock_idle_min` 0, 30 for supervisors with PII; `cfg.auth.biometric_unlock` false (optional, battery-neutral) |
| Lost or stolen phone | Suspend then revoke (s2.5). Offline forever, the data is behind the phone's lock screen (if set) and is class Personal, not Sensitive. Whether a screen lock can be required on shared phones is unknown (Q58) |

Disabled-user upload (D-551, G-qa-83). Offboarding says "disable the user, revoke devices after upload", and doc 17 s4.6 used to say that a 403 `user_disabled` stops sync for the user: a rep dismissed at 17:00 would then strand the day's sales. Now a disabled user can no longer capture, log in or fetch anything, but the device keeps the UPLOAD grant of s2.7 (the same grant a revoked device gets) for `cfg.user.dismissal_upload_grace_h` (48) so rows captured before the disable still upload. They arrive with `source = disabled_user`, are stored as `parked_user_disabled` and wait for the zone TSO or support (`quarantine.fix`) to accept or discard; nothing is silently counted and nothing is silently lost.

Revoked-device upload (D-474, G-21-02). `cfg.auth.revoked_device_grace_upload_h` (72, doc 19) lets a revoked phone deliver its pending rows once. The rows arrive with `source = revoked_device`, are PARKED for the zone TSO to accept or discard (F-ADM-030), and the app wipes after the upload is acknowledged or the window ends. A lost phone then costs the sales on it only if the TSO discards them, and a thief cannot inject sales silently.

### 2.8 Web authentication (D-114, G-sec-10, G-man-091)

| Item | Rule |
| --- | --- |
| Session | Next.js BFF: access token in memory only; refresh token in an `HttpOnly; Secure; SameSite=Strict; Path=/auth/refresh` cookie; data calls use `Authorization: Bearer`, so CSRF cannot reach data endpoints; `/auth/refresh` checks `Origin` and `Sec-Fetch-Site` |
| Lifetimes | Access 15 min; `cfg.auth.web_session_idle_min` 30; absolute 7 days for admin bundles; "Remember me" `cfg.auth.web_remember_me_days` 0 (off) up to 30 for non-admin roles |
| MFA | TOTP (RFC 6238) mandatory for the admin bundles: `cfg.auth.mfa_required_roles` default [admin], recommended [admin, top, wm]; 10 single-use recovery codes. If AKTCL has Microsoft Entra ID, SSO replaces local passwords for DMO, WM, top and admin (unknown; confirm with the business, G-sec-10) |
| Login screen | "User ID" (case-insensitive), password with show or hide, no forgot-password link (PARITY: reset goes through the TSO or support); failure, locked and expired-session texts are authored (F-WEB-043) |
| Headers | HSTS 1 year (preload after the pilot), CSP `default-src 'self'` with a Next.js nonce, `X-Content-Type-Options`, `Referrer-Policy strict-origin-when-cross-origin`, `Permissions-Policy` (geolocation only for map pages), `frame-ancestors 'none'`; no third-party scripts |
| PII reports | Need the `pii` claim and a re-authentication within `cfg.pii.reauth_min` (15, new key, OI-21-12) before an export with phone or owner columns |

### 2.9 First login at cutover (D-119, G-sec-03)

"Same logins where possible" (CLAUDE.md 6) means the same usernames. Passwords migrate only if the dump allows it; the dump request (Q18) asks for the hash algorithm and parameters.

| Case | Path |
| --- | --- |
| Verifiable hash (allow-list, ASSUMPTION: bcrypt, scrypt, PBKDF2 with at least 10,000 iterations, Argon2) | The importer stores it in `stg.apsis_credential` with column grants for the `auth` app only, flagged `apsis_hash_algo`. At first login the server verifies in constant time and re-hashes to Argon2id; the staging hash is deleted at that moment, and any unused hash is purged 60 days after the user's wave (ASSUMPTION) |
| Weak or unknown hash (MD5, SHA-1, unsalted, plain) | Never an active credential. Wave day uses TSO-issued temporary passwords per SR with forced change (F-ADM-068), audited |
| Tokens or API keys found in the dump | Catalogued and rotated, never reused (CLAUDE.md guardrail); a credential-discovery report is a 7a deliverable |
| Usernames | Preserved; memo-producing roles (`sr`, `amo`) must match `^[a-z][a-z0-9]{3,31}$` because the memo number is `<username>-<yyMMdd>-<seq3>` and a hyphen would break the split (OI-17-10, D-488); other roles may keep hyphens (`TSO-1012`); the importer quarantines violators with a rename map |

Proved by: T-0-74, T-0-77, T-0-79, T-1-11, T-1-12, T-1-13, T-1-55, T-1-70, T-1-71, T-1-78, T-3-10, T-3-71, T-3-78, T-3-79, T-4-73, T-7-75, T-7-77.

### 2.10 Security messages (G-man-021, G-man-024, G-man-091; catalogue owner doc 15 s11.5)

Parity strings are kept verbatim with their existing keys; the rest are authored with AKTCL review because no manual prints a failure, offline or validation text (G-man-102).

| Key | Text or trigger | Status |
| --- | --- | --- |
| auth.otp.heading, auth.otp.hint, auth.otp.new_device | "Enter OTP"; "Enter the 4-digit OTP provided by your TSO."; the red new-device footer (SR-M-004 to 006) | PARITY, bn twin authored |
| auth.login.error_invalid, auth.login.locked, auth.login.step_up | Wrong user or password; locked with minutes left; "enter the OTP with your password" | authored (proposed keys) |
| auth.session.needs_relogin, auth.session.password_changed, auth.session.scope_changed | "Log in again to send your records"; "your password was changed by <role> at <time>"; "your route changed, refreshing" | authored; capture continues |
| auth.device.unbound, auth.device.suspended, auth.device.revoked | Phone not bound; suspended with support code; revoked, data will be sent once then removed | authored |
| auth.otp.expired, auth.otp.attempts_exceeded, auth.otp.bind_locked | Wrong, expired, too many attempts, binding locked until the TSO clears it | authored |
| logout.confirm | "আপনি কি নিশ্চিত যে লগআউট করতে চান?" with "হ্যাঁ" and "না" (SR, AMO) | PARITY |
| tso.logout.confirm, logout.pending_items | "Log Out! / Your all app data will removed." (grammar fixed); "N items not yet sent" with Sync now and Cancel | PARITY plus authored guard (D-69) |
| web.password.rules | The four rules of the Credentials page; the example password line is never reproduced (D-244) | PARITY |
| geo.mock_warning | The only fraud text an SR ever sees (D-123) | authored |

## 3 Authorization

CLAUDE.md 4 and docs/09: a user's reach is derived on the server from role and assignment; the client never sends scope ids. The current build sends all 1,051 zone ids from the browser; one unscoped query would reproduce that flaw, so scope is enforced twice (D-106, closes G-sec-06).

### 3.1 Scope resolution and the two reach rules

| Piece | Design |
| --- | --- |
| Geography closure | `app.geo_closure(anc_type, anc_id, desc_type, desc_id)` maintained by triggers on the geography tables; about 11,336 routes x 6 ancestor levels = 68k rows |
| Per-user reach | `app.user_route_reach(user_id, route_id, scope_version)` rebuilt on every change to `user_scope`, `route_assignment` or role, and nightly for `valid_from` and `valid_to` roll-overs. SR: routes with an active assignment for the date, including same-day cover (D-85, which bumps `scope_version`); AMO: the zone's routes (3,500 to 11,000 outlets, D-72); TSO: the territory; DMO: the division; WM: the wing; top and admin: all |
| Non-geo scope | Product scope for the TSO (`cfg.tso.product_scope`, Q14) as an optional category filter; default all tobacco categories |
| Cache | Redis `reach:<user>:<scv>` as a sorted int array (a wing is about 1,134 route ids, 9 KB); TTL 24 h; invalidated by a `scope_version` bump; a Redis loss recomputes from Postgres |
| Web filters | The five-level cascade is defaulted and bounded by the token; a filter value outside reach returns 403 (F-WEB-041) |

Two rules, because a user's reach moves while phones are offline (D-470, closes the write half of G-cfg-12):

| Path | Reach used | Why |
| --- | --- | --- |
| Reads (bundle, lists, reports, Exceptions) | Current reach at the request | A transferred SR must stop seeing the old zone at the next refresh (401 `scope_changed`) |
| Writes (sync ingest) | Assignment as of the record's `business_date`, cover included | An SR transferred on Tuesday must still be able to upload Monday's sales captured offline on his old route; judging by current reach would quarantine honest rows |
| A write outside the as-of reach | Parked with `reason = scope_out_of_reach`, never dropped and never accepted silently (F-SYS-014) | |
| A write by a user with no assignment on that date | Parked; the zone TSO accepts or discards | Catches a shared-phone attribution error as well as forgery |

### 3.2 Enforcement pattern in the API (NestJS)

1. `AuthGuard` verifies the JWT (signature, `exp` with the 60 s batch grace, `aud`, `iss`, `kid`), checks `rev_user`, `rev_dev` and `scope_ver` (fail open or closed per D-491) and builds the `Principal {user_id, role, device_id, scv, perms, pii}`. `alg=none`, wrong `kid` and wrong `aud` are refused (T-0-74).
2. `ScopeInterceptor` opens one database transaction per request (required under PgBouncer transaction pooling, D-137) and runs `SET LOCAL app.user_id`, `app.role`, `app.pii`. `SET LOCAL` only exists inside that transaction, so nothing leaks to the next client of the pooled connection.
3. `ScopeContext`, a request-scoped provider, offers `reachRouteIds()`, `reachZoneIds()`, `assertRouteInReach()`, `assertOutletInReach()` and `assertUserInReach()`.
4. Every repository method takes `ScopeContext` and builds its query through `scoped()`, which appends the route, zone or territory predicate (and the geo key predicate for `dw.agg_*` and `dw.fact_*` reads). The lint rule `no-unscoped-query` and a Semgrep rule fail CI when a Kysely query on a scoped table is built outside a `*Repository` class or without `scoped()`; a new endpoint without a case in the scope-leak harness also fails (T-0-75, T-0-76).
5. Writes ignore any `user_id`, `device_id` or `sync_batch_id` in a device payload and stamp them from the `Principal`; a batch may only contain rows of the authenticated user (the engine uploads A's rows under A's grant, never under B's). Approvals stamp `verified_by` and `approved_by` from the `Principal`; an approver equal to the requester is refused with 409 `separation_of_duties`.
6. Defence in depth: PostgreSQL row-level security, `ENABLE` and `FORCE`, on `app.outlet`, `visit`, `memo`, `memo_line`, `due_collection`, `loyalty_ledger`, `redemption`, `attendance_event`, `outlet_change_request`, `task`, `call_assessment`, `distribution_check`, `risk_signal` and `dw.dim_outlet_pii`. SELECT policy: `route_id IN (SELECT route_id FROM app.user_route_reach WHERE user_id = current_setting('app.user_id', true)::bigint)`. INSERT policy checks ownership only (`user_id` equals the principal); the as-of assignment rule of s3.1 is applied by the ingest function. Roles follow doc 16 s13.4: `api_rw` is subject to RLS; `worker_rw` and `migrator` have `BYPASSRLS`; `web_ro` reads `dw` only and never `app` (R1), scoped through `scoped()` and the closure; `bi_reader` reads `dw` through `dw.bi_user_scope`. A policy that is missing returns zero rows, not the nation.
7. Budget: RLS adds at most 10 percent to p95 of `/sync/batch` and `/sync/bundle` (T-4-70). If it does not, RLS stays on the PII tables only, the application pattern remains the sole control on the rest, and the fallback is recorded as a decision. The wide-reach roles (WM, top) read `dw`, not RLS tables, so the policy subselect stays short (an SR reaches about 2 routes, an AMO at most 54, docs/22 P-13).

Proved by: T-0-75, T-0-76, T-0-77, T-4-70, T-4-74.

### 3.3 Permission matrix

Permissions are codes; a user has one role plus zero or more admin bundles (doc 19 s5.1 owns the bundles and the console, D-91). Y means allowed within the scope in brackets; N means refused by default-deny.

| Operation (doc 19 permission) | sr | amo | tso | dmo | wm | top | Admin bundles |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Capture visits, memos, dues, stock, attendance | Y (own routes) | Y (zone) | N | N | N | N | none |
| Raise outlet requests (new, close, info, location) | Y | Y | N | N | N | N | none |
| `outlet.verify` | N | Y (zone; never own request) | Y (territory; verifier of AMO-originated requests) | N | N | N | none |
| `outlet.approve` (web panel) | N | N | Y (territory) | Y (AMO-originated requests) | N | N | `master_data`; the approver is never the verifier of the same request |
| Sales Submit | Y (own route) | Y (supervisor day) | N | N | N | N | none |
| `web.final_submit`, `day.final_submit` | N | N | Y (territory); delegate per D-262 | N | N | N | none |
| `day.reopen` | N | N | N | N | N | N | `ops_admin` with a reason (Q11 MUST-CONFIRM, D-55) |
| `day.void` (audited data void, F-API-048) | N | N | Y (own scope, before Final Submit) | N | N | N | `ops_admin` |
| `task.assign` | N | Y | Y | N | N | N | none |
| `device.otp.view` | N | N | Y (territory) | N | N | N | `ops_admin` |
| `device.otp.issue` (re-issue, bulk) | N | N | Y (own zones; D-540) | N | N | N | `support_l2`, `ops_admin`, `security_admin`; `support_l1` only with the TSO's confirmation (doc 19 s5.1b) |
| `device.suspend`, `device.revoke` | N | N | Y (territory) | N | N | N | `ops_admin`, `security_admin` |
| Temporary password (F-TSO-023) | N | N | Y (territory users) | N | N | N | `support_l2`, `security_admin` |
| `account.unlock` | N | N | Y (territory users) | N | N | N | `support_l1` (not privileged accounts), `support_l2`, `security_admin`, `ops_admin` |
| `submit.void` (D-539) | N | N | Y (own zone) | N | N | N | `support_l1` with TSO confirmation, `support_l2`, `ops_admin` |
| `calendar.emergency` (D-542) | N | N | N | N | N | N | `ops_admin`; `support_l2` proposes; retroactive needs a second approver |
| `backfill.enter`, `backfill.approve` (D-543) | N | N | approve (own zone) | N | N | N | `support_l2` enters; `ops_admin` approves |
| `support.decode`, `support.decrypt` (D-541) | N | N | decode (own zones) | N | N | N | `support_l1` decode; `support_l2` decrypt (audited) |
| `master.user.bounded`, `master.assignment.bounded` (D-551) | N | N | propose (own territory) | check or propose (own division) | N | N | `user_admin` (maker-checker), `security_admin`, `master_data` |
| Leave apply, approve | N | N | apply | approve (TSO leave) | N | N | none |
| `master.target` (enter), `master.target_approve` | N | N | Y (own scope, enter) | N | approve as WMO (ASSUMPTION, MQ-48) | read | `master_data` |
| `report.read` | N | Y | Y | Y | Y | Y | all bundles |
| Export with PII columns | N | N | `cfg.pii.export_allowed_roles` | same | same | same | `pii_officer` approves large exports |
| `risk.review` (reviewed, dismissed, confirmed) | N | Y (zone) | Y (territory) | read, sees severity-4 dismissals | read | read | `security_admin` |
| Read an SR's phone | N | N (11 asterisks, D-171) | Y (own territory; ASSUMPTION: needed to call SRs) | N | N | N | `security_admin`; `support_l1` sees it masked |
| `master.*` (geography, products, prices, routes, assignments, outlets) | N | N | `master.sales_plan`, own scope | N | N | N | `master_data` |
| `master.user`, `master.scope`, `permission.grant` | N | N | N | N | N | N | `security_admin` |
| `cfg.edit.*`, `cfg.approve` | N | N | `cfg.propose.geo` | N | N | N | `config_editor`, `config_approver` |
| `finance.adjust`, `finance.approve` | N | N | N | N | N | N | `finance_admin`, `finance_approver` |
| `release.manage`, `import.run`, `audit.view` | N | N | N | N | N | N | `release_mgr`, `importer`, `security_admin` with `config_approver`, `pii_officer`, `ops_admin` |

Authority for support and operations is ONE table, doc 19 s5.1b; this matrix cites it and where they differ doc 19 wins (D-540). Rules: (a) at least two break-glass accounts exist per shift (`cfg.sys.break_glass_holders_min`), used only through the audited flow: restore or restrict, plus the bounded emergency-widen lane of doc 19 s7.6b (D-124, D-436, D-527); (b) no shared admin accounts; (c) service principals (`api_rw`, `worker_rw`, `web_ro`, `bi_reader`, `pii_reader`, `support_ro`, `migrator`) authenticate to Postgres with Entra managed identities, not passwords; (d) a permission that is not in the table is refused, and the menu is never the control: a hidden menu with a callable endpoint returns 403 (T-4-65); (e) the DMO, WM, WMO and top menus are unknown until MQ-48 is answered, so their rows are the Q16 defaults (web only, read, DMO approves TSO leave).

### 3.4 Object-level checks (IDOR) and separation of duties

| Check | Rule | Gate |
| --- | --- | --- |
| Every `GET /x/:id`, `PATCH`, and every foreign key in a write body (`against_memo_id`, `supersedes_memo_id`, `outlet_id`, `assignee_id`, `task_id`, `route_id`) | Resolved through `ScopeContext.assert*` before use; a foreign id returns 403 or 404 and zero rows | T-4-74 |
| Memo edit | `supersedes_memo_id` must belong to the same user (or the same zone for an AMO) and the same outlet, same business date, chain depth at most `cfg.memo.edit_chain_max` (3), no QC at the outlet, before Sales Submit; otherwise `rejected(edit_window_closed)` and FS-02 (D-86, DQ-16, DQ-17) | T-2-70 |
| Due collection | `against_memo_id` must be a memo of that outlet with an open balance, and the amount must not exceed it | T-5-72 |
| Outlet request lifecycle | Requester, verifier and approver are three different people; a supervisor-originated request is verified by the TSO (`cfg.outlet.supervisor_request_verifier`) and approved by someone else; verification of a location change needs the AMO's own fix within the radius of the proposed point or the request is flagged `remote_verification` (D-111) | T-3-70, T-3-11 |
| Approval cooling | A `config_approver` grant newer than `cfg.sec.approver_cooling_h` (24) cannot approve a C3 change (FS-29, D-435) | T-6-73 |

### 3.5 Maker-checker register

| Item | Maker | Checker | Rule |
| --- | --- | --- | --- |
| C3 config (radius at division scope and above, mock policy, token lifetimes, loyalty rate, PII fields in the bundle) | `config_editor` | A different `config_approver` outside the requester's chain | Doc 19 s7.3, D-88, D-435 |
| Admin grants and revokes | `security_admin` | A second `security_admin` or `config_approver` | C3 change of type `grant` (doc 19 s5.1) |
| Finance adjustments (dues write-off, loyalty adjustment) | `finance_admin` | `finance_approver` above `cfg.sec.fraud.adjust_approval_mtk` (50,000 Tk = 50,000,000 mtk) per adjustment or 200,000 Tk per actor per day | FS-05, FS-29 |
| Back-dated master data (price, target after month start, past calendar) | `finance_admin` | `finance_approver` with a blast-radius preview | D-98, G-fraud-11 |
| Data Entry above 20 memos per route-day by support or admin | Support or admin | The zone TSO | FS-28, doc 19 s8.4 |
| Support replay of a device file | Support | The zone TSO (four-eyes) | `entry_source = support_replay`, `replay_excess` flag |
| Bind held by the takeover rule | the acting TSO | DMO or `security_admin`, or the wave-cohort acknowledgement | D-112, D-486 |
| Break-glass | one named on-call | A reviewer other than the actor within 24 h | restore or restrict only, 4 h maximum |

Proved by: T-0-75, T-3-70, T-3-71, T-3-78, T-4-70, T-4-74, T-4-76, T-6-70, T-6-72, T-6-73.

## 4 PII and privacy

docs/22 P-12 changes the plan: phone and owner name are filled for 100 percent of 734,789 outlets, the NID is the placeholder `123` for 589k and blank for 145k, TIN and trade licence are empty, and the address is filled for 13. The working PII is therefore phone and owner name. NID, TIN and licence stay classified and protected but nothing is built that depends on them (D-256).

### 4.1 Inventory and treatment

| Field or data | Where | Class | Who needs it | Protection |
| --- | --- | --- | --- | --- |
| NID, TIN, trade licence | `app.outlet` | Sensitive | `master_data` when editing; the TSO web baseline shows the columns (D-108) | Envelope encryption (s4.3); never in a bundle, never in `dw`; the placeholder `123` is stored as NULL (DQ-47); column grant to `pii_reader` only |
| Outlet phone, owner name, address | `app.outlet`, `dw.dim_outlet_pii` | Personal | SR, AMO, TSO on route-scoped screens; web TSO and above; de-duplication | Plaintext behind grants pending legal review; `phone_hash` = HMAC-SHA-256(pepper, E.164) with `pepper_version` for matching; `dw.v_outlet_masked` for BI; bundle carries phone only for outlets in reach; logged export |
| SR, AMO, TSO name, phone, employee code | `app.app_user` | Personal (employee) | Supervisors in scope per s4.2 | The server returns no SR phone to an AMO view (D-171); `dim_user` holds no phone |
| GPS fixes, attendance fixes, radio environment | `app.geo_fix`, `visit`, `attendance_event` | Personal (employee location) | AMO and TSO see the last synced fix of the own team only (D-170); the fraud job reads raw fixes | Scoped reads; notice and consent (s4.7); raw fixes age out at 6 months on the primary |
| Photos | Blob `photos/` | Personal and evidence | SR (own), AMO and TSO in scope, approval panel | Private containers; read SAS of 15 minutes for one blob; EXIF stripped on the device; SHA-256 and perceptual hash stored (s5.3) |
| Passwords, refresh tokens, OTPs, device keys | `user_credential`, `refresh_token`, `device_otp`, Keystore | Secret | nobody | Hashes only (passwords, tokens); OTP encrypted and reversible (s2.5); keys never leave Keystore or Key Vault |
| PDA-to-Support bundles | Blob `support/` | Personal (a day of outlets) | Support through `POST /support/decrypt` | Encrypted to a Key Vault key (s5.4); 30-day retention |
| Apsis dump | import staging | Personal, may hold credentials | `importer` | s4.8 |
| Risk signals, user risk score | `app.risk_signal`, `user_risk` | Personal (employee conduct) | AMO, TSO, DMO, `security_admin` | Scoped reads; the SR sees none (D-123) |

### 4.2 Role by subject matrix (closes G-feat-59, G-man-039, G-man-062; D-108, D-207)

Y = shown in clear, M = masked (`01*****123` style), N = not returned by the server, own = own data only. A restriction is a recorded change from the current build; the parity baseline is what the manuals show.

| Subject | SR | AMO | TSO | DMO, WM, top | `master_data` | `security_admin` | `support_l1` | BI |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Retailer phone and owner (route-scoped screens, label "name (code-phone-cluster)") | Y | Y | Y | M | Y | M | M | M |
| Retailer address, NID, TIN, trade licence | N | N | Columns shown, null where blank or the placeholder (PARITY baseline, MUST-CONFIRM by 4c: ask for one real Excel file) | N | Y (decrypted by the API, logged) | N | N | N |
| SR phone | own | N (11 asterisks, parity) | Y | N | N | Y | M | N |
| AMO or TSO phone | N | own | own | N | N | Y | M | N |
| Last synced fix of own team | N | Y (zone) | Y (territory) | N | N | N | N | N |
| Raw GPS fixes and radio environment | own via the app | N | N | N | N | Y (Exceptions drill) | N | N |
| OTP values | N | N | Y (own territory, open OTPs) | N | N | N | N | N |
| Photos | own | zone | territory | N | approval panel | evidence only | N | N |
| Risk signals and score | N | zone | territory | read | N | Y | N | N |

DMO, WM and top visibility of retailer phones is the Q16 default (masked); it changes through `cfg.pii.field_roles` (class C3). Whether the live Excel export carries NID or TIN is unknown (the page shows the columns blank, D-207); the proceed-with default is to keep the columns for the TSO and return null for the placeholder.

### 4.3 Protection decision (D-107, G-sec-04)

| Option | Verdict |
| --- | --- |
| A. `pgcrypto` in SQL | Rejected: the key reaches the session, so it lands in statement text and `pg_stat_statements` |
| B. Application-level envelope encryption: per-column data key (AES-256-GCM) wrapped by a Key Vault key, AAD = `outlet_id + column` (concatenated) | Adopted for NID, TIN and trade licence (never searched): ciphertext `key_id(2) + nonce(12) + ct + tag(16)` (concatenated) in `bytea`; `app.pii_key` holds the wrapped keys; DEK cached in API memory for 1 hour; a Key Vault key rotation (365 days) re-wraps, it does not re-encrypt |
| C. Tokenisation vault | Rejected: a second system at 8,500-user scale for three unused columns |
| D. Plaintext, grants, TDE, masked views, export log | Adopted for phone, owner name and address pending legal review: searchable, and the bundle generator is the only hot reader |

Switch rule (D-482): if counsel finds phone or owner name sensitive, or a localisation duty applies (s4.6), phone and owner move to option B in two migrations (expand with a ciphertext column and backfill, then contract); search and de-duplication keep working through `phone_hash`. The bundle generator decrypts about 735k values in the nightly pre-generation; that is a few seconds of CPU (ASSUMPTION, measured in T-4-75). The design keeps PII separable (`dim_outlet_pii`, `pii_key`) so an in-country mirror stays possible.

### 4.4 Redaction in logs, telemetry and analytics (closes G-sec-14, G-fraud-15)

| Layer | Rule |
| --- | --- |
| API logs (pino) | Allow-list of fields per event; ids, `memo_no`, `client_uuid` allowed, names and phones not. Redact `req.body.*.contact_number`, `owner_name`, `nid`, `password`, `refreshToken`, the `authorization` header and `X-Device-Proof`; a final scrubber (`cfg.pii.log_scrub_patterns`) removes Bangladesh phone shapes `(\+?88)?01[3-9]\d{8}` and the same in Bengali digits `[০-৯]{10,13}`, 10, 13 and 17-digit numeric runs, and SAS query strings (`sig=`, `sv=`, `se=`) |
| Sentry and Application Insights | `beforeSend` scrubber on device and server; ids only, no names; sampling per D-136; Sentry residency is MUST-CONFIRM by 2e (D-13, OI-20-11) |
| Device logs | The same scrubber; rotate at 5 MB; the PDA-to-Support bundle carries the last 200 lines |
| PostgreSQL | `log_statement = 'ddl'`, slow-query logging only, `log_parameter_max_length = 0`, `pgaudit` for the platform administrator role and DDL |
| `dw` and BI | `dim_outlet_pii` separate; `bi_reader` has no grant on it; `v_outlet_masked`; Power BI through the replica only |
| Analytics events | Event names and ids; no free text |

Gate: inject a phone, an NID, a password, a token and a SAS URL (Latin and Bengali digits) into every request path and grep App Insights and pino output (T-1-75).

### 4.5 Row budgets, masking and exports (D-121, G-fraud-13)

A paginated `GET /outlets` at 60 requests per minute and 200 rows per page (ASSUMPTION) would let one account read 12,000 rows per minute with no export log. Controls:

| Control | Rule | Key |
| --- | --- | --- |
| Masked by default | List endpoints return personal columns masked for DMO, WM, top and admin roles; a reveal is an explicit `?reveal=1` per page and is logged. Field roles and the TSO see route, zone and territory data in clear because the work needs it (PARITY: web p4 shows unmasked contact numbers) | `cfg.pii.field_roles`, `cfg.pii.mask_style` |
| Hourly budget | 2,000 revealed rows per user per hour across list endpoints; the TSO 5,000 (ASSUMPTION: about twice a 2,500-outlet territory, D-72); 429 beyond. At 2,000 per hour a 735k-outlet harvest takes 367 hours | `cfg.pii.list_rows_per_hour` by ROLE |
| Daily export budget | 5,000 rows per user per day; above `cfg.pii.export_approval_threshold_rows` (1,000, ASSUMPTION) an export needs `pii_officer` approval and runs as an async job | `cfg.pii.export_rows_per_day`, `export_approval_threshold_rows` |
| Re-authentication | An export with PII columns needs the `pii` claim and a login within 15 minutes | `cfg.pii.reauth_min` (new key, OI-21-12) |
| Watermark | Every PII export carries a hidden sheet with `user_id`, `request_id`, timestamp and a per-row hash of (row key, `request_id`), so a leaked 10-row fragment is attributable (T-4-14) | `cfg.pii.export_watermark` |
| Export log | `app.report_export_log` records user, report, filters, row count, `includes_pii`, format; the viewer is F-WEB-063; every PII export is audited (s8) | none |
| Spreadsheet sanitiser | In every xlsx and CSV formatter a cell that starts with `=`, `+`, `-`, `@`, tab or carriage return (after leading whitespace or quotes) is written as text with a leading apostrophe; tested with the OWASP CSV-injection corpus (G-fraud-14) | fixed |
| Bundle gate | Adding a PII column to the SR bundle (`cfg.bundle.outlet_fields`) is C3 and needs the `pii` role mapping (T-4-76) | `cfg.pii.field_roles` |
| BI coordinates | `bi_reader` may read exact outlet coordinates (ASSUMPTION: a retailer's shop is a business location; a small shop can also be a home, counsel to confirm); employee fixes and SR phones never (closes OI-16-32 for doc 16) | s4.2 |

### 4.6 Legal position: unknown; confirm with the business (G-sec-01, blocker, 7a entry; D-570)

Timing (D-570, G-qa-105). The Apsis dump with outlet owner names and phone numbers is imported at 7a (weeks 23 to 25) and the pilot at 7b captures real SR fixes, photos and attendance, so the answers below that decide region, PII design and notice are needed BEFORE real personal data lands in the new system, not at 7c exit (RK-05 itself says the opinion is needed by week 24). The rows marked 7a block the 7a ENTRY: if counsel has not answered by then, 7a runs ONLY on a pseudonymised or synthetic import (phone and owner hashed or replaced, location coarsened) and 7b does not start. The pilot consent and notice text is the D-120 text of s4.7, not a separate draft.

No Azure region exists in Bangladesh (D-05: Southeast Asia primary, East Asia DR). Nothing here is legal advice, and the author does not assert what the law says. The proceed-with default is to build for the strictest plausible case.

| Question for AKTCL counsel | Why it matters | Proceed-with default | Blocks |
| --- | --- | --- | --- |
| What data-protection statute or ordinance is in force, who is the regulator, what are the controller duties and penalties | Decides notice, consent, breach notification and subject-access tooling | Lawful-basis record, notice, consent log, access, correction and erasure runbooks (s4.8, s4.9) | 7a entry (D-570) |
| Does retailer PII (phone, owner, address) or employee location have to stay in Bangladesh, and is storage in Singapore a transfer needing a basis or a registration | Region (D-05) and option B or an in-country mirror (s4.3) | Singapore with PII separable; record the transfer basis in DECISIONS.md | 7a entry (D-05, D-107; D-570) |
| Is a retailer's phone or owner name "sensitive"? Are NID, TIN and licence | Option D versus B for phone and owner (D-107) | NID, TIN, licence sensitive; phone and owner personal | 4c (G-sec-04) |
| Employee location monitoring: notice, consent, retention of raw fixes | D-120 text and the 730-day rounding | Notice at first login, consent stored, rounding in `dw` after 730 days | 7a entry for the text; 7b entry for the consent screen (G-sec-22; D-570) |
| Telecom rules for SMS receipts to retailers (FS-07) | Whether SMS receipts can launch | Off; opt-in per outlet if adopted | 5c |
| Rules on NID handling | Whether storing retailer NIDs is allowed at all | Encrypt, build nothing on it | 7a entry (D-570) |
| Tobacco-advertising restrictions on the AV and KV content and POSM features | Content that the app displays | Content is data (`campaign_content`) and can be disabled | 6a |
| Duty to notify a regulator or affected persons after a breach | The incident clock (s4.9) | 72 hours internal target (ASSUMPTION) until counsel answers | 7a entry (D-570) |

### 4.7 Employee location: notice, consent and retention (D-120, G-sec-22)

| Item | Rule |
| --- | --- |
| Notice | Bangla and English screen at first login: location is recorded at check-in and check-out, outlet open, force sale and outlet capture, never continuously, and is used to verify routes (F-SYS-075); HR and legal text is MUST-CONFIRM by the 7a entry (D-570) and is the text that the pilot SRs of 7b accept (T-7-160) |
| Consent record | `app.user_consent(user_id, policy_version, accepted_at)`, a queued record; a new policy version re-prompts |
| Supervisor view | Last synced fix with "last seen HH:MM (n min ago)" and its source, greyed after `cfg.tso.team_location_max_age_min` (120); no continuous track (D-170, CLAUDE.md 3) |
| Retention | Raw fixes on the primary 6 months (`cfg.retention.geo_fix_months`); in `dw` rounded to 3 decimals (about 100 m) after 730 days (`cfg.retention.fix_round_days`); no export of fixes except to `security_admin` |
| Radio environment | Cell identities and salted truncated Wi-Fi hashes only, same class as the fix (D-110, D-478); capture is off until privacy sign-off (`cfg.geo.radio_env_enabled` false, OI-19-17) |

### 4.8 Retention, deletion and the Apsis dump

| Data | Retention | Deletion path |
| --- | --- | --- |
| Transactions, aggregates | Hot 13 months then archive, never hard-deleted (D-23); statutory period unknown (7 years assumed; MUST-CONFIRM by 6c) | Archive job with manifest |
| Audit, config audit, export log, security events | 7 years (`cfg.retention.audit_years`, floor 7; ASSUMPTION; MUST-CONFIRM by 6b, D-113) | Never shortened below the floor |
| Photos | `cfg.retention.media_days` 730 (ASSUMPTION; unknown; confirm with the business, Q21, MUST-CONFIRM by 6c, D-132): Hot, Cool at 30 days, Cold at 90, Archive at 365; a legal-hold flag stops deletion | Blob lifecycle |
| `device_integrity`, `risk_signal` | 2 years, partition drop | Job |
| Support bundles | 30 days (`cfg.support.*`) | Blob lifecycle |
| Refresh tokens | 30 days after expiry or revocation (forensics) | Job |
| Retailer erasure request | Outlet set to `closed`, personal columns nulled, `owner_name` set to `[erased]`, `phone_hash` kept for de-duplication only if counsel allows; transactions keep `outlet_id` | Admin action, audited |
| Employee leaving | `status = disabled`, devices and tokens revoked, HR policy decides the rest (unknown) | Admin |
| Raw Apsis dump | A locked Blob container, 30 days after reconciliation then deleted (D-155); credentials catalogued and rotated; PII only into `app.*` and `dw.dim_outlet_pii`, never into `stg` copies analysts can read; CI never sees it (D-151) | Lifecycle rule plus a T-7-74 check |
| Pilot devices | They hold migrated PII beside the old app: same hardening (s5.7), purge on exit from the pilot | Cutover runbook |

### 4.9 Incident runbooks (minimum set, exercised at T-7-72)

| # | Incident | Detection | Decides | First 60 minutes | Evidence |
| --- | --- | --- | --- | --- | --- |
| 1 | Lost or stolen device | TSO report | TSO with `security_admin` | Suspend, then revoke; the grace upload parks pending rows (D-474); watch FS-18 | Audit export, device record |
| 2 | Credential leak (user, admin, service) | FS-18, fleet lockout alert, user report | `security_admin` | Force-logout, rotate; admin credential: revoke grants, review audit | Audit chain, Key Vault logs |
| 3 | Spoofing ring in a zone | FS-08, FS-25, co-located users | DMO with `security_admin` | Zone Exceptions review, TSO and DMO call, joint calls | `risk_signal` evidence, raw fixes |
| 4 | PII export misuse | Export log, row-budget 429s | `pii_officer` | Disable the account, review exports | `report_export_log`, watermark |
| 5 | Key compromise (JWT, DEK, SAS identity) | Key Vault diagnostics | `security_admin` with the tech lead | Rotate the key (two keys active), re-wrap, revoke the SAS delegation key | Key Vault logs |
| 6 | Vulnerable dependency with a public exploit | Dependabot, OSV | Tech lead | Patch, rebuild, publish with human approval | SBOM, scan report |

Each runbook ends with the notification duty (counsel, s4.6) and a DECISIONS.md entry.

Proved by: T-1-75, T-4-14, T-4-71, T-4-72, T-4-75, T-4-76, T-7-72, T-7-74, T-7-76.

## 5 Transport, storage and platform

### 5.1 Network and transport

| Control | Decision |
| --- | --- |
| TLS | Front Door managed certificate, TLS 1.2 minimum, HSTS. No certificate pinning (D-483): a certificate or CA change would strand offline phones for a release cycle, and the app bakes in two hostnames, the custom domain and the Front Door default endpoint (D-81). Instead the Android network security config trusts system anchors only (user-added CAs refused, `cleartextTrafficPermitted=false`), the hostname is verified, and `usesCleartextTraffic=false`. A rooted phone with a system-store CA can still intercept its own traffic (RR-10) |
| Origin exposure | Front Door PREMIUM from the pilot (D-573, one decision with D-06): Premium is the only tier with a Private Link origin, so the Container Apps ingress is internal and reached through Private Link from the first real traffic. If the Private Link spike (G-18-06, due before the pilot) fails, the fallback is a public origin protected by the `AzureFrontDoor.Backend` service-tag restriction AND the `X-Azure-FDID` header check, both tested by a direct-origin request that must be refused (T-7-162); doc 18 s2.4 states the same tier and date |
| WAF | Managed rule sets and bot protection exist only on Front Door Premium (Standard supports custom rules only; Microsoft Learn, read 2026-10-04), so the pilot and every wave run PREMIUM (base fee about USD 330 a month against about 35 for Standard; doc 18 s3.3 carries the line): the managed rules run in Log mode on `/sync` and `/media` through the whole pilot (7b) on the real payload corpus, false positives that return an HTML 403 to `/sync/batch` are found there, and enforcement starts at wave 1 only for rule groups with zero false positives (T-7-162; D-81, D-573); a per-IP custom rule only as a DDoS backstop at or above 20,000 requests per 5 minutes on `/sync/*` (ASSUMPTION from the lens; calibrate from the 500-devices-one-IP test and the first pilot week); geo-filtering logs and does not block, because roaming SIMs exist |
| PostgreSQL | Private endpoint only; `require_secure_transport = on`; Entra authentication through managed identities for every application role; Azure Policy denies public network access in prod. No password fallback exists for `api_rw` or `worker_rw`: pooled connections are sized to outlive the Entra token lifetime plus the outage budget of doc 18 (FM list), and the break-glass administrator password sits in Key Vault with two-person retrieval and a rotation after every use |
| Redis, Blob, Key Vault | Azure Managed Redis with Entra auth and TLS; Blob with private endpoints, `allowBlobPublicAccess=false`, shared-key access disabled (user-delegation SAS only), soft delete 14 days and versioning on `photos/`, an immutable container for `audit-export/`; Key Vault with RBAC (not access policies), soft delete, purge protection, private endpoint, diagnostics to Log Analytics for 1 year |
| Internal calls | ACA to Postgres, Redis, Blob and Key Vault over private endpoints with managed identity; no service-to-service secret |

### 5.2 Secrets and keys

| Item | Store and access | Rotation |
| --- | --- | --- |
| JWT signing root key (EC P-256) | Key Vault `kv-sign`, `sign` only, non-exportable; the API identity signs a 24-hour delegation per replica (s2.2b, D-574); DR copy by backup and restore inside the same subscription and geography | 90 days, two `kid` active; T-7-71, T-4-173 |
| Password pepper, phone-hash pepper | Key Vault secrets, API identity; `pepper_version` column | Yearly or on compromise; re-hash at next login, re-hash of `phone_hash` as a job |
| OTP encryption key, PII DEK wrapping key | Key Vault keys (`wrapKey`, `unwrapKey`) | 365 days; re-wrap job |
| Support-bundle key (RSA-3072) | Key Vault key; private part never leaves; `unwrapKey` through `POST /support/decrypt` only | Yearly; old versions kept until bundles age out (30 days) |
| Radio-hash salt | Delivered in the bundle config (a salt, not a secret: it only prevents cross-dataset matching) | Yearly |
| SAS issuance | Storage Blob Data Contributor on the API identity; user-delegation key cached per replica (D-132) | Automatic, 7-day key life |
| FCM credentials (when `cfg.ops.push_enabled`), Google Maps key | Key Vault; the Maps key is restricted by package name and signing SHA with a billing alert (D-08) | Vendor-driven; yearly |
| APK keys | The app signing key is held by Play App Signing or a Key Vault key used through a manually approved signing service and never exists on a CI runner; the upload key is a separate environment secret for `prod` behind required reviewers | Upload key yearly; signing key never |
| GitHub to Azure | OIDC federated credentials per environment | n/a |

No secret is committed or shipped in the APK; CI greps the APK strings and runs `gitleaks` on history (T-0-72, T-1-74).

### 5.3 Media path (closes G-sec-13, G-fraud-15; D-75)

1. The device compresses to at most `cfg.media.photo_max_kb` (150), strips EXIF (the GPS lives in the record, not the file), and stores SHA-256 and a 64-bit perceptual hash on the record. Evidence photos (force sale, outlet capture, base update) are camera-only, no gallery picker (`cfg.media.evidence_camera_only` true).
2. `POST /media/sas {client_uuid, purpose, sha256, bytes}` checks reach, that the record exists or is in the same batch, a per-device daily cap (`cfg.media.max_photos_per_device_day` 200, an abuse cap, new key OI-21-12), and returns a user-delegation SAS: create and write only, 15 minutes, pinned to `photos/{business_date}/{device_uuid}/{client_uuid}.jpg`.
3. A `BlobCreated` worker verifies size (at most 300 KB), a JPEG content sniff and that the SHA-256 matches the record; a mismatch sets `photo_state = invalid` and raises FS-16. Blob versioning keeps the original if someone re-PUTs a different image.
4. Reads go through `GET /media/:id/url`, a read SAS of 15 minutes for one blob, issued only if the record is in reach; the web never uses a public container.
5. SAS query strings are on the log scrubber list (s4.4); the local media queue holds the URL in plaintext for at most its 15 minutes.

### 5.4 Support bundle custody (closes G-sec-14; D-480, OI-17-11)

The bundle content is doc 17 s10.4 (unsynced and rejected outbox payloads, journal, 200 log lines without PII); it is a day of retailer phones, so:

| Step | Rule |
| --- | --- |
| On the device | gzip, then hybrid encryption: a random AES-256-GCM key per bundle, wrapped with RSA-OAEP-256 under the AKTCL support public key whose id is in the bundle config (`cfg.support.public_key_id`); an unknown key id refuses to send |
| Upload | SAS to the `support/` container (write-only, no listing); Wi-Fi only by default (`cfg.support.pda_upload_wifi_only`); queued offline |
| Decrypt | `POST /support/decrypt {bundle_id, ticket_id}` needs the `support` bundle, MFA and a ticket; the API calls Key Vault `unwrapKey` and streams the plaintext to a viewer or the replay job; plaintext is never written to disk; each call writes `support_decrypt` to the audit log with who, bundle, ticket and purpose |
| Replay | Four-eyes (support and the zone TSO); `entry_source = support_replay`; rows not in the device's last claimed counts are flagged `replay_excess`; the record-signature check applies to replayed rows; idempotent through the same `client_uuid` values (doc 17 D-402) |
| Retention | 30 days, then lifecycle delete |

### 5.5 Trusted capture time (D-20, G-sec-12, G-fraud-02, F-SYS-049)

Clock back-dating is invisible to every GPS check, so `business_date` must not come from the device wall clock. Mechanics are doc 17 s5.5; the fraud rules are here.

| Step | Rule |
| --- | --- |
| Anchor | Every server response carries `server_time`; the app stores `{server_time, elapsedRealtime, boot_id}` in secure storage (`boot_id` from the Android boot counter) |
| Capture | Each record stores wall time, `captured_elapsed_ms` and `boot_id` |
| Server | `captured_at_trusted = anchor.server_time + (captured_elapsed_ms - anchor.elapsed)` when the `boot_id` matches; otherwise the offset at last contact bounded by `[last server contact, receipt]` and `time_untrusted = true` |
| Skew flag | Wall time more than `cfg.sync.max_clock_skew_min` (10) away from trusted time: row kept, `clock_skew_flag`, FS-13 |
| Window | Trusted time later than receipt, or earlier than receipt minus `cfg.sync.max_backdate_days` (7), is parked |
| Month close | A `time_untrusted` or skewed row claiming a month closed more than `cfg.day.month_close_grace_days` (3) ago is parked for supervisor acceptance and never auto-aggregated (DQ-40); the back-date window never crosses a month boundary for untrusted rows |
| Honest limit | After a reboot the anchor is lost. A row claiming 30th 20:00 that arrives on the 2nd falls inside the 3-day grace and is accepted with `time_untrusted` and FS-13. Operational rule for sales ops: close month-end incentives only after the grace plus the FS-13 review |
| Gates that use it | The 17:00 check-out gate and the offline-unlock window use trusted time; a phone whose clock moved forward cannot open check-out at 15:00 |

### 5.6 Local store

SQLCipher with a per-user 256-bit key wrapped by the Keystore master key (D-67): protects a lost phone, not a rooted unlocked one. The tamper-evidence that matters against a rooted attacker is the record signature (s2.6), not the encryption. If the APK exceeds 30 MB per ABI or the battery gate fails, the fallback is Android file-based encryption and a new decision is recorded (D-67, MUST-CONFIRM by 1b: security owner sign-off, G-sync-13). No backup leaves the phone (s5.7).

### 5.7 Mobile hardening (android-permissions-security and android-intent-security skills apply at review)

| Control | Setting |
| --- | --- |
| Backups | `allowBackup=false`, no `fullBackupContent`, data-extraction rules deny all |
| Components | No exported activity, service or receiver beyond the launcher; no deep links; WorkManager jobs internal; `FileProvider` only for the printer and photo flows with scoped grants |
| Permissions allow-list | Location (precise, while in use, never background), camera, Bluetooth connect and scan, internet, network state, install packages for the updater. No `RECORD_AUDIO` (D-115; the Audio prompt in the manual is a camera-plugin side effect; Q15, MQ-55 confirm), no `READ_PHONE_STATE`, no storage permission (scoped storage). Checked by a manifest lint (T-2-79) |
| Build | `flutter build --release --obfuscate --split-debug-info`, R8 on, `debuggable=false`, per-ABI splits; symbol files kept in the release pipeline |
| Updater | Downloads only the URL in `app_release`, verifies SHA-256 and that the APK's signing-certificate digest equals the installed app's before calling the installer; `min_version` never blocks capture or upload (D-79, D-130) |
| Tamper | The app checks its own signing certificate at start and the result is a weight, not a block (a repack can remove the check; the server-side attestation of s2.6 is the real signal) |
| Screens | `FLAG_SECURE` off for SR selling screens (SRs may screenshot a memo for a retailer; unknown, confirm), on for supervisor screens that show retailer phones (`cfg.sec.flag_secure_pii_screens` true, new key OI-21-12) |
| Clipboard and logs | No copy button on PII; phone dial by intent only; release logs at warning and above through the scrubber |
| Pilot devices | The same hardening applies on pilot phones that run both apps (different `applicationId`s, D-01) |

Proved by: T-0-72, T-1-71, T-1-74, T-1-75, T-1-76, T-2-72, T-2-78, T-2-79, T-4-71, T-7-71, T-7-73.

## 6 API hardening

### 6.1 Rate limits (D-116, closes G-scale-04)

Limits are per device and per user in Redis (sliding window, `cfg.api.rl.*`, server-only delivery so launch-day tuning needs no deploy). The source IP is never a key because carrier-grade NAT puts thousands of SRs behind one address; the WAF per-IP rule of s5.1 is a high backstop only. Responses carry `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` and a jittered `Retry-After`. Redis failure follows D-491.

| Endpoint | Key | Limit | On exceed |
| --- | --- | --- | --- |
| `POST /auth/login` | username; device_uuid (from the body) | 10 per 15 min; 30 per 15 min | 429 plus the lockout counter (s2.4) |
| `POST /auth/refresh` | device_id | 20 per hour, burst 5 | 429 |
| `POST /auth/bind-device` | device_uuid | 5 per hour; OTP attempts counted separately (s2.5) | 429 |
| `GET /sync/bundle` | device_id | 12 per hour full, 60 per hour delta or 304 | 429, `Retry-After` 300 |
| `POST /sync/batch` | device_id | `cfg.api.rl.device_per_min` 120, burst 20 | 429 or 503 with `Retry-After` 5 to 60 s |
| `POST /media/sas` | device_id | 300 per day, 20 per minute | 429 |
| `POST /day/*` | user_id | 30 per hour | 429 |
| `GET /app/home`, `/reports/*`, `/dashboard/*` | user_id | `cfg.api.rl.user_per_min` 300 on the web, 60 for report reads, burst 10 | 429 |
| `/reports/*?format=xlsx` | user_id | 20 per hour; large exports queued | 429 |
| `/admin/*` writes | user_id | 60 per minute | 429 |
| any | device_id or user_id in flight | 4 concurrent | 429 |
| Global | in-flight batches per replica | `cfg.api.inflight_batches_per_replica` 64 | 503 |

Expected load: the trigger design (doc 17 D-59) sends at most 12 batches per minute per device (5 s debounce), so 120 per minute is a ceiling 10 times the honest rate.

### 6.2 Payload and parsing limits (D-116)

| Limit | Value | Key |
| --- | --- | --- |
| `POST /sync/batch` compressed body | at most 1 MiB | `cfg.api.max_batch_body_kb` 1024 |
| Decompressed body | at most 8 MiB and a 20:1 ratio; streaming inflate with a hard cap; a 100:1 bomb returns 413 within 50 ms (T-1-72) | `cfg.api.max_batch_decompressed_mb` 8 |
| Rows per batch | at most 500 | `cfg.api.max_batch_rows` 500 |
| Memo lines per memo | at most 60 (docs/22: maximum observed 40) | `cfg.sale.max_lines_per_memo` 60 |
| JSON | depth at most 8; unknown keys rejected (`strict()` schemas) | fixed |
| Strings | names at most 120 characters, free text at most 1,000, `edit_reason` from a code list | shared schema |
| Request timeout | 30 s server, 60 s client | fixed |
| Photo | at most 300 KB (the device target is `cfg.media.photo_max_kb` 150) | s5.3 |
| Report rows | synchronous up to the report limit, larger as an async export | `cfg.ops.report_export_max_rows` |

### 6.3 Replay and idempotency seen from security (D-21, D-62, D-353)

| Concern | Rule |
| --- | --- |
| `batch_uuid` replay | Keyed by `(device_id, batch_uuid)`: another device presenting the same uuid gets a fresh ingest, never a stored response; the same uuid with a different row set returns 409; the stored response holds no PII |
| Same `client_uuid`, different payload | First payload wins, the second goes to `sync_conflict`; if the registered row belongs to another user the answer is `rejected(conflict)` with no detail and `security_event(uuid_collision_cross_user)`; two in a day from one device raise FS-18 |
| Row ownership | `user_id`, `device_id`, `sync_batch_id` stamped from the principal |
| Business-date window | `business_date` between today minus `cfg.sync.max_backdate_days` (7) and today plus 1 in Dhaka, else parked |
| Regenerated uuids | The exact content fingerprint refuses the second record (DQ-41 as REJECT, D-353); the record signature already fails because `client_uuid` is inside the digest; the near-duplicate FS-30 (s7.7) catches a replay that nudges the time (D-477, closes OI-16-29: this document confirms doc 16's REJECT) |
| Edits | Chain depth, QC and Sales Submit rules as s3.4; FS-02 counts voids and edits of unprinted memos |
| Proof replay | Refresh and bind proofs: 10-minute cache; batch proofs need none |

### 6.4 Server recompute and validation from `/packages` (D-117, D-150)

One Zod schema set in `/packages/contract` is the source for the API, the web and (through generated JSON Schema and Dart) the app; enums that the admin can edit come from the code tables of the bundle's `config_version`.

| Rule | Behaviour |
| --- | --- |
| Geo verdict | Recomputed from the stored fix and the radius resolved as of capture (D-87); the device flag is a convenience; a mocked fix is never valid (DQ-23) |
| Prices and discounts | Looked up for the outlet's resolved price list on the business date and the offer rules at the claimed `config_version`; a difference sets `price_mismatch` and never rejects, because the paper memo exists |
| Totals | `net = gross - offer discount - DRP discount - QC settlement` (D-18) and `due = net - paid`; arithmetic that does not hold is rejected |
| Quantities | `qty > 0`; quantity not a multiple of the pack size for sticks, and quantity above `cfg.sale.max_line_qty_base` (retail 20,000 sticks, wholesale 1,000,000), are flags, not rejections (docs/22 P-16) |
| Coordinates | Inside the box of `cfg.geo.country_bbox` (one owner and one value, D-576: latitude 20.3 to 26.9, longitude 87.9 to 92.8, a deliberately wide box so GPS noise at the border raises no false alarm; doc 16 DQ-22 reads the same key) else `geo_out_of_country`; accuracy above 0 and at most 5,000 m |
| Identifiers | Device ids are UUID v4 (regex and version nibble); server ids must be in reach |
| Text | NFC, control characters stripped, Bengali digits normalised in numeric fields (D-118); never rendered as HTML |
| Targets | At least 0 at entry (the -37,500 percent bug) |

### 6.5 Injection and rendering safety (D-118, closes G-fraud-14)

| Surface | Control |
| --- | --- |
| SQL and jsonb | Parameterised Kysely only; named report queries, no dynamic SQL; a Semgrep rule forbids string-built `jsonb_path_query` or `->>` paths from request data; quarantine payloads are shown through an escaping viewer |
| Spreadsheets | The export sanitiser of s4.5 |
| Printer | The device strips C0 and C1 control bytes from every string before the raster renderer, because a new-shop name is printed locally before it syncs; the server does the same at ingest |
| Names | Unicode format characters (category Cf) other than ZWJ and ZWNJ are stripped; bidi controls are never rendered; the approval panel shows the normalised name and an "unusual characters" badge; the trigram duplicate check (FS-03) runs on the normalised form |
| Logs | Pino emits one JSON object per line; reasons come from code lists, never free text, so a newline cannot forge a log line |

### 6.6 Errors and headers

Error envelope `{ code, message, details, request_id }` with stable `ERR_<AREA>_<NAME>` codes; no stack trace, no SQL, no internal ids beyond `request_id`. `helmet` headers, a CORS allow-list of the web origins only, one OpenAPI generated from `/packages`, unused endpoints removed, base path `/v1`. A response is an API response only when its content type is JSON and `success` is present; a WAF HTML 403 or an edge 502 is a transport failure and the device retries the same `batch_uuid` (D-81).

Proved by: T-1-72, T-1-73, T-1-75, T-2-13, T-2-71, T-4-14, T-4-74.

## 7 Anti-abuse

docs/05: assume the fix is hostile, report rather than silently block, and keep the audit trail. This section is the catalogue that makes that executable: GPS spoofing controls, plausibility rules, outlet-location drift, the attack-by-attack control table, the 33 signals, and the surfaces supervisors act on.

### 7.1 Principles and storage

1. Signals are computed on the server from stored facts (D-109). The client contributes raw inputs only.
2. A signal flags and surfaces; it never reverses a memo, a due or a point. Corrections are new rows with reasons (D-22).
3. Each signal has a formula, threshold keys under `cfg.sec.fraud.*` (editor `cfg.sec`, never the field-operations function whose SRs are policed, D-267), a severity (1 info, 2 low, 3 medium, 4 high) and a surface.
4. Supervisors review, dismiss or confirm with a note; confirmations feed the SR's rolling risk score.
5. Latency classes used below: L0 = at ingest or after the batch lands (aggregation lag p95 60 s, D-135); L1 = nightly job (visible by 07:00 Dhaka, D-71); L2 = weekly or monthly pattern; L3 = an alert in minutes.

Storage (owner of the contract: this document; migration shells M-43 and M-56, doc 16):

```sql
CREATE TABLE app.risk_signal (
  id bigint GENERATED ALWAYS AS IDENTITY, at timestamptz NOT NULL DEFAULT now(), business_date date NOT NULL,
  kind text NOT NULL,                    -- 'FS-01' .. 'FS-39'
  severity smallint NOT NULL CHECK (severity BETWEEN 1 AND 4),
  subject_type text NOT NULL, subject_id bigint NOT NULL,        -- user | outlet | device | memo | visit | route | zone
  user_id bigint, route_id bigint, zone_id bigint,               -- scoping keys for RLS and the closure
  evidence jsonb NOT NULL,               -- the numbers that fired it: ids, counts, distances, thresholds, config_version
  score numeric(6,2) NOT NULL, config_version int NOT NULL,
  status text NOT NULL DEFAULT 'open' CHECK (status IN ('open','reviewed','dismissed','confirmed')),
  PRIMARY KEY (id, business_date)) PARTITION BY RANGE (business_date);
CREATE TABLE app.risk_review (client_uuid uuid PRIMARY KEY, signal_id bigint NOT NULL, signal_date date NOT NULL,
  action text NOT NULL CHECK (action IN ('reviewed','dismissed','confirmed')), note text, reviewer_id bigint NOT NULL, at timestamptz NOT NULL);
CREATE TABLE app.user_risk (user_id bigint PRIMARY KEY, score_30d numeric(6,2) NOT NULL DEFAULT 0,
  confirmed_30d int NOT NULL DEFAULT 0, open_signals int NOT NULL DEFAULT 0, updated_at timestamptz);
```

`risk_review` rows are append-only events with a client uuid, so a supervisor's offline action syncs idempotently; `risk_signal.status` is an enrichment column the worker derives from them. `dw.fact_risk_signal` mirrors the table for reports. Per-record checks run in the outbox worker after each batch; pattern checks run in the nightly job (D-61, D-71).

### 7.2 Geo-spoofing controls

| # | Control | Runs | Catches | Defeated by | Default and key | Gate |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Mock invariant: `isMocked` stored per fix; a mocked fix is never `geo_validated_server`, under every policy value (D-96, DQ-23) | device flag, server verdict | Play-Store fake-GPS apps | A hook that clears the flag (needs root) | `cfg.geo.mock_policy` silent_flag, warn_rep, block_sale; default warn_rep; the hard block stays off | T-2-75 |
| 2 | Integrity weights: developer options, mock app installed, root hints, `test-keys`, emulator hints | device at login and bundle | Casual cheating | Magisk DenyList | `cfg.geo.integrity_weight`; `cfg.geo.rooted_policy` flag | T-2-75 |
| 3 | Attestation trust level (s2.6) | server at bind | Repack, emulator, foreign signer | Frida inside a genuine attested app (RR-1) | `cfg.sec.attestation_required_for_trust` true | T-1-12 |
| 4 | Fix freshness and shape (DQ-35): `stale_fix` when fix age exceeds `(cfg.geo.fix_reuse_max_age_s + 5) x 1000` ms (65 s), `fix_time_regress` when `fixed_at` is earlier than the user's previous fix, `synthetic_fix_shape` when provider is GPS with null satellites and zero altitude, speed and bearing on 3 or more fixes in a day. The device never uses `getLastKnownLocation` for a verdict and requires `elapsedRealtimeNanos` inside the request window (D-74) | server on ingest | A replayed or edited fix | Realistic noise added by the attacker (hence the signature) | `cfg.geo.fix_reuse_max_age_s` 60 | T-2-12 |
| 5 | GNSS time (DQ-36): `abs(fixed_at - captured_at)` above the key | server | SDR simulators, which often carry a wrong or frozen GPS time (ASSUMPTION from the fraud analysis; T-2-11 tests it where a HackRF exists) | A simulator with correct time | `cfg.geo.gps_time_skew_max_s` 60 | T-2-11 |
| 6 | Radio environment (D-478), below | device capture, server rules | Rooted hook with a joystick walk; many SDR cases | A spoofer standing under the real serving cell (RR-3) | `cfg.sec.fraud.radio_mismatch_km` 5, `cell_distance_max_km` 10 | T-2-76, T-2-10 |
| 7 | Plausibility rules (s7.3) | server | Teleports, zero jitter, one coordinate for a route | Slow, jittered simulation | `cfg.geo.*` thresholds | T-2-10 |
| 8 | Co-location: 3 or more users with fixes within 50 m and 10 minutes at a point with no outlet inside the radius gives `co_located_users` | nightly job | A ring spoofing several phones at one place | A ring that spoofs a position consistent with the real cell | none (fixed) | T-2-11 |
| 9 | Record signature and `X-Device-Proof` (s2.6) | server | SQLite edits, curl forgery, payload replay | In-process hook inside the signed app | `cfg.sec.record_signature_mode` | T-1-11 |
| 10 | Server re-check as of capture (D-87): device valid and server not is `geo_mismatch`, shown beside K-09 | worker | Client-side tampering | none | `radius_m_used` on the visit | T-2-75 |
| 11 | Evidence photo: camera-only, perceptual-hash reuse (FS-16), photo captured within 10 minutes of the visit | device, worker | Force sale from home with a recycled photo | A fresh photo of any wall | `cfg.media.evidence_camera_only` true | T-2-72 |
| 12 | Outlet-location drift controls (s7.4) | server | Moving the pin to the SR's house | none known | `cfg.sec.fraud.location_move_alert_m` 300 | T-3-11 |

Radio environment (D-478, G-fraud-01, privacy sign-off MUST-CONFIRM by 2d). The one cheap tell that no other signal gives is the cell that actually served the phone.

| Item | Design |
| --- | --- |
| Capture | At each fix (outlet open, attendance, force sale, outlet capture): serving and neighbour cell identities `(MCC, MNC, TAC, CI, RSRP)` from the telephony stack through the location permission the app already holds; up to 8 visible Wi-Fi BSSIDs as 8-byte salted hashes with RSSI only when a recent system scan already exists. No scan is started, no new permission (`READ_PHONE_STATE` is not requested), no sensor held open (R4). Whether the cell read works without extra permission on Android 8 to 12 reference devices is an ASSUMPTION verified in T-2-76 |
| Size | About 200 B per fix (ASSUMPTION), at most 80 fixes per day (D-73): about 16 KB per day before gzip against a 1 MB-per-day data budget |
| Storage | `app.geo_fix.radio_env jsonb`; `app.cell_centroid(mcc, mnc, tac, ci, lat, lng, radius_m, samples, updated_at)` learned from fixes of devices with `trust_level` normal and no flags |
| Rule A (consistency) | The same cell identity seen at two fix positions farther apart than the larger of `radio_mismatch_km` (5) and 1.5 times the cell's learned radius, within 30 minutes: `radio_mismatch` |
| Rule B (centroid) | A fix farther than the larger of `cell_distance_max_km` (10) and 1.5 times the learned radius from the cell's learned centroid: `radio_mismatch`. Before a cell has 20 samples (ASSUMPTION) only Rule A applies |
| Rule C (absence) | No cell information while a SIM is present and a sale is made: `radio_absent`, weight 10 |
| False positives | Rural macro cells can legitimately span tens of kilometres, so both rules scale with the learned radius; calibration uses the same 14 pilot days as the radius calibration (D-93). City cells are small, which makes the rule tight where most fraud value sits (ASSUMPTION) |
| Privacy | Same class as the fix; off until sign-off (`cfg.geo.radio_env_enabled` false, OI-19-17); the salt is delivered in the bundle config and is not a secret |
| Visibility | Supervisor only (D-123) |

### 7.3 Plausibility rules and default thresholds (cfg keys by reference)

Rule text lives in doc 16 DQ-25 and DQ-35 to DQ-43; registered thresholds live in doc 19 s3.2.1. Weights are `cfg.geo.integrity_weight`.

| Rule | Formula | Default keys | Flag and weight |
| --- | --- | --- | --- |
| Teleport | Consecutive fixes of one user on one business date: distance at least `teleport_min_distance_m` and distance over trusted time difference above `max_speed_kmh` | 500 m; 60 km/h (16.7 m/s), so two fixes 500 m apart less than 30 s apart flag and two fixes 30 m apart never do | `teleport` 40 |
| Zero jitter and perfect accuracy | At least `min_fixes_for_jitter` validated visits in a day whose offsets from their outlets have a standard deviation below `jitter_threshold_m`; or accuracy at or below `perfect_accuracy_threshold_m` on all of 5 or more fixes | 8; 2 m; 3 m | `zero_jitter` 40 |
| Same point | Fixes within 20 m of each other while the outlets' stored coordinates are at least `radius_m_used` apart, for more than `same_point_outlets_max` outlets; outlets with `location_confirmed = false` are excluded | 3 | `same_point` 30 |
| Inter-visit gap | 5 or more consecutive visits with gaps below `cfg.sec.fraud.min_visit_gap_s` | 90 s | FS-08 evidence |
| Accuracy cap | A fix with `accuracy_m` above the key cannot be geo-valid; accuracy is not subtracted from the distance | `max_accuracy_m` 100; `accuracy_tolerant` false (D-264) | not valid |
| Freshness | Fix reuse up to the key's age and 30 m | `fix_reuse_max_age_s` 60 | `stale_fix` 20 |
| GNSS time | s7.2 row 5 | `gps_time_skew_max_s` 60 | `synthetic_fix` 30 |
| Suspicious visit | The weighted sum reaches the threshold | `suspicious_score_threshold` 50 | counted in the suspicious-location count |

Dense markets (docs/22 P-10: 80 percent of outlets share a 55 m cell, the densest cell holds 383) are why same-point compares fixes with stored outlet coordinates: co-located shops alone never flag.

Suspicion score. `score = min(300, trust_multiplier x sum of the weights of the fired flags)`, trust multiplier 1.0, or 1.5 for a `low` trust device (ASSUMPTION, calibrated in the pilot). Examples: mock alone 100 (always over 50); teleport alone 40, not suspicious; teleport plus `radio_absent` 50, suspicious; `stale_fix` 20 plus rooted 20 plus developer options 10 equals 50, suspicious; teleport alone on a low-trust device 60, suspicious. The route-level number shown to supervisors is the count of visits at or above the threshold per route-day.

The doc 16 text of DQ-25 names a speed default of 90; doc 19 registers 60 (ASSUMPTION: urban motorbike beat). This document follows doc 19 (OI-21-09).

### 7.4 Outlet-location drift (D-111, D-95, D-163; closes G-fraud-06 and G-man-017)

Verification fact (V-outlets): the manuals say only that "location information will be updated" when the photo is taken; they do not prove that master coordinates are overwritten and they show no approval. The constraint wins: the photo raises a request and a provisional point, and the stored location changes only on approval, except where it is missing or a placeholder (DELIBERATE CHANGE).

| Situation | Rule | Key |
| --- | --- | --- |
| Missing or placeholder location (1,093 none, 34,454 on 11,222 shared points, docs/22 P-09) | The first force-sale or capture fix becomes the provisional location at once, the AMO alone confirms it and `location_confirmed` becomes true; no distance rule, no punitive geo-fail | `cfg.geo.no_location_policy` force_sale_required |
| Confirmed outlet, SR force sale or AMO Manual Override | Photo and fix raise a `location` request; the call proceeds (`photo_validated` true, `geo_validated` false); the requester's later visits use a provisional point flagged `location_basis = provisional` | `cfg.geo.first_capture_sets_location`, `cfg.geo.override_max_per_day` 10 |
| Credit for provisional visits (D-487, G-21-03) | A visit validated only against a provisional point is photo-valid and visited but is not in the K-09 numerator until the request is approved; approval re-evaluates the requester's visits at that outlet since the request (at most 30 days) through the idempotent worker re-check; rejection leaves them not geo-valid. Moving a pin to one's house therefore earns nothing until a verifier agrees, and the SR is still not forced to Force Sell at that shop every day | OI-21-10 asks doc 16 to amend K-09 |
| Move above the alert distance on a confirmed outlet | Needs TSO approval (`requires_tso`), FS-22 on requester, verifier and outlet | `cfg.sec.fraud.location_move_alert_m` 300 |
| Verification | The AMO's own fix must be within the resolved radius of the proposed point, else the verification is accepted but flagged `remote_verification` and routed to the TSO | `cfg.outlet.verify_requires_onsite_fix` true |
| Moved toward the requester | Proposed point within 500 m of the requester's check-in centroid (mean of his check-in fixes of the last 30 days, ASSUMPTION): `moved_to_home` evidence | fixed |
| Repeat moves | More than the key per outlet per quarter | `cfg.sec.fraud.moves_per_outlet_quarter` 2 |
| Update Base (AMO) | AMO's fix within the key of the chosen point; a move limit; a monthly cap per outlet; no mocked fix; photo required | `cfg.geo.update_base_max_distance_m` 100, `update_base_max_move_m` 1000, `update_base_max_per_outlet_month` 1 |
| Approval policy | Default `amo_then_web`. Recommendation to doc 19 (OI-19-05): remove `auto`; keep `amo_only` only for `location_confirmed = false` outlets, which is exactly the D-111 placeholder path, so the enum value is redundant for confirmed outlets | `cfg.geo.outlet_location_change_approval` |
| Request volume (D-545, G-qa-76) | One OPEN request per outlet and purpose; a repeat force sale adds evidence instead of a request; no request below `cfg.outlet.location_request_min_move_m` (25 m) or from a fix less accurate than `cfg.outlet.location_request_max_accuracy_m` (50 m) or from a mocked fix; aging and escalation to the TSO after 72 h and lapse after 14 days; a bulk approve of proposals that agree within 30 m with at least 3 independent visits behind them (never for a move above the alert distance, never for `remote_verification` or `moved_to_home`); the volume model is doc 18 s1.11 | doc 16 s4.4; doc 19 keys |
| Staff-phone ghost shops | A proposed outlet phone equal to any `app_user.phone` or a phone on another user's request within 90 days: `outlet_phone_is_staff` (DQ-38) | FS-03 evidence |

### 7.5 Attack, prevention, detection, supervisor report

Rows are the attacks a motivated SR, a rooted helper, a supervisor, an admin or an outsider can still run. "Residual" ids point to s11.

| # | Attack | Prevention | Detection (signal, default threshold) | Supervisor report and latency |
| --- | --- | --- | --- | --- |
| 1 | Play-Store fake GPS from home | Mock invariant; mock-app and developer-options weights | Score 100; FS-08 evidence | AMO Exceptions and suspicious-location count, L0 |
| 2 | Rooted hook clears the mock flag, joystick walks at 4 km/h | Attestation trust low (x1.5); root weight | FS-25 radio mismatch (over 5 km from the serving cell); FS-08 gap rule (visits under 90 s apart) | AMO and TSO Exceptions, L0 to L1; RR-1 when the radio is consistent |
| 3 | SDR GNSS simulator in a shop or car | none on the device | GPS time anomaly (60 s); co-located users (3 users, 50 m, 10 min); radio mismatch | TSO web Exceptions, L1; RR-3 beside the tower |
| 4 | Replay a genuine fix by editing SQLite | Record signature; no `getLastKnownLocation` | `stale_fix` (65 s), `fix_time_regress`, `synthetic_fix_shape`; `sig_invalid` PARK | AMO Exceptions; FS-18 alert, L0 |
| 5 | Force sale from home every time | Photo required; a force sale is never geo-valid in any KPI | FS-09 share above 2.0 x zone median; FS-16 photo reuse | AMO Exceptions weekly (L2); By-Route Geo Capture report |
| 6 | Move an outlet's pin to the SR's house | Provisional point earns no K-09 credit; approval; on-site verification | FS-22: move over 300 m; within 500 m of check-in centroid; remote verification | Badge in the AMO verification screen and web approval panel, L0 |
| 7 | Check in from home | Mock invariant | FS-12: check-in over 2,000 m from the nearest planned outlet, no visit for 2 h | GIGO report, L1 |
| 8 | Visit unplanned outlets or days to lift CPR | Target outlets include every visited outlet (D-57) | `unplanned_visits` shown apart | Daily Tracking, L1 |
| 9 | Open and abandon visits to pad "visited" | Abandoned visits excluded (D-38) | none needed | none |
| 10 | Phantom credit memos in the last 3 days of the month | Server price recompute; edit rules | FS-01: credit share 2.5 x own 60-day median; last-3-day STD 2.0 x month daily mean; outlet memo count 3 x its 90-day mean; FS-04 dues age over 30 days | AMO Exceptions, L1; the retailer's dispute is the hard proof; FS-07 optional |
| 11 | Sell, print, then edit down | Edit window (D-86); void is an event | FS-02: reduction over 30 percent; over 10 edits a month; edit of an unprinted memo | Exceptions; Live Dashboard shows edited and voided counts, L0 |
| 12 | Zero-sale every outlet from the car | Zero sale is a visit, not a successful call (K-04) | FS-14: share over 2.0 x zone median; dwell under 30 s | Strike-rate footnote, AMO Exceptions, L1 |
| 13 | Split one sale into several memos | none | FS-15: 3 or more memos at one outlet within 15 minutes | Memo report, L1; two memos escape, bounded gain |
| 14 | Collect cash and record less or nothing | Dues ledger; mark-as-paid settles the whole memo (D-37) | FS-04; FS-32 cash variance against the counter-party count; FS-33 collection without presence | TSO Exceptions; distribution-house settlement view, L1; needs D-481 |
| 15 | Fake a collection to clear fabricated dues | Same | FS-33 | TSO Exceptions, L1 |
| 16 | Loyalty farming, cash-back pocketed | Points computed on the server; over-balance rule D-266 | FS-06: over 1 redemption per outlet per month; all-credit outlets; gift photo missing after 7 days | Campaign Gift Redemption report; AMO Exceptions, L1 |
| 17 | Ghost outlet through a new-shop request | On-site verification; approver is not the verifier | FS-03: `phone_hash` match, name similarity at least 0.6 within 100 m, staff phone, dormant after approval 30 days | Duplicate badge; approval needs "merge" or "not a duplicate" plus a note, L0 |
| 18 | Closure requests to shrink the denominator | Approval; closure is a status | FS-23: requests over 3.0 x peers; reappearing `phone_hash` | TSO and DMO report, L2 |
| 19 | Under-report stock issue and sell off-book | Counter-party confirmation (D-481) | FS-17 against the confirmed issue | Stock report; settlement view, L1; interim 10 percent register sample |
| 20 | DRP and free-sample abuse | Server recompute of the discount | FS-19: DRP share 2.5 x zone median; price-mismatch rate over 5 percent | Discount report, Price Compliance, L1 |
| 21 | Stay offline all day, sell at yesterday's prices | Stale bundle limited to 2 days and flagged (D-70) | FS-20: offline share 2.5 x peers on 10 or more days a month | AMO Exceptions; Daily Tracking pending upload, L2 |
| 22 | Skip printing so edits cannot be disputed | Print state recorded | FS-27: unprinted share over 10 percent | Memo report, L2 |
| 23 | Astha tier for a friendly outlet | Tier set at verification is a proposal until the TSO approves (`cfg.astha.tier_requires_tso_approval`) | FS-31 tier outlier | TSO approval screen, L0 |
| 24 | Share the password or swap phones | Binding limits; identity prompt on a shared phone (F-SR-072) | FS-21 interleave: two users capturing within 30 minutes | AMO Exceptions, L1; RR-2 |
| 25 | Stolen phone, token read off it | Device-key proof; suspend and revoke | Reuse gives FS-18 | TSO device panel, L3 |
| 26 | Rooted shared phone: B uses A's session | Upload grant is separate and cannot read (D-471) | FS-21 | AMO Exceptions, L1; RR-1 |
| 27 | Repackaged or downgraded APK | Server validates every row; attestation trust low; blocked-version parking (DQ-39) | Low-trust badge; `blocked_version_capture` | AMO and TSO, L0 |
| 28 | Supervisor takeover chain: reset, OTP, bind own phone | Held bind; the victim's phone shows the reset (401 reason); wave cohort pre-acknowledgement | FS-21 severity 4 | DMO and `security_admin` alert, L3 |
| 29 | Clock back-dating | Trusted time anchor | FS-13: skew over 10 min; untrusted rows in a closed month parked | Sync-health and Exceptions, L0; RR-11 |
| 30 | Replay the same batch | `(device_id, batch_uuid)` replay | none needed | none |
| 31 | Replay with regenerated uuids | Signature covers `client_uuid`; exact fingerprint REJECT (D-353) | FS-24; near-duplicate FS-30 | Exceptions, L0 and L1 |
| 32 | Forge a batch with curl and a lifted token | Token plus proof plus record signature; `user_id` stamped from the token | `sig_invalid` and FS-18 | `security_admin` alert, L3 |
| 33 | Support edits a device file before replay | Four-eyes; `entry_source = support_replay` | `replay_excess` | Zone TSO, L1 |
| 34 | AMO dismisses every exception | Dismissal review (s7.6) | FS-26: dismissal rate against peers; 10 percent re-sample | DMO report, L2 |
| 35 | TSO raises radius to 2,000 m | Propose mode default; any increase ending above 150 m is C3 | Two-sided anomaly watch: geo-valid % rise, force-sale drop | Config home; DMO, L1 |
| 36 | Retroactive price, target or calendar edits | Future-dated only; maker-checker; target lock after month start | Blast-radius preview; FS-23 evidence | "Restated" marker; retroactive-calendar report, L0 |
| 37 | Raise fraud thresholds to silence the catalogue | `cfg.sec` ownership, floors and ceilings | Dead-signal watch: fleet count down 90 percent week on week | `security_admin` alert, L2 |
| 38 | Data Entry of phantom memos | TSO confirmation above 20 memos per route-day | FS-28 | Online/Offline report; Exceptions, L1 |
| 39 | Edit rows in the database | `tx_no_rewrite` trigger; no UPDATE or DELETE grants | Nightly `dw.reconcile()` sum diff | Sev1 page, L1 |
| 40 | Break-glass used to loosen a control | Restore or restrict only (D-436) | Audit, 24 h review | All approvers alerted, L3 |
| 41 | Page the retailer list through list endpoints | Masked by default; hourly budget | 429 plus export log | `pii_officer`, L0 |
| 42 | Formula injection, ESC/POS bytes, bidi names | Sanitisers (s6.5) | Unusual-characters badge | Approval panel, L0 |
| 43 | Credential stuffing; wave-day lockout denial of service | Device-keyed lockout, step-up, uniform errors | Fleet lockout alert (500 usernames in 10 min) | Sev1, L3 |
| 44 | Runner injects code into the APK | Human publish; independent rebuild; signing key off the runner | Digest mismatch blocks publish | Release manager, L3 |

### 7.6 Supervisor surfaces, SR visibility, dismissal review and risk score

| Surface | Role | Content and behaviour |
| --- | --- | --- |
| Exceptions screen (F-AMO-038) | AMO, zone | Open signals grouped by SR then kind; one-tap review, dismiss, confirm with a note; delivered in the bundle, at most 200 rows, readable offline; actions are `risk_review` records that sync idempotently (OFFLINE capture, QUEUED upload; 3a; T-3-73) |
| Exceptions page (F-WEB-057) | TSO, DMO, WM, top, `security_admin` in scope | Filters date, kind, severity, status; drill to evidence; export carries outlet code and name only. The TSO app keeps its seven drawer entries (D-204), so the TSO reads Exceptions on the web; whether the business wants a TSO app entry is OI-21-12 |
| Suspicious-location report (F-WEB-044) | AMO, TSO+ | Visits at or above the score threshold, mock counts, a pattern over time (docs/05) |
| SR risk badge | AMO, TSO on Team Performance | `user_risk.score_30d` band: green below 20, amber 20 to 59, red 60 and over (ASSUMPTION; keys `cfg.sec.fraud.user_risk_amber`, `user_risk_red`; IMPROVEMENT) |
| Sync-health | ops | `security_event` counts, FS-13 and FS-18 trends |

SR-visible versus supervisor-visible (D-123, closes G-fraud-26):

| Item | SR sees | AMO and TSO see |
| --- | --- | --- |
| Mocked fix | The warning `geo.mock_warning` under `warn_rep` or `block_sale` | Mock count, score |
| Geo failure | The Force Sale prompt | Geo %, force share |
| Plausibility, radio, signature, time-skew, duplicate and every other signal | Nothing | The signal, evidence and score |
| Rejection | The reason text of REJECT-policy codes only | The same plus the code |
| Operational prompts (set the date and time, permission rationale, printer state) | Yes: they are instructions, never a "flagged" statement | Not applicable |

`cfg.sync.reason_texts` contains only REJECT-policy codes; a lint fails the build if a FLAG-policy code appears (T-2-79). Showing flags to the rep teaches evasion.

Dismissal review (D-109, G-fraud-20, FS-26):

| Rule | Behaviour | Key or gate |
| --- | --- | --- |
| Severity-4 dismissal | Needs a note of at least 20 characters and is visible to the DMO | fixed; T-3-12 |
| Re-sample | A random 10 percent of AMO dismissals are re-queued to the TSO | `cfg.sec.fraud.dismissal_resample_pct` 10; T-3-12 |
| Rate signal | An AMO whose dismissal rate is 3 times his peers, or whose dismissed signals are later confirmed, raises FS-26 | T-3-12 |
| Confirmed signals | Feed `user_risk`: `score_30d` = sum over the last 30 days of severity weight (info 1, low 3, medium 10, high 30) x status factor (confirmed 1.0, open 0.5, reviewed 0.25, dismissed 0), ASSUMPTION calibrated in the pilot | T-5-71 |

### 7.7 Signal catalogue FS-01 to FS-34

FS-01 to FS-19 come from the security lens, FS-20 to FS-29 from the fraud critic, FS-30 to FS-33 are new here (block FS-30 to FS-39) and FS-34 was added by the round-3 pass (D-571). Keys are `cfg.sec.fraud.<name>` unless a `cfg.geo` key is named; "Phase" is the first sub-milestone in which the signal is computed.

| ID | Scheme | Signal (formula) | Threshold keys (defaults) | Sev | Surface | Phase |
| --- | --- | --- | --- | --- | --- | --- |
| FS-01 | Fake sales to hit targets | Per SR-day: credit share (sum due over sum net) against the SR's own 60-day median; last-3-day STD over month daily mean; outlets whose memo count jumps over their 90-day mean; sales to `location_confirmed = false` or closed outlets | `credit_share_x` 2.5, `month_end_spike_x` 2.0, `outlet_jump_x` 3.0 | 3 | AMO Exceptions; web sales-anomaly report | 5c |
| FS-02 | Memo edits after the fact | Edit or void where the original has QC or day state at or past `sales_submitted` (rejected and flagged); net reduction; edits per SR-month; edit with `server_geo_valid` false; edit or void of an unprinted memo | `edit_reduction_pct` 30, `edits_per_month` 10 | 3 to 4 | Rejected at ingest outside the window; else AMO Exceptions | 2d |
| FS-03 | Ghost or duplicate outlets | At request: `phone_hash` match; name trigram similarity within a radius; requester's new-outlet rate against the zone mean; staff phone; zero sales 30 days after approval; first 5 memos all credit | `dup_name_sim` 0.6, `dup_radius_m` 100, `dormant_after_approval_days` 30 | 3 | Duplicate badge on the verification screen and approval panel | 3a |
| FS-04 | Dues skimming | Outlet due age; dues growing while the outlet buys cash elsewhere; a collection followed by a superseding memo that lowers the due; collections equal to round numbers | `due_age_days` 30 | 3 | TSO Exceptions; dues ageing report | 4a |
| FS-05 | Dues write-off abuse | `finance.adjust` above the key without an approver; adjustments per outlet per quarter | `adjust_approval_mtk` 50,000,000 (50,000 Tk) | 4 | Maker-checker; audit report | 6b |
| FS-06 | Loyalty abuse | Redemptions per outlet per month; cash-back where all the outlet's memos were credit; gift photo missing after the key; points never from the client | `redemptions_per_outlet_month` 1, `gift_photo_due_days` 7 | 3 | Campaign Gift Redemption; AMO Exceptions | 5a |
| FS-07 | Retailer-confirmed money (optional) | SMS receipt to the outlet phone on a credit sale and on a collection; consent per outlet; volume unknown; confirm with the business (the critic's estimate is 10,000 to 20,000 a day, ASSUMPTION) | `sms_receipts_enabled` false (C3) | n/a | Business decision | 5c |
| FS-08 | Clean-GPS fakery | Route-day with all visits in one 55 m cell, 5 or more consecutive visits under the gap, zero accuracy variance, geo-valid 100 percent with identical accuracy; plus s7.3 | `min_visit_gap_s` 90 | 3 | Suspicious-location count per route | 2d |
| FS-09 | Force-sale abuse | Force-sale share per SR-week against the zone median; force sales at outlets other SRs validate; the same photo hash | `force_share_x` 2.0 | 2 to 3 | AMO Exceptions; By-Route Geo Capture | 2d |
| FS-10 | Approver collusion | One AMO verifying a high share of new outlets that go dormant; an AMO verifying a request he raised (blocked); reopens per TSO-month | `dormant_after_approval_days` 30 | 3 | DMO web report | 3a |
| FS-11 | OTP or device misuse | Bind from a device model never seen in the zone; one device bound to users of different zones; over `max_users_per_device`; bind at odd hours; password success on an unknown device while a bound device exists | none (fixed) | 3 | TSO device panel; `security_event` | 1b |
| FS-12 | Attendance fraud | Check-in fix farther than the key from the nearest planned outlet (not a route centroid, which is meaningless on a 10 km rural route); check-in then no visit for 2 h; check-out at exactly 17:00 with the last visit hours earlier | `checkin_radius_m` 2,000 | 2 | GIGO report | 2e |
| FS-13 | Clock back-dating | `clock_skew_flag` or `time_untrusted`; wall time moving backwards between syncs; `clock_changed_count` | `cfg.sync.max_clock_skew_min` 10 | 3 | Sync-health; Exceptions | 1b |
| FS-14 | CPR inflation with zero-sale calls | Zero-sale share per SR-day against the zone median; dwell under 30 s; outlets zero-sale for 4 weeks yet visited | `zero_sale_share_x` 2.0 | 2 | Strike-rate footnote; AMO Exceptions | 2d |
| FS-15 | Memo splitting | 3 or more memos at one outlet by one SR within the window; one-line identical-SKU runs | `split_window_min` 15 | 2 | Memo report | 2d |
| FS-16 | Photo reuse or tampering | Duplicate SHA-256 across records; perceptual-hash Hamming distance at most the key across different outlets or days; SHA-256 mismatch at upload; photo more than 10 minutes from the visit | `phash_distance` 6 | 3 | Approval panel; Exceptions | 2c |
| FS-17 | Stock leakage | Per SR-day-SKU: issued minus sold minus returned, against the confirmed issue (D-481), beyond the tolerance in base units; chronic negative (selling more than issued) | `stock_tolerance_qty_base` 0 | 2 to 3 | Stock report; distribution-house reconciliation | 4a |
| FS-18 | Credential or token abuse | Refresh reuse outside the grace; more than 2 devices in a day; cross-user `client_uuid` collisions; proof or record-signature failures | none (fixed) | 4 | `security_event`; alert; family revoked | 1b |
| FS-19 | Discount or offer abuse | Offer applied without qualifying lines or DRP packs; `price_mismatch` rate per SR; DRP share; free-sample outlets with no paid purchase in 30 days | `price_mismatch_pct` 5, `drp_share_x` 2.5 | 2 to 3 | Discount report; Price Compliance | 2a |
| FS-20 | Offline outlier | `captured_offline` share per user-day against same-zone peers, on at least the key's days a month; `stale_price` memos per SR-month | `offline_share_x` 2.5, `offline_outlier_days` 10 | 2 | AMO Exceptions; Daily Tracking pending upload | 4a |
| FS-21 | Supervisor takeover or device interleave | Reset plus OTP for one user by one actor inside the window; bind of a device previously bound to the actor or another user; two users capturing on one device inside the key | `cfg.sec.takeover_window_h` 24, `user_interleave_min` 30 | 4 | DMO and `security_admin` alert; bind held | 3b |
| FS-22 | Outlet location drift | s7.4 | `location_move_alert_m` 300, `moves_per_outlet_quarter` 2 | 3 to 4 | Verification screen, approval panel, Exceptions | 2d |
| FS-23 | Denominator manipulation | Closure requests per SR-month against peers; a closed outlet reappearing by `phone_hash` or visited by another SR within 90 days; retroactive visit-day or holiday edits per TSO-month | `closure_requests_x` 3.0 | 3 | TSO and DMO report; approval-panel badge | 3a |
| FS-24 | Content replay | Same `content_fp` for the same user within 48 h: the second record is refused and FS-24 is raised on the first (D-353) | none | 3 | Exceptions | 1b |
| FS-25 | Radio mismatch or synthetic fix | `stale_fix`, `fix_time_regress`, `synthetic_fix_shape`, `gps_time_anomaly`, `radio_mismatch`, `radio_absent`, `co_located_users` | `radio_mismatch_km` 5, `cell_distance_max_km` 10, `cfg.geo.gps_time_skew_max_s` 60 | 3 to 4 | Suspicious-location count | 2d |
| FS-26 | Dismissal abuse | Dismissal rate per AMO against peers; dismissed-then-confirmed; re-sample hits | `dismissal_resample_pct` 10 | 3 | DMO report | 3a |
| FS-27 | Unprinted memo pattern | Unprinted share per SR-week; edit or void of an unprinted memo | `unprinted_share_pct` 10 | 2 | Memo report | 4a |
| FS-28 | Web-entry share | Web-entry memos over memo count per route-day; Data Entry above the key without TSO confirmation | `web_entry_share_alert_pct` 10, `web_entry_memos_per_route_day` 20 | 3 | Online/Offline report; Exceptions | 4c |
| FS-29 | Approver cooling or grant abuse | An approver grant newer than the key approving a C3 change; adjustments per actor-day over the key | `cfg.sec.approver_cooling_h` 24, `adjust_daily_total_mtk` 200,000,000 | 4 | `security_admin` alert | 6b |
| FS-30 | Near-duplicate content (D-477) | Memo with the same outlet, the same sorted (SKU, quantity, unit price) lines and the same paid amount as another memo of the same user within the window, ignoring time; nightly; flag only | `near_dup_window_h` 48 | 2 | Exceptions on the second memo | 5c |
| FS-31 | Astha tier outlier | Tier proposed at verification against the outlet's 90-day STD percentile within the zone | `tier_percentile_min` 50 (ASSUMPTION) | 2 | TSO approval screen | 5a |
| FS-32 | Cash handover variance (D-481) | Per SR-day: cash collected (cash memos plus collections minus cash-back paid) against the cash counted by the confirmer; unconfirmed days older than one trading day | `cash_variance_mtk` 2,000 (2 Tk, ASSUMPTION) | 3 | TSO Exceptions; distribution-house settlement view | 4a |
| FS-33 | Collection without presence | A `due_collection` whose fix or visit is outside the outlet radius or absent, on a memo created by the same SR in the last 3 days of a month | none (fixed) | 3 | TSO Exceptions | 4a |
| FS-34 | Config stamp regress (D-571) | A device whose rows are stamped with a `config_version` below one the server delivered to it more than `cfg.sys.config_apply_grace_min` before the capture, on at least `stamp_regress_min_rows` rows in a business day; the rows are judged under the as-of value | `cfg.sec.fraud.stamp_regress_min_rows` 3, `cfg.sys.config_apply_grace_min` 10 | 3 | AMO and TSO Exceptions; `security_admin` list | 2d |

Threshold keys not yet in doc 19's 16-row table (closes OI-19-11 on the doc 21 side; doc 19 registers them with the common attributes: scope G, editor S, effect B, delivery srv, class C2, a floor and a ceiling so the GUI cannot disable a rule):

| Key | Default | Floor to ceiling |
| --- | --- | --- |
| `credit_share_x`, `month_end_spike_x`, `outlet_jump_x` | 2.5, 2.0, 3.0 | 1.2 to 10 |
| `edit_reduction_pct`, `edits_per_month` | 30, 10 | 5 to 90; 3 to 100 |
| `dup_name_sim`, `dup_radius_m` | 0.6, 100 | 0.3 to 0.95; 20 to 500 |
| `due_age_days`, `dormant_after_approval_days` | 30, 30 | 7 to 180 |
| `adjust_approval_mtk` | 50,000,000 | 1,000,000 to 1,000,000,000 |
| `redemptions_per_outlet_month`, `gift_photo_due_days` | 1, 7 | 1 to 5; 1 to 60 |
| `min_visit_gap_s`, `split_window_min` | 90, 15 | 30 to 600; 5 to 60 |
| `force_share_x`, `zero_sale_share_x` | 2.0, 2.0 | 1.2 to 10 |
| `checkin_radius_m` | 2,000 | 500 to 10,000 |
| `phash_distance` | 6 | 0 to 16 |
| `stock_tolerance_qty_base` | 0 | 0 to 1,000 |
| `price_mismatch_pct` | 5 | 1 to 50 |
| `sms_receipts_enabled` | false | C3 |
| `near_dup_window_h`, `tier_percentile_min`, `cash_variance_mtk` | 48, 50, 2,000 | 1 to 168; 0 to 100; 0 to 100,000 |
| `user_risk_amber`, `user_risk_red` | 20, 60 | 1 to 300 |

A floor or ceiling that stops a rule from being disabled is the point: raising `credit_share_x` to 100 is refused by the console (T-6-10).

### 7.8 Money-side counter-party controls (G-fraud-07, D-481)

Fabricated cash memos, skimmed collections, under-reported issue and pocketed cash-back all balance on the SR's own numbers, because the SR reports issue, sales, returns and cash. A signal needs a second party:

| Control | Design | Phase |
| --- | --- | --- |
| Counter-party confirmation | The distribution house, or the AMO as proxy when the keeper has no login (F-AMO-042; Q41), confirms issue, return and cash handed over each day (F-SR-051, F-SR-052; settlement view F-WEB-059). FS-17, FS-04 and FS-32 compute against the confirmed figures; unconfirmed days are marked `unconfirmed` and age on the report | 4a; unknown; confirm with the business (Q41): does the keeper get a login |
| Cash-back in the ledger | The 2 Tk per point cash-back (D-41) enters the cash ledger as a negative line so the hand-over count reconciles | 5a |
| Interim before the actor exists | The stock-memo print carries a signature line and the AMO photographs the keeper's issue register for a 10 percent sample (`cfg.sec.fraud.stock_sample_pct`) | 2e |
| SMS receipts | FS-07, off by default, credit sales and collections only, opt-in per outlet | 5c |

### 7.9 Rails that keep the catalogue from being silenced (mechanics: doc 19 s7)

| Requirement from this document | Rail |
| --- | --- |
| Fraud thresholds are not editable by the policed function | `cfg.sec.fraud.*` editor `cfg.sec` or `config_approver`, class C2 or higher, floor and ceiling per key (D-109, D-267) |
| A silenced signal is noticed | Dead-signal watch: a kind whose fleet count falls by `cfg.sec.fraud.dead_signal_drop_pct` (90) week on week after a threshold change alerts `security_admin` |
| Loosening cannot hide | Two-sided anomaly watch: a rise in geo-valid % after a radius increase, or a drop in force-sale share, alerts (D-94) |
| Radius increases | Any increase that ends above `cfg.geo.radius_increase_escalation_m` (150) is C3; TSO edits are proposals by default (`cfg.geo.tso_radius_mode`); the skeleton reading of D-94 is used (OI-19-04) |
| Emergency changes | Break-glass restores or restricts only (D-124, D-436) |
| History cannot be rewritten through master data | Prices, targets after the month starts and past calendar dates are future-dated only; back-dating is `finance_approver` maker-checker with a restated-row preview (D-98) |
| Approvers are independent | Cooling of 24 h, outside the requester's chain (FS-29) |

Proved by: T-1-10, T-1-11, T-1-12, T-1-13, T-2-10, T-2-11, T-2-12, T-2-13, T-2-14, T-2-75, T-2-76, T-2-77, T-2-79, T-3-10, T-3-11, T-3-12, T-3-73, T-3-77, T-4-14, T-4-77, T-5-10, T-5-71, T-5-73, T-6-10, T-6-73, T-7-10.

## 8 Audit logging

R6 asks for an audit trail on every operating parameter; security needs the same trail for people, devices and data access. The current `activity_log` is client-asserted and is not security-grade (G-sec-11).

### 8.1 What is logged

| Event family | Table | Fields beyond the common columns (`id`, `at`, `actor_id`, `actor_role`, `request_id`, `via`) |
| --- | --- | --- |
| Master-data create, update, delete (geography, products, prices, sales plan, routes, assignments, users, scope, outlets, classifications) | `app.audit_log` | `entity`, `entity_id`, `before`, `after`, `diff` |
| Config changes | `cfg.config_change_audit` (doc 19 s7.8) | `approved_by` mandatory for C3, `config_version`, acknowledgement reach |
| Targets and revisions, approvals (outlet requests, leave, target sets) | `app.audit_log` | approval level, requester, verifier, approver (different people) |
| Day control (final submit, reopen, data void, Data Entry, entry unlock) | `app.audit_log` | reason mandatory on reopen, void and unlock |
| Security events | `app.security_event` | `kind`, `target_user_id`, `device_id`, `ip`, `ua_hash`, `detail` (catalogue below) |
| PII access (s8.3) | `app.report_export_log`, `app.audit_log(action = 'pii_read')` | `includes_pii`, filters, `row_count` |
| Finance adjustments (dues, loyalty) | `app.audit_log` | maker, checker, amount |
| Risk reviews | `app.risk_review` (append-only) | action, note |
| Support decrypts and replays | `app.audit_log` | bundle, ticket, purpose |
| Device-side activity | `app.activity_log` | client-asserted and batched, never security evidence |

Security event kinds (D-485): `login_failure` (deduplicated per username, device and minute), `login_success_new_device`, `bind`, `bind_held`, `bind_ack`, `cohort_ack`, `otp_issue`, `otp_view` (one row per page view with a row count), `otp_consume`, `otp_bind_locked`, `refresh_reuse`, `uuid_collision_cross_user`, `sig_invalid` (aggregated per device per hour), `device_suspend`, `device_revoke`, `device_reactivate`, `force_logout`, `password_change`, `password_reset`, `mfa_enrol`, `mfa_verify_failure`, `lockout`, `step_up`, `fleet_lockout_alert`, `scope_changed`, `break_glass`. A successful refresh is not logged (it is a metric): with 8,500 devices refreshing about hourly it would add about 120,000 rows a day for no forensic value. Expected volume with the rule is under 100,000 rows a day, dominated by first-morning logins (ASSUMPTION).

### 8.2 Immutability (D-113, D-484; proved by T-0-64, T-1-77, T-6-71)

1. Grants: `api_rw` has INSERT and SELECT only on `app.audit_log`, `cfg.config_change_audit`, `app.security_event` and `app.report_export_log`; UPDATE, DELETE and TRUNCATE are revoked from every role, `worker_rw` included. Only `migrator` owns the tables and migrations never touch audit rows.
2. Trigger `audit_no_rewrite` (BEFORE UPDATE OR DELETE) raises; partition detach for archival is allowed only through the archival job after export.
3. Transactional tables: doc 16 s6.6 owns the `tx_no_rewrite` trigger and its per-table enrichment allow-lists, and `api_rw` has no DELETE grant on any transactional table (D-98). This document requires both as a fraud control: an administrator editing a memo row through the database is stopped by the trigger and caught by the nightly `dw.reconcile()` sum diff if the trigger is dropped.
4. Hash chain: `row_hash = sha256(prev_hash || canonical_json(row))` set by an insert function that holds an advisory lock per table, so the chain is serial; at under 100,000 rows a day that is about 1 per second on average and low hundreds per second at the wave-morning peak (ASSUMPTION). If contention is measured in T-1-77, the chain becomes a per-minute Merkle root over a batch of rows and the decision is recorded. A daily job re-verifies the chain; a mismatch pages `security_admin` as Sev1.
5. Export: the previous Dhaka day's rows go as NDJSON to the `audit-export/` container with immutable time-based retention (7 years, ASSUMPTION; `cfg.retention.audit_years` floor 7; MUST-CONFIRM by 6b, legal) and a legal-hold capability; storage redundancy is RA-GZRS (D-132). A locked time-based policy cannot be shortened, so the policy stays unlocked during the pilot and is locked before wave 1, after counsel confirms the period. The export's SHA-256 and the chain head are also written to a Log Analytics workspace under separate RBAC, so a database administrator cannot rewrite both.
6. Platform layer: Azure Activity Log, Key Vault logs, `pgaudit` on the platform administrator role and DDL, and Front Door WAF logs go to Log Analytics for 1 year with their own access control (no `api_rw` access).
7. Viewer: F-ADM-034 reads only, filters by entity, actor and date, and its own exports are logged.

### 8.3 PII-read log

Every list or detail response that returns phone, owner name, address, NID, TIN or licence writes one aggregated row per request (user, endpoint, row count, whether any value was unmasked); a reveal, an OTP view, a SR-phone read by a TSO, an export and a support decrypt each write their own row. Per-row logging is deliberately avoided: a 2,000-row page would otherwise write 2,000 audit rows. Viewer filters: user, kind, day; alert when a user's hourly count reaches 80 percent of `cfg.pii.list_rows_per_hour` (T-4-14).

Proved by: T-0-64, T-1-77, T-4-14, T-4-72, T-6-71, T-7-73.

## 9 Supply chain and CI security (D-122, closes G-sec-19 [G-fraud-16])

| Area | Rule | Gate |
| --- | --- | --- |
| Repository | Branch protection on `main`: pull request required, 1 reviewer, 2 for `/infra`, `/db/migrations`, `/api/src/auth`, `/api/src/scope`; required status checks; CODEOWNERS on those paths; force-push blocked; signed commits recommended | T-0-78 |
| Secrets in git | GitHub secret scanning with push protection; `gitleaks` on history and pre-commit; `.env*` ignored; no secret in the APK | T-0-72, T-1-74 |
| Cloud credentials | GitHub OIDC federated credentials per environment, no stored cloud secret; the `prod` environment needs two approvers (D-14) and runs only from `main` | T-0-78 |
| Actions | Third-party actions pinned by commit SHA; `permissions:` least privilege per job; no `pull_request_target` that checks out pull-request code | T-0-78 |
| Node dependencies | `npm ci` with the lockfile; Dependabot weekly and grouped plus OSV; `npm audit` at high; `ignore-scripts=true` with an explicit allow-list | T-0-71 |
| Dart dependencies | `pubspec.lock` committed; OSV-Scanner on the lockfile; plugins with native code (printer, geolocator, secure storage, SQLCipher) reviewed and pinned | T-0-71 |
| SAST | CodeQL for TypeScript and Semgrep with custom rules (`no-unscoped-query`, no request bodies in logs, no `pgp_sym_*`, no string-built jsonb paths) blocking on high | T-0-70, T-0-76 |
| IaC | Bicep in `/infra`; PSRule for Azure and checkov; Azure Policy in prod denies public network access and requires TLS 1.2 and managed identity; Defender for Cloud on the subscription | T-0-73 |
| Containers | Base image pinned by digest; Trivy blocks critical; images signed with cosign (keyless, OIDC); ACA deploys by digest, never by tag; a CycloneDX SBOM per image stored with the release | T-0-70 |
| Web | `next` pinned; the CSP of s2.8; no third-party scripts | T-4-73 |
| Environments | dev, staging and prod have separate Key Vaults, Postgres, Blob and Redis; prod data is never copied down; staging uses synthetic or anonymised data and the importer has an `--anonymise` mode (D-151) | T-0-78 |
| APK release | A human `release_mgr` action publishes `app_release`, separate from CI. An independent rebuild of the release tag on a second runner, or on the release manager's machine, must reproduce the CI digest per ABI before publish (`cfg.release.require_reproducible_build` true). Flutter builds are reproducible when the SDK version, pub cache and build number are pinned (ASSUMPTION: verified on the first pilot APK, 2e, so a failure surfaces before wave 1). The app signing key is never on a runner (s5.2); provenance (SLSA-style attestation) is stored with the release | T-7-10 |
| Runners | The self-hosted device-lab runner executes only `device-lab` jobs on protected tags or branches, never `pull_request`; ephemeral runner VMs reset per job; the lab network is segmented; the runner token rotates | T-7-10 |
| People | A named security owner in DECISIONS.md; quarterly access review of GitHub, Azure RBAC, admin bundles and `pii` flags; quarterly key-rotation and incident drills (doc 20 s2.8) | T-7-71, T-7-72 |

Proved by: T-0-70, T-0-71, T-0-72, T-0-73, T-0-76, T-0-78, T-4-73, T-7-10.

## 10 Security test plan

Doc 20 owns the gate register and the cadence (s2.8, D-457); this section lists the security gates and the 20 new ones minted here (IDs in the security block 77 to 79 and the fraud block 10 to 19; doc 20 places and registers them, OI-21-14). Every gate has an evidence artefact and a verifier who is not the author (D-141). Existing gates are named by their doc 20 meaning; the new gates carry their pass criterion.

### 10.1 Gates by sub-milestone

| Sub-ms | Gates (existing) | Gates (new, defined in 10.2) |
| --- | --- | --- |
| 0a | T-0-70 SAST; T-0-71 dependency scan; T-0-72 secrets; T-0-73 IaC scan | T-0-78 |
| 0c | T-0-74 auth negative suite; T-0-75 scope-leak harness; T-0-76 lint and grants | T-0-77, T-0-79 |
| 1a | T-1-10 monotonic time; T-1-71 offline unlock; T-1-74 APK config; T-1-76 trusted time | none |
| 1b | T-1-11 record signature; T-1-12 attestation; T-1-13 lockout without denial of service; T-1-55 expiry wave and clock skew; T-1-70 device binding; T-1-72 batch forgery; T-1-73 rate limits; T-1-75 log redaction | T-1-79 |
| 1c | none | T-1-77, T-1-78 |
| 2a, 2b, 2c | T-2-71 price recompute; T-2-70 edit rules; T-2-72 photo pipeline; T-2-13 injection | none |
| 2d | T-2-10 adversary lab; T-2-11 SDR spoof; T-2-12 fix freshness; T-2-14 blocked-version capture; T-2-75 mock invariant and policy | T-2-76, T-2-77, T-2-79 |
| 2e | T-2-73 gstack `/cso` audit (blocks the pilot); T-2-74 DAST | T-2-78 |
| 3a | T-3-11 on-site verification; T-3-12 dismissal re-sample; T-3-70 separation of duties; T-3-73 Exceptions screen | T-3-77 |
| 3b | T-3-10 takeover chain; T-3-71 device lifecycle; T-3-72 final submit and reopen audit | T-3-78, T-3-79 |
| 4a | none | T-4-77 |
| 4c | T-4-14 PII budgets; T-4-70 RLS performance; T-4-71 BI grants; T-4-72 export controls; T-4-73 web headers, CSRF, MFA; T-4-74 IDOR sweep | T-4-75, T-4-76 |
| 5a, 5c | T-5-70 loyalty; T-5-71 fraud recall (FS-01 to FS-19); T-5-72 dues; T-5-10 fraud recall (FS-20 to FS-29) | T-5-73 |
| 6b | T-6-10 rails direction; T-6-70 config console; T-6-71 audit immutability; T-6-72 admin bundles | T-6-73 |
| 7a | T-7-74 Apsis dump handling | T-7-77 |
| 7c | T-7-70 external penetration test (blocks wave 1); T-7-71 key rotation; T-7-72 incident tabletop; T-7-73 DR with Key Vault; T-7-75 wave-day auth storm; T-7-10 reproducible APK | T-7-76 |

The penetration test scope includes s7.5 rows 2, 3, 26, 28, 31, 32 and 43 (rooted hook, SDR, shared-phone session, takeover chain, replay with new uuids, curl forgery, lockout denial of service) as well as the OWASP lists of s11. The gstack `/cso` audit before the pilot (T-2-73) and the adversary lab (T-2-10) run on the primary device against staging.

### 10.2 New gates

| Gate | Sub-ms | Test | Pass criterion | Blocking | Closes |
| --- | --- | --- | --- | --- | --- |
| T-0-77 | 0c | Bump `scope_version` for a user; send a request with the old token; upload a batch of yesterday's rows for a route the user lost this morning | 401 `scope_changed`, refresh returns new claims; the as-of rows are accepted, rows outside the as-of reach are parked `scope_out_of_reach` and never dropped | Y | G-cfg-12, G-21-04 |
| T-0-78 | 0a | CI policy lint on every workflow: every third-party action pinned by SHA, no `pull_request_target` checkout, no stored cloud secret, least-privilege `permissions`, CODEOWNERS present for the sensitive paths, branch protection read through the API | Zero violations | Y | G-sec-19 |
| T-0-79 | 0c | Reset and OTP flows: temporary password shown once and expiring in 24 h, `must_change` enforced, victim device shows the reset notice, no self-service reset on the web, user id case-insensitive; OTP stored as AES-GCM ciphertext and absent from logs; lockout counters survive a Redis flush | All hold | Y | G-feat-34, G-man-091, G-man-021 |
| T-1-77 | 1c | UPDATE, DELETE and TRUNCATE on `app.audit_log`, `cfg.config_change_audit`, `app.security_event`, `app.report_export_log` fail for every role; chain verifies; a 1,000-row burst keeps the chain intact; refresh successes are not logged; failure deduplication holds | All hold; contention measured and recorded | Y | G-sec-11, G-21-10 |
| T-1-78 | 1c | Two users on one bound phone: after A logs out, the upload grant uploads A's rows while B works; the upload grant is refused on `/sync/bundle`, reads, bind and day events; A's full refresh token is unreadable without A's unlock; kill-and-relaunch mid-session resumes without the password; `logout scope=upload` after the last ack | All hold | Y | G-fraud-19, OI-17-09 |
| T-1-79 | 1b | Stop Redis in staging: `/sync/batch`, `/auth/refresh`, `/day/*` continue; `/admin/*`, a PII report and `/auth/bind-device` return 503; no sale is blocked on the device | All hold | Y | D-491, G-21-13 |
| T-2-76 | 2d | Radio environment: capture on Android 8, 10 and 12 reference devices with no extra permission; no scan started; battery within D-73; BSSIDs stored only as salted hashes; Rule A and B fire on an injected 8 km offset and do not fire across a rural cell with a learned 12 km radius; capture is off when `cfg.geo.radio_env_enabled` is false | All hold | Y | G-fraud-01, D-478 |
| T-2-77 | 2d | Confirmed outlet, force sale photo: request raised, call proceeds, provisional point used; the visit is not in the K-09 numerator; approval re-evaluates it; rejection leaves it not valid; placeholder-pin outlet sets the location at once | All hold | Y | G-man-017, G-fraud-06, G-21-03 |
| T-2-78 | 2e | PDA to Support bundle: ciphertext in Blob, no plaintext phone anywhere in storage; decrypt only through `/support/decrypt` with MFA and a ticket; every decrypt audited; an unknown key id refuses to send | All hold | Y | G-sec-14, OI-17-11 |
| T-2-79 | 2d | SR-surface lint: the SR bundle and responses carry no `risk_signal` and no FLAG-policy code; `cfg.sync.reason_texts` holds REJECT codes only; manifest permission allow-list holds (no `RECORD_AUDIO`, no `READ_PHONE_STATE`) | Zero violations | Y | G-fraud-26, G-feat-28 |
| T-3-77 | 3a | New-outlet request with a matching `phone_hash`, a similar name within 100 m and a staff phone: duplicate badge shown on the AMO screen and the approval panel; approval blocked until "merge" or "not a duplicate" plus a note | All hold | Y | G-sec-18 |
| T-3-78 | 3b | OTP panel: view-only; a TSO sees only own-territory SRs; value shown only for open OTPs; one aggregated `otp_view` row per page view; 5 wrong attempts expire the OTP; the 10-per-day failed cap locks binding until a TSO clears it with an audit row | All hold | Y | G-man-021, D-473 |
| T-3-79 | 3b | Wave cohort: 1,000 binds with TSO-issued temporary passwords and OTPs across 35 TSOs are not held when the DMO has acknowledged the cohort; a user outside the cohort, a replacement of a bound device and a device previously bound to the actor are held | All hold | Y | G-21-01, D-486 |
| T-4-75 | 4c | Role-by-subject matrix of s4.2 through every serialiser: an AMO payload never contains an SR phone (not a masked string), a DMO list masks retailer phones, the TSO baseline shows NID and TIN columns with null for the placeholder, `master_data` reads NID decrypted and the read is logged | All hold | Y | G-feat-59, G-man-039, G-man-062, G-sec-04 |
| T-4-76 | 4c | Adding a PII column to the SR bundle (`cfg.bundle.outlet_fields`) is refused without the C3 approval and the `pii` mapping; the bundle field allow-list test passes | All hold | Y | G-sec-04 |
| T-4-77 | 4a | Planted stock and cash variances against a confirmed issue and a counted cash figure raise FS-17 and FS-32; an unconfirmed day ages on the report; a collection without a fix on a month-end memo raises FS-33 | Recall 90 percent or more, false positives 5 percent or fewer per clean SR-day | Y | G-fraud-07, D-481 |
| T-5-73 | 5c | Planted FS-30 (a memo repeated with a 1 s time shift) and FS-31 (a Platinum tier proposal for a low-sales outlet) scenarios | Recall 90 percent or more; the Astha tier is not effective until the TSO approves | Y | G-fraud-05, G-fraud-23 |
| T-6-73 | 6b | A `config_approver` grant made 2 h ago approves a C3 change: refused (`ERR_CFG_COOLING`); an adjustment total above 200,000 Tk in a day needs the approver whatever its size | All hold | Y | FS-29 |
| T-7-76 | 7c | Re-verification at wave 1 of the T-7-160 record (D-570). Evidence file check: counsel's written opinion on localisation, sensitivity of phone and owner, location monitoring and breach duty is on record; D-05, D-107, D-120 answered; the WORM policy locked after the retention answer | File present, decisions closed | Y (wave 1) | G-sec-01, G-sec-22 |
| T-7-77 | 7a | Credential paths on 20 pilot users: the verifiable-hash path verifies then re-hashes and deletes the staging hash; the temporary-password path forces a change; a weak hash is never an active credential; usernames violating the memo-role regex are quarantined with a rename map | All hold | Y | G-sec-03, D-119, D-488 |

Proved by: every gate named in 10.1 (this section is the register for the security and fraud ranges).

## 11 Residual risks accepted (D-124)

Written down so nobody assumes otherwise. RR ids are this document's; the sponsor's R1 to R6, the rejected recommendations R-01 to R-34 and the project risks RK-01 to RK-25 are different namespaces.

| # | Residual | Why accepted | Compensating signals |
| --- | --- | --- | --- |
| RR-1 | A rooted phone with a hidden hook inside the genuine, attested app feeding fixes consistent with the real serving cell (the attacker stands in the right market) | Needs physical presence and skills beyond the SR population; a motion sensor would cost battery and false positives | FS-08 timing, FS-01 content signals, the money counter-party (FS-32), joint calls |
| RR-2 | Deliberate credential sharing between two people on one phone | No per-action identity without changing the field workflow (docs/01 "same workflow") | FS-21 interleave, the identity prompt (F-SR-072), attribution audit |
| RR-3 | An SDR spoofer standing beside the cell tower that serves the real outlet | GNSS and radio stay consistent | The ledger, SMS receipts (FS-07), supervisor joint calls |
| RR-4 | A zone AMO bundle (up to about 11,000 outlets, D-72) readable on a rooted phone | The AMO needs retailer phones to do the job | Row budgets protect the web only; SQLCipher; the 2 MB paging cap (D-72) |
| RR-5 | Two colluding `config_approver` holders loosening a C3 key | Two-person control is the ceiling the business asked for | Two-sided anomaly watch, change-rate limit, WORM audit, cooling |
| RR-6 | Break-glass misuse for at most 4 hours | Emergencies need one-person action | All approvers alerted at once, 24 h review, restore-or-restrict only |
| RR-7 | A retailer disputes a real due, or claims non-receipt | Out of the system's reach | Paper memo, optional SMS receipt, due-dispute event |
| RR-8 | A revoked principal keeps working up to the access lifetime (60 minutes) during a Redis outage | Field availability beats immediate revocation (D-491) | Revocation applies at the next refresh; admin and PII paths fail closed |
| RR-9 | A 4-digit OTP: with the password known, 0.1 percent a day per user | Parity with the live app (D-103) | Caps in s2.5, new-device notice, FS-11 |
| RR-10 | A rooted phone with a system-store CA can intercept its own traffic (no pinning, D-483) | Pinning would strand offline phones at a certificate change | Record signatures and attestation make intercepted content unforgeable |
| RR-11 | Month-end rows captured after a reboot and claiming the previous month inside the 3-day grace | A legitimate 2-day offline run must be accepted | FS-13, `time_untrusted` flag, close incentives only after grace plus review |
| RR-12 | Collusion between an SR and the keeper or AMO who confirms stock and cash (D-481) | The counter-party is human | Joint calls, random register sampling, FS-17 trend per confirmer |

Proved by: T-1-12, T-2-10, T-2-11, T-5-71, T-7-70.

## 12 Gaps owned, decisions and gates minted

### 12.1 The 39 master gaps owned by this document (doc 14 s7), each closed in the text

Every entry is closed here by text, a decision where one applies, and a gate. Aliases are retired ids merged into the master.

| Master (aliases) | Sev | Phase | Closed in | Decision | Gate |
| --- | --- | --- | --- | --- | --- |
| G-feat-11 (G-man-021) Device OTP issuance surface and launch-day throughput | blocker | 0c | s2.5, s3.3; four digits, view-only TSO panel, encrypted reversible OTP, wave pre-issue, caps | D-103, D-164, D-165, D-473 | T-0-79, T-1-70, T-3-78, T-7-75 |
| G-sec-03 (G-feat-66) Apsis hashes versus forced reset | blocker | 7a | s2.9 | D-119 | T-7-74, T-7-77 |
| G-sec-01 Data-protection and localisation obligations unknown | blocker | 7c | s4.6 (questions, defaults), s4.3 (switch rule) | D-05, D-107 | T-7-76 |
| G-sec-19 (G-fraud-16) No supply-chain controls | major | 0a | s9 | D-122 | T-0-70 to T-0-73, T-0-78, T-7-10 |
| G-cfg-12 Scope change does not reach an issued token | major | 0c | s2.3, s3.1 (`scope_version`, as-of write reach) | D-101, D-470 | T-0-77 |
| G-feat-34 (G-sec-20, G-man-091) Password reset, forgot and lockout flow | major | 0c | s2.4, s2.8 (temporary password, no self-service reset on the web, case-insensitive id) | D-102 | T-0-74, T-0-79, T-4-124 (doc 20) |
| G-sec-05 Token lifetimes, rotation, revocation, device proof, offline login | major | 0c | s2.2, s2.3, s2.6, s2.7 | D-101, D-104, D-68 | T-0-74, T-1-70, T-1-71 |
| G-sec-06 No scope enforcement pattern | major | 0c | s3.2 | D-106, D-490 | T-0-75, T-0-76, T-4-70, T-4-74 |
| G-sre-06 Hourly access-token expiry wave | major | 0c | s2.2, s2.3 | D-101 | T-1-55 |
| G-fraud-03 (G-sync-13) Local store and batch not tamper-evident | major | 1b | s2.6, s5.6 | D-104, D-67, D-476 | T-1-11 |
| G-fraud-05 Replay with regenerated uuids | major | 1b | s6.3, s7.7 (FS-24, FS-30) | D-21, D-353, D-477 | T-2-10, T-5-73 |
| G-sec-07 PBKDF2 verifier below the floor | major | 1b | s2.7 | D-68 | T-1-71 |
| G-fraud-19 Shared rooted phone, user B uses A's refresh token | major | 1c | s2.7 (two grants, session copy) | D-471 | T-1-78 |
| G-sec-11 (G-cfg-15, G-feat-50) Audit is client-asserted | major | 1c | s8 | D-113, D-484, D-485 | T-0-64, T-1-77, T-6-71 |
| G-sec-13 Multipart upload, no path pinning | major | 2c | s5.3 | D-75 | T-2-72 |
| G-fraud-01 (G-sync-18, G-fraud-25) No radio-environment corroboration | major | 2d | s7.2, s7.3 | D-110, D-478 | T-2-10, T-2-11, T-2-12, T-2-76 |
| G-fraud-06 Outlet location drift | major | 2d | s7.4 | D-111, D-95, D-487 | T-3-11, T-2-77 |
| G-man-017 Photo capture overwrites the outlet location | major | 2d | s7.4 (corrected statement of s7.3 of the skeleton: the manual says "location information will be updated" and shows no approval; the constraint wins) | D-163, D-95, D-111, D-487 | T-2-77, T-3-11 |
| G-scale-04 (G-sec-15) Per-IP rate limiting harms carrier NAT | major | 2d | s6.1, s5.1 | D-116 | T-1-73 |
| G-sec-08 Fraud controls beyond GPS absent | major | 2d | s7.1 to s7.9 | D-109 | T-2-10, T-3-73, T-5-71, T-5-10 |
| G-feat-28 (G-sec-09) Microphone and voice recording | major | 2e | s5.7 (camera only; the Audio prompt is a plugin side effect) | D-115 | T-2-79 |
| G-fraud-04 (G-sec-21) Repack and downgrade indistinguishable | major | 2e | s2.6 | D-105, D-479 | T-1-12, T-2-14 |
| G-fraud-12 Wave-day lockout denial of service | major | 2e | s2.4 | D-102, D-475 | T-1-13 |
| G-sec-14 No redaction; PDA to Support uploads a whole DB | major | 2e | s4.4, s5.4 | D-480 | T-1-75, T-2-78 |
| G-fraud-08 Supervisor takeover chain | major | 3b | s2.5 | D-112, D-486, D-489 | T-3-10, T-3-79 |
| G-fraud-07 Money fraud invisible without a counter-party | major | 4a | s7.8 | D-481 | T-4-77 |
| G-fraud-14 Formula injection, ESC/POS and bidi characters | major | 4b | s4.5, s6.5 | D-118 | T-2-13, T-4-14 |
| G-feat-59 (G-man-039, G-man-062) PII role by field matrix | major | 4c | s4.2 | D-108, D-171, D-207 | T-4-75, T-3-121 (doc 20) |
| G-fraud-13 PII row budgets, masking default, export volume | major | 4c | s4.5 | D-121, D-482 | T-4-14 |
| G-sec-04 Phone and owner protection level | major | 4c | s4.3 | D-107, D-108, D-482 | T-4-75, T-4-76 |
| G-sec-10 Web MFA and SSO | major | 4c | s2.8, s3.3 | D-114, D-91 | T-4-73 |
| G-fraud-15 SAS patterns absent from redaction; camera-only evidence | minor | 2c | s4.4, s5.3 | D-75 | T-1-75, T-2-72 |
| G-fraud-26 Showing fraud flags to the SR | minor | 2d | s7.6 | D-123 | T-2-79 |
| G-fraud-20 AMO dismissals have no consequence | minor | 3a | s7.6 | D-109 | T-3-12 |
| G-sec-18 No duplicate-outlet detection | minor | 3a | s7.7 (FS-03) | D-109 | T-3-77 |
| G-fraud-22 No signal for deliberate offline days | minor | 4a | s7.7 (FS-20) | D-109 | T-5-10 |
| G-fraud-23 Astha tier at verification | minor | 5a | s7.7 (FS-31), s7.5 row 23 | D-109 | T-5-73 |
| G-sec-17 (G-sync-19) Points from the client; redemption race | minor | 5a | s7.7 (FS-06); points are computed on the server | D-266, D-41 | T-5-70 |
| G-sec-22 Employee location notice, consent, retention | minor | 7c | s4.7 | D-120 | T-7-76 |

### 12.2 Gaps owned elsewhere that this document touches

| Gap | Owner | What this document states | Gate |
| --- | --- | --- | --- |
| G-man-024 (G-sync-16) Logout with unsynced data | doc 17 | The credential side: SR and AMO keep the database and the upload grant until the outbox is acknowledged; the TSO wipes only on a reconciled device; the dialog pair keeps the manual's text with fixed grammar (s2.7, D-69) | T-1-78, T-2-130 (doc 20) |
| G-sync-01, G-sec-02 Binding model | doc 17 | Limits and states of s2.5; the numbers are MUST-CONFIRM by 1c (D-66) | T-1-70 |
| G-data-16 credential and device tables | doc 16 | Behaviour defined here; storage in doc 16 s6.4 | T-0-07 (doc 20) |
| G-sre-04 Argon2id memory storm | doc 18 | Parameters and limiter of s2.4 (D-472, D-126) | T-7-75 |

### 12.3 Decisions minted (D-470 to D-491; the doc 14 author copies them into DECISIONS.md with date 2026-10-04)

| ID | Decision | Status | Why | Docs |
| --- | --- | --- | --- | --- |
| D-470 | Reads use current reach; ingest writes use the assignment as of the record's `business_date` including cover; a write outside it is parked `scope_out_of_reach`; scope_version bumps coalesce per user per 5 min | DEFAULT | A transferred SR must still upload yesterday's honest rows; G-cfg-12 | 16, 17, 21 |
| D-471 | Two refresh grants per user: full (`aud aron-api`) and upload (`aud aron-upload`, password-free, batch and media only); the full token is stored wrapped under the verifier key plus a session copy deleted at logout; logout revokes the full grant now and the upload grant after the last acknowledgement, with a 7-day idle expiry | DEFAULT | G-fraud-19; answers OI-17-09 | 17, 21 |
| D-472 | Server passwords: Argon2id m 64 MiB, t 3, p 1 with a Key Vault pepper and `pepper_version`, on the `auth` app behind the per-replica limiter | DEFAULT (ASSUMPTION on parameters) | Doc 18 capacity maths; OWASP-class minimum | 18, 21 |
| D-473 | OTP entropy controls: one active OTP per user, 3 creations per user per hour, 10 failed attempts per user per 24 h then binding locked until a TSO or `ops_admin` clears it; the panel shows only open OTP values | DEFAULT; MUST-CONFIRM (by 0c) with D-103 | 4 digits give k in 10,000 per k tries | 19, 21 |
| D-474 | Suspended: capture continues, sync refused. Revoked: refresh refused; one grace upload within `cfg.auth.revoked_device_grace_upload_h` whose rows are parked for the zone TSO; then the app wipes that user's data | DEFAULT | A lost phone must not lose sales or accept silent injection | 17, 19, 21 |
| D-475 | Lockout store is PostgreSQL; the lock key is (username, device); `ip_class` and fleet-wide username counters drive step-up (password plus OTP in one call) and a Sev1 alert, never a lock | DEFAULT | G-fraud-12; the attacker controls `deviceUuid` | 18, 19, 21 |
| D-476 | Signature unit is the record family; canonical JSON generated from `/packages/contract`; `X-Device-Proof` on batches signs body hash, batch uuid and attempt; refresh and bind proofs use a 5-minute nonce bucket and a 10-minute replay cache | DEFAULT | D-104 detail | 16, 17, 21 |
| D-477 | Confirms D-353 (exact content duplicate is refused) and adds FS-30, a nightly time-free near-duplicate flag | DEFAULT | A 1 s time shift evades the exact fingerprint; closes OI-16-29 | 16, 21 |
| D-478 | Radio environment: passive cell identities and salted 8-byte Wi-Fi hashes, no new permission, learned centroids with per-cell radius scaling, 14-day pilot calibration, supervisor-visible only | DEFAULT; MUST-CONFIRM (by 2d) privacy sign-off (D-110) | The one cheap tell that survives a hooked mock flag | 16, 17, 21 |
| D-479 | Trust level: normal after a verified attestation chain and signer match, else low (x1.5 on weights); attestation roots come from configuration | DEFAULT | D-105 detail | 17, 21 |
| D-480 | Support bundles are hybrid-encrypted on the device to a Key Vault RSA-3072 key, decrypted only through `POST /support/decrypt` (MFA, ticket, audit), plaintext never persisted, 30-day retention | DEFAULT | OI-17-11; G-sec-14 | 17, 21 |
| D-481 | Counter-party confirmation of issue, return and cash (distribution house or AMO as proxy) is a fraud control; FS-17, FS-04 and FS-32 compute against confirmed figures; unconfirmed days age | DEFAULT; MUST-CONFIRM (by 4a): Q41 | G-fraud-07 | 15, 16, 21 |
| D-482 | If counsel finds phone or owner sensitive, or localisation applies, phone and owner move to envelope encryption in two migrations; list budgets are per role (TSO 5,000 an hour, others 2,000) | DEFAULT; MUST-CONFIRM (by 4c) legal | D-107 switch rule | 16, 19, 21 |
| D-483 | No certificate pinning; system trust anchors only; TLS 1.2 minimum | DEFAULT | Rotation safety for an offline fleet; two baked-in hostnames (D-81) | 17, 21 |
| D-484 | Audit chain through a serial insert function (Merkle batches if contended); the WORM policy stays unlocked in the pilot and is locked before wave 1 | DEFAULT; MUST-CONFIRM (by 6b) retention (D-113) | A locked policy is irreversible | 16, 18, 21 |
| D-485 | Security events are a fixed catalogue; refresh successes are not logged; failures deduplicated per minute | DEFAULT | Volume and signal | 16, 21 |
| D-486 | A wave cohort's binds are pre-acknowledged by the DMO or `security_admin` on the pre-bind day so D-112 does not hold 1,000 honest binds | DEFAULT; MUST-CONFIRM (by 7b) with the approver roster (Q23) | Wave-day temporary passwords plus OTPs by one TSO look exactly like a takeover | 14, 19, 21 |
| D-487 | A visit validated only against a provisional location earns no K-09 credit until the request is approved; approval re-evaluates, rejection does not | DEFAULT (detail of D-95) | Moving a pin to one's house must not pay | 16, 21 |
| D-488 | Usernames of memo-producing roles match `^[a-z][a-z0-9]{3,31}$`; all usernames are case-insensitive; other roles may keep hyphens | DEFAULT | OI-17-10; the memo number format | 16, 17, 21 |
| D-489 | A reset, change or scope revocation surfaces on the victim's phone as a 401 reason with who and when; a password success on a new device raises a banner on the bound device; SMS is optional | DEFAULT; MUST-CONFIRM (by 3b): employee phone numbers and sender | Detection must not depend on the victim trying to log in | 15, 17, 21 |
| D-490 | RLS role model: `api_rw` subject to RLS (`FORCE`), `worker_rw` and `migrator` bypass, `web_ro` reads `dw` only; SELECT policies use current reach, INSERT policies check ownership | DEFAULT | D-106 detail | 16, 21 |
| D-491 | Redis outage: fail open on `/sync/*`, `/auth/refresh`, `/day/*`, `/config/*`; fail closed on `/admin/*`, PII reports, `/auth/bind-device`, `/support/*` | DEFAULT | A Redis outage may stop an admin, never a sale | 18, 21 |

### 12.4 Gaps minted here (block G-21-01 to G-21-40; the doc 14 author adds them to doc 14 s7)

| ID | Sev | Phase | Gap | Closed by | Gate |
| --- | --- | --- | --- | --- | --- |
| G-21-01 | major | 3b | D-112 would hold every first bind on a wave day (TSO-issued temporary passwords plus OTPs) | D-486, s2.5 | T-3-79 |
| G-21-02 | major | 3b | A revoked phone's pending rows have no defined path (doc 19 has the grace key, nothing defines the behaviour) | D-474, s2.7 | T-3-71 |
| G-21-03 | major | 2d | Visits validated against a provisional location would credit K-09 and make moving a pin profitable | D-487, s7.4 | T-2-77 |
| G-21-04 | major | 0c | Write-path scope judged on current reach would quarantine honest rows after a transfer or cover change | D-470, s3.1 | T-0-77 |
| G-21-05 | major | 1b | Password spraying and a new-device login on a bound user's account are invisible to the victim | D-475, D-489, s2.4 | T-1-13 |
| G-21-06 | major | 0c | A 4-digit OTP with 5 attempts per code has no per-user budget | D-473, s2.5 | T-3-78, T-1-70 |
| G-21-07 | minor | 5c | The exact content fingerprint is evaded by a 1 s time shift | D-477, FS-30 | T-5-73 |
| G-21-08 | minor | 2d | Radio rules can false-positive on rural macro cells | D-478, s7.2 | T-2-76 |
| G-21-09 | minor | 2e | Key custody for the support bundle was unspecified | D-480, s5.4 | T-2-78 |
| G-21-10 | minor | 1c | Audit chain contention, WORM lock irreversibility and security-event volume | D-484, D-485, s8 | T-1-77 |
| G-21-11 | minor | 4c | One list budget for every role would block a TSO browsing a 2,500-outlet territory | D-482, s4.5 | T-4-14 |
| G-21-12 | minor | 7a | Imported usernames may violate the memo-number format (hyphens, case) | D-488, s2.9 | T-7-77 |
| G-21-13 | minor | 1b | Redis failure behaviour for revocation was unstated | D-491, s2.3 | T-1-79 |

### 12.4b Round-2 gaps closed in this document (skeptic review, D-500 to D-553)

| Gap | Closed by | Where | Gate |
| --- | --- | --- | --- |
| G-qa-42 | D-518: per-family random absolute expiry, warning from day 75, renewal, 7-day offline grace | s2.3, s2.7 | T-4-162 (doc 18 S10), T-7-75 |
| G-qa-71 | D-540: one authority matrix (doc 19 s5.1b) | s2.5, s3.3 | T-2-158 |
| G-qa-83 | D-551: disabled-user upload grant and parked rows | s2.7 | T-3-154 |
| G-qa-76 | D-545: location-request volume control | s7.4 | T-4-152 |
| G-qa-70 | D-539: `submit.void` permission | s3.3 | T-3-150 |
| G-qa-73, G-qa-74 | D-542, D-543: `calendar.emergency`, `backfill.*` permissions | s3.3 | T-4-154, T-2-151 |

### 12.5 New gates

The 20 gates minted in s10.2 (T-0-77, T-0-78, T-0-79, T-1-77, T-1-78, T-1-79, T-2-76, T-2-77, T-2-78, T-2-79, T-3-77, T-3-78, T-3-79, T-4-75, T-4-76, T-4-77, T-5-73, T-6-73, T-7-76, T-7-77) use free numbers in the security block 70 to 79 (the lens used 70 to 76 in phases 0 and 1 and fewer in later phases); doc 20 places them in its register.

### 12.5b Round-3 gates owned or cited by this document (D-554 to D-601)

Placed and verified in doc 20 s3.15. Cited here so the security range is complete.

| Gate | Sub-ms | Test | Pass criterion | Closes |
| --- | --- | --- | --- | --- |
| T-4-173 | 4d | Key Vault unreachable for 90 minutes during S2 load: minting, refresh and uploads continue; a replica restarted inside the window leaves the minting pool and verification still works; the alert fires at the first failed `sign` | All hold | G-qa-110, D-574 |
| T-7-160 | 7a | Residency, PII and notice record: counsel's answers to the 7a rows of s4.6 are on file, or 7a runs on a pseudonymised or synthetic import and 7b is blocked; the pilot consent screen shows the D-120 text | File present or synthetic-only proved by a PII scan of the import | G-qa-105, D-570 |
| T-7-162 | 7b | Front Door Premium with the managed rules in Log mode through the pilot; a direct request to the Container Apps origin is refused; no managed-rule false positive on `/sync/batch` is left in enforcement mode | All hold | G-qa-109, D-573 |
| T-3-157 | 3b | Policy unbind with pending rows: a third device is bound while the oldest holds 20 unsent rows; all 20 arrive as `source = revoked_device`; the bind screen warned and the TSO saw the count | All hold | G-qa-124, D-585 |
| T-2-167 | 2e | Replace-device wizard and held binds: the checker approves before the bind and the bind is not held; an unapproved replacement sits in the queue with its age, is released in one click by a delegate after 15 minutes, and the alert fires at 45 minutes | All hold | G-qa-125, D-586 |
| T-2-162 | 2d | Stamp regress and the no-grace tightening (doc 19 s2.3b): FS-34 raises on 3 rows | All hold | G-qa-107, D-571 |

### 12.4c Round-3 gaps closed in this document (skeptic review, D-554 to D-601)

| Gap | Closed by | Where | Gate |
| --- | --- | --- | --- |
| G-qa-105 | D-570: residency, PII and notice answers move to the 7a entry | s4.6, s4.7 | T-7-160 |
| G-qa-107 | D-571: stamp regress, FS-34, no-grace tightening | s7.7, doc 19 s2.3b | T-2-162 |
| G-qa-109 | D-573: Front Door Premium from the pilot; origin lockdown spike before the pilot | s5.1 | T-7-162 |
| G-qa-110 | D-574: delegated signing keys, separate vaults, upload grant life, DR vault by backup and restore | s2.2, s2.2b, s5.2 | T-4-173 |
| G-qa-112 | D-576: one bounding box key | s6.4 | T-0-159 |
| G-qa-124 | D-585: policy unbind treated like revoke | s2.5 | T-3-157 |
| G-qa-125 | D-586: replace-device wizard, state `replaced`, held-binds operation | s2.5 | T-2-167 |

## Open items

Where this document disagrees with the skeleton or another document it still follows the skeleton and records the disagreement here. Every MUST-CONFIRM decision applied is listed by D-id with the proceed-with default (rule 3).

| ID | Item | Why open | Owner role | Needed by | What proceeds meanwhile |
| --- | --- | --- | --- | --- | --- |
| OI-21-01 | Token lifetimes, field-role password policy, OTP TTL, retries, whether the AMO binds (D-101, D-102, D-103, D-473; Q4, MQ-15) | Only AKTCL can answer; the manual shows no expiry | Sponsor and security owner | 0c | 60 min and 30 d and 90 d; 8 characters with a deny-list for field roles; OTP 120 min and 5 attempts; the same bind gate for all flavours |
| OI-21-02 | Binding model: users per device, devices per user, screen lock on shared phones (D-66; Q58) | Business policy; no MDM | Sales ops | 1c | 3 users per device, 2 devices per user; screen lock recommended, not enforced |
| OI-21-03 | SQLCipher sign-off against the 30 MB per ABI and battery gates (D-67) | Measured on the reference devices | Security owner | 1b | SQLCipher on |
| OI-21-04 | OTP re-verify after an in-place update (D-80; MQ-15) | 8,500 TSO lookups per release | Sales ops | 2e | Off; the pilot may set it true |
| OI-21-05 | Whether Google Play services exist on the whole fleet (G-fraud-04; Q60) and the attestation root rotation behaviour | Fleet census (Q31) | Engineering | 2e | Play Integrity off; attestation as a weight; roots from configuration |
| OI-21-06 | Privacy sign-off for the radio environment (D-110, D-478) | Legal | AKTCL legal | 2d | `cfg.geo.radio_env_enabled` false |
| OI-21-07 | Microphone (D-115; Q15, MQ-55) | Whether call audio is a real feature | Sponsor | 2e | No `RECORD_AUDIO`; a recording feature needs a consent design first |
| OI-21-08 | Legal opinion: localisation, sensitivity of phone and owner, employee monitoring, breach duty; region (D-05, D-107, D-120, D-482; G-sec-01, G-sec-04, G-sec-22); whether the live Excel carries NID or TIN (D-108, D-207); Entra availability (D-114); audit retention (D-113, D-484); statutory transaction retention (D-23) and photo retention (D-132, Q21); Sentry residency (D-13) | Unknown; counsel and IT | AKTCL legal, IT | 4c to 7c (6b audit, 6c retention, 2e Sentry) | Singapore with PII separable; the table of s4.6; TSO baseline columns kept; TOTP; 7 years assumed; Sentry with the scrubber |
| OI-21-09 | Speed default: doc 16 DQ-25 text says 90 km/h, doc 19 registers 60 | Written in parallel | doc 16 author | 2d | 60 (doc 19) Resolved at the editorial merge: doc 16 DQ-25 and its key preface now say 60. |
| OI-21-10 | Doc 16 K-09 must exclude `location_basis = provisional` from the numerator until approval and re-evaluate on approval (D-487) | Contract from this document | doc 16 author | 2d | The visit is stored with `location_basis`; the rule is applied in the worker Resolved at the editorial merge: doc 16 s9 K-09 row excludes `location_basis = provisional` from the numerator until approval. |
| OI-21-11 | Doc 17 s7.2 says the password is typed "only on first use, after refresh expiry or after a password change"; this document adds a local unlock after an explicit logout and the two-grant design (D-471) | Doc 17 was written before the G-fraud-19 closure | doc 17 author | 1c | s2.7 of this document Resolved at the editorial merge: doc 17 s7.2 now states the local unlock after an explicit logout and the upload-only grant. |
| OI-21-12 | New keys for doc 19 to register: `cfg.auth.refresh_grace_s` 60, `cfg.auth.otp_issue_per_user_per_h` 3, `cfg.auth.otp_failed_per_user_day` 10, `cfg.auth.upload_grant_idle_days` 7, `cfg.pii.reauth_min` 15, `cfg.sec.lockout_storm_usernames` 500, `cfg.sec.flag_secure_pii_screens` true, `cfg.media.max_photos_per_device_day` 200, `cfg.support.public_key_id`, `cfg.sec.fraud.*` of s7.7; doc 15 and doc 19 differ on who runs bulk OTP pre-issue (F-ADM-069 lists the TSO; doc 19 gives `device.otp.issue` to `ops_admin`); whether the TSO app wants an Exceptions entry (D-204 keeps seven); needs F-id (doc 15): `POST /support/decrypt`, the wave-cohort acknowledgement, the held-bind acknowledgement, step-up login, the new-device banner and the grant-scoped `/auth/refresh` | Keys raised while writing | doc 19 author, doc 15 author | 0c to 2d | The keys are used as named; this document follows doc 19 for permissions Resolved at the editorial merge: the keys are registered in doc 19 (s3.2.4, s3.2.6, s3.2.7 and the fraud sub-table); F-API-066 to F-API-068 were minted for the endpoints; Q58 to Q60 were assigned. |
| OI-21-13 | Doc 19 OI-19-05: this document recommends removing `auto` from `cfg.geo.outlet_location_change_approval` and keeping `amo_only` only for `location_confirmed = false` outlets | D-111 already covers placeholders | doc 19 author | 2d | Both values stay C3 |
| OI-21-14 | Doc 20: T-0-76 names the role `app_api` (the role is `api_rw`, D-368); the 20 new gates need placement | Parallel writing | doc 20 author | 0a | Gate ids as in s10.2 Resolved at the editorial merge: doc 20 T-0-76 says `api_rw`; the 20 gates of s10.2 and T-4-63 are registered in doc 20 s3.11 (388 gates). |
| OI-21-15 | Tables and columns doc 16 must carry that its s6.4 and s6.5 do not list: `app.geo_closure` and `app.user_route_reach` (s3.1), `app.pii_key` (doc 16 has only `pii_key_id`), `app.auth_attempt`, `app.user_consent`, `app.risk_review`, `app.cell_centroid`, `stg.apsis_credential`, `refresh_token.grant` and `replay_response`, and a daily failed-OTP counter with a bind-locked state on `device_otp`; `device.public_key`, `hw_backed`, `trust_level`, `ingest_registry.content_fp`, `geo_fix.radio_env` and `tx_no_rewrite` already exist | Contracts from this document | doc 16 author | 0b to 2d | The objects are created in the migrations M-40, M-41, M-43, M-56, M-57 as their shells grow Resolved at the editorial merge: doc 16 s6.7 carries the inventory and migration blocks of every object listed here. |
| OI-21-16 | D-486 and D-91: who acknowledges a wave cohort and who holds break-glass (Q23; OI-19-12) | Only AKTCL can name people | Sponsor | 7b | Two named approvers per domain from operations and security |
| OI-21-17 | D-481: does the distribution-house keeper get a login (Q41) | Business decision | Sales ops | 4a | The AMO confirms as proxy |
| OI-21-18 | D-489: employee phone numbers and an SMS sender for reset notices (Q59) | Unknown | HR, IT | 3b | In-app notice only |
| OI-21-19 | D-119: Apsis hash algorithm and parameters in the dump (Q18) | Dump contents | Sales ops with Apsis | 7a | Temporary passwords on wave day |
| OI-21-20 | Size: this document is above the 60 to 100 KB budget of skeleton rule 19 | The 33-signal catalogue, the 44-row attack table and the 20 gates carry content the register requires | doc 14 author | merge | Kept whole |
| OI-21-21 | Device-key renewal of the absolute refresh lifetime (D-518) weakens the periodic proof of the person for trusted, hardware-backed devices | a security versus herd trade | security owner | 2e | one renewal per cycle by device key, then the password |
| OI-21-22 | The authority matrix of doc 19 s5.1b widens OTP issue, unlock and submit void to the TSO, L1 and L2 (D-540) against the narrower first draft of s3.3 | support could not run its scripts | security owner, support lead | 2e | doc 19 s5.1b wins |
| OI-21-23 | The disabled-user upload grace (48 h, D-551) lets a dismissed employee's phone still upload rows | the alternative strands the day's sales | HR, security owner | 2e | upload-only grant, parked for the TSO |
| OI-21-24 | Whether East Asia and Southeast Asia are one Azure geography for Key Vault backup and restore (D-574); if not, the second-root-key fallback applies | Learn states the same-geography rule; the mapping is read from the Trust Center page at 0c | security owner | 0c | the second-root-key fallback is designed, not built |
| OI-21-25 | Counsel's answers for the 7a rows of s4.6 (D-570) | Only counsel can answer | AKTCL legal | 7a entry | 7a on a pseudonymised or synthetic import; 7b blocked |
| OI-21-26 | The Premium base fee and the Private Link origin spike result (D-573, G-18-06) | measured and priced at 0c | engineering | before the pilot | the public-origin fallback with the service tag and header check |

## Traceability

### Requirements

| Requirement | Mechanism in this document | Section | Phase | Gates |
| --- | --- | --- | --- | --- |
| R1 DATA | Security events, risk signals, audit, export and PII-read logs are structured tables mirrored into `dw`; nothing needs the transaction log to be re-derived | s7.1, s8 | 1c to 5c | T-1-77, T-5-71 |
| R2 FEATURES | Parity of OTP flow and panel, logout dialogs, TSO PII baseline, SR phone masking, web login, Update Base and Force Sale photo behaviour with each deviation a D-id | s2.5, s2.7, s2.8, s4.2, s7.4 | 0c to 4c | T-0-79, T-3-78, T-4-75 |
| R3 SCALE | Jittered tokens, local verification, per-replica hash limiter, device-keyed lockout and limits, Redis fail-open on field paths, WAF per-IP only as a backstop | s2.2, s2.4, s6.1, D-491 | 0c to 1b | T-1-13, T-1-55, T-1-73, T-1-79, T-7-75 |
| R4 BATTERY | No new sensor, passive radio read, one signature per record family, no timer-driven auth, no extra security round trip | s2.2, s2.6, s7.2 | 1b to 2d | T-1-11, T-2-76 |
| R5 OFFLINE and IMMEDIATE SYNC | Offline unlock, upload grant, session survives kill-and-relaunch, revocation never blocks capture, upload resumes on any connectivity | s2.3, s2.7 | 0c to 1c | T-1-71, T-1-78 |
| R6 ADMIN CONFIG | Fraud thresholds and auth, PII and signature keys are registry keys with classes, floors, maker-checker and audit; no change reaches the field outside the rails | s3.5, s7.7, s7.9, s8 | 2d to 6b | T-6-10, T-6-70 to T-6-73 |
| Process | A security gate in every phase and a gate list per sub-milestone | s10 | 0a to 7c | s10.1 |

CLAUDE.md constraints: 1 offline-first (principle 3, s2.7, D-491); 2 idempotent sync (s6.3, D-353, D-477); 3 battery and data (s2.6, s7.2); 4 server-side scope (s3, D-470, D-490); 5 geo and anti-spoofing (s7.2 to s7.4); 6 no-hiccup cutover (s2.9, s2.5 wave exemption); 7 Dhaka business date (s5.5); 8 bilingual (s2.10 message keys, no hardcoded security text).

### Identifiers

| Kind | Ids used | Handled in |
| --- | --- | --- |
| F-ids | F-SYS-001 to 005, 013, 031, 049, 052, 057 to 059, 066, 071, 072, 075; F-API-001 to 004, 030, 031, 036, 048, 053, 057; F-TSO-019, 022, 023; F-ADM-007, 009, 022, 030, 034, 064, 068, 069; F-WEB-033, 041 to 044, 057, 059, 063; F-AMO-038, 042; F-SR-051, 052, 072 | s2, s3, s4, s7, s8 |
| Master gaps | the 39 of s12.1, G-man-017, 021, 024, 039, 062, 091 | s12.1, s12.2 |
| Minted gaps | G-21-01 to G-21-13 | s12.4 |
| Decisions applied | D-05, D-13, D-20, D-21, D-22, D-23, D-41, D-57, D-61, D-62, D-66 to D-69, D-73 to D-75, D-79 to D-81, D-85 to D-88, D-91, D-93 to D-98, D-100 to D-124, D-126, D-128, D-130, D-132, D-135, D-137, D-141, D-150, D-151, D-155, D-163 to D-165, D-170, D-171, D-182, D-190, D-204, D-207, D-244, D-253, D-256, D-264 to D-267, D-353, D-368, D-435, D-436, D-457 | by section |
| Decisions minted | D-470 to D-491 | s12.3 |
| Gates | existing T-0-64, T-0-70 to 76, T-1-10 to 13, T-1-55, T-1-70 to 76, T-2-10 to 14, T-2-70 to 75, T-3-10 to 12, T-3-70 to 73, T-4-14, T-4-70 to 74, T-5-10, T-5-70 to 72, T-6-10, T-6-70 to 72, T-7-10, T-7-70 to 75; new, the 20 of s10.2 | s10 |
| Config keys | `cfg.auth.*` and `cfg.pii.*` of doc 19 s3.2.7; `cfg.sec.record_signature_mode`, `attestation_required_for_trust`, `takeover_window_h`, `approver_cooling_h`, `cfg.sec.fraud.*`; `cfg.geo.*` of doc 19 s3.2.1; `cfg.api.*`, `cfg.retention.*`, `cfg.support.*`; keys proposed in OI-21-12 | s2 to s9 |
| Signals | FS-01 to FS-33 | s7.7 |
| Residual risks | RR-1 to RR-12 | s11 |
| Open items | OI-21-01 to OI-21-20 | Open items |

### Added at the editorial merge

**Master gaps owned by this document (39):** G-feat-11 (G-man-021), G-sec-03 (G-feat-66), G-sec-01, G-sec-19 (G-fraud-16), G-cfg-12, G-feat-34 (G-sec-20, G-man-091), G-sec-05, G-sec-06, G-sre-06, G-fraud-03 (G-sync-13), G-fraud-05, G-sec-07, G-fraud-19, G-sec-11 (G-cfg-15, G-feat-50), G-sec-13, G-fraud-01 (G-sync-18, G-fraud-25), G-fraud-06, G-man-017, G-scale-04 (G-sec-15), G-sec-08, G-feat-28 (G-sec-09), G-fraud-04 (G-sec-21), G-fraud-12, G-sec-14, G-fraud-08, G-fraud-07, G-fraud-14, G-feat-59 (G-man-039, G-man-062), G-fraud-13, G-sec-04, G-sec-10, G-fraud-15, G-fraud-26, G-fraud-20, G-sec-18, G-fraud-22, G-fraud-23, G-sec-17 (G-sync-19), G-sec-22.

**Decisions that DECISIONS.md assigns to this document and that it applies without an inline citation:** D-304, D-309, D-312. Most are "See D-nn" aliases of a decision cited above or decisions raised by doc 14; the substance was not re-verified row by row.

### Added by the round-2 gap resolution (D-500 to D-553)

| Kind | Ids | Handled in |
| --- | --- | --- |
| Gaps | G-qa-42, G-qa-71, G-qa-76, G-qa-83, G-qa-70, G-qa-73, G-qa-74 | s2.3, s2.5, s2.7, s3.3, s7.4, s12.4b |
| Decisions | D-518, D-539, D-540, D-542, D-543, D-545, D-551 | as above |
| Gates | T-4-162, T-2-158, T-3-154, T-4-152, T-3-150, T-4-154, T-2-151 | s12.4b |
| Config keys | cfg.auth.refresh_absolute_jitter_days, cfg.auth.refresh_renew_warn_days, cfg.auth.offline_grace_after_absolute_expiry_days, cfg.user.dismissal_upload_grace_h, cfg.sys.break_glass_holders_min, cfg.outlet.location_request_min_move_m, cfg.outlet.location_request_max_accuracy_m | s2.3, s2.7, s3.3, s7.4 |

### Added by the round-3 gap resolution (D-554 to D-601)

| Kind | Ids | Handled in |
| --- | --- | --- |
| Gaps | G-qa-105, G-qa-107, G-qa-109, G-qa-110, G-qa-112, G-qa-124, G-qa-125 | s2.2b, s2.5, s4.6, s4.7, s5.1, s5.2, s6.4, s12.4c |
| Decisions | D-570, D-571, D-573, D-574, D-576, D-585, D-586 | as above |
| Gates | T-4-173, T-7-160, T-7-162, T-3-157, T-2-167, T-2-162 | s12.5b |
| Signals | FS-34 | s7.7 |
| Config keys | cfg.sec.dek_cache_min, cfg.auth.upload_access_ttl_min, cfg.geo.country_bbox, cfg.sec.fraud.stamp_regress_min_rows, cfg.sys.config_apply_grace_min, cfg.auth.held_bind_sla_min, cfg.auth.held_bind_delegate_roles, cfg.sla.held_bind_alert_min, cfg.auth.unbind_block_if_pending_rows | doc 19 s3.2.11 |
