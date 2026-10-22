-- V0101 field counting units (owner directive 2026-10-22, docs/34 s4.2, DECISIONS D-UNITS-02, supersedes the stick
-- entry default of D24-65): the apps enter, show, print and sync cigarettes and bidi in packets, lighters in boxes of
-- 25 and matches in dozens; storage and every server aggregate stay in base units (qty_base = qty_entered x pack_factor).
-- 1. Existing SKUs (dev and test: the seed catalogue; a new account has none yet, its SKUs arrive through the admin
--    import with the right values): lighters with one piece per pack become a box of 25; cigarettes, bidi and lighters
--    enter in 'pack', matches in 'dozen' (their base unit, base_per_pack 1). Cigarette and bidi pack sizes (20 or 10,
--    12 or 25 sticks) are already per SKU in base_per_pack. Prices stay per base unit, so a packet price is
--    price x base_per_pack, exact in milli-taka (integer times integer).
-- 2. cfg.sale.qty_entry_unit default becomes {"cigarette":"pack","bidi":"pack","lighter":"pack","match":"dozen"}.
-- 3. New key cfg.sale.allow_loose_base_units (bool, default false): when false the quantity fields take whole packets,
--    boxes and dozens only (docs/34 s4.2). Global, delivered to device and server (the server re-checks), class C2.

SET lock_timeout = '5s';

UPDATE app.sku SET base_per_pack = 25 WHERE category_code = 'lighter' AND base_per_pack = 1;
UPDATE app.sku SET entry_unit_default = 'pack' WHERE category_code IN ('cigarette', 'bidi', 'lighter') AND entry_unit_default <> 'pack';
UPDATE app.sku SET entry_unit_default = 'dozen' WHERE category_code = 'match' AND entry_unit_default <> 'dozen';

UPDATE app.cfg_key
   SET default_value = '{"cigarette": "pack", "bidi": "pack", "lighter": "pack", "match": "dozen"}'::jsonb, updated_at = now()
 WHERE key = 'cfg.sale.qty_entry_unit';

INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, editor_permission, description_en) VALUES
  ('cfg.sale.allow_loose_base_units', 'sale', 'S', 'bool', 'false'::jsonb, '{}'::jsonb, NULL,
   ARRAY['global']::text[], 2, NULL, 'B', 'both', false, false, 'cfg.edit.ops',
   'When false, quantities are entered in whole packets, boxes and dozens only (no loose sticks or pieces); the server re-checks (docs/34 s4.2).');

COMMENT ON COLUMN app.sku.base_per_pack IS 'Base units in one pack: 20 or 10 sticks per cigarette packet, the stick count of a bidi packet, 25 pieces per lighter box, 1 for matches (dozen is the base unit; V0101).';
COMMENT ON COLUMN app.sku.entry_unit_default IS 'Unit the apps enter, show and print: pack for cigarettes, bidi and lighters (box), dozen for matches (V0101, D-UNITS-02).';
