# Loading the pack pictures (load-packshots)

**What it is:** `tools/data/load_packshots.py` puts the 42 SKU pack pictures into an environment through the audited admin
API: asset store (purpose `sku_image`) -> `app.sku.image_asset_id` (decision D-PACK-03). From there the design is the one of
D-PACKSHOT-01: the sync bundle carries the SKU's asset id, each phone downloads every picture once from
`GET /v1/sku-images/{asset_id}` after a sync, and the Stock and Sale rows draw it from the phone's disk cache ("When the
phones show it" below says how the owner sees it). The pictures are not in git (the repository is public until the product
is complete, CLAUDE.md rule 13) and not in any APK. The loader is the BULK way in: the web admin's SKU form
(`docs/runbooks/sku-pack-pictures.md`, one SKU at a time) writes the same fields through the same endpoints, with the same
validation and audit rows; the loader does the 42 in one go and reads every one of them back.
**Who:** the laptop operator session, after the owner's go-ahead in chat (see "Owner approval"). No lane runs `--apply`,
`--dry-run` or `--unbind` against a real host on its own.
**Time:** not drilled on a real environment (design figure: about 200 requests for a fresh load, which is 2 sign-in, 1 list,
37 POST, 37 PUT, 42 PATCH and 84 GET (each picture is read back twice, from the blob store and from the API), so under three
minutes, plus up to 31 s of waiting before each run after the first, because an authenticator code works once).

## When to run it

| Situation | Run |
|---|---|
| First time in an environment that has the 42 SKUs (dev after a deploy with the seed, later staging and prod) | the steps below: `--dry-run`, a one-SKU canary, then `--apply` |
| After an owner-approved reset to `dev-lite` or any re-seed from an empty database | the steps below. The reset deletes the storage account and the PostgreSQL server together (`reset-to-profile.sh`), so every binding and every blob is gone |
| After a plain dev deploy or a `dev` reset | `--dry-run` only to see "42 skipped". A deploy never touches `image_asset_id` (`db/seed/02_products.sql` only inserts SKUs that are missing), so nothing needs loading |
| A picture was replaced or added | `prepare_packshots.py`, then `--apply` (or `--apply --only CODE`): a new file has a new hash, hence a new asset, and the SKU is rebound. **`--apply` overwrites whatever picture the SKU has**, also one the owner chose in the web admin (step 3 shows those) |

## Prerequisites

1. Python 3.11 or newer on the laptop (every command uses `python -P`, which needs 3.11). The loader is standard library only;
   `prepare_packshots.py` needs Pillow (`python -m pip install pillow`).
2. The processed files and the manifest, made from the owner's folder (read only):
   `python -P tools/data/prepare_packshots.py` (default source `E:/Github Project/Aron-Pro-Max/packshot`, or `--src` /
   `ARON_PACKSHOT_SRC`). It writes `assets/packshots/processed/` (git-ignored) and `assets/packshots/manifest.csv`.
3. The offline check is green (step 1 below).
4. The environment's API origin, the admin password and the admin's TOTP secret. All three are read from the
   environment or a file and are never printed, stored or written to a status file:

   | What | Where it comes from |
   |---|---|
   | `ARON_API_BASE` | the API origin without `/v1`: for dev the Front Door host in `docs/status/device-checks.md`. `https` only (`http` is accepted for localhost) |
   | `ARON_ADMIN_USER` | optional, default `admin1001` (ADMIN or SUPERADMIN is required: `POST /v1/admin/assets` and `PATCH /v1/admin/skus/{id}` are writer-only) |
   | `ARON_ADMIN_PASSWORD` | Key Vault `kv-aron-dev-7i7g53`, secret `aron-dev-seed-password` (the owner holds Key Vault Secrets User since 2026-10-15). Read it fresh each run: the 2026-10-17 log recommends rotating it |
   | `ARON_ADMIN_TOTP_FILE` (or `--totp-file`) | `%USERPROFILE%\AronSecrets\admin1001-mfa-enrolment.json`: the saved answer of `POST /v1/auth/mfa/enrol`; the loader reads only the secret inside `otpauth_uri`. Or `ARON_ADMIN_TOTP_SECRET` (base32) |

5. The Azure CLI is signed in: `az account show` must succeed (run `az login` first, with the account that holds Key Vault Secrets
   User). Step 2 reads the password from Key Vault with `az keyvault secret show`; without a login that fails, and the loader
   would only stop later with `ARON_ADMIN_PASSWORD is not set` or a 401.
6. The laptop reaches the storage account (`infra/modules/storage.bicep` leaves public network access on with the firewall open;
   not yet proven from a laptop) and its clock is right (TOTP tolerates one 30-second step either way).

## Steps (PowerShell, repository root)

**PowerShell does not stop when a program exits with an error:** the next line simply runs. So each step below is its own
paste, ends with an exit-code guard, and has a STOP rule. Paste one step, read what it printed, and go on only when the
STOP rule says so. The owner's yes (see "Owner approval") covers steps 3 to 6, in this order, on ONE environment.

**A STOP or a `throw` ends with the secrets cleared.** The password, the authenticator secret file and the API origin live in this
PowerShell session's environment from step 2 until step 6 removes them; a step that stops early would leave them there. The guards
in steps 2 to 5 and in the `--unbind` command therefore clear them before they `throw`. If you stop for any other reason (a STOP
rule says stop, or you doubt the output), run this yourself before doing anything else:

```powershell
Remove-Item Env:ARON_ADMIN_PASSWORD, Env:ARON_ADMIN_TOTP_FILE, Env:ARON_ADMIN_TOTP_SECRET, Env:ARON_API_BASE -ErrorAction SilentlyContinue
```

Before any rerun or any `--unbind` after a STOP, run step 2 again: it reads the password from Key Vault fresh, as the prerequisites
ask for every run.

### Step 1. Offline check (no network)

```powershell
python -P tools/data/load_packshots.py --check
if ($LASTEXITCODE -ne 0) { throw "offline check failed (exit $LASTEXITCODE): do not continue" }
```

Expected: four `WARNING` lines (the suspect Black Diamond pictures, see "Duplicate pictures"), then
`check: OK, 42 SKUs 1:1 with the catalogue, 42 processed files verified (854 KB); 4 suspect pictures (see the WARNING lines)`.
**STOP** on anything else: rerun `prepare_packshots.py`, or read the `problem:` lines.

### Step 2. Environment (secrets are never printed)

```powershell
$env:ARON_API_BASE = "https://<dev API host>"
$env:ARON_ADMIN_PASSWORD = az keyvault secret show --vault-name kv-aron-dev-7i7g53 -n aron-dev-seed-password --query value -o tsv
$rc = $LASTEXITCODE
if ($rc -ne 0 -or -not $env:ARON_ADMIN_PASSWORD) { Remove-Item Env:ARON_ADMIN_PASSWORD, Env:ARON_ADMIN_TOTP_FILE, Env:ARON_ADMIN_TOTP_SECRET, Env:ARON_API_BASE -ErrorAction SilentlyContinue; throw "could not read the password from Key Vault (exit $rc; is az login done?): secrets cleared, do not continue" }
$env:ARON_ADMIN_TOTP_FILE = "$env:USERPROFILE\AronSecrets\admin1001-mfa-enrolment.json"
```

**STOP** when the guard throws: sign in with `az login` (prerequisite 5), then paste step 2 again. Nothing was sent to the API.

### Step 3. Dry run (no SKU or asset is changed; it does sign in, which opens a session and uses one authenticator step)

```powershell
python -P tools/data/load_packshots.py --dry-run
$rc = $LASTEXITCODE
if ($rc -ne 0) { Remove-Item Env:ARON_ADMIN_PASSWORD, Env:ARON_ADMIN_TOTP_FILE, Env:ARON_ADMIN_TOTP_SECRET, Env:ARON_API_BASE -ErrorAction SilentlyContinue; throw "dry run failed (exit $rc): secrets cleared, STOP; no SKU or asset was changed" }
```

Expected on a fresh environment: the four `WARNING` lines again, then
`plan: 42 pictures: 0 skipped, 42 to bind, 37 assets to upload, 0 not loadable (dry run: nothing was written)`,
with no `[replaces another picture]` mark on any SKU line and no line `WARNING: N of the pictures to bind would REPLACE a picture that is already set`.
**STOP** when the exit code is not 0, when the plan says something else (for example `not loadable` above 0, or
`manifest codes unknown to the server` above 0: the environment does not hold these SKUs), or when the owner has not yet
answered about the four suspect pictures (see "Owner approval": if the answer is "load the other 38 first", use
`--skip-suspect` in steps 3 to 6 and the plan reads `plan: 38 pictures: ... 34 assets to upload`).

**STOP also when a SKU line is marked `[replaces another picture]`** (the `WARNING: N ... would REPLACE` line gives N): that SKU
already has a picture that is not this manifest's, most likely one the owner chose in the web admin (for example a corrected
`BDDF-20s`). `--apply` would overwrite it, and `--unbind` afterwards cannot bring it back: it only removes, so the SKU would
end with no picture at all, not the owner's (the loader keeps no record of what a SKU showed before). Ask the owner which of
those SKUs to leave alone, then leave them out of steps 3 to 6 with `--only` (the codes to load: every other code) or, when
they are among the four suspect SKUs, with `--skip-suspect`:

```powershell
$keep = @("BDDF-20s")   # the SKUs whose picture stays as it is
$load = ((Import-Csv assets/packshots/manifest.csv).sku_code | Where-Object { $keep -notcontains $_ }) -join ","
python -P tools/data/load_packshots.py --dry-run --only $load   # the same --only in steps 4 to 6
```

A `[replaces another picture]` mark on a SKU that an earlier run of this loader bound (after a regenerated file, for example) is
harmless: the owner can say so, and the loader's own earlier picture is not lost by it.

**If the loader printed `login: MFA step refused`, do not rerun it blindly** (here, in step 4, or anywhere else): each run presents
up to two wrong codes, and 10 wrong codes within 15 minutes lock `admin1001` (`ERR_AUTH_ACCOUNT_LOCKED`) for 15 minutes, doubling
at every further lock (30, 60 ... minutes, at most 2 hours). That is the account the web admin uses too. Check the secret file
and the laptop clock (`w32tm /resync`) first, then run once more at most; five failed runs within 15 minutes are enough to lock it.

### Step 4. Canary: one SKU, the first proof of the whole chain

```powershell
python -P tools/data/load_packshots.py --apply --only MAX
$rc = $LASTEXITCODE
if ($rc -ne 0) { Remove-Item Env:ARON_ADMIN_PASSWORD, Env:ARON_ADMIN_TOTP_FILE, Env:ARON_ADMIN_TOTP_SECRET, Env:ARON_API_BASE -ErrorAction SilentlyContinue; throw "canary failed (exit $rc): secrets cleared, STOP; see 'When it fails' and 'Rollback' (step 2 again before --unbind)" }
```

The canary proves the whole chain on one SKU, with two reads of the stored picture: `GET blob` is the admin's read link of
the blob (what the web admin's preview shows; it also shows whether Azure kept the PUT `Content-Type`), and `GET api` is
`GET /v1/sku-images/{asset_id}` with the admin's token, the very request the phones make: the API reads the blob itself and
answers a bare 404 unless the asset is a stored `sku_image` of at most 300 KB that a SKU binds, so a 200 with the file's
bytes proves the bind and the storage read from the API together. It is good only when it printed **exactly** this (MAX is
padded to 20 columns; the spacing is the loader's) and exited 0:

```
MAX                  bound  [POST 200 PUT 201 PATCH 200 GET blob 200 GET api 200] verified image/jpeg, sha256 ok
summary: 1 pictures: 1 bound, 0 repaired, 0 skipped, 0 failed; 1 assets uploaded
```

**STOP** on anything else, for example a `FAILED` line or a `STOPPED:` line. When it says the check failed right after the bind
(`STOPPED: MAX: a SKU failed its check right after it was bound`), MAX is already bound to a picture that does not read back
right: undo it with the `--unbind --only MAX` command the loader printed under `undo with:` (see "Rollback"). If it printed
`cannot be undone with --unbind (MAX)` instead (the failure was `no image_url` and the API's answer named no `image_asset_id`
either), `--unbind` cannot see that binding and would change nothing: follow the `next step for MAX:` line (see the
`no image_url` row of "When it fails"). Either way report the output to infra/backend and do not run step 5. After
`login: MFA step refused` do not rerun blindly: read the MFA paragraph at the end of step 3 first. The guard has cleared the
secrets; the `--unbind` command needs step 2 again first.

A canary that passes does not put a picture on any phone yet: that needs the phone's own steps, see "When the phones show it".

### Step 5. Apply all

```powershell
python -P tools/data/load_packshots.py --apply
$rc = $LASTEXITCODE
if ($rc -ne 0) { Remove-Item Env:ARON_ADMIN_PASSWORD, Env:ARON_ADMIN_TOTP_FILE, Env:ARON_ADMIN_TOTP_SECRET, Env:ARON_API_BASE -ErrorAction SilentlyContinue; throw "apply failed (exit $rc): secrets cleared, STOP; read the FAILED and STOPPED lines (step 2 again before a rerun or --unbind)" }
```

Expected after the canary: MAX is `skipped: already bound`, and
`summary: 42 pictures: 41 bound, 0 repaired, 1 skipped, 0 failed; 36 assets uploaded`
(with `--skip-suspect`: `summary: 38 pictures: 37 bound, 0 repaired, 1 skipped, 0 failed; 33 assets uploaded`).
Without a canary (not recommended) it reads `42 bound, 0 repaired, 0 skipped, 0 failed; 37 assets uploaded`.
A rerun after a partial failure does only the missing work.

### Step 6. Prove it, clean up, record

```powershell
python -P tools/data/load_packshots.py --dry-run
Remove-Item Env:ARON_ADMIN_PASSWORD, Env:ARON_ADMIN_TOTP_FILE, Env:ARON_ADMIN_TOTP_SECRET, Env:ARON_API_BASE -ErrorAction SilentlyContinue
```

Expected: `plan: 42 pictures: 42 skipped, 0 to bind, 0 assets to upload, 0 not loadable (dry run: nothing was written)`
(38 skipped with `--skip-suspect`). Record the run in `docs/status/laptop.md` with the counts and statuses the loader printed
(no URL, no token, no secret), as in step 4c of 2026-10-15. The secrets are cleared whether or not the closing dry run passed.
Nothing has reached a phone yet: "When the phones show it" says when it will, and what to do on a test phone to see it today.

### Good to know

* The dry run, the canary and the apply follow each other within seconds, and a TOTP code works once. The loader therefore
  remembers the last step it presented (a bare number in the temp folder, `ARON_PACKSHOT_STATE_DIR` to move it; not a secret)
  and starts the next run by waiting out that step (`waiting N s for the next authenticator step`, at most 31 s), instead of
  presenting a spent code, which the server counts as a wrong code towards the account lockout.
* **The run stops by itself** (exit 1) in two cases, because the server accepts a bind without looking at the blob, so a
  systematic fault would leave every further SKU bound to a bad picture: (a) the first SKU whose check (either read) fails right
  after the run bound it (`STOPPED: <code>: a SKU failed its check right after it was bound ...`), and (b) three SKUs in a row
  failing the same way (`STOPPED: <code>: 3 SKUs in a row failed the same way (...)`). The three can also be reads of already
  bound pictures that fail (a dead or hanging storage endpoint, or a phone path that answers 503, on a rerun or a dry run). It
  then prints `bound in this run (N): ...` and the exact `--unbind --only ...` command that undoes those bindings. A binding for
  which the API's answer shows neither an `image_url` nor an `image_asset_id` is left out of that command, because `--unbind`
  goes by those and would change nothing: the loader prints `cannot be undone with --unbind (...)` and a `next step for ...` line
  for it instead (today's admin answer carries `image_url` only, so that means: no `image_url`). The closing `summary:` line counts such a SKU as failed and
  says how many of the failed were bound on the server (`1 failed (1 of them bound on the server, but failed their check)`), so it
  agrees with `bound in this run`. Other single failures (a stale `If-Match`, a SKU missing on the server) are reported per SKU
  and the run goes on.

### What `--apply` does, per SKU

1. Sign in once (`POST /v1/auth/login`, client `web`, then `POST /v1/auth/mfa/verify`) and list the SKUs (`GET /v1/admin/skus?limit=500`).
2. Skip a SKU that is already bound to this very picture when both reads of it (step 4) answer 200, `image/jpeg` and the same
   sha256. What a SKU is bound to is read from its answer: `image_asset_id` when the answer carries it (the contract's `Sku`
   does; today's admin answer, `SkuOut` in `AdminProducts.kt`, does not), else the asset id inside its `image_url`.
3. Otherwise `POST /v1/admin/assets` (`asset_id` = the first 128 bits of the file's sha256 with the UUID v4 bits set, so a
   rerun and the SKUs that share art use ONE asset), `PUT` the bytes to the returned write URL
   (`x-ms-blob-type: BlockBlob`, `Content-Type: image/jpeg`; no bearer token goes to the blob store), then
   `PATCH /v1/admin/skus/{id}` with `image_asset_id`, `If-Match` and a `change_reason`.
4. Read the picture back twice and require 200, `image/jpeg` and the file's sha256 from both: `GET` the returned `image_url`
   (the blob, no token), then `GET /v1/sku-images/{asset_id}` with the admin's token (the phones' path, see "When the phones show
   it"). The server never checks that the blob exists, so a failed PUT is never followed by a PATCH, and every binding is proven
   by these two GETs.
5. A stale `If-Match` (412) is refetched and retried once. A token near its 15-minute expiry, or a 401, signs in again; a
   TOTP code works once, so the second sign-in waits for the next 30-second step.

Exit code 0: all good. 1: any SKU failed, the run stopped, or the sign-in failed. 2: usage or configuration (nothing was sent).
The output is counts, SKU codes and HTTP statuses; every URL query (a SAS signature) is masked and the password, TOTP secret and
tokens never appear. A SKU whose picture the manifest marks suspect carries `[SUSPECT picture: owner to confirm]` on its line.

## Expected output

Fresh environment, dry run (step 3):

```
WARNING BDA-10s              suspect picture: same art as BDDF-10s, a different catalogue variant (owner to confirm the right pack)
...
dry run: 42 pictures to <host>
waiting 24 s for the next authenticator step (a code works once)    <- only when a run used the step less than 30 s ago
login: ok
server: 42 SKUs; manifest: 42 pictures; 0 manifest codes unknown to the server
AB-12s               would upload + bind
...
Supreme-20S          would bind (asset shared with Supreme-10S)
plan: 42 pictures: 0 skipped, 42 to bind, 37 assets to upload, 0 not loadable (dry run: nothing was written)
WARNING: 4 suspect pictures in this run (BDA-10s, BDA-20s, BDDF-10s, BDDF-20s); the owner has not confirmed that they show the right pack
```

Apply (step 5; 37 distinct pictures, so 37 uploads and 42 bindings without a canary, 36 uploads and 41 bindings after it):

```
AB-12s               bound  [POST 200 PUT 201 PATCH 200 GET blob 200 GET api 200] verified image/jpeg, sha256 ok
BDA-10s              bound  [POST 200 PUT 201 PATCH 200 GET blob 200 GET api 200] verified image/jpeg, sha256 ok [SUSPECT picture: owner to confirm]
MAX                  skipped: already bound, GET blob 200 GET api 200 image/jpeg, sha256 ok          <- only after the canary
Supreme-20S          bound (asset shared with Supreme-10S)  [PATCH 200 GET blob 200 GET api 200] verified image/jpeg, sha256 ok
summary: 42 pictures: 41 bound, 0 repaired, 1 skipped, 0 failed; 36 assets uploaded
```

A repeat run: `skipped: already bound, GET blob 200 GET api 200 image/jpeg, sha256 ok` per SKU and `42 skipped ... 0 assets uploaded`.
A bound SKU whose blob (or phone path) no longer answers right is `repaired` (re-upload, no PATCH); a SKU bound to another picture is `rebound`
(its earlier picture is overwritten and `--unbind` cannot restore it: step 3 stops on these before anything is written).

## When it fails

| Output | Meaning and fix |
|---|---|
| `login: HTTP 401 ERR_AUTH_INVALID_CREDENTIALS` | the password is wrong or was rotated: read it from Key Vault again. One attempt is made, never a retry loop |
| `login: MFA step refused, HTTP 401 ERR_AUTH_MFA_INVALID` | wrong secret file, or the laptop clock is more than a step off (`w32tm /resync`). At most two codes are presented per run, so one run does not lock the account, **but a repeat does the same again**: 10 wrong codes within `cfg.auth.lockout_window_min` (15) lock `admin1001` for `cfg.auth.lockout_min` (15 minutes, doubling per lock), so five runs in a row with a wrong secret lock it, the web admin's sign-in too. Check the secret file and the clock first; rerun once at most |
| `ERR_AUTH_ACCOUNT_LOCKED` | too many wrong codes; **wait**: a retry during the lock is refused and gains nothing, and the lock lasts `cfg.auth.lockout_min` minutes (15, then 30, 60 ... up to 2 hours for repeated locks) |
| `login: the account must change its password first` | sign in once on the web admin and change it |
| `POST asset: HTTP 503` | the API has no blob store (`ARON_BLOB_ACCOUNT` unset): the environment is not configured, nothing was uploaded |
| `POST asset: HTTP 413` | a processed file is above 307,200 bytes: rerun `prepare_packshots.py` (it validates the cap) |
| `POST asset: HTTP 409 ERR_CONFLICT` | the asset id already stores a different file; cannot happen with content-addressed ids, report it |
| `PUT blob: HTTP 403` | the write URL expired (15 minutes), the laptop's clock is wrong, or the storage firewall refuses this IP. No PATCH was sent; rerun. Three in a row stop the run |
| `STOPPED: ...: a SKU failed its check right after it was bound` with `content-type is application/octet-stream` | the blob store did not keep the PUT content type. The run already stopped; the SKUs it bound are listed. Unbind them (rollback below) and report it to infra/backend before loading more |
| `the picture answers HTTP 404` right after the PUT | the blob read (`GET blob`) found nothing: the PUT did not land (the run stops, as above); fix the cause, rerun repairs it |
| `the phone path answers HTTP 503 ERR_SERVICE_UNAVAILABLE` (step `GET api 503`) | the blob read passed, but the API cannot read the blob itself: its blob reader is not configured in this environment (`UnconfiguredBlobBytesReader`: the phones would get the same 503) or storage is down. The run stopped at the first fresh bind and lists what it bound: unbind it (rollback below), report it to infra/backend, load nothing more until `GET /v1/sku-images/{asset_id}` answers 200 |
| `the phone path answers HTTP 404` (step `GET api 404`) | the API says no stored `sku_image` of at most 300 KB is bound under that asset id (a bare 404 never says which): the bind did not take, the asset is not a `sku_image` or is over 300 KB, or the API reads another storage than the one the PUT wrote to. Same handling as the 503 |
| `the phone path: content-type is ...` or `...: the stored bytes differ from the file (sha256)` | the API served something other than the uploaded file (its content type is the asset row's `mime`, `image/jpeg`). Same handling; report it |
| `PATCH: HTTP 412` after one retry | somebody is editing the SKU; rerun |
| `PATCH: HTTP 400 ERR_VALIDATION` | the body or the asset was refused (`unknown_asset`, `above_cap`); read the SKU and asset in the web admin |
| `no image_url (is the blob store configured?)` | the API could not sign a read URL. The bind itself went through (the server accepts it without a link), the run stopped, and **`--unbind` cannot undo it** when the answer also names no `image_asset_id` (today's admin answer does not): it goes by those and prints `no picture: nothing to unbind` with exit 0 while the SKU stays bound. The loader prints `cannot be undone with --unbind (CODE)` and `next step for CODE:`. Fix the read-URL signing on the server, then `--apply --only CODE` finds the binding and checks it (`skipped`, or `repaired` if the blob is bad); or clear the pack picture of that SKU in the web admin SKU form |
| `connection failed (...)` | network or DNS; rerun. Three SKUs in a row stop the run, also in the read of an already bound picture (rerun or dry run) |

A single failure of the kinds that cannot repeat for the next SKU (a stale `If-Match`, a SKU the server does not have) is reported
per SKU and the run goes on with the others; the exit code is 1 and a rerun does only the missing work.


> **Accepted window.** The server accepts a binding without looking at the blob. If the loader stops because the check right after a fresh
> bind failed, that SKU stays bound to a picture that does not read back until you run the `--unbind` command it printed. Until then the
> bundle carries that asset id and a phone gets a 404 for it (the lane's phone code remembers the failure until the app process restarts).
> This is why the canary comes first: one SKU, not forty-two.

## Rollback

The binding is data, not a deploy: nothing else to roll back.

* **The loader's own undo:** `--unbind` sends `PATCH /v1/admin/skus/{id}` with `{"image_asset_id": null, "change_reason": "..."}`
  and the current `If-Match` (a stale one is refetched once) for the SKUs bound to a picture **of this manifest**; the phones go
  back to the silhouette at their next bundle (see "When the phones show it"). It signs in like the other modes (same environment variables, same owner approval), needs the manifest
  but not the processed files, and prints one line per SKU and a summary.

  ```powershell
  # after a STOP the guard cleared the session: run step 2 again first
  python -P tools/data/load_packshots.py --unbind --only MAX,AB-12s   # the codes a STOPPED run printed, or the canary
  $rc = $LASTEXITCODE
  if ($rc -ne 0) { Remove-Item Env:ARON_ADMIN_PASSWORD, Env:ARON_ADMIN_TOTP_FILE, Env:ARON_ADMIN_TOTP_SECRET, Env:ARON_API_BASE -ErrorAction SilentlyContinue; throw "unbind failed (exit $rc): secrets cleared; read the FAILED lines" }
  python -P tools/data/load_packshots.py --unbind                     # all of them
  Remove-Item Env:ARON_ADMIN_PASSWORD, Env:ARON_ADMIN_TOTP_FILE, Env:ARON_ADMIN_TOTP_SECRET, Env:ARON_API_BASE -ErrorAction SilentlyContinue
  ```

  A SKU bound to any other picture (the web admin's choice, an older load) is left alone and reported as `left alone`; a SKU
  without a picture is `no picture: nothing to unbind`. The test is repeated when a stale `If-Match` makes the loader refetch the
  SKU: a picture the owner chose in the web admin while `--unbind` was running is reported as `left alone: changed meanwhile`
  and is not unbound. **`--unbind` only removes; it never restores an earlier picture.** A SKU that `--apply` had *rebound* from
  another picture (the owner's choice in the web admin, say) shows the silhouette afterwards, not the earlier picture: set that
  one in the web admin again. That is why step 3 stops on `[replaces another picture]` before anything is written. A binding
  whose answer shows neither an `image_url` nor an `image_asset_id` (see "When it fails") is invisible to `--unbind`; clear it
  in the web admin. Once nothing binds an asset any more, `GET /v1/sku-images/{asset_id}` answers 404 for it (the API serves
  bound pictures only); a phone that already has the file keeps it in its cache.
* The web admin's SKU form can set the same field (F-ADM-065), and its picture control has a remove button (the `CLEAR` value
  in `web/src/components/admin/crud/entity-form.tsx`, which sends the clear). That is read from the code; it has not been
  exercised on an environment, so prefer `--unbind`.
* Assets and blobs are append-only (a database trigger refuses UPDATE and DELETE on `app.admin_asset`, and there is no delete
  endpoint). An abandoned upload is an orphan of about 20 KB; harmless. A wrong picture is replaced, not deleted: fix the file,
  `prepare_packshots.py`, `--apply --only CODE` (a new hash, a new asset, the SKU is rebound).

## Owner approval

The loader writes to a shared environment as `admin1001` and uses the owner's MFA secret, so a run needs the owner's yes in chat
(precedent: `docs/status/laptop.md` 2026-10-15, steps 4a to 4c). **One yes covers the whole sequence of steps 3 to 6 (dry run,
canary, apply, closing dry run) on one named environment**, and `--unbind` on that environment if the canary or the apply fails;
it is not a standing permission and does not carry over to another environment or to a later load.

**Ask the owner about the four suspect Black Diamond pictures in the same message.** They are pictures the loader cannot judge:

| SKU (catalogue variant) | Why it is suspect |
|---|---|
| `BDA-20s` (Black Diamond Advanced) and `BDDF-20s` (Black Diamond Double Filter) | the owner's two files are byte-identical |
| `BDA-10s` (Black Diamond Advanced) and `BDDF-10s` (Black Diamond Double Filter) | two different files (185x387 and 185x385) that show the same art, found by the thumbnail comparison in `prepare_packshots.py` |

Phones would show one variant's pack on the other until the owner supplies the right files. The options: (a) load all 42 and
fix the four later, (b) load the other 38 first with `--skip-suspect` (every command above, also the dry runs) and the four
once the right files are in. Either way the four stay marked `suspect-different-variants` in the manifest until a corrected
file changes their hashes.

## When the phones show it

The loader changes the server only. A phone shows a picture after three steps of its own. The first one is NOT a sync run (read
from the code of this tree on 2026-10-19; the lane's runbook `docs/runbooks/sku-pack-pictures.md` says "the next sync or the
next morning's bundle", which is true of the bundle's content but not of when a phone asks for it).

1. **The phone takes its changed bundle.** The bundle lists every SKU with its `image_asset_id` (only while the asset is a
   stored `sku_image`: `BundleService.products`) and never a link, so binding or unbinding a picture changes the bundle and the
   phone's held copy is out of date. A sync run does not tell the phone so: the server's sync answer carries
   `bundle_version_current = null` (`IngestService.kt`), and the phone's "the server's bundle is newer" check after a run
   (`refreshIfServerNewer`) has nothing to act on. The SR app takes the changed bundle (SKUs travel in the bundle delta) when
   * it is started: the day-start download sends the held ETag and gets a full answer when the content changed; a force-stop and
     reopen does it at once, with no time gate;
   * it comes back to the front, at most every 30 to 240 minutes (`cfg.bundle.delta_min_interval_min`);
   * the next business day begins; or
   * a push message asks for a bundle pull (`PushPullKind.BUNDLE`), if the server sends one.

   The AMO and TSO apps have no start-up or foreground bundle download of their own in this tree (`app-amo` and `app-tso` wire
   only the push pull and the sync-runner hook), so check an SR phone first.
2. **The phone downloads the pictures.** At the end of every completed sync run (after a save, when the network comes back, on
   the sync sheet's **Sync now**) `PackshotPreload` reads the pictured SKUs from the phone's own database and, for every asset
   that is not on disk yet, calls `GET /v1/sku-images/{asset_id}` with the user's token: one download per picture, mobile data
   allowed, at most 100 per run, each at most 300 KB, kept in the bounded image cache (40 MB by default) under the asset id.
   The 42 pictures are 37 distinct files, 762,985 bytes in all, so about 0.75 MB per phone, once. A picture that is already
   on the phone is never fetched again; a changed picture is a new upload with a new asset id, so every phone fetches it once.
   A download that fails (offline, refused) is not tried again until the app process starts again (`SkuPackshots.failed`): a
   force-stop and reopen retries it. (The lane's runbook says "retried after every sync"; the code does not.)
3. **The rows draw them.** Stock and Sale (the kit's `PackCard`) read the phone's disk cache and nothing else: no screen waits
   for a picture or for the network. A SKU with no picture shows the grey pack silhouette of its category, never an error text.
   A picture that has just landed shows the next time the screen is opened.

A dev build (`-Paron.devPackshots=true`, the dev-origin CI job only) also carries stand-in crops of the owner's reference
screenshot for the six Maxim SKUs (`MaxR-10S`, `MaxR-20S`, `MaxB-10S`, `MaxB-20S`, `MaxWB-10S`, `MaxWB-20S`; `android/dev-packshots`).
A loaded picture takes their place once it is on the phone; a production build never carries them. Unbinding a picture takes
the phone back to the silhouette (or the crop) at its next bundle; the file stays in the phone's cache until it is evicted.

### Check on a test phone

After step 6, on an SR phone (the device-owner Honor), on a validated Wi-Fi (a device-check row for this is not in
`docs/status/device-checks.md` yet):

1. Force-stop the SR app (Android Settings, Apps, Aron SR, Force stop) and open it: the start-up download takes the bundle with
   the asset ids.
2. Open the sync sheet from Home and tap **Sync now**: the sync run ends with the picture downloads (about 0.75 MB). Give it a
   few seconds.
3. Open Stock and the Sale product list: each SKU row shows its pack picture at the left of the row (the kit's `PackCard` slot, 56 x 90 dp, 44 dp wide on a narrow screen).
   Every SKU with a picture shows it; none shows an error.
4. Switch on airplane mode and reopen both screens: the pictures stay (the cache), nothing waits.
5. Android Settings, Apps, Aron SR, Storage, **Clear cache** (not Clear data; the pictures live in the app's cache folder,
   which the system may also empty when storage runs low), then airplane mode: the grey silhouette shows, with no error text.
   Back online, the next completed sync run downloads the pictures again.
6. Sunlight mode: the pack sits beside the numbers on a solid surface; the numbers stay readable.

How the picture fills the slot is the kit's: `PackshotImage` crops to fill (`ContentScale.Crop`, `core-ui` `Packs.kt`). A portrait
pack fits that slot; **Aster, a landscape group shot (320 x 175 after `prepare_packshots.py`), loses its sides in a portrait slot.**
The first design (D-PACK-03 notes it) asked for letterbox, never crop; that is an open point for the kit lane and the lead, not something the loader can
change (the picture itself is never cropped or edited).

## Duplicate pictures

Five pairs have byte-identical source files, so the manifest puts them in one `duplicate_group` and the loader binds one
asset to both SKUs. One more pair has different files with the same art. The manifest classifies them:

* `Supreme-10S`/`Supreme-20S`, `MaxDB-10S_10HL`/`MaxDB-20S_20HL`, `MaxB-10S`/`MaxB-20S`, `MaxR-10S`/`MaxR-20S`
  (`same-art-pack-sizes`): the same variant in two pack sizes; one picture for both is expected.
* `BDA-20s`/`BDDF-20s` (`suspect-different-variants`, byte-identical files) and `BDA-10s`/`BDDF-10s`
  (`suspect-different-variants`, near-identical files): Black Diamond **Advanced** and Black Diamond **Double Filter** are
  different variants but the owner's files show the same art in both pack sizes, which looks like a source mistake. All four
  SKUs get a `WARNING` line in `--check`, `--dry-run` and `--apply` and are loaded unless `--skip-suspect` is given. When the
  owner supplies the right files: replace them in the source folder, run `prepare_packshots.py` (only those SKUs' hashes
  change) and `--apply --only BDDF-10s,BDDF-20s` (or whichever SKUs got new files); the untouched SKUs keep their asset.

## Offline checks (no network, safe anywhere)

```powershell
python -P tools/data/load_packshots.py --check
python -P -m unittest discover -s tools/data -p "test_*.py"
```

`--check` works without the processed files too: it then validates the manifest against `db/seed/sku_catalog.csv` alone and
says so. CI does not run either yet (the `tools/data` tests are not in `.github/workflows/ci.yml`, and no CI step refuses a forced
`git add -f` of a pack picture); adding them is an infra-lane change, not made here.
