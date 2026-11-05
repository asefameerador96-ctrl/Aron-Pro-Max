# Laptop operator log

Owner's Windows laptop, `C:\Users\User\Aron`. No secrets recorded here.

## 2026-10-06

| Step | Command | Result |
|---|---|---|
| 1 | `git pull` | Fast-forward de59818..9fa618e |
| 2 | `.\infra\bootstrap-azure.ps1 -AlertEmails <owner's email>` | First run failed at "Granting rights" (see below). Fixed, then a rerun succeeded |
| 3 | `.\tools\make-signing-key.ps1` (once) | Keystore created in `%USERPROFILE%\AronSigning`; 4 `ANDROID_SIGNING_*` secrets set |
| 4 | `adb devices` | No devices attached |

The owner approved steps 2 and 3 after reading what the scripts actually do. Step 2 also registers resource providers
across the subscription and creates the empty group `rg-aron-scope-probe`.

### Bootstrap failure and fix

`az` is a `.cmd` wrapper on Windows, so cmd.exe re-parses its arguments and treats the `)` in a JMESPath function
(`--query 'length(@)'`) as the end of a block. The error was `-o was unexpected at this time`, and the script then
blamed identity replication. The fix in `infra/bootstrap-azure.ps1` does the Contributor count and the quota filter
in PowerShell, so the query has no parentheses. The script is idempotent, and the rerun completed every step.

### Verified state

- Subscription and tenant: the script's defaults (IDs are in the GitHub secrets, not repeated here).
- `rg-aron-dev` (southeastasia) created. The existing `rg-aron` was not touched.
- App `sp-aron-github-dev` (client id is in the `AZURE_CLIENT_ID` secret). It has 2 role assignments, both on
  `rg-aron-dev`: Contributor, and RBAC Administrator with the role condition.
- GitHub: environment `azure-dev`, secrets `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, variables
  `AZURE_RESOURCE_GROUP`, `AZURE_LOCATION`, `ARON_ALERT_EMAILS`.

### Compute quota, southeastasia

| name | used | limit |
|---|---|---|
| Total Regional Low-priority vCPUs | 0 | 3 |
| Total Regional vCPUs | 0 | 10 |

### Android signing key fingerprints (public)

```
SHA1:   95:C9:7E:AF:8A:C3:BF:52:84:B9:50:71:8F:C5:75:AF:60:B0:9C:83
SHA256: 46:8D:9B:4E:CC:B7:76:E9:F4:0E:20:E2:0A:AF:0F:14:62:7E:23:16:E9:01:D5:3A:F3:FC:9A:A4:E3:93:A4:FB
```

Owner action: back up `C:\Users\User\AronSigning` somewhere safe, offline.

## 2026-10-06 (follow-up): OIDC subject fix

| Step | Command | Result |
|---|---|---|
| 1 | `git pull --no-rebase` | Fast-forward 1991868..0f90002 |
| 2 | `.\infra\bootstrap-azure.ps1 -AlertEmails <owner's email>` | Succeeded. The owner approved after seeing the one expected change |
| 3 | `az ad app federated-credential list --id <app id> -o json` (no `--query`) | 2 credentials, listed below |

Federated credentials on `sp-aron-github-dev`. Both have issuer `https://token.actions.githubusercontent.com` and
audience `api://AzureADTokenExchange`:

| name | subject |
|---|---|
| `github-environment-azure-dev` | `repo:asefameerador96-ctrl/Aron-Pro-Max:environment:azure-dev` |
| `github-environment-azure-dev-ids` | `repo:asefameerador96-ctrl@<owner-id>/Aron-Pro-Max@<repo-id>:environment:azure-dev` |

The IDs are this repository's numeric owner and repo IDs from `gh api repos/asefameerador96-ctrl/Aron-Pro-Max`. There is
no wildcard, branch, pull-request or other-repo subject. Role assignments are unchanged: Contributor, and the conditional
RBAC Administrator role, both on `rg-aron-dev` only. CI jobs were not re-run from here.

## 2026-10-07: GitHub governance

| Step | Command | Result |
|---|---|---|
| 1 | `git pull --no-rebase` | Pulled to 8a2d293 (governance script with 8 checks and azure-stage/azure-prod) |
| 2 | `.\tools\github-governance.ps1` (no `-MakePrivate`) | The owner approved the 8a2d293 version, including the new force-push/deletion protection on the integration branch. The first run failed to parse at line 87 (`"$Int:"` is not a valid variable reference), so nothing ran. Fixed to `${Int}:` and rerun: all steps succeeded |
| 3 | `adb devices` | See below |

Verified afterwards with read-only `gh api` calls (the script discards API errors, so its own messages prove nothing):

- `main` created at `c433001` (the integration head at run time). Tag `baseline-2026-10-06` points to `aac06e0`.
- `main` protection: changes only via PR, 0 required approvals, branch must be up to date with `main` before merging
  (`strict`), review conversations must be resolved, no force push, no deletion, admins not enforced. Required checks
  (8): Repository gates (secrets, migrations, contract); Contract lint; Shared, db and backend (build and tests);
  Android debug APKs, unit tests and lint; Release APKs and APK size gate; Web (lint, types, tests, build, e2e);
  Container images (build and runtime smoke); Infra validation (Bicep, workflows).
- Integration branch protection: force push and deletion blocked; no required checks, no PR requirement.
- Environments: `azure-dev` unchanged (branch `claude/wonderful-thompson-k6ejnf`); `azure-stage` (branch `main` only);
  `azure-prod` (tags `server-v*` only, required reviewer: the owner).
- Repository still public; default branch unchanged; secret scanning and push protection enabled (both were already on).

`adb devices`: no devices attached. Windows sees a Galaxy Note8 only over Bluetooth; no USB, MTP or ADB interface is
present. The phone is not connected by USB, or the cable is charge-only, or USB debugging is not on yet.

## 2026-10-15: SR app on the Honor X5c Plus (dev API, enrolment gate on)

| Step | Command | Result |
|---|---|---|
| 1 | `adb devices -l` | Honor X5c Plus listed as `device` (model NLA_LX2P, transport 1); no Allow prompt needed |
| 2 | `gh run download 37736394003 -n aron-release-signed-dev-722 -D %USERPROFILE%\AronApk\run-37736394003` | 8 files: `aron-{sr,amo,tso}-0.1.722-dev.apk` (SR 12.5 MB) with `.idsig`, `SHA256SUMS`, `release-manifest.json`. `sha256sum -c` OK for all three; manifest says source `e528b98d`, version code 722, signing cert `468d9b4e…a4fb` (matches the dev app_release) |
| 3 | `adb install -r aron-sr-0.1.722-dev.apk`, then launch `com.aktcl.aron.sr/.MainActivity` | Success; installed versionCode 722 (minSdk 26, targetSdk 36). App opens on the Bangla "এই ফোনটি নিবন্ধন করুন" (Enrol this phone) screen with Scan QR, token field, and "login without enrolment" link. No crash in `logcat -b crash`; only vendor noise (RtgSched, SELinux denials on `ro.debuggable` and the profile dir) in the process log. Phone has Wi-Fi, no SIM ("SOS only") |
| 4a | `az keyvault secret show --vault-name kv-aron-dev-7i7g53 -n aron-dev-seed-password --query attributes.enabled` | **ForbiddenByRbac**. The signed-in account is subscription Owner only; the vault is RBAC-mode and no user principal holds a Key Vault data-plane role on it. Reading the seed password needs "Key Vault Secrets User" for the owner's account on this vault (owner's approval asked). `GET /v1/health` on dev: ok, build `950ddbff`. Python has no QR library (`qrcode`, `segno`, `PIL` all absent); `pip install --user segno` proposed. Step 4 script prepared in the session scratchpad (prints statuses only) |
| 4b | `az role assignment create --role "Key Vault Secrets User" --scope <kv-aron-dev-7i7g53>` for the owner's account (owner approved); `pip install --user segno` (owner approved) | Both done. Note: from Git Bash the `/subscriptions/...` scope is path-mangled (MissingSubscription); run `az` scope commands from PowerShell |
| 4c | Scratchpad script: Key Vault read, `POST /v1/auth/login` (web), `POST /v1/auth/mfa/enrol`, local TOTP, `POST /v1/auth/mfa/verify`, `POST /v1/admin/enrolment-tokens` `{sr, dev, 2 uses, 24 h}` | login 200 `mfa_required`; enrol 200 (answer saved to `%USERPROFILE%\AronSecrets\admin1001-mfa-enrolment.json`, owner-only ACL; recovery codes inside, nothing printed); verify 200 `ok` as ADMIN; token **201**, token_id 2, expires 2026-10-16 09:36 UTC; `qr_text` 961 chars rendered with segno to `%USERPROFILE%\AronSecrets\sr-enrolment-qr.png` (owner-only ACL) and opened on screen. admin1001 is now MFA-enrolled on dev: later logins are password + TOTP from that file |
| 5a | Phone: scanned the SR QR, "Enrolling…" then the login screen (enrolment passed on the Honor). Laptop typed `sr1001` + seed password over `adb shell input` (owner approved; nothing echoed) and tapped Login | App moved to "এই ফোন যুক্ত করুন" (bind this phone, 4-digit code). `tso1001` web login 200 `ok` (no MFA, as documented); `GET /v1/admin/device-otps?search=sr1001` shows a live OTP (expires 11:43 UTC, attempts 0). Code given to the owner in chat (not recorded here). `adb logcat -v threadtime` recording to `%USERPROFILE%\AronApk\logs\sr-step5-full.logcat` since before the login |
| 5b | Owner (evening, before a 6-hour break): design the apps from the vendor screenshots (`E:\Aron SR App Screenshots`, 55 captures), make SR, AMO, TSO and the web dashboard designed, not plain text; supplied the real retailer list (`Browse Retailer 11th October.xlsx`, about 732k outlets with owner names, phones, NIDs and coordinates: kept out of git, copied to `%USERPROFILE%\AronData\in`) and the September 2026 geofence radius PDF (per-territory proposed radii); **approved factory reset and device owner on the Honor** (dedicated test phone). Work runs on branch `lane/ui-calm-glass` (worktree `.claude/worktrees/ui-calm-glass`); see `docs/status/ui-calm-glass.md` |
| 6 | 2026-10-17: `adb install -r` of a locally built, release-signed `aron-sr-0.1.724-dev.apk` (R8 release, dev Front Door, signed with `%USERPROFILE%\AronSigning\aron-release.jks`, cert `468d9b4e…a4fb`) over 722; enrolment and bind kept | Success; versionCode 724; cold start 1.7 s; Home, Stock and Sale picker render the Calm Glass UI; crash buffer empty. `svc power stayon usb` was used for the captures and reset to off. Full log: `docs/status/ui-calm-glass.md` on branch `lane/ui-calm-glass` |
| 7 | 2026-10-17 (Dhaka 10:55 to 11:05): owner factory-reset the Honor and re-enabled USB debugging; laptop ran `adb install -r aron-sr-0.1.724-dev.apk`, `adb shell dpm set-device-owner com.aktcl.aron.sr/com.aktcl.aron.dpc.AronDeviceAdminReceiver` | **Device owner set** ("Device owner set to package …", `dpm list-owners` shows it, `adb_enabled` stays 1 under the dev lockdown). Enrolment: token 3 typed over adb was refused as unknown (the on-screen keyboard likely altered a character); token 4 minted (SR, dev, 2 uses, expires 2026-10-18 05:00 UTC) and scanned from the laptop screen with the in-app QR scanner: enrolled. Login sr1001 typed over adb (two failed attempts on a garbled username, then success), bind code read from the Device OTP panel and typed: bound. App now on the location-notice consent. One lesson: `adb shell input text` into a focused field while the keyboard is up shifts the layout; close the keyboard between fields. A fragment of the dev seed password was visible on the phone screen during the mistyped attempt: rotate `aron-dev-seed-password` (test account) with `infra/scripts/seed-secrets.sh` when convenient |
| 8 | 2026-10-17 11:09: D-04 check-in half on the device-owner Honor | PASS: Facebook, TikTok, YouTube suspended after check-in (`dumpsys package … suspended=true`); check-out release pending at 17:00 Dhaka; details in `docs/status/device-checks.md` (branch `lane/ui-calm-glass`) |
| 9 | 2026-10-17: `git push -u origin lane/ui-calm-glass` (head `58d54666`) and `git push -u origin claude/amazing-kilby-fdc9ab` (hardening, head `21e35276`) after a secret scan of both diffs (test placeholders only) | Both branches on GitHub; no pull request opened (owner's rule) |
| 10 | 2026-10-17 11:19: D-05 mechanism over adb (MockGpsHoldTest fed a fake fix to the fused client) | PASS: the visit screen flagged the fake location and refused the sale (force sale offered); real fake-GPS apps still pending (need Play or APKs). Test APK uninstalled, shell mock app-op reset. Check-out half of D-04 scheduled for 17:02 Dhaka |
| 11 | 2026-10-17 17:03: D-04 check-out half over adb (Attendance → Check out → press-and-hold) | PASS: Facebook, TikTok and YouTube released (`suspended=true` 0/0/0, `suspendedPackages` gone). D-04 complete. Committed in the worktree, not pushed |
| 12 | 2026-10-17 17:10 to 17:25: owner reported the map blank and no location during selling. Location: the fused client still held the mock fix from the D-05 harness; `adb reboot` cleared it, real fixes resumed (visit verdict 5,544 m from the seeded outlet, which has invented coordinates). Map: the local builds had an empty Maps key (CI-only secret); the key was read from the CI-built 722 APK's manifest and baked into local builds 725 and 726 (no secret printed). 726 also fixes the outlet map screen (solid black radius disc, always-on offline note, white status icons) | 726 installed; map loads with the shop pin and a translucent accent ring |

## 2026-10-24: pack pictures loaded into dev (`docs/runbooks/load-packshots.md`)

Owner said "run the runbook" in chat and chose "load all 42" for the four suspect Black Diamond pictures. Environment: dev only. Every
step read the admin password from Key Vault fresh and cleared it, the authenticator file path and the API origin afterwards (no `ARON_*`
variable was left in the session). Nothing secret or URL is recorded here.

| Step | Command | Result |
|---|---|---|
| 1 | `load_packshots.py --check` | OK: 42 SKUs 1:1 with the catalogue, 42 processed files verified (854 KB); 4 suspect pictures (BDA-10s, BDA-20s, BDDF-10s, BDDF-20s) |
| 3 | `--dry-run` | 42 pictures, 0 skipped, 42 to bind, 37 assets to upload, 0 not loadable; 0 manifest codes unknown to the server; no `[replaces another picture]` mark |
| 4 | `--apply --only MAX` (canary) | exactly the expected line: `bound [POST 200 PUT 201 PATCH 200 GET blob 200 GET api 200] verified image/jpeg, sha256 ok`; 1 bound, 1 asset uploaded |
| 5 | `--apply` | 42 pictures: 42 bound, 0 repaired, 0 skipped, 0 failed; 37 assets uploaded. Every SKU: POST/PUT 200/201 (shared art: PATCH only), PATCH 200, `GET blob` 200 and `GET api` 200 (the phones' path), `image/jpeg`, sha256 ok. **MAX printed `rebound`, not `skipped`** (see the finding below); the end state is the same picture |
| 6 | closing `--dry-run`, twice | First run: **42 to bind, all marked `[replaces another picture]`**, 0 skipped (not the expected 42 skipped). Cause found, loader fixed, second run: `plan: 42 pictures: 42 skipped, 0 to bind, 0 assets to upload, 0 not loadable` |

**Finding (loader bug, fixed in the working tree, not committed):** the live admin SKU answer carries `image_url` only, and Azure's
read link writes the blob name as ONE path segment (`.../media/assets%2Fsku_image%2F<id>.jpg`). `asset_in_url` matched the literal
`/assets/sku_image/<id>.jpg`, so on a real environment it never found the asset id: the loader could not recognise its own bindings
(no `skipped: already bound`, every SKU `rebind`), and **`--unbind` (the rollback) would have reported every SKU "left alone"**
because it goes by the same function. The server data was never wrong (the read link held exactly the expected asset ids; both
reads verified). Fix: `asset_in_url` decodes the path before matching (`tools/data/load_packshots.py`), with a test for the encoded
form (`tools/data/test_load_packshots.py`); 157 tests pass. The runbook's expected output for step 4 to 6 now holds on dev. The
unit tests used unencoded URLs only, which is why they missed it. `--unbind` on dev was not exercised after the fix.

Not done: no phone has the pictures yet ("When the phones show it" in the runbook: force-stop and reopen the SR app, then Sync now).
The four Black Diamond SKUs still show the same art in pairs until the owner supplies the right BDDF files.
