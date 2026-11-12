-- V0172 loyalty portal, notes for the data dictionary (lane portal-loyalty-api; D-LOY-API-06). Comments only: no table, column or data changes.
-- Corrections of the header of V0170, which was already in a green lane push when the lead's second Opus pass asked for them, so (forward-only) they live here:
-- (a) V0170 point 2 quotes "D-DISC-API-10" for the Discount precedent; that sentence is in docs/status/portal-discount-api.md ("an old ADMIN token without the
--     `discounts` claim gets 401 ERR_SCOPE_CHANGED until refresh"), not in DECISIONS.md under that id.
-- (b) Side effects of the V0170 scope_version UPDATE: the app_user touch trigger also raises app_user.version, so a user editor open on such an ADMIN gets one 412 and
--     reloads; an ADMIN in the middle of the MFA step signs in again.
-- (c) V0170 point 1 says no signed, expiring address is stored for a gift picture. The first build of the routes broke that in app.gift_change.snapshot (it kept the
--     signed read URL); the routes of this head store the snapshot with image_url null plus image_asset_id and resolve the URL when they answer, replays included.
SET lock_timeout = '5s';

COMMENT ON COLUMN app.gift_change.snapshot IS 'The gift as the first result of the call returned it (JSON, contract member names) with image_url null, plus image_asset_id. The picture URL is a signed, expiring address and is never stored here: it is resolved from the asset again whenever the call is answered, replays included.';
COMMENT ON COLUMN app.gift.image_asset_id IS 'Uploaded picture of the gift (app.admin_asset, purpose gift_image); the portal API turns it into a read URL when it answers, so no signed address is stored in this table, in app.gift_change.snapshot or in an audit row. Null when the gift has no picture.';
COMMENT ON COLUMN app.app_user.scope_version IS 'Counter bumped on any change of role, status, scope or assignment; tokens carrying an older value are refused. V0170 raised it once for every ADMIN holding a loyalty grant (their pre-V0160 tokens lack the claim); the touch trigger raises app_user.version with it, so an open user editor gets one 412.';
