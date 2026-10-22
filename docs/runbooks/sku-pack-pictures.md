# Runbook: upload the real pack pictures (packshots) of the SKUs

**Who:** the owner, or an admin they name (role ADMIN or SUPERADMIN in the web admin portal).
**When:** once AKTCL supplies the approved pack art, and again whenever a pack design changes.
**Why:** the SR, AMO and TSO apps show each SKU's pack picture on the Stock and Sale rows (PARITY-SPEC s2.6,
`docs/ui-reference/sr/stock.webp`). Until a SKU has a picture, the row shows a neutral grey pack silhouette. The dev
test builds also carry stand-in crops of the owner's screenshot for the six Maxim Regular, Bolt and Wild Berry SKUs.
An uploaded picture always replaces the stand-in.

**The art comes from AKTCL.** Use only the current approved pack art, with the pictorial health warning as printed.
Do not use photos from the internet.

## What to prepare (per SKU)

- One front view of the pack, portrait, roughly 2 wide to 3 high (the row shows it at about 56 x 90 dp, cropped to fill).
- JPEG or PNG, **at most 300 KB** (the portal refuses anything bigger). About 300 x 480 px at JPEG quality 80 is enough;
  bigger pictures cost the reps' data packs and add nothing on a phone.
- 10-stick and 20-stick packs of the same design may share one picture, but upload it on each SKU.

## Steps (about 1 minute per SKU)

1. Sign in to the web admin portal (MFA as usual).
2. Open **Admin, Master data, Products, SKUs** (`/admin/skus`).
3. Open the SKU (for example `MaxR-10S`), then **Edit**.
4. In **Pack image** (`প্যাকের ছবি`), choose the file. The portal uploads it, checks the type and the 300 KB limit, and
   shows the preview.
5. Enter the change reason (for example "AKTCL pack art 2026-10") and **Save**.

## When the phones show it

- The SKU change goes into the next phone bundle (the next sync or the next morning's bundle).
- After that sync, each phone downloads the picture once in the background, on Wi-Fi or mobile data; it is a small
  file. A sale never waits for a picture. A phone that is offline keeps the silhouette, or its last picture, until its
  next sync.
- A changed picture is a new upload, so every phone fetches it once. An unchanged picture is never fetched again.
- To remove a picture: edit the SKU, clear **Pack image**, save. The phones go back to the silhouette after their next
  sync.

- The release that first brings this field makes each phone download its full bundle once (every SKU gains
  `image_asset_id`). That is a few hundred KB per phone at pilot size, a one-off.

## Checks

- Admin list: the SKU shows the preview.
- Phone: open **Stock** (or **Sale**) after a sync. The pack picture is at the left of the SKU row.
- If a phone still shows the silhouette after a sync with signal, report the SKU code and the phone's login. The
  picture download is retried after every sync.

Status: written 2026-10-18 (lane ui-packshots). Not drilled; the end-to-end check runs on the dev test account once
the server path is deployed there.
