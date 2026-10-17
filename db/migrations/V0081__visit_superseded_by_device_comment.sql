-- V0081 documents how a paper (web_entry) visit stops counting once the phone's own visit for the same memo arrives
-- (lead row 2026-10-15; docs/requests/backend-core-backfill-vs-phone-memo-review.md step 3; answer in
-- docs/requests/db-backend-core-visit-supersede.md). Decision: reuse the data-void tombstone, no new column.
-- app.visit.voided_at is already write-once on a synced row (V0007 guard '=voided_at') and every dw path skips voided
-- visits: the projector's visit set (voided_at IS NULL), dw.fact_visit.voided, dw.v_daily_sr and dw.v_geo_integrity
-- (NOT voided). The visit and strike counts take sr_call only, so after the match the dashboards show the phone's one
-- visit and the paper memo's one strike. dw.v_geo_integrity counts every non-voided visit kind, so an unmatched paper
-- visit does count there (as no_fix) until it is matched and voided; that is the view's existing rule, unchanged here. The link to the phone visit and the reason
-- superseded_by_device_visit live in the backfill_matched audit row (step 6), as for every other data void.
-- Comment only: no lock beyond the catalogue update.

SET lock_timeout = '5s';

COMMENT ON COLUMN app.visit.voided_at IS 'UTC time the row was tombstoned (admin data void, or a paper web_entry visit superseded by the phone''s visit for the same memo); write-once, the row is never deleted, voided visits never count.';
