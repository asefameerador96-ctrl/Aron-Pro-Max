# backend-core: photo retention exemptions before the 730-day delete rule bites (infra session 7, 2026-10-28)

**From:** infra lane, data-architecture action row 7 (D-INFRA-LAKE-01). **To:** backend-core (and db for any table). **Priority:** not urgent for the pilot, mandatory before the first photo turns 730 days old (no earlier than 730 days after the first field upload in any environment).

**What infra shipped:** `infra/modules/storage.bicep` deletes block blobs under `media/photos/` 730 days after their last modification (`photoDeleteDays`, the docs' `cfg.retention.media_days`, docs/21 s4.8 and docs/16 s13.1). `media/assets/` (admin assets, tutorials) is never deleted by age; `media/support/` bundles are deleted at 30 days (docs/21 s4.8, D-INFRA-LAKE-04). Exports (`exports/`) are deleted after 7 days.

**What the rule cannot do:** docs/16 s13.1 exempts an active outlet's reference photo, and `app.retention_hold` (designed in docs/16 s13.1, not built yet) must stop deletion for a fraud case or a retailer dispute. A Blob lifecycle rule filters only by prefix and by blob index tag equality, and every field photo today is `photos/<business date>/<device>/<uuid>.jpg` whatever its purpose (`MediaModule.kt` `PURPOSES`, BC-81), so the rule cannot tell an outlet reference photo or a held photo from any other.

**Ask (pick one, tell infra which):**
1. **Move exempt photos out of `photos/`**: when a photo becomes an active outlet's reference photo, or is put on hold, the worker copies it server-side to `media/reference/...` or `media/hold/...` (no age rule there) and records the new path; releasing the hold or retiring the reference photo deletes the copy. Infra changes nothing.
2. **Blob index tags**: the worker sets a tag (for example `retain=keep`) on exempt photos, and infra changes the rule to delete only blobs tagged `retain=standard`, which the worker then sets on every other photo after the BlobCreated event. Needs the worker identity to hold tag rights (Storage Blob Data Owner or a custom role with `tags/write`); infra would add that.

Option 1 keeps the storage rule simple and needs no extra role; infra recommends it.

**Also:** a deleted blob leaves its media row marked stored, so a read link would 404. Mark rows past `cfg.retention.media_days` as expired (or treat a 404 on read as expired) so the apps and the web show "photo expired", not a broken image.

**Until then:** nothing reaches 730 days during the pilot, so the gap is latent; this file and the open item in `docs/status/infra.md` keep it visible.
