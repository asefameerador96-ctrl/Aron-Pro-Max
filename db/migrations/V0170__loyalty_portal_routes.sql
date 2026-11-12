-- V0170 loyalty portal, train 2 (lane portal-loyalty-api; D-LOY-API-05).
-- 1. app.gift.image_asset_id: the gift picture is an uploaded admin asset (purpose gift_image); the API resolves it to a read URL when it answers (a replay
--    included), so no signed, expiring address is stored in this table, in app.gift_change.snapshot or in an audit row (image_url of V0007 stays as it was, unused). The foreign key is added NOT VALID here and
--    validated by V0171 (squawk rule); the table holds no gift yet (the portal is the first writer).
-- 2. V0160 carried the `targets` grants of every ADMIN over to `loyalty` without bumping app.app_user.scope_version, so an ADMIN access token
--    minted before V0160 has no `loyalty` claim and stays valid until it expires. Bumping the version of every ADMIN who now holds a `loyalty`
--    grant makes such a token answer 401 ERR_SCOPE_CHANGED, the client refreshes, and the new token carries the claim (the Discount precedent: the
--    follow-up note in docs/status/portal-discount-api.md, "an old ADMIN token without the `discounts` claim gets 401 ERR_SCOPE_CHANGED until refresh").
--    Idempotent in effect: a second run only bumps again. Side effects of the UPDATE: the app_user touch trigger also raises app_user.version, so a user
--    editor open on such an ADMIN gets one 412 and reloads; an ADMIN in the middle of the MFA step signs in again.
SET lock_timeout = '5s';

ALTER TABLE app.gift ADD COLUMN image_asset_id uuid;
ALTER TABLE app.gift ADD CONSTRAINT gift_image_asset_fk FOREIGN KEY (image_asset_id) REFERENCES app.admin_asset(asset_id) NOT VALID;
COMMENT ON COLUMN app.gift.image_asset_id IS 'Uploaded picture of the gift (app.admin_asset, purpose gift_image); the portal API turns it into a read URL when it answers. Null when the gift has no picture.';

UPDATE app.app_user u SET scope_version = scope_version + 1
 WHERE u.role = 'ADMIN' AND EXISTS (SELECT 1 FROM app.user_module_grant g WHERE g.user_id = u.id AND g.module = 'loyalty');

-- 3. Notes for the data dictionary (the shipped V0158/V0162 files cannot be edited; these restate the table comments with what train 2 learned).
COMMENT ON TABLE app.programme IS 'Loyalty programme definition (Diamond League month, Astha quarter, campaign, Superstar). The loyalty portal (V0158) manages drafts, versions and the lifecycle; nothing in the field reads it (docs/27: loyalty in the field is deferred; bundle programmes null). A programme that has a version can never be deleted: the API role holds no DELETE and app.programme_version references it; end it instead (status inactive). Writer rule: the portal copies the new version''s name, period, dates, points expiry and rules into this row in the same transaction that sets current_version_id, only while the programme is a draft; the API enforces that rule, the database enforces only the status moves (draft to active to inactive), that the pointer changes only while draft and that the pointer names a version of this programme.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON TABLE app.programme_enrolment IS 'Enrolment of an outlet in a programme for a period, with league, tier and base target. Written by PUT /v1/admin/programmes/{id}/enrolments (batch UUID, app.programme_enrolment_batch). Sizing note for the final account: reads page by id within a programme and rely on the exclusion constraint index and the outlet index; add a btree on (programme_id, id) if a programme passes about 100000 enrolments.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON TABLE app.gift IS 'A gift of a loyalty programme with points cost (integer points, no taka value in v1) and tiers. Written only by the portal (SUPERADMIN) through app.gift_change; a gift is never deleted, set it inactive. The picture is image_asset_id (V0170); image_url of V0007 is legacy and unused by the portal.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON COLUMN app.gift_change.snapshot IS 'The gift as the first result of the call returned it (JSON, contract member names) with image_url null, plus image_asset_id. The picture URL is a signed, expiring address and is never stored here: it is resolved from the asset again whenever the call is answered, replays included.';

SELECT app.apply_db_role_grants();
