-- V0111 dw.v_memo_flags: the stable surface for the server's memo integrity flags (lead request 2026-10-24, from
-- backend-core #16: app.memo.server_flags, written by MemoChecks/IngestService (zero_price_line, price_mismatch,
-- arithmetic_mismatch, pack_factor_mismatch and the others), had no reader; standing rule 5 says integrity findings are
-- surfaced). One row per memo whose server_flags is non-empty, with the scope keys the analytics endpoint filters by
-- (GET /v1/dashboards/flagged-memos, contract 1.8, backend-core builds it on this view).
-- Scope: route_id and zone_id are the memo's own capture context (zone stamped at insert from the route's zone on the
-- business date, V0042); a memo uploaded without a route falls back to the outlet's current route and zone, as
-- dw.v_daily_outlet does. territory, division and wing follow that zone through the current hierarchy.
-- No names: dw carries no user names or logins (dw.dim_user, web_ro and bi_reader read every dw relation), so the
-- endpoint resolves user names from app.app_user itself. memo_no (it embeds the login) is the printed memo number,
-- already on dw.fact_memo. The outlet's name and code are not personal (dw.v_outlet_masked shows them too); its contact
-- number is not exposed here.
-- Voided (admin data void, voided_at) and SR-voided or superseded memos stay in the view with their status and
-- voided_at: readers filter them.
-- No index: at pilot size app.memo is small and the business_date index of every partition serves the date filter a
-- dashboard always applies; a partial index on server_flags <> '{}' is the first thing to add if this view gets slow
-- at fleet size (D-DB-MEMO-FLAGS-VIEW-01).

SET lock_timeout = '5s';

CREATE VIEW dw.v_memo_flags AS
SELECT m.id AS memo_id, m.client_uuid AS memo_client_uuid, m.memo_no, m.business_date, m.status, m.user_id,
       m.outlet_id, o.code AS outlet_code, o.name AS outlet_name,
       coalesce(m.route_id, o.route_id) AS route_id, z.id AS zone_id, z.territory_id, t.division_id, d.wing_id,
       m.gross_mtk, m.server_flags AS flags, m.received_at AS stored_at, m.voided_at
  FROM app.memo m
  JOIN app.outlet o ON o.id = m.outlet_id
  LEFT JOIN app.zone z ON z.id = coalesce(m.zone_id, o.zone_id)
  LEFT JOIN app.territory t ON t.id = z.territory_id
  LEFT JOIN app.division d ON d.id = t.division_id
 WHERE m.server_flags <> '{}';

-- The role grants of the map (V0014), as for every new dw object.
SELECT app.apply_db_role_grants();

COMMENT ON VIEW dw.v_memo_flags IS 'Stable view: one row per memo the server flagged (app.memo.server_flags non-empty: zero price line, price, arithmetic or pack-factor mismatch and the like), with its scope keys; voided and superseded memos included with their status.
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON COLUMN dw.v_memo_flags.memo_id IS 'Memo (app.memo id; with business_date the memo''s key).';
COMMENT ON COLUMN dw.v_memo_flags.memo_client_uuid IS 'Client-generated UUID of the memo (idempotency key).';
COMMENT ON COLUMN dw.v_memo_flags.memo_no IS 'Printed memo number <username>-<yyMMdd>-<seq>.';
COMMENT ON COLUMN dw.v_memo_flags.business_date IS 'Asia/Dhaka business date of the memo.';
COMMENT ON COLUMN dw.v_memo_flags.status IS 'Memo status: active, voided (by a memo void) or superseded (by an edit).';
COMMENT ON COLUMN dw.v_memo_flags.user_id IS 'User who captured the memo (app.app_user); names are resolved by the API, not in dw.';
COMMENT ON COLUMN dw.v_memo_flags.outlet_id IS 'Outlet (app.outlet).';
COMMENT ON COLUMN dw.v_memo_flags.outlet_code IS 'Outlet code.';
COMMENT ON COLUMN dw.v_memo_flags.outlet_name IS 'Outlet name.';
COMMENT ON COLUMN dw.v_memo_flags.route_id IS 'Route of the memo; the outlet''s current route when the memo was uploaded without one.';
COMMENT ON COLUMN dw.v_memo_flags.zone_id IS 'Zone of the memo (its route''s zone on the business date); the outlet''s current zone when the memo has none.';
COMMENT ON COLUMN dw.v_memo_flags.territory_id IS 'Territory of that zone (current hierarchy).';
COMMENT ON COLUMN dw.v_memo_flags.division_id IS 'Division of that territory (current hierarchy).';
COMMENT ON COLUMN dw.v_memo_flags.wing_id IS 'Wing of that division (current hierarchy).';
COMMENT ON COLUMN dw.v_memo_flags.gross_mtk IS 'Gross sales value of the memo before discounts, in milli-taka.';
COMMENT ON COLUMN dw.v_memo_flags.flags IS 'Server flags of the memo (app.memo.server_flags), for example zero_price_line or price_mismatch.';
COMMENT ON COLUMN dw.v_memo_flags.stored_at IS 'UTC instant the server stored the memo (app.memo.received_at).';
COMMENT ON COLUMN dw.v_memo_flags.voided_at IS 'UTC instant of an admin data void; null when not data-voided. Readers filter it.';
