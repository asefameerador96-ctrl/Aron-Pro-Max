-- V0111 dw.v_memo_flags: the stable surface for the server's memo integrity flags (lead request 2026-10-24, from
-- backend-core #16: app.memo.server_flags, written by MemoChecks/IngestService (zero_price_line, price_mismatch,
-- arithmetic_mismatch, pack_factor_mismatch and the others), had no reader; standing rule 5 says integrity findings are
-- surfaced). One row per memo whose server_flags is non-empty, with the scope keys the analytics endpoint filters by
-- (GET /v1/dashboards/flagged-memos, contract 1.8, backend-core builds it on this view).
-- Scope, frozen at the business date like V0042's capture context: route_id and zone_id are the memo's own (zone
-- stamped at insert from the route's zone on that date). A memo uploaded without a route takes the outlet's placement
-- on its business date (app.outlet_placement_history) and that route's zone on the date (app.route_zone_on), so a
-- later re-placement does not move an old memo into another territory's reach; only when neither exists does it fall
-- back to the outlet's current route and zone. territory, division and wing follow the zone through the current
-- hierarchy. family_uuid groups a memo with its edits: counts use status = 'active' AND voided_at IS NULL.
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
SELECT m.id AS memo_id, m.client_uuid AS memo_client_uuid, m.family_uuid AS memo_family_uuid, m.memo_no,
       m.business_date, m.status, m.user_id, m.outlet_id, o.code AS outlet_code, o.name AS outlet_name,
       s.route_id, z.id AS zone_id, z.territory_id, t.division_id, d.wing_id,
       m.gross_mtk, m.server_flags AS flags, m.received_at AS stored_at, m.voided_at
  FROM app.memo m
  JOIN app.outlet o ON o.id = m.outlet_id
  LEFT JOIN LATERAL (
    SELECT coalesce(m.route_id, p.route_id, o.route_id) AS route_id,
           coalesce(m.zone_id,
                    app.route_zone_on(p.route_id, m.business_date),
                    (SELECT r.zone_id FROM app.route r WHERE r.id = p.route_id),
                    o.zone_id) AS zone_id
      FROM (SELECT (SELECT h.route_id FROM app.outlet_placement_history h
                     WHERE m.route_id IS NULL AND h.outlet_id = m.outlet_id AND h.valid_from <= m.business_date
                       AND (h.valid_to IS NULL OR h.valid_to > m.business_date)) AS route_id) p
  ) s ON true
  LEFT JOIN app.zone z ON z.id = s.zone_id
  LEFT JOIN app.territory t ON t.id = z.territory_id
  LEFT JOIN app.division d ON d.id = t.division_id
 WHERE m.server_flags <> '{}';

-- The role grants of the map (V0014), as for every new dw object.
SELECT app.apply_db_role_grants();

COMMENT ON VIEW dw.v_memo_flags IS 'Stable view: one row per memo the server flagged (app.memo.server_flags non-empty: zero price line, price, arithmetic or pack-factor mismatch and the like), with its scope keys; voided and superseded memos included with their status (counts use status = ''active'' AND voided_at IS NULL).
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON COLUMN dw.v_memo_flags.memo_id IS 'Memo (app.memo id; with business_date the memo''s key).';
COMMENT ON COLUMN dw.v_memo_flags.memo_client_uuid IS 'Client-generated UUID of the memo (idempotency key).';
COMMENT ON COLUMN dw.v_memo_flags.memo_family_uuid IS 'Family UUID shared by a memo and its edits (superseding memos); group by it to count a problem once.';
COMMENT ON COLUMN dw.v_memo_flags.memo_no IS 'Printed memo number <username>-<yyMMdd>-<seq>.';
COMMENT ON COLUMN dw.v_memo_flags.business_date IS 'Asia/Dhaka business date of the memo.';
COMMENT ON COLUMN dw.v_memo_flags.status IS 'Memo status: active, voided (by a memo void) or superseded (by an edit).';
COMMENT ON COLUMN dw.v_memo_flags.user_id IS 'User who captured the memo (app.app_user); names are resolved by the API, not in dw.';
COMMENT ON COLUMN dw.v_memo_flags.outlet_id IS 'Outlet (app.outlet).';
COMMENT ON COLUMN dw.v_memo_flags.outlet_code IS 'Outlet code.';
COMMENT ON COLUMN dw.v_memo_flags.outlet_name IS 'Outlet name.';
COMMENT ON COLUMN dw.v_memo_flags.route_id IS 'Route of the memo; for a memo uploaded without one, the outlet''s placement on the business date (else its current route).';
COMMENT ON COLUMN dw.v_memo_flags.zone_id IS 'Zone of the memo (its route''s zone on the business date); without a route, the placement route''s zone on that date (else the outlet''s current zone).';
COMMENT ON COLUMN dw.v_memo_flags.territory_id IS 'Territory of that zone (current hierarchy).';
COMMENT ON COLUMN dw.v_memo_flags.division_id IS 'Division of that territory (current hierarchy).';
COMMENT ON COLUMN dw.v_memo_flags.wing_id IS 'Wing of that division (current hierarchy).';
COMMENT ON COLUMN dw.v_memo_flags.gross_mtk IS 'Gross sales value of the memo before discounts, in milli-taka.';
COMMENT ON COLUMN dw.v_memo_flags.flags IS 'Server flags of the memo (app.memo.server_flags), for example zero_price_line or price_mismatch.';
COMMENT ON COLUMN dw.v_memo_flags.stored_at IS 'UTC instant the server stored the memo (app.memo.received_at).';
COMMENT ON COLUMN dw.v_memo_flags.voided_at IS 'UTC instant of an admin data void; null when not data-voided. Readers filter it.';
