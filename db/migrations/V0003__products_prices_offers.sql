-- V0003 product tree, SKUs, the five price types, sales plans and offers as data (row N-005, schema v1a).
-- docs/24 s7, s12.1, s12.5 items 1; contract ProductNode, Sku, SkuPrice, SalesPlan, Offer.

-- Product tree: category > segment > brand > variant (one table, typed by level).
CREATE TABLE app.product_node (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  level         text NOT NULL CHECK (level IN ('category','segment','brand','variant')),
  parent_id     bigint REFERENCES app.product_node(id),
  code          text CHECK (length(code) <= 40),
  name          text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  name_bn       text CHECK (length(name_bn) <= 120),
  sort          int NOT NULL DEFAULT 0,
  sales_enable  boolean NOT NULL DEFAULT true,
  status        text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  external_ref  varchar(64) UNIQUE,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  version       int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by    bigint,
  updated_by    bigint,
  CHECK ((level = 'category') = (parent_id IS NULL))
);
-- Names are unique among siblings (categories among themselves).
CREATE UNIQUE INDEX product_node_sibling_name ON app.product_node (level, coalesce(parent_id, 0), lower(name));
CREATE UNIQUE INDEX product_node_code ON app.product_node (level, code) WHERE code IS NOT NULL;
CREATE INDEX ON app.product_node (parent_id);

-- Keeps the tree shaped: a segment's parent is a category, a brand's a segment, a variant's a brand.
CREATE FUNCTION app.product_node_check_parent() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  parent_level   text;
  expected_level text := (ARRAY['category','segment','brand'])[array_position(ARRAY['segment','brand','variant'], NEW.level)];
BEGIN
  IF NEW.parent_id IS NOT NULL THEN
    SELECT level INTO parent_level FROM app.product_node WHERE id = NEW.parent_id;
    IF parent_level IS DISTINCT FROM expected_level THEN
      RAISE EXCEPTION 'product_node % (%) cannot have a % parent', NEW.id, NEW.level, coalesce(parent_level, 'missing')
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER product_node_parent BEFORE INSERT OR UPDATE OF parent_id, level ON app.product_node
  FOR EACH ROW EXECUTE FUNCTION app.product_node_check_parent();

-- SKU: quantities are integers in base_unit (stick, piece, dozen); base_per_pack sticks per pack (1 for lighter
-- and match); the pack badge is computed, never stored (docs/24 s7.2).
CREATE TABLE app.sku (
  id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                  text NOT NULL UNIQUE CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$'),   -- contract Sku.code
  variant_id            bigint NOT NULL REFERENCES app.product_node(id),
  category_code         text NOT NULL CHECK (category_code IN ('cigarette','bidi','lighter','match')),
  name                  text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  short_name            text NOT NULL CHECK (length(short_name) BETWEEN 1 AND 20),   -- printed on the 58 mm memo
  name_bn               text CHECK (length(name_bn) <= 120),
  base_unit             text NOT NULL CHECK (base_unit IN ('stick','piece','dozen')),
  base_per_pack         int NOT NULL CHECK (base_per_pack BETWEEN 1 AND 1000),
  pack_type             text CHECK (length(pack_type) <= 40),                         -- HLP, Soft Pack, Box, Dozen ...
  entry_unit_default    text NOT NULL CHECK (entry_unit_default IN ('stick','piece','dozen','pack')),
  report_unit           text CHECK (report_unit IN ('stick','piece','dozen','box','pack')),
  report_factor         numeric(16,3) NOT NULL DEFAULT 1 CHECK (report_factor > 0),
  sort                  int NOT NULL DEFAULT 0,
  sales_enable          boolean NOT NULL DEFAULT true,
  status                text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  thumbnail_media_uuid  uuid,
  external_ref          varchar(64) UNIQUE,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  version               int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by            bigint,
  updated_by            bigint
);
CREATE INDEX ON app.sku (variant_id);

-- A SKU hangs off a variant node, never a category, segment or brand.
CREATE FUNCTION app.sku_check_variant() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM app.product_node WHERE id = NEW.variant_id AND level = 'variant') THEN
    RAISE EXCEPTION 'sku %: variant_id % is not a variant node', NEW.code, NEW.variant_id USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER sku_variant BEFORE INSERT OR UPDATE OF variant_id ON app.sku FOR EACH ROW EXECUTE FUNCTION app.sku_check_variant();

-- Effective-dated prices for the five price types; amount_mtk is the price of per_base_qty base units (1 for every
-- seed price). Integer milli-taka because seed prices carry three decimals (7.935 Tk = 7,935 mtk).
CREATE TABLE app.sku_price (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  sku_id        bigint NOT NULL REFERENCES app.sku(id),
  price_type    text NOT NULL CHECK (price_type IN ('outlet','cc','distributor','reporting','nto')),
  amount_mtk    bigint NOT NULL CHECK (amount_mtk >= 0),
  per_base_qty  int NOT NULL DEFAULT 1 CHECK (per_base_qty BETWEEN 1 AND 1000),
  valid_from    date NOT NULL,
  valid_to      date,                                -- exclusive; null = open
  publish_batch_uuid uuid,                           -- PricePublishRequest.batch_uuid (idempotent publish)
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    bigint,
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT sku_price_no_overlap EXCLUDE USING gist
    (sku_id WITH =, price_type WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);
CREATE INDEX ON app.sku_price (valid_from);

-- SKUs enabled for a zone, effective-dated (one row per zone, SKU and period).
CREATE TABLE app.sales_plan (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  zone_id     bigint NOT NULL REFERENCES app.zone(id),
  sku_id      bigint NOT NULL REFERENCES app.sku(id),
  valid_from  date NOT NULL,
  valid_to    date,                                  -- exclusive; null = open
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  bigint,
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (zone_id WITH =, sku_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);

-- Offers as data (docs/24 s12.5 item 1): the offer is the stable identity, every rule change is a new
-- offer_version; memos record the versions they used. Phase 2 adds typed rule tables beside these.
CREATE TABLE app.offer (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code          text NOT NULL UNIQUE CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$'),
  group_code    text NOT NULL CHECK (length(group_code) <= 40),
  status        text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  current_version_id bigint,                         -- FK added below
  external_ref  varchar(64) UNIQUE,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  version       int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by    bigint,
  updated_by    bigint
);

CREATE TABLE app.offer_version (
  id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  offer_id               bigint NOT NULL REFERENCES app.offer(id),
  version_no             int NOT NULL CHECK (version_no >= 1),
  offer_type             text NOT NULL CHECK (offer_type IN ('pct_discount','amount_per_unit','free_qty','drp_slide')),
  level                  text NOT NULL CHECK (level IN ('line','memo')),
  title_en               text NOT NULL CHECK (length(title_en) <= 200),
  title_bn               text CHECK (length(title_bn) <= 200),
  valid_from             date NOT NULL,
  valid_to               date NOT NULL,              -- inclusive last day, as in the contract Offer
  threshold_qty_base     int CHECK (threshold_qty_base >= 1),
  discount_bp            int CHECK (discount_bp BETWEEN 1 AND 10000),
  discount_mtk_per_unit  bigint CHECK (discount_mtk_per_unit >= 1),
  reward_sku_id          bigint REFERENCES app.sku(id),
  reward_qty_base        int CHECK (reward_qty_base >= 1),
  stacking               text NOT NULL CHECK (stacking IN ('exclusive','stackable')),
  rule                   jsonb NOT NULL DEFAULT '{}'::jsonb,   -- extra typed parameters (slab steps); Phase 2 rule tables replace it
  change_reason          text,
  created_at             timestamptz NOT NULL DEFAULT now(),
  created_by             bigint,
  UNIQUE (offer_id, version_no),
  CHECK (valid_to >= valid_from),
  CHECK (offer_type <> 'pct_discount'    OR discount_bp IS NOT NULL),
  CHECK (offer_type <> 'amount_per_unit' OR discount_mtk_per_unit IS NOT NULL),
  CHECK (offer_type NOT IN ('free_qty','drp_slide') OR (reward_sku_id IS NOT NULL AND reward_qty_base IS NOT NULL
                                                         AND threshold_qty_base IS NOT NULL))
);
ALTER TABLE app.offer ADD CONSTRAINT offer_current_version_fk FOREIGN KEY (current_version_id) REFERENCES app.offer_version(id);

CREATE TABLE app.offer_version_product (
  offer_version_id bigint NOT NULL REFERENCES app.offer_version(id),
  product_level    text NOT NULL CHECK (product_level IN ('category','brand','variant','sku')),
  product_id       bigint NOT NULL,
  PRIMARY KEY (offer_version_id, product_level, product_id)
);

-- Empty scope = everywhere.
CREATE TABLE app.offer_version_scope (
  offer_version_id bigint NOT NULL REFERENCES app.offer_version(id),
  node_type        text NOT NULL CHECK (node_type IN ('wing','division','territory','zone','route','channel','sub_channel')),
  node_id          bigint NOT NULL,
  PRIMARY KEY (offer_version_id, node_type, node_id)
);

CREATE TRIGGER product_node_touch BEFORE UPDATE ON app.product_node FOR EACH ROW EXECUTE FUNCTION app.touch_master();
CREATE TRIGGER sku_touch          BEFORE UPDATE ON app.sku          FOR EACH ROW EXECUTE FUNCTION app.touch_master();
CREATE TRIGGER offer_touch        BEFORE UPDATE ON app.offer        FOR EACH ROW EXECUTE FUNCTION app.touch_master();
