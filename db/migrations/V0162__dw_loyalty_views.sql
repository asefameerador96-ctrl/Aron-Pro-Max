-- V0162 dw views of the loyalty-programme portal; dw view contract 1.6 -> 1.7 (lane portal-loyalty-api, train 1; D-LOY-API-01).
-- Additive views for later engines and dashboards (docs/31 s3: read dw.v_*, not app tables): dw.v_programme (one row per
-- programme with the definition of its current version), dw.v_programme_version (one row per version, with the tiers, products
-- and scope as jsonb arrays) and dw.v_gift (the gift catalogue). The change reason stays in app.programme_version and the audit
-- log (no admin free text in dw). The portal exists; the field does not use any of it (docs/27: loyalty in the field is deferred).
SET lock_timeout = '5s';

CREATE VIEW dw.v_programme AS
SELECT p.id AS programme_id, p.kind, p.code AS programme_code, p.status, p.current_version_id, v.version_no AS current_version_no,
       p.name_en, p.name_bn, p.period_label, p.active_from, p.active_to, p.points_expire_on,
       COALESCE((SELECT jsonb_agg(jsonb_build_object('tier_code', t.tier_code, 'sort_order', t.sort_order, 'label_en', t.label_en,
                                                      'label_bn', t.label_bn, 'threshold', t.threshold, 'threshold_basis', t.threshold_basis)
                                  ORDER BY t.sort_order)
                   FROM app.programme_version_tier t WHERE t.programme_version_id = p.current_version_id), '[]'::jsonb) AS tiers,
       (SELECT count(*)::int FROM app.programme_version x WHERE x.programme_id = p.id) AS version_count,
       p.created_at, p.updated_at, 'production'::app.data_origin AS data_origin,
       p.status = 'active' AS is_effective
  FROM app.programme p
  LEFT JOIN app.programme_version v ON v.id = p.current_version_id;

CREATE VIEW dw.v_programme_version AS
SELECT v.id AS programme_version_id, v.programme_id, p.code AS programme_code, v.version_no, v.id = p.current_version_id AS is_current,
       v.name_en, v.name_bn, v.period_label, v.active_from, v.active_to, v.points_expire_on, v.rules,
       COALESCE((SELECT jsonb_agg(jsonb_build_object('tier_code', t.tier_code, 'sort_order', t.sort_order, 'label_en', t.label_en,
                                                      'label_bn', t.label_bn, 'threshold', t.threshold, 'threshold_basis', t.threshold_basis)
                                  ORDER BY t.sort_order)
                   FROM app.programme_version_tier t WHERE t.programme_version_id = v.id), '[]'::jsonb) AS tiers,
       COALESCE((SELECT jsonb_agg(jsonb_build_object('product_level', x.product_level, 'product_id', x.product_id) ORDER BY x.product_level, x.product_id)
                   FROM app.programme_version_product x WHERE x.programme_version_id = v.id), '[]'::jsonb) AS products,
       COALESCE((SELECT jsonb_agg(jsonb_build_object('node_type', s.node_type, 'node_id', s.node_id) ORDER BY s.node_type, s.node_id)
                   FROM app.programme_version_scope s WHERE s.programme_version_id = v.id), '[]'::jsonb) AS scope,
       v.created_at, 'production'::app.data_origin AS data_origin,
       (v.id = p.current_version_id AND p.status = 'active') AS is_effective
  FROM app.programme_version v
  JOIN app.programme p ON p.id = v.programme_id;

CREATE VIEW dw.v_gift AS
SELECT g.id AS gift_id, g.programme_id, p.code AS programme_code, g.code AS gift_code, g.name_en, g.name_bn, g.points_cost,
       g.tier_codes, g.image_url, g.status, g.created_at, g.updated_at, 'production'::app.data_origin AS data_origin,
       (g.status = 'active' AND p.status = 'active') AS is_effective
  FROM app.gift g
  JOIN app.programme p ON p.id = g.programme_id;

COMMENT ON VIEW dw.v_programme IS 'Stable view: one row per loyalty programme (app.programme: Diamond League, Astha, campaign, Superstar) with the definition of its current version and its tiers as a JSON array. The portal exists; the field does not use it (no points earned or redeemed, bundle programmes null, docs/27). is_effective = status active.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON VIEW dw.v_programme_version IS 'Stable view: one row per immutable programme version with its window, rules, tiers, products and scope as JSON arrays (empty products or scope = everything). is_effective = the current version of an active programme.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';
COMMENT ON VIEW dw.v_gift IS 'Stable view: the gift catalogue (app.gift) with its programme code and the points cost; no taka value in v1. is_effective = an active gift of an active programme.
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';

COMMENT ON COLUMN dw.v_programme.programme_id IS 'Programme (app.programme.id).';
COMMENT ON COLUMN dw.v_programme.kind IS 'diamond_league, astha, campaign or superstar.';
COMMENT ON COLUMN dw.v_programme.programme_code IS 'Stable business code of the programme (app.programme.code).';
COMMENT ON COLUMN dw.v_programme.status IS 'Lifecycle status: draft, active or inactive (Ended in the portal).';
COMMENT ON COLUMN dw.v_programme.current_version_id IS 'Programme version (app.programme_version.id) in force; null for programmes made before the portal.';
COMMENT ON COLUMN dw.v_programme.current_version_no IS 'Version number of the current version, from 1; null when there is none.';
COMMENT ON COLUMN dw.v_programme.name_en IS 'English name of the programme (current definition).';
COMMENT ON COLUMN dw.v_programme.name_bn IS 'Bangla name; null when none.';
COMMENT ON COLUMN dw.v_programme.period_label IS 'Period label such as 2026-10 or 2026-Q4; null when none.';
COMMENT ON COLUMN dw.v_programme.active_from IS 'First business date (Asia/Dhaka) of the programme window.';
COMMENT ON COLUMN dw.v_programme.active_to IS 'Last business date (Asia/Dhaka, inclusive) of the programme window.';
COMMENT ON COLUMN dw.v_programme.points_expire_on IS 'Date after which points of the period expire; null when they do not.';
COMMENT ON COLUMN dw.v_programme.tiers IS 'JSON array of the current version''s tiers {tier_code, sort_order, label_en, label_bn, threshold, threshold_basis}; threshold is STD as stored (never negative, may be null); empty when none.';
COMMENT ON COLUMN dw.v_programme.version_count IS 'Number of versions the programme has had.';
COMMENT ON COLUMN dw.v_programme.created_at IS 'UTC instant the programme was created.';
COMMENT ON COLUMN dw.v_programme.updated_at IS 'UTC instant the programme row last changed (status or current version).';
COMMENT ON COLUMN dw.v_programme.data_origin IS 'Always production: programmes carry no origin of their own.';
COMMENT ON COLUMN dw.v_programme.is_effective IS 'True when the programme status is active.';

COMMENT ON COLUMN dw.v_programme_version.programme_version_id IS 'Programme version (app.programme_version.id).';
COMMENT ON COLUMN dw.v_programme_version.programme_id IS 'Programme (app.programme.id) the version belongs to.';
COMMENT ON COLUMN dw.v_programme_version.programme_code IS 'Stable business code of the programme.';
COMMENT ON COLUMN dw.v_programme_version.version_no IS 'Version number within the programme, from 1.';
COMMENT ON COLUMN dw.v_programme_version.is_current IS 'True for the version in force (app.programme.current_version_id).';
COMMENT ON COLUMN dw.v_programme_version.name_en IS 'English name in this version.';
COMMENT ON COLUMN dw.v_programme_version.name_bn IS 'Bangla name in this version; null when none.';
COMMENT ON COLUMN dw.v_programme_version.period_label IS 'Period label in this version; null when none.';
COMMENT ON COLUMN dw.v_programme_version.active_from IS 'First business date (Asia/Dhaka) of the window in this version.';
COMMENT ON COLUMN dw.v_programme_version.active_to IS 'Last business date (Asia/Dhaka, inclusive) of the window in this version.';
COMMENT ON COLUMN dw.v_programme_version.points_expire_on IS 'Date after which points of the period expire; null when they do not.';
COMMENT ON COLUMN dw.v_programme_version.rules IS 'Kind-specific rules as JSON (empty object in v1; no slabs are invented, docs/27).';
COMMENT ON COLUMN dw.v_programme_version.tiers IS 'JSON array of {tier_code, sort_order, label_en, label_bn, threshold, threshold_basis}; threshold is STD as stored.';
COMMENT ON COLUMN dw.v_programme_version.products IS 'JSON array of {product_level, product_id} rows (category, brand, variant or sku); empty = every product.';
COMMENT ON COLUMN dw.v_programme_version.scope IS 'JSON array of {node_type, node_id} place rows (wing, division, territory, zone, route, channel, sub_channel); empty = everywhere.';
COMMENT ON COLUMN dw.v_programme_version.created_at IS 'UTC instant the version was made.';
COMMENT ON COLUMN dw.v_programme_version.data_origin IS 'Always production: programmes carry no origin of their own.';
COMMENT ON COLUMN dw.v_programme_version.is_effective IS 'True for the current version of an active programme.';

COMMENT ON COLUMN dw.v_gift.gift_id IS 'Gift (app.gift.id).';
COMMENT ON COLUMN dw.v_gift.programme_id IS 'Programme (app.programme.id) the gift belongs to.';
COMMENT ON COLUMN dw.v_gift.programme_code IS 'Stable business code of the programme.';
COMMENT ON COLUMN dw.v_gift.gift_code IS 'Gift code, unique within its programme.';
COMMENT ON COLUMN dw.v_gift.name_en IS 'English name of the gift.';
COMMENT ON COLUMN dw.v_gift.name_bn IS 'Bangla name; null when none.';
COMMENT ON COLUMN dw.v_gift.points_cost IS 'Points a Diamond League redemption costs (1 to 100000); null for gifts that are chosen, not redeemed (Astha). No taka value is stored in v1.';
COMMENT ON COLUMN dw.v_gift.tier_codes IS 'Tier codes the gift is offered to (Astha); empty = all tiers.';
COMMENT ON COLUMN dw.v_gift.image_url IS 'Address of the gift picture (uploaded through the admin asset store); null when none.';
COMMENT ON COLUMN dw.v_gift.status IS 'active or inactive.';
COMMENT ON COLUMN dw.v_gift.created_at IS 'UTC instant the gift was created.';
COMMENT ON COLUMN dw.v_gift.updated_at IS 'UTC instant the gift last changed.';
COMMENT ON COLUMN dw.v_gift.data_origin IS 'Always production: gifts carry no origin of their own.';
COMMENT ON COLUMN dw.v_gift.is_effective IS 'True for an active gift of an active programme.';

CREATE OR REPLACE FUNCTION dw.view_contract_version() RETURNS text
LANGUAGE sql IMMUTABLE
AS $$ SELECT '1.7' $$;

-- Restated V0016 comments: the portal now manages the definitions; the field stays deferred.
COMMENT ON TABLE app.programme IS 'Loyalty programme definition (Diamond League month, Astha quarter, campaign, Superstar). The loyalty portal (V0158) manages drafts, versions and the lifecycle; nothing in the field reads it (docs/27: loyalty in the field is deferred; bundle programmes null).
owner: backend:masterdata | capture: ONLINE | retention: master | pii: none';

SELECT app.apply_db_role_grants();
