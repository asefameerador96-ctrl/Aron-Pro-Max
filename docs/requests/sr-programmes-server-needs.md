# SR programmes: what the phone still needs from the server (lane android-sr-programmes)

Written 2026-11-18 by lane A-PROG2. Each item says what the phone does today and what it would do with the server's answer. None of this blocks the cutover.

## S-1 Which unit numbers of a campaign gift are already photographed (Gift Verify, manual 58)

`pending_gift_verifications[]` carries `redemption_client_uuid`, `outlet_id`, `gift_id`, `qty` and `photos_captured` (a COUNT). The phone shows one camera slot per unit (`unit_no` 1..qty) and cannot tell WHICH units the count covers.

- Today: the count is taken as the lowest unit numbers not photographed on this phone (this phone's own `gift_photo` records are exact). If another phone or the TSO photographed unit 3 of 3, this phone offers units 1 and 2 again and the server stores the later photos as flagged duplicates (never refused), so the harm is a wasted photo.
- Request: add to each pending item the list of unit numbers that already have a (non-duplicate, non-voided) photo: `units_captured: [integer]` (each 1..50, ascending, at most `qty` entries), next to `photos_captured` (kept for old phones). Same bound and same snapshot as the rest of the section.
- With it: the phone offers exactly the open units and drops the lowest-units rule.

## S-2 The tier of an outlet for gift selection

Already asked as N-7: `loyalty[].tier_code` (and `gift_assignments[].tier_code`, which exists). Until the server sends a tier the gift page says so and offers no restricted gift.

## S-3 Quarter months beyond the bundle's current month

Already asked as N-1 (`astha_truncated_months`, the whole quarter within the 1,000-row bound). Until then the phone reads other months online with `listAsthaTargets`, keeps them in an app-private file cache, and shows "not downloaded, connect once" for a month never fetched.

## S-4 A gift photo record uuid held by another user must not merge a second user's photo (lead final pass, low; for the server lane, no change to the key shape without the lead's word)

The phone fixes the record uuid of a gift photo by its subject (Astha: assignment and quarter; campaign: redemption, gift line, unit) so that a retry or a kill makes one record, and, after the server REFUSES a record, a retake uses the same parts plus `attempt n` (the next attempt; attempt 0 keeps the old shape). The parts hold no user or install id. The server's generic insert treats a `client_uuid` held by ANOTHER user as already there, so two phones (or two logins on one phone) photographing the same subject before either syncs end with the second record acknowledged as a duplicate of the first user's, its photo blob uploaded under that uuid, and the rule "earliest `captured_at` wins, the rest flagged duplicate" (GiftPhotoRecords) bypassed by arrival order.

Asked: either the gift photo ingest answers a record uuid held by another user as its own kind of conflict that the phone can show (and the phone then derives a per-user uuid), or the lead decides the phone adds the user id to the parts (that changes every photo key already held, so it needs a one-time rule for records already queued). Until then the phone sends one photo per subject per phone.
