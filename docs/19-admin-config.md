# 19 — Central Admin Dashboard and Runtime Configuration

> **What this doc decides**
> 1. Config model and precedence: every operating parameter is a scoped, effective-dated, audited row in `cfg.config_value`, resolved most-specific-wins over eleven levels, stamped on every captured row, and re-checked by the server against the value the device knew at capture time (D-87, D-430, D-431; s2).
> 2. Risk classes and rails: C0 to C3 with bounds enforced inside the database, blast-radius preview, two-person approval and mandatory canary for C3, a two-sided anomaly watch, restrictive-only break-glass and change-freeze windows (D-88, D-94, D-100, D-433, D-434, D-436; s7).
> 3. Propagation: a scope-relevant `X-Config-Version` on every response, delta pull, ack as a queued outbox row, scheduled values applied offline, urgent push with jitter, and kill switches that never block upload (D-89, D-130, D-446; s4).
> 4. Admin pages and permission bundles: console pages P1 to P18 plus the TSO web back-office tools, with authority held as bounded permission bundles and menus held as data, not one `admin` role (D-91, D-185, D-190, D-435, D-448; s5, s8).
> 5. Geofence: radius at global, wing, division, territory, house, geo_class, zone and outlet with a map, density view, what-if over stored fixes and a calibration plan, TSO edits in propose mode by default, and a no-location policy (D-93, D-94, D-95, D-254; s6).

## 1 Principles and scope

R6 is the sponsor's requirement for a central admin dashboard where the geofencing radius and every other important operating parameter can be adjusted from the GUI, with an audit trail, and the change reaches the field apps. This document is the single owner of the config registry, its mechanics, the console and the back-office write tools. Other documents cite its keys and never redefine them.

### 1.1 R6 clause by clause

| R6 clause | Mechanism | Section | First works | Proved by |
| --- | --- | --- | --- | --- |
| A central admin dashboard | Web console `/admin/*`, pages P1 to P18, plus the TSO back-office pages | s5 | 1c minimal (radius, check-out time, min_version), 6b full | T-1-65, T-6-60 |
| The geofencing radius can be adjusted from the GUI | Radius at eight scope levels with map, bulk edit, what-if and calibration report | s6 | 1c (global and territory), 2d (all levels) | T-1-61, T-2-67 |
| Every other important operating parameter | The key registry (s3): every parameter the spec and the manuals hard-code is a row, with default, bounds, scope, editor and class | s3 | 0b (registry seeded), 6b (every key editable) | T-0-60, T-6-60 |
| With audit trail | `cfg.config_change_audit` (hash-chained, append-only) plus unified audit viewer P16 | s2.1, s7.8 | 0b | T-0-64 |
| The change reaches the field apps | Header-triggered delta pull, queued ack, scheduled values, reach view per version | s4 | 1c | T-1-61, T-2-61, T-2-62 |
| Plan first, then build phase by phase, each phase testable | Keys, pages and tools are assigned to sub-milestones | s11 | all | T-0-60 to T-7-62 |

### 1.2 Four things that look alike

| Kind | Examples | Stored in | Edited by | Version stream | Approval | Reaches phones by |
| --- | --- | --- | --- | --- | --- | --- |
| Config (setting) | radius, check-out time, token lifetime, rounding mode | `cfg.config_value` | config console (P3, P2, P4) | `config_version` | by class C0 to C3 (s7) | config delta (s4) |
| Content (content-type key) | reason codes, gift catalogue, survey questions, holidays, print templates, i18n overrides | its own table (SCD columns, doc 16) | console page of its owner | the same `config_version` through `cfg.bump_version` (s2.7) | by class, usually C0 or C1 | config delta carries `{key, content_version}`, rows ride the bundle delta |
| Master data | geography, products, prices, routes, assignments, users, outlets, targets | `app.*` tables, 58 entities (doc 15 s7) | master-data pages P9, P10, back-office tools (s8) | none; bundle delta by `updated_since` and `scope_version` | maker-checker only where KPI history is touched (D-98) | bundle delta |
| Code | schema, invariants, formulas | repository | engineers | releases | pull request | app release |

Adding or removing a key is a migration (code). Changing a value is config (GUI). A key's class may be raised from the GUI and never lowered (D-88).

### 1.3 What is not config (deliberately)

| Item | Why it stays code | Decision |
| --- | --- | --- |
| Idempotency by `client_uuid`, batch replay, content fingerprint | A switch would create failure modes with no business upside | D-21, D-62 |
| Money unit milli-taka, business-date time zone Asia/Dhaka, API envelope | Correctness invariants (the cutoff time of day is a key, the zone is not) | D-15, D-20 |
| "A mocked fix is never geo-valid"; the Haversine formula | Invariant; only the policy around it (`cfg.geo.mock_policy`) is a key | D-96 |
| No position stream, no background location | CLAUDE.md rule 3; no key can enable it (the lens key `store_fix_on_every_screen_open` is not registered) | D-74 |
| A kill switch or version block never stops upload or wipes data | The one exception is the version-scoped sync hold, which has a mandatory duration and keeps rows on the device | D-130 |
| Final Submit once per zone per day; the login event definition | Primary key and a locked decision (the lens keys `final_submit_once_per_day` and `login_event_definition` are not registered) | D-55, D-30 |
| KPI formulas K-01 to K-18 | Formulas are code; denominators, bands, bases and caps are keys | plan KPI table (doc 16 s9) |
| The role set (`sr`, `amo`, `tso`, `dmo`, `wm`, `top`, `admin`) | Scope resolution and PII rules depend on it; adding a role is a release | D-91 |

### 1.4 Where this document departs from the lens, and why

The seven lens files pre-date the manuals, the data profile and the skeleton. Where they disagree, the skeleton, then the manual register's unified names, then the lens win in that order. The substantive departures are collected in s3.4 (reconciled values) and s3.5 (register keys not registered). The structural ones are:

| Lens position | Here | Why |
| --- | --- | --- |
| Kind T means structured content | Kind T means time-shaped (a time of day, window or date set the device compares with trusted time); content is a value type `content(<table>)` | The plan's naming rules define S, T, O this way |
| `cfg.fraud.*` editable by `cfg.field` | `cfg.sec.fraud.*` and the geo plausibility thresholds, class C2 or higher, editor S, floors and ceilings mandatory | D-109, D-267 |
| Server re-check resolves "as of capture instant" only | As of capture, capped at the config version the device had, inside an acceptance window | D-431 (closes G-cfg-05) |
| One global `config_version` in every header | The header carries the highest version relevant to the caller's scope chain | D-446; avoids 8,500 devices pulling an empty delta for a change in one zone |
| Ack as a header | Ack is a `config_ack` outbox row (priority class 1) for keys flagged `requires_ack` | doc 17 s6.7 |
| Flags `cfg.flag.<kind>.<name>` | Keys are `cfg.flag.<name>`; the kind (release, ops, wave) is registry metadata | D-144, D-148, D-152 name flags without a kind segment; D-99 keeps the kind as a property |

Proved by: T-0-60, T-0-63, T-1-65.

## 2 Config data model

Schema `cfg` in PostgreSQL 16, migration slot M-45 (doc 16 owns the migration file, this section is its contract). Everything is plain SQL and forward-only. All tables are ONLINE-ONLY on the server; the device holds a resolved copy (`ref_config`, doc 17 s2) that is CACHED and applies OFFLINE. Phase: 0b.

### 2.1 Tables

```sql
-- Creation order in the migration: scope_level, config_item, config_version, config_change_request, config_value, config_version_scope,
-- config_change_audit, config_ack, config_snapshot, permission tables, view. config_version carries no foreign key to the request (avoids a cycle).
CREATE SCHEMA cfg;
CREATE EXTENSION IF NOT EXISTS btree_gist;       -- exclusion constraint on (key, scope, range); verify the Flexible Server allow-list in 0a (G-19-01)

-- Scope levels and precedence (data, not an enum). Higher precedence wins (D-87, D-430).
CREATE TABLE cfg.scope_level (
  scope_type text PRIMARY KEY,   -- global role wave wing division territory house geo_class zone route outlet user device
  precedence int NOT NULL UNIQUE,-- 0 10 20 30 40 50 60 70 80 90 100 110 120
  id_table   text,               -- table that scope_id references; NULL for global
  note       text);

-- Registry: one row per key. Inserted by migration, never by the GUI.
CREATE TABLE cfg.config_item (
  key              text PRIMARY KEY,                       -- cfg.geo.radius_m
  area             text NOT NULL,                          -- first segment after cfg.
  kind             char(1) NOT NULL CHECK (kind IN ('S','T','O')),   -- S setting, T time-shaped, O operational switch (auto-expiring)
  value_type       text NOT NULL,                          -- int bool time text url semver money_mtk pct list json enum content(<table>)
  json_schema      jsonb,                                  -- for list, json and content values
  constraints      jsonb NOT NULL DEFAULT '{}',            -- {"min":..,"max":..,"floor":..,"ceiling":..,"enum":[..],"max_items":..,
                                                           --  "dynamic_min":"cfg.geo.radius_min_m","dynamic_max":"cfg.geo.radius_max_m",
                                                           --  "ref":{"table":"app.app_release","col":"version","status":"published"}}
  default_value    jsonb NOT NULL,                         -- recommended value
  parity_value     jsonb,                                  -- observed Apsis behaviour where the manual differs from the recommended value
  scope_levels     text[] NOT NULL,                        -- subset of cfg.scope_level.scope_type allowed to hold an override
  editor_domain    text NOT NULL CHECK (editor_domain IN ('F','O','P','S','R','FIN')),  -- field ops, ops, programme, security, release, finance
  bounded_roles    text[] NOT NULL DEFAULT '{}',           -- roles that may propose or edit inside their own scope (tso, dmo, wm)
  risk_class       smallint NOT NULL CHECK (risk_class BETWEEN 0 AND 3),   -- set in code
  risk_class_raised smallint CHECK (risk_class_raised >= risk_class),      -- GUI may raise, never lower (D-88)
  effect           char(1) NOT NULL CHECK (effect IN ('B','S','R')),       -- B next request or bundle, S next session or new day, R next release
  push             boolean NOT NULL DEFAULT false,         -- also sent as an FCM data message (D-09)
  delivery         text NOT NULL CHECK (delivery IN ('server','device','both')),  -- device and both are in ref_config
  requires_ack     boolean NOT NULL DEFAULT false,         -- device sends a config_ack outbox row (doc 17 s6.7)
  stamp_on         text[] NOT NULL DEFAULT '{}',           -- tables whose rows store the resolved value: visit memo attendance due_collection redemption
  future_dated_only boolean NOT NULL DEFAULT false,        -- effective_from must be a future Dhaka midnight
  restrictive_dir  text NOT NULL DEFAULT 'none' CHECK (restrictive_dir IN ('up','down','enum_order','none')),  -- s7.5
  canary_required  boolean NOT NULL DEFAULT false,         -- C3 at scope territory or wider (s7.3)
  anomaly_watch    text[] NOT NULL DEFAULT '{}',           -- KPIs armed after a change: geo_valid_pct force_sale_pct login_pct submit_pct
  content_table    text,                                   -- value_type content: the table this key versions
  flag_kind        text CHECK (flag_kind IN ('release','ops','wave')),     -- D-99 kind as metadata
  owner_decision   text, gap_id text,
  description_en   text NOT NULL, description_bn text,
  introduced_in    text NOT NULL,                          -- sub-milestone code, first consumed
  deprecated_at    timestamptz, replaced_by text REFERENCES cfg.config_item(key));

-- Values: never updated in place. A change closes the open row and inserts the new one in the same transaction.
CREATE TABLE cfg.config_value (
  id               bigserial PRIMARY KEY,
  key              text NOT NULL REFERENCES cfg.config_item(key),
  scope_type       text NOT NULL REFERENCES cfg.scope_level(scope_type),
  scope_id         bigint NOT NULL DEFAULT 0,              -- 0 for global; geography id, role id, wave id, geo_class id, user id, device id, outlet id
  parent_type      text NOT NULL DEFAULT 'global',         -- only for scope_type geo_class: 'Hill outlets inside wing 4'
  parent_id        bigint NOT NULL DEFAULT 0,
  value            jsonb NOT NULL,
  effective_from   timestamptz NOT NULL DEFAULT now(),     -- stored UTC, edited in Asia/Dhaka
  effective_to     timestamptz,                            -- NULL = open
  config_version   bigint NOT NULL REFERENCES cfg.config_version(version),   -- version that created the row
  superseded_in_version bigint,                            -- version that closed it (needed for known-version resolution, s2.3)
  change_request_id bigint NOT NULL REFERENCES cfg.config_change_request(id),
  set_by           bigint NOT NULL, set_at timestamptz NOT NULL DEFAULT now(),
  reason           text NOT NULL,
  CONSTRAINT geo_class_parent CHECK (parent_type = 'global' OR scope_type = 'geo_class'),
  EXCLUDE USING gist (key WITH =, scope_type WITH =, scope_id WITH =, parent_type WITH =, parent_id WITH =,
                      tstzrange(effective_from, effective_to, '[)') WITH &&));
CREATE INDEX ON cfg.config_value (key, scope_type, scope_id, effective_from DESC);
CREATE INDEX ON cfg.config_value (config_version);

-- One row per committed change set; the number is what headers, bundles, rows and acks carry.
CREATE TABLE cfg.config_version (
  version          bigint PRIMARY KEY,                     -- nextval('cfg.config_version_seq') inside the applying transaction
  change_request_id bigint NOT NULL,
  kind             text NOT NULL CHECK (kind IN ('change','revert','rollback','schedule_apply','expiry','content')),
  committed_at     timestamptz NOT NULL DEFAULT now(), committed_by bigint NOT NULL,
  summary          text NOT NULL,                          -- "cfg.geo.radius_m territory DHK-N: 100 -> 150"
  max_risk_class   smallint NOT NULL,
  devices_targeted int,                                    -- computed at commit; NULL for server-only keys
  is_revert_of     bigint REFERENCES cfg.config_version(version));
-- Which scope nodes a version touches: the index behind the scope-relevant header (s2.4).
CREATE TABLE cfg.config_version_scope (
  version bigint NOT NULL REFERENCES cfg.config_version(version),
  scope_type text NOT NULL, scope_id bigint NOT NULL, parent_type text NOT NULL DEFAULT 'global', parent_id bigint NOT NULL DEFAULT 0,
  relevant_zone_id bigint,                                 -- outlet and route changes are relevant to the zone that owns them
  PRIMARY KEY (version, scope_type, scope_id, parent_type, parent_id));
CREATE INDEX ON cfg.config_version_scope (scope_type, scope_id, version DESC);

-- Every change, including single-approver ones, is a request (a change set is atomic).
CREATE TABLE cfg.config_change_request (
  id               bigserial PRIMARY KEY,
  status           text NOT NULL CHECK (status IN ('proposal','draft','pending_approval','approved','scheduled','applied','rejected','cancelled','expired','reverted')),
  source           text NOT NULL CHECK (source IN ('console','api','bulk_upload','system_expiry','import')),
  changes          jsonb NOT NULL,                         -- [{type:'value'|'grant'|'risk_raise', key, scope_type, scope_id, parent_type, parent_id, old_value, new_value, effective_from, effective_to}]
  max_risk_class   smallint NOT NULL,                      -- after dynamic escalation (s7.2)
  blast_radius     jsonb NOT NULL,                         -- {"zones":..,"routes":..,"outlets":..,"devices":..,"users":..,"overrides_kept":..}
  whatif           jsonb,                                  -- geo keys: {"geo_valid_pct_before":..,"after":..,"newly_valid":..,"ambiguous_share":..}
  canary_of        bigint REFERENCES cfg.config_change_request(id),
  proposed_by      bigint,                                 -- TSO propose mode (D-94): an editor adopts the proposal
  requested_by     bigint NOT NULL, requested_at timestamptz NOT NULL DEFAULT now(), reason text NOT NULL,
  approved_by      bigint, approved_at timestamptz, approval_note text,
  rejected_by      bigint, rejected_at timestamptz, rejection_note text,
  break_glass      boolean NOT NULL DEFAULT false,
  review_due_at    timestamptz, reviewed_by bigint, reviewed_at timestamptz,
  apply_at         timestamptz,                            -- NULL on approval; C2 delayed apply = now() + cfg.sys.c2_delay_min
  applied_version  bigint REFERENCES cfg.config_version(version),
  expires_at       timestamptz NOT NULL,                   -- cfg.sys.request_expiry_h
  CONSTRAINT two_person CHECK (break_glass OR max_risk_class < 3 OR approved_by IS NULL OR approved_by <> requested_by));
-- Approver cooling, reporting-chain separation and the 5-per-hour C3 limit are enforced by trigger cfg.guard_apply() (s7).

-- Immutable audit. REVOKE UPDATE, DELETE; trigger-written from config_value, config_change_request, config_item.risk_class_raised and grants.
CREATE TABLE cfg.config_change_audit (
  id bigserial PRIMARY KEY, at timestamptz NOT NULL DEFAULT now(),
  actor_id bigint NOT NULL, actor_bundle text, actor_ip inet, actor_user_agent text,
  action text NOT NULL,   -- request_created request_adopted request_approved request_rejected request_cancelled applied reverted rollback break_glass_applied break_glass_reviewed
                          -- risk_raised grant_added grant_revoked flag_flipped switch_expired anomaly_alert otp_viewed
  change_request_id bigint, config_version bigint,
  key text, scope_type text, scope_id bigint, old_value jsonb, new_value jsonb, effective_from timestamptz, effective_to timestamptz,
  reason text, details jsonb,
  prev_hash bytea NOT NULL, row_hash bytea NOT NULL);       -- row_hash = sha256(prev_hash || canonical_json(row)); verified nightly (s7.8)
CREATE INDEX ON cfg.config_change_audit (key, at DESC);
CREATE INDEX ON cfg.config_change_audit (actor_id, at DESC);

-- Delivery tracking (the "pending until next sync" view).
CREATE TABLE cfg.config_ack (
  device_id bigint NOT NULL REFERENCES app.device(id), config_version bigint NOT NULL,
  acked_at timestamptz NOT NULL DEFAULT now(), app_version text, source text NOT NULL CHECK (source IN ('ack_row','header')),
  PRIMARY KEY (device_id, config_version));
ALTER TABLE app.device ADD COLUMN config_version bigint, ADD COLUMN config_acked_at timestamptz, ADD COLUMN wave_id bigint;   -- latest applied version, denormalised for the device list

-- Resolved snapshot per scope chain; a cache that is safe to truncate.
CREATE TABLE cfg.config_snapshot (
  chain_hash bytea NOT NULL, config_version bigint NOT NULL, as_of_date date NOT NULL,
  resolved jsonb NOT NULL, scheduled jsonb NOT NULL, built_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (chain_hash, config_version, as_of_date));

-- Authority (D-91): atomic permissions, named bundles, grants bounded by geography.
CREATE TABLE cfg.permission (permission text PRIMARY KEY, description text NOT NULL);
CREATE TABLE cfg.permission_bundle (bundle text NOT NULL, permission text NOT NULL REFERENCES cfg.permission, PRIMARY KEY (bundle, permission));
CREATE TABLE cfg.admin_grant (
  id bigserial PRIMARY KEY, user_id bigint NOT NULL, bundle text NOT NULL, domain text,      -- config_editor grants carry a domain: F, O or P
  scope_type text NOT NULL DEFAULT 'global', scope_id bigint NOT NULL DEFAULT 0,
  change_request_id bigint NOT NULL,                       -- a grant is a C3 change request (type 'grant')
  granted_at timestamptz NOT NULL DEFAULT now(), valid_to timestamptz, revoked_at timestamptz);

-- Read-only compatibility view for the importer and legacy paths (db/schema.sql territory_geo_config).
CREATE VIEW cfg.territory_geo_config AS
  SELECT t.id AS territory_id,
         (SELECT (r.value #>> '{}')::int FROM cfg.resolve(ARRAY['cfg.geo.radius_m'],
            jsonb_build_array(jsonb_build_object('t','global','i',0), jsonb_build_object('t','territory','i',t.id)), now(), NULL) r) AS radius_m
  FROM app.territory t;
```

Field rules (each is enforced by constraint or trigger, gate T-0-61, T-0-62, T-0-64):

| Rule | Why |
| --- | --- |
| `scope_id = 0` for global; `scope_type = 'role'` uses the id of `app.role_ref` (doc 16, ASSUMPTION: roles become a code table beside the enum); `wave` references `app.rollout_wave.id` (s9.3) | One shape for every scope and no NULLs inside the exclusion constraint |
| A change never UPDATEs `value`. It sets `effective_to` and `superseded_in_version` on the open row and INSERTs the new row, in one transaction, under one `config_version` | "What was the radius for outlet X at 11:04 on 2026-10-04" is one range lookup; the server re-check depends on it |
| `effective_from` is `timestamptz`. A key with `future_dated_only` must land on a Dhaka midnight by trigger (rounding mode, quantity unit, numbering, periods, business-date cutoff) | A time-of-day key such as check-out time must change at a wall-clock instant; a business-date key must never split a day |
| The version number is taken inside the applying transaction and committed with the rows; `pg_notify` is not used through PgBouncer (D-137): the API publishes `cfg:ver` on Redis after commit | Readers can never see a version whose rows are not visible |
| `config_change_audit` is append-only (INSERT only for the API role), trigger-written, hash-chained and exported daily to the WORM container (D-113) | The audit cannot be bypassed by a code path that forgets to log |
| Content-type keys hold only `{ "content_version": n }` in `config_value`; the content table carries the SCD columns and its write path calls `cfg.bump_version(key, scope, summary)` | One version stream and one history page for settings and content |

### 2.2 Scope levels and the scope chain

Most specific wins. Precedence, highest first (D-87; user and device added by D-430):

| Level | Precedence | Id refers to | Which keys may hold it |
| --- | --- | --- | --- |
| device | 120 | `app.device` | `cfg.flag.*` only (D-430) |
| device_model | 115 | `app.device_model` (normalised manufacturer plus model from `device_info` at bind, for example `xiaomi/redmi-9a`) | ONLY the keys listed in the read-only `cfg.sys.device_model_scoped_keys`: `cfg.sync.periodic_min`, `cfg.geo.fix_timeout_s`, `cfg.media.photo_max_kb`, `cfg.media.long_edge_px`, `cfg.sync.wakelock_max_s`, `cfg.sync.upload_on_metered`, `cfg.media.jpeg_quality` (D-598) |
| user | 110 | `app.app_user` | `cfg.flag.*` and `cfg.app.default_locale` only (D-430) |
| outlet | 100 | `app.outlet` | outlet-shaped keys: geo, credit, promo |
| route | 90 | `app.route` | route-shaped keys: day exceptions, calendar overrides |
| zone | 80 | `app.zone` | most keys |
| geo_class | 70 | `app.geo_classification` (Hill, Urban, SemiUrban, Rural; and "none") with an optional parent geography | outlet-shaped keys: `cfg.geo.radius_m`, `max_accuracy_m`, `fix_timeout_s` |
| house | 60 | `app.house` | few keys |
| territory | 50 | `app.territory` | most keys |
| division | 40 | `app.division` | most keys |
| wing | 30 | `app.wing` | most keys |
| wave | 20 | `app.rollout_wave` | flags, release, sync, media, auth OTP lifetime on a wave day |
| role | 10 | `app.role_ref` | per-role keys (password length, locale, PII fields, bundle fields) |
| global | 0 | none | all keys |

Rules that follow from D-87:

| # | Rule |
| --- | --- |
| 1 | A geography beats a role, a role beats global. A per-role value inside one territory is a json value keyed by role at the territory scope, never a cross-product scope |
| 2 | `geo_class` is an outlet attribute, so it applies to outlet-shaped keys only. A `geo_class` row may name a parent (global, wing, division, territory, house): "Hill outlets inside wing 4" is `(geo_class=Hill, parent=wing:4)`. Among `geo_class` rows the more specific parent wins. A zone-level value beats every `geo_class` row |
| 3 | The chain is derived on the server and never sent by the client (CLAUDE.md rule 4, D-106) |
| 4 | User context (login, bundle, home, delta): global, role, wave of the device, then wing to zone of each route assigned for the business date, then route, user, device. Outlet context (geo re-check, credit limit, promo): global, role and wave of the capturing user, the outlet's wing to zone and route as of the business date (`dim_outlet` history, because outlets move), its `geo_class` rows for each ancestor, then outlet. A visit uses the outlet chain for outlet-shaped keys and the user chain for the rest |
| 5 | `config_item.scope_levels` already excludes nonsensical combinations: no outlet level on `cfg.auth.*`, no user level on `cfg.geo.*` |
| 6 | Resolved provenance travels with the value `{value, scope_type, scope_id, parent, effective_from, config_version}`; the GUI shows "100 m, from Territory DHK-N (R. Karim, 2026-09-30: dense market)" and the app stores the value used |
| 7 | SHADOWING (D-512, G-qa-35). Because `geo_class` (70) outranks house (60), territory (50), division (40) and wing (30), an edit of the radius at one of those scopes changes NOTHING for every outlet whose class has a `geo_class` row that applies there (after the calibration of s6.4 that is up to 81 percent of outlets; the other 19 percent have class `none` and do follow the territory value). The console therefore computes and shows EFFECT, not target: `outlets_changed` (outlets whose resolved value will actually differ), `outlets_shadowed_by_geo_class` with the row that shadows them, and `overrides_kept` (s7.4); the edit drawer offers "also update the geo_class row X for this scope" in the same change set; and the approval request and the reach view carry the same three counts |
| 8 | DEVICE MODEL (D-598, G-qa-137). The fleet is unknown until the census (RK-01, D-11), so one OEM model with a camera, Bluetooth, WorkManager or storage problem could otherwise be handled only globally, per wave or per single device row. A `device_model` row beats geography for the seven hardware-bound keys only (a model limit belongs to the phone, not to the territory) and sits below the single-device row. It inherits every budget-impact rail of s2.6: the preview names the models, devices and users affected with the budget-impact line, a value above the key default is C3, and the list of allowed keys is migration-only. The scope chain gains the tuple `{"t":"device_model","i":<id>}` (19 tuples at most). Gate T-2-156 (extended) runs the perf protocol at the ceiling of each key for the smallest-battery model of the census (D-564) |

### 2.3 Resolution query and the as-of rule

```sql
-- $1 key[] (NULL = all) ; $2 chain jsonb [{"t":"global","i":0},{"t":"geo_class","i":3,"pt":"wing","pi":4},{"t":"zone","i":5012},{"t":"outlet","i":9912}]
-- $3 instant (trusted capture time or now()) ; $4 known_version (NULL = everything committed; otherwise only what a device at that version could know)
WITH chain AS (
  SELECT c->>'t' AS scope_type, (c->>'i')::bigint AS scope_id,
         COALESCE(c->>'pt','global') AS parent_type, COALESCE((c->>'pi')::bigint,0) AS parent_id
  FROM jsonb_array_elements($2) c),
cand AS (
  SELECT v.key, v.value, v.scope_type, v.scope_id, v.parent_type, v.parent_id, v.effective_from, v.config_version,
         sl.precedence, pl.precedence AS parent_prec
  FROM cfg.config_value v
  JOIN chain USING (scope_type, scope_id, parent_type, parent_id)
  JOIN cfg.scope_level sl ON sl.scope_type = v.scope_type
  JOIN cfg.scope_level pl ON pl.scope_type = v.parent_type
  WHERE ($1 IS NULL OR v.key = ANY($1))
    AND v.effective_from <= $3
    AND ($4 IS NULL OR v.config_version <= $4)
    AND (v.effective_to IS NULL OR v.effective_to > $3
         OR ($4 IS NOT NULL AND v.superseded_in_version > $4)))     -- closed later than the device knew: still open for the device
SELECT i.key, COALESCE(c.value, i.default_value) AS value, COALESCE(c.scope_type,'default') AS scope_type,
       c.scope_id, c.parent_type, c.parent_id, c.effective_from, c.config_version
FROM cfg.config_item i
LEFT JOIN LATERAL (SELECT * FROM cand WHERE cand.key = i.key ORDER BY precedence DESC, parent_prec DESC LIMIT 1) c ON true
WHERE ($1 IS NULL OR i.key = ANY($1)) AND i.deprecated_at IS NULL;
```

`cfg.resolve(keys, chain, at, known_version)` wraps this query; typed wrappers `resolve_int`, `resolve_json` serve the ingest transaction. Cost: a chain has at most 18 tuples (global, role, wave, wing to house, five geo_class parents, zone, up to two routes, outlet, user, device), `config_value` holds a few thousand rows (600 keys, at most 1,051 zone rows per key, an outlet-override cap of 50 per zone), and every probe is an index range scan. Target below 2 ms for all keys and below 0.3 ms for one key (T-1-62).

As-of rules (which instant and which version decide a value):

| Key shape | Examples | Server resolves at | Reason |
| --- | --- | --- | --- |
| Instant-shaped | radius, accuracy, mock policy, check-out time | the trusted capture instant of the row (D-20), capped at the device's config version (D-431) | The rule in force when the rep acted |
| Business-date-shaped (`future_dated_only`) | rounding mode, quantity unit, memo number format, loyalty period | 00:00 Dhaka of the row's `business_date` | One rule per business day; a day is never split |
| Rule-set stamped on the row | price list, promotion rules, rounding mode used | the version stamped on the memo (`price_list_version`, `promo_rule_version`, `rounding_mode_used`) | Disputes replay the exact rule set |
| Server-only, no row | thresholds, alerts, retention | now | No captured row depends on them |

**D-431, device-known value with a tolerance window (closes G-cfg-05; amended by D-519, G-qa-43).** Each captured row stores the device's applied `config_version` and the resolved value it used (`radius_m_used`). On sync the server computes `known = cfg.resolve(key, chain, capture_instant, row.config_version)` and finds the SUPERSEDING CHANGE: the first committed version `V_n` above `row.config_version` in this chain whose change moves the key's resolved value, with `t_n = effective_from(V_n)` (or its commit time, whichever is later). The measure that decides is how long the device COULD have known about the change: `knowable_for = capture_instant - t_n`. Rules:

| Case | Verdict value | Flag |
| --- | --- | --- |
| No superseding change before `capture_instant` (the device's value is the current one) | the as-of value (which equals `known`) | none |
| A superseding change exists, the stamped value equals `known`, and `knowable_for <= cfg.sys.config_accept_window_h` (48 h, equal to `cfg.bundle.stale_max_days` of 2 days) | `known`: an honest phone that had not yet learned of the change is judged by what it held | none |
| A superseding change exists, the stamped value equals `known`, and `knowable_for > cfg.sys.config_accept_window_h` | the as-of value | `config_stale` |
| The stamped value is not what `row.config_version` contained | the as-of value | `config_value_mismatch` (a tamper signal for doc 21) |

The first draft measured `capture_instant - committed_at(row.config_version)`, the age of the device's OWN version. For an honest phone that last synced config a month ago that is a month-old timestamp, so every capture fell outside the window and was judged under the new value and flagged `config_stale`, which defeated the purpose of D-431. The new measure ignores the age of the device's version: only the time since the change it missed counts. A device cannot gain by claiming an older version beyond 48 h after a change, and cannot gain by inventing a value. The rule is symmetrical: for a tightening the honest phone keeps the looser value for at most 48 h after the change; for a loosening a phone that missed it is judged by the tighter value it held (consistent with what its own gate decided), and after 48 h the server re-check uses the looser as-of value, so a device-side force sale may become a server-side geo-valid visit with `geo_mismatch` flagged, the force-sale photo staying as evidence. If the key changed twice after `row.config_version`, the FIRST change after it starts the clock (the conservative reading).

**Worked example.** Radius 100 m at territory T. A phone last synced config at version 9,100 (a month ago, value 100). At 11:00 an admin sets 60 m (effective 11:00, version 9,402). The phone, offline all month, captures at 11:20 at 80 m with stamped version 9,100 and `radius_m_used = 100`. The superseding change is 9,402 at 11:00; `knowable_for` is 20 minutes, inside 48 h, `known` is 100 m: verdict geo-valid, no flag. The same phone, still offline, captures 5 days later at 80 m: `knowable_for` is 5 days, beyond 48 h: the as-of 60 m applies, force sale, flag `config_stale`. A phone online captures at 11:20 with version 9,402 and 80 m: 60 m applies. A phone that stamps 9,100 but `radius_m_used = 150`: `config_value_mismatch`, 60 m applies. Fixtures of T-1-63: (a) a visit stamped with a 30-day-old version whose value changed 2 hours earlier: accepted under the old value; (b) the same stamp with the change 5 days earlier: judged under the new value, `config_stale`; (c) radius TIGHTENED 100 to 60 and (d) radius LOOSENED 60 to 150, each with the change 2 hours and 5 days earlier; (e) two changes after the stamped version; (f) a lying stamp.

### 2.3b Stamp regress and the no-grace tightening (D-571, G-qa-107)

D-431 trusts the `config_version` the device stamps on a row. A modified client can keep stamping a version from before a radius tightening and keep the old, wider radius for the whole 48 h window, and an emergency tighten has the same grace. The exposure is bounded (the old radius was acceptable until the change) but it is exactly the actor the geofence is meant to stop, so the server also compares the stamp with what it KNOWS it delivered.

| Element | Rule |
| --- | --- |
| Delivery record | `app.device_config_seen(device_id, relevant_version, first_served_at)`, one row per device and relevant version, written when the server first sends that version to the device (response header `X-Config-Version`, a delta body or a bundle) and, with a lower weight, when the device first reports it in a request header. Rows older than `cfg.sys.config_delta_max_age_versions` versions are purged nightly with the L2 snapshots |
| Test | For a row with stamp `s` captured at trusted time `c`: `served(c)` is the highest version whose `first_served_at <= c - cfg.sys.config_apply_grace_min` (10 minutes, enough for the one-transaction apply of s4.2). If `s < served(c)` the row is `config_stamp_regress` |
| Verdict | The tolerance window of D-431 does NOT apply. The server judges the row under `cfg.resolve(key, chain, c)`, the value in force at capture time, sets `geo_mismatch` or the matching flag as usual, flags the row `config_stamp_regress` and counts it. A device with `cfg.sec.fraud.stamp_regress_min_rows` (3) such rows in a business day raises signal FS-34 (doc 21 s7.7) to the AMO and TSO exceptions list. An honest phone cannot trigger it: it has the version it was served before the capture, or it was offline and was never served it, in which case there is no delivery record and D-431 applies unchanged. A restored backup or a reinstall can produce a benign regress; the three-row threshold and the device-restore marker (doc 17 s4.12) keep that out of the supervisor list |
| No-grace tightening | A C3 change set that TIGHTENS a geo, fraud or auth key may carry `no_grace = true` (allowed while `cfg.sys.no_grace_tighten_allowed` is true; an emergency tighten by break-glass sets it by default and needs the after-the-fact approver of s7.6). For such a set the server judges every row captured after `effective_from` under the new value whatever the stamp, flagged `config_stale_strict` (not `config_stale`, and not a tamper signal, because an honest offline phone is judged strictly by design). The blast-radius preview prints how many devices with pending rows have not yet received the version ("n devices will be judged strictly"), so the approver sees the price of the choice |
| Gate | T-2-162 (extends the T-1-63 fixtures: a stamp 10 days old on a phone served the new version 3 hours before the capture; the same on a phone never served it; a no-grace tighten with an offline honest phone) |

### 2.4 Versions, scope relevance and device visibility

`X-Config-Version` is the highest version that touched any node of the caller's scope chain (D-446). A change to one zone therefore changes the header only for the devices of that zone, so a zone change does not trigger 8,500 empty delta pulls.

| Step | Definition |
| --- | --- |
| Relevant version of a chain | `SELECT max(version) FROM cfg.config_version_scope s JOIN chain USING (scope_type, scope_id, parent_type, parent_id)`; outlet and route rows count for their `relevant_zone_id`; cached per `(chain_hash, current_version)` in the L0 cache |
| Device state | `app.device.config_version` is the last applied relevant version; `config_acked_at` is when |
| "Behind by n" | count of `config_version_scope` rows relevant to the device's chain with version greater than `device.config_version` |
| Stamping | The device stamps its applied relevant version on `visit`, `memo`, `attendance`, `due_collection` and `redemption` rows, with the resolved values listed in `config_item.stamp_on` (`radius_m_used`, `mock_policy_used`, `rounding_mode_used`, `price_list_version`, `promo_rule_version`; columns are doc 16 s5) |
| Wire | Request header `X-Config-Version` carries the device's applied version; the response header carries the relevant version (doc 17 s4); the batch repeats it |

### 2.5 Server caches

| Layer | Holds | Invalidation | Why |
| --- | --- | --- | --- |
| L0 in-process | `current_version`; LRU of resolved snapshots by `(chain_hash, version, as_of_date)`, 10,000 entries (about 20 MB); relevant-version map | Redis pub/sub `cfg:ver` sets `current_version`; a 30 s poll is the fallback. `LISTEN` is not used because PgBouncer transaction mode forbids it (D-137, G-sre-15) | 8,500 bundle requests an hour share about 1,402 node chains (1,051 zones, 291 territories, 50 divisions, 10 wings); hit rate above 99 percent |
| L1 Redis | `cfg:ver`, `cfg:snap:<chain_hash>:<version>:<date>` (TTL 36 h), `cfg:delta:<from>:<to>:<chain_hash>` (TTL 24 h) | Version in the key; no explicit invalidation | A freshly scaled replica serves snapshots without Postgres during the morning storm |
| L2 Postgres `cfg.config_snapshot` | Same, durable | Rows older than `current_version - cfg.sys.config_delta_max_age_versions` purged nightly | Redis is a cache; its loss at 07:00 must not become a Postgres stampede (per-chain mutex on rebuild) |
| Pre-built bundles | `config`, `scheduled` and `config_version` embedded in each pre-generated bundle (D-129) | A change after generation marks bundles of the affected scope stale; the API serves the pre-built bundle plus a delta, not a rebuild (D-100 coalesces) | An admin edit at 06:30 must not trigger regeneration of 8,500 bundles |

A future-dated value needs no event at the switch instant: snapshots are keyed by `as_of_date`, the `scheduled` list already holds the value, and device and server switch by comparing times.

### 2.6 Validation layers

| Layer | What it checks | Where |
| --- | --- | --- |
| 1 Shape | `value_type`, `json_schema`, enum, min and max, `max_items`, regex | `/packages/contract` Zod schemas (D-150) at the API, the same schemas on the device for delivered bounds |
| 2 Bounds in the database | `floor` and `ceiling` from `constraints` (immutable, changed only by migration) and `dynamic_min` and `dynamic_max` (the current value of a bound key such as `cfg.geo.radius_min_m`), by BEFORE INSERT trigger `cfg.check_bounds()` | PostgreSQL; the API cannot bypass it (T-0-61) |
| 3 Referential | `ref` constraints: `min_version` must be a published `app_release`; brand and SKU lists exist and are `sales_enable`; roles are valid | API and trigger |
| 4 Dependency rules | Named cross-key rules in code, listed read-only in `cfg.sys.dependency_rules`: `agg.late_data_recompute_days >= sync.max_backdate_days`; `day.checkout_latest_time > checkout_earliest_time`; `auth.refresh_ttl_days >= 7`; `media.photo_max_kb <= 300 when upload_network_policy = any`; `geo.radius_min_m < geo.radius_max_m`; `release.latest_version >= release.min_version`; `sync.retry_cap_s >= sync.retry_backoff_s` | API; shown as warnings or blocks in the edit drawer |
| 5 Device | A delivered value outside the bounds that ship with the key is refused; the device keeps the last good value (doc 17 s6.7) | App |

Budget-linked keys (D-546, G-qa-78). The R4 hard budgets (doc 17 s8: APK at most 30 MB per ABI, photos 150 KB, at most 15 bundle deltas a day, at most 80 GPS fixes and 15 GPS minutes, 1 MB a day without photos) are gates measured at DEFAULT values; a GUI-adjustable ceiling above a budget would let the console break R4 without a release. Each key below has its ceiling set at or below its budget, and any change that RAISES it above its default is C3 with a computed budget-impact line in the blast-radius preview; the perf protocol of doc 17 s8.8 is run at the ceiling of each key (T-2-156), and a dependency rule ties each to D-73.

| Key | Old ceiling | New ceiling (default) | Budget it protects | Budget-impact line shown at submit |
| --- | --- | --- | --- | --- |
| `cfg.release.apk_max_mb` | 45 | 30 (target 22) | APK at most 30 MB per ABI | none; the gate and the ceiling are the same number |
| `cfg.media.photo_max_kb` | 300 | 200 (150) | 150 KB photos, 3 MB a day with photos | extra mobile MB per day = 17.6 photos x (new - 150 KB) |
| `cfg.media.long_edge_px` | 1,600 | 1,280 (1,024) | photo bytes and CPU of 1.5 s per photo | photo bytes scale with the square of the edge |
| `cfg.bundle.delta_min_interval_min` | floor 5 | floor 30 (30) | at most 15 bundle deltas a day | deltas a day = 480 / interval |
| `cfg.geo.fix_timeout_s` | 60 | 30 (15); above 15 is C3 | at most 80 fixes and 15 GPS minutes | worst-case GPS minutes = 80 x timeout |
| `cfg.geo.refresh_max` | 10 | 5 (3) | at most 80 fixes | worst-case fixes per outlet |
| `cfg.geo.fix_accuracy_mode` = `high` | C2 | C3 | battery budget | high-accuracy fixes cost about 3 x the energy of balanced ones (ASSUMPTION, measured T-2-156) |
| `cfg.content.max_item_mb` | 50 | 20 (8) | 1 MB a day, 8,500 phones | fleet GB per item = 8,500 x size |
| `cfg.app.image_cache_mb` | 200 | 70 (40) | installed size at most 70 MB | cache plus installed size |

Required-element invariants (D-547, G-qa-79). Some C1 keys can silently stop day close for the whole fleet: hiding "Sales Submit" through `cfg.app.home_tiles`, hiding Final Submit through `cfg.app.drawer_items`, setting `cfg.day.sales_submit_offline_queue` false (every dead zone at 17:30 blocks day close), a `cfg.outlet.mobile_regex` that rejects every new outlet, and a `cfg.sale.max_lines_per_memo` below the observed maximum of 40 lines (docs/22 P-02). Each gets an invariant that the database enforces (`cfg.config_invariant(key, role, kind, spec)` and a BEFORE INSERT trigger, so the API cannot bypass it), or its class is raised where the database cannot enforce it:

| Key | Invariant | Enforced by | Class |
| --- | --- | --- | --- |
| `cfg.app.home_tiles` | per role the list MUST contain Attendance, Sale, Memo and Sales Submit (SR and AMO; the TSO home must keep its dashboard entry) | trigger on the required ids | C1 (invariant makes a fleet-wide block impossible) |
| `cfg.app.drawer_items` (TSO) | MUST contain Final Submit | trigger | C1 |
| `cfg.day.sales_submit_offline_queue` | cannot be enforced in the database (it is a behaviour, not a list) | n/a | raised from C1 to C2 |
| `cfg.outlet.mobile_regex` | must accept every positive and reject every negative of the stored test-vector set `cfg.outlet.mobile_regex_test_vectors` (at least 10 valid and 10 invalid numbers, including the 11-digit `01[3-9]` forms) | the API runs the vectors before the row is accepted; the trigger re-runs them | C2 |
| `cfg.sale.max_lines_per_memo` | floor 40 (was 20) | bounds trigger | C1 |

JSON Schema validation inside Postgres may be unavailable on Flexible Server (G-cfg-21, closed here): layers 1 and 3 live in the API; layer 2 is plain CHECK and trigger code with no extension beyond `btree_gist`; if `btree_gist` is not allow-listed the exclusion constraint is replaced by a serialisable unique-open-row check inside the apply function and T-0-62 adapts (G-19-01).

### 2.6b Dependency rules: the complete list of 14 (D-591, G-qa-130)

Layer 4 above named seven rules. The registry notes state at least five more "must exceed" relations, one of which protects idempotency: if `cfg.retention.ingest_registry_days` could be set to 30 with `cfg.sync.max_backdate_days` at 30, the dedupe registry would be pruned while rows that old were still accepted, which is the duplicate-sale path of CLAUDE.md rule 2. All 14 rules are enforced by the database (a BEFORE INSERT trigger calling `cfg.check_dependency()`, so the API cannot bypass them), shown read-only in the edit drawer, listed in `cfg.sys.dependency_rules`, and each has a test in T-0-61 and T-0-157. A rule is evaluated on the RESOLVED values at every scope the change touches, and on the values a scheduled change will resolve to at its effective date.

| # | Rule | Why |
| --- | --- | --- |
| 1 | `agg.late_data_recompute_days >= sync.max_backdate_days` | A late row must still be re-aggregated |
| 2 | `day.checkout_latest_time > day.checkout_earliest_time` | |
| 3 | `auth.refresh_ttl_days >= 7` | |
| 4 | `media.photo_max_kb <= 300` when `upload_network_policy = any` | |
| 5 | `geo.radius_min_m < geo.radius_max_m` | |
| 6 | `release.latest_version >= release.min_version` | |
| 7 | `sync.retry_cap_s >= sync.retry_backoff_s` | |
| 8 | `sys.config_accept_window_h >= bundle.stale_max_days x 24` (the clock counts working days, D-584) | Raising `stale_max_days` to 3 must not silently flip honest rows to `config_stale` |
| 9 | `retention.ingest_registry_days >= max(2 x sync.max_backdate_days + sync.resync_window_h / 24 + 1, sync.resync_late_max_days + 1)`, plus the longest declared break in `calendar.break_overrides` (ASSUMPTION: the factor 2 covers the weekly off-day and a typical break when the backdate window is counted in working days) | The dedupe registry must outlive every row the server still accepts and every re-send |
| 10 | `web.entry_backdate_days <= sync.max_backdate_days` | The web window may not be wider than the app window it mirrors |
| 11 | `sync.resync_window_h / 24 < retention.ingest_registry_days` | Implied by rule 9, kept explicit so the message names the key |
| 12 | `day.attendance_missing_checkout_autoclose_time > day.checkout_latest_time` and `day.final_submit_autoclose_time > day.checkout_latest_time` | An auto-close earlier than the latest honest check-out rejects honest late syncs |
| 13 | `auth.offline_unlock_max_days >= bundle.stale_max_days + 2` (working days) | An SR who can start the day on a stale bundle must still be able to unlock the phone |
| 14 | Every value of `calendar.break_overrides` is within `bundle.stale_max_cal_days_ceiling` (stale), `retention.ingest_registry_days / 2` (backdate) and 14 (offline unlock) | An override cannot defeat rules 8, 9 and 13 |

The edit drawer lists the rules that involve the key being edited with the current counterpart value and the margin; a violation returns `ERR_CFG_DEPENDENCY` naming both keys. Gate T-0-157.

### 2.7 Content and master-data changes on the same stream

| Change | Version bump | Delivery |
| --- | --- | --- |
| Reason codes, gift catalogue, survey questions, rubric, task and leave types, holidays, tutorial videos, support contacts, i18n overrides, print templates (content-type keys) | `cfg.bump_version(key, scope, summary)` from the content table's write path; kind `content` | Config delta carries `{key, content_version}`; the rows ride the bundle delta (`?since=`), one request. Codes are immutable; a business edit deactivates, never deletes (G-cfg-10, G-cfg-11) |
| Price list and promotion set publication | A publish bumps the marker keys `cfg.price.list_version` and `cfg.promo.rules` | The reach view shows how many devices hold the new price list (G-cfg-14); memos store `price_list_version` |
| Outlet coordinates, sales plan, routes, assignments, targets, offers | Not a config version (thousands of changes a day) | Bundle delta by `updated_since` |
| User scope or role change | Bumps `app_user.scope_version`; a token with an older version gets 401 `scope_changed` and refreshes silently (D-101); bumps coalesce per user per 5 minutes (D-100) | next request |

Proved by: T-0-60, T-0-61, T-0-62, T-0-63, T-0-64, T-1-60, T-1-62, T-1-63, T-2-69.

## 3 Key registry

The registry is the list of every operating parameter the spec, the four manuals, the screenshots, the data profile and the critics hard-code. It reconciles the lens catalogue (241 rows), the manual register s2.5 (173 rows, whose unified names are canonical, D-92), the fraud critic s4.4, the SRE critic s5, the field critic s11.1 and the keys that documents 14, 15, 17 and 18 mint. A key that appears in two sources appears once, with the value the skeleton or the owning document fixed. Phase of every key is the sub-milestone in which code first reads it; the key is registered in 0b regardless (T-0-60).

### 3.1 How to read the tables

| Column | Meaning |
| --- | --- |
| Key | `cfg.<area>.<name>`, lower snake case, units in the suffix (`_s`, `_min`, `_m`, `_kb`, `_mtk`, `_pct`, `_days`, `_h`). A family row ends in `.*` or `.<surface>` and states how many keys it holds |
| K | S setting (value in `config_value`); T time-shaped (a time of day, window or date set the device compares with trusted time, delivered in the `scheduled` list); O operational switch (mandatory duration, auto-expires). Content-type keys are kind S with type `content(<table>)` |
| Type | `int`, `bool`, `time` (HH:MM Asia/Dhaka), `pct`, `money_mtk`, `text`, `url`, `semver`, `enum(..)`, `list<..>`, `json`, `content(<table>)` |
| Default | The recommended value. Where the manual shows different behaviour the cell reads `P: <parity> / R: <recommended>`. A value the spec and the manuals do not give is marked A (ASSUMPTION; the reason is the Note) or "unknown; confirm" with the proceed-with default |
| Bounds | Static hard bounds (`floor`, `ceiling`) unless written `dyn:` (the bound is another key). The API and the database both enforce them (s2.6) |
| Scope | Levels that may hold an override: G global, ROLE, WAVE, W wing, D division, T territory, H house, GC geo_class, Z zone, R route, O outlet, U user, DEV device. Precedence is s2.2 |
| Ed | Editor domain: F field operations, O ops, P programme, S security, R release, FIN finance. `+TSO` means the TSO may propose or edit inside their own scope (bounded by `cfg.geo.tso_radius_mode` where geo); `+DMO` the DMO proposes in own scope |
| Eff | Takes effect: B on the next request, delta or bundle (server keys on commit); S next session or new business day; R next release. `+P` also pushed by FCM when `cfg.ops.push_enabled` (D-09). `*` the device returns a `config_ack` (D-446). Keys marked FD are future-dated only (effective on a Dhaka midnight) |
| Dl | srv evaluated on the server only; dev held on the device only; both in `ref_config` and evaluated on both |
| Cls | Risk class C0 to C3 (s7.1). `Ca>Cb`: class Ca at territory scope and narrower, class Cb at division scope and wider. `C1/2/3` is used for the radius only (outlet, zone-to-territory, division-to-global). Dynamic escalation (s7.2) can raise a single change above its key class |
| Ref | Decision, gap, question or manual reference that owns the row |
| Ph | Sub-milestone in which the key is first consumed |

All keys are edited ONLINE-ONLY in the console. Keys with Dl `dev` or `both` are CACHED on the device and apply OFFLINE (resolved snapshot plus scheduled values, doc 17 s6.7). Gate for every row of s3: T-0-60 (registry completeness), T-0-61 (bounds), T-0-63 (no unregistered literal in `/api` or `/app`).

Restrictive direction (D-432) is registered for every key where a direction exists and is listed in s7.5; keys not listed there have none.

### 3.2 Registry by area

#### 3.2.1 Geofence and location: `cfg.geo.*`

| Key | K | Type | Default | Bounds | Scope | Ed | Eff | Dl | Cls | Note and risk if mis-set | Ref | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.geo.radius_m` | S | int | 100 | `dyn: radius_min_m .. radius_max_m` | G W D T GC Z O | F+TSO | B* | both | C1/2/3 | C1 at O, C2 at Z, GC, H and T, C3 at D, W and G. Any increase that ends above `radius_increase_escalation_m` is C3 (D-94). An outlet value above `outlet_override_max_ratio` times the resolved zone value needs an approver. Too small: every sale becomes a force sale; too large: the gate proves "in this market", not "at this shop" (docs/22 P-10). Stamped on visit as `radius_m_used` | D-93, D-94; G-cfg-03, G-cfg-23 | 1c |
| `cfg.geo.radius_min_m` | S | int | 20 (A: GPS noise floor on cheap phones) | 10 to 100 | G | O | B | srv | C3 | The lower bound rail; too loose makes the rail useless | D-93 | 1c |
| `cfg.geo.radius_max_m` | S | int | 2000 (A: a rural haat) | 500 to 5000 | G | O | B | srv | C3 | The upper bound rail | D-93 | 1c |
| `cfg.geo.radius_increase_escalation_m` | S | int | 150 | 50 to 500 | G | S | B | srv | C3 | A radius change that increases the resolved value and ends above this is raised to C3 whatever the scope or editor. The fraud critic proposed "by more than 50 m or 1.5 times"; the skeleton value wins (OI-19-04) | D-94; G-fraud-09 | 2d |
| `cfg.geo.tso_radius_mode` | S | enum(propose, apply) | propose | enum | G W | S | B | srv | C3 | propose: a TSO edit becomes a proposal an F editor adopts; apply: the TSO edits T, Z and O inside bounds | D-94; Q24 | 2d |
| `cfg.geo.outlet_override_max_ratio` | S | float | 3.0 | 1.0 to 10.0 | G | S | B | srv | C3 | An outlet value above this multiple of the resolved zone value needs an approver | D-93 | 2d |
| `cfg.geo.outlet_override_max_per_zone` | S | int | 50 (A: keeps the sparse map at 400 B) | 10 to 200 | G Z | F | B | srv | C2 | Caps outlet overrides per zone so a zone-wide AMO bundle stays small | G-cfg-13 | 2d |
| `cfg.geo.no_location_policy` | S | enum(force_sale_required, allow_unvalidated, block) | force_sale_required | enum | G W T Z | F | B* | both | C3 | `block` strands the 1,093 outlets without coordinates and the 34,454 on shared pins (docs/22 P-09). restrictive: enum order | D-95; Q7 | 2d |
| `cfg.geo.placeholder_min_shared` (RETIRED, D-525: use `cfg.outlet.placeholder_pin_min_shared`) | S | int | 3 (A: two shops in one building are legitimate) | 2 to 20 | G | F | S | srv | C1 | Outlets sharing one 6-decimal point at or above this count are marked `location_confirmed = false` by the importer and DQ-48. Added at the editorial merge from doc 16 s4 | D-253; docs/22 P-09 | 7a |
| `cfg.geo.first_capture_sets_location` | S | bool | P: true (photo overwrites) / R: true only when the stored location is missing or a placeholder (`location_confirmed` false), otherwise a location-change request and a provisional flagged location | n/a | G T | F | B | both | C2 | DELIBERATE CHANGE | D-95, D-163; G-man-017 | 2d |
| `cfg.geo.outlet_location_change_approval` | S | enum(amo_then_web, amo_only, auto) | amo_then_web | enum | G W | F | B | srv | C3 | `amo_only` and `auto` let one field role move an outlet; the fraud critic asks for removal (OI-19-05) | D-163; G-fraud-06 | 2d |
| `cfg.geo.update_base_requires_photo` | S | bool | true | n/a | G | F | B | both | C1 | docs/07 Update Base | docs/07 | 2d |
| `cfg.geo.update_base_max_distance_m` | S | int | 100 | 20 to 500 | G T | F | B | both | C2 | The AMO's own fix must be within this distance of the chosen point | D-95; G-man-018 | 2d |
| `cfg.geo.update_base_max_move_m` | S | int | 1000 (A) | 100 to 5000 | G | F | B | both | C2 | Hard cap on one move; moves above `cfg.sec.fraud.location_move_alert_m` also need TSO approval | D-111 | 2d |
| `cfg.geo.update_base_max_per_outlet_month` | S | int | 1 (A) | 1 to 5 | G | F | B | srv | C2 | Monthly cap per outlet (register R-32) | G-man-018 | 2d |
| `cfg.geo.override_max_per_day` | S | int | 10 per AMO (A) | 1 to 100 | G | F | B | both | C1 | Manual Override presses per AMO per day | G-man-017 | 2d |
| `cfg.geo.fix_timeout_s` | S | int | 15 | 5 to 30 (D-546); above 15 is C3 | G T Z GC | O | B | dev | C1 | Doc 17 value (the lens had 20). Too short: no fix, force sales; too long: battery and waiting reps | D-74 | 1a |
| `cfg.geo.fix_accuracy_mode` | S | enum(balanced, high) | balanced | enum | G T Z | O | B | dev | C3 for `high` (D-546, budget-impact line) | `high` everywhere breaks the battery budget | CLAUDE.md 3 | 1a |
| `cfg.geo.max_accuracy_m` | S | int | 100 | 30 to 300 | G GC T Z | F | B | both | C2 | A fix with an accuracy radius above this cannot be geo-valid. The lens had 150 | D-264 | 1c |
| `cfg.geo.accuracy_tolerant` | S | bool | false | n/a | G | F | B | both | C2 | true subtracts the accuracy from the distance and loosens the gate | D-264 | 1c |
| `cfg.geo.refresh_max` | S | int | 3 | 1 to 5 (D-546) | G | F | B | dev | C1 | Refresh presses offered before Force Sale is the only way | G-man-029 | 1a |
| `cfg.geo.require_precise` | S | bool | true | n/a | G | O | B | dev | C2 | Android Precise location is required; Approximate cannot validate | D-74; G-man-020 | 1a |
| `cfg.geo.fix_reuse_max_age_s` | S | int | 60 | 0 to 300 | G | O | B | dev | C1 | A fix up to this age and 30 m away may be reused; `getLastKnownLocation` is never used for a verdict | D-74; G-field-13 | 1a |
| `cfg.geo.mock_policy` | S | enum(silent_flag, warn_rep, block_sale) | warn_rep | enum | G W D T Z | F | B* | both | C3 | `block_sale` with a false-positive detector stops selling. "A mocked fix is never geo-valid" holds under every value (D-96). restrictive: enum order silent_flag < warn_rep < block_sale | D-96; G-fraud-18 | 1c |
| `cfg.geo.mock_block_message_key` | S | text | `geo.mock_blocked` | key exists in the i18n bundle | G | O | B | dev | C0 | Message shown under `block_sale` | D-96 | 2d |
| `cfg.geo.max_speed_kmh` | S | int | 60 (A: urban beat, motorbike plausible to 60) | floor 30, ceiling 150 | G W T Z | S | B | srv | C2 | Plausibility (FS-08). Too low flags honest riders; too high misses teleports. Owned like a fraud threshold (D-267) | D-109 | 2d |
| `cfg.geo.teleport_min_distance_m` | S | int | 500 (A) | 100 to 5000 | G T | S | B | srv | C2 | Pairs with speed so two fixes 30 m apart never flag | D-109 | 2d |
| `cfg.geo.min_fixes_for_jitter` | S | int | 8 (A) | 3 to 50 | G | S | B | srv | C2 | Fewer fixes make the zero-jitter rule fire on short routes | D-109 | 2d |
| `cfg.geo.jitter_threshold_m` | S | int | 2 (A: real GPS never repeats to under 2 m across a route) | floor 1, ceiling 20 | G | S | B | srv | C2 | 0 would disable the rule, so the floor is 1 | D-109 | 2d |
| `cfg.geo.perfect_accuracy_threshold_m` | S | int | 3 (A) | 1 to 10 | G | S | B | srv | C2 | "Impossibly perfect accuracy" | D-109 | 2d |
| `cfg.geo.same_point_outlets_max` | S | int | 3 (A: four or more outlets "visited" from one coordinate flag the route) | 2 to 20 | G T | S | B | srv | C2 | The 34,454 placeholder-pin outlets are excluded by `location_confirmed` | D-109; docs/22 P-09 | 2d |
| `cfg.geo.gps_time_skew_max_s` | S | int | 60 | 5 to 600 | G | S | B | srv | C2 | GNSS time against device time (DQ-36) | G-fraud-01 | 2d |
| `cfg.geo.integrity_weight` | S | json | {mock 100, rooted 20, dev_options 10, play_integrity_fail 30, teleport 40, zero_jitter 40, same_point 30, radio_mismatch 40, radio_absent 10, stale_fix 20, synthetic_fix 30} (A) | each 0 to 100 | G | S | B | srv | C2 | Weights of the supervisor-visible suspicious score. Only the mock warning is shown to the SR (D-123) | D-109, D-123 | 2d |
| `cfg.geo.suspicious_score_threshold` | S | int | 50 (A) | 1 to 300 | G W T | S | B | srv | C2 | Too low drowns supervisors in flags | D-109 | 2d |
| `cfg.geo.play_integrity_enabled` | S | bool | false (A: needs Google Play services, unverified on the fleet) | n/a | G | S | S | both | C2 | true on devices without Play adds login friction | G-sec-21 | 2d |
| `cfg.geo.rooted_policy` | S | enum(ignore, flag, block_login) | flag | enum | G | S | S | both | C3 | `block_login` with unknown root prevalence is a lockout. restrictive: enum order | docs/05 | 2d |
| `cfg.geo.radio_env_enabled` | S | bool | false until the 2d privacy sign-off, then true | n/a | G | S | B | dev | C2 | Passive cell and Wi-Fi BSSID-hash capture with the fix; battery cost zero | D-110; G-fraud-01 | 2d |
| `cfg.geo.density_neighbour_radii_m` | S | list<int> | [25, 50, 100, 150, 300] (A) | 1 to 8 values, 10 to 1000 | G | O | B | srv | C1 | Radii precomputed per outlet for the density view (s6.3) | D-254 | 2d |
| `cfg.geo.calibration_min_visits` | S | int | 500 (A) | 100 to 5000 | G | F | B | srv | C1 | Minimum visits in a geo_class by territory cell before a suggested radius is shown | D-441 | 2d |
| `cfg.geo.calibration_target_valid_pct` | S | pct | 95 (A) | 80 to 99 | G | F | B | srv | C1 | Share of honest visits a suggested radius must cover | D-441 | 2d |
| `cfg.geo.density_warn_pct` | S | pct | 70 (A) | 30 to 100 | G | F | B | srv | C0 | Density index at which P2 warns that the gate proves the market, not the shop | D-254 | 2d |
| `cfg.geo.outlet_override_review_days` | S | int | 90 (A) | 30 to 365 | G | F | B | srv | C0 | Outlet overrides older than this are listed for review | D-442 | 2d |

#### 3.2.2 Day cycle, submit and final submit: `cfg.day.*`

| Key | K | Type | Default | Bounds | Scope | Ed | Eff | Dl | Cls | Note and risk if mis-set | Ref | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.day.checkout_earliest_time` | T | time | 17:00, inclusive (05:00 PM accepted) | 12:00 to 22:00 | G ROLE W D T Z | F | B* | both | C2>C3 | The device enforces it offline from the scheduled value; the server re-checks. 07:00 lets reps check out at breakfast; 23:00 means nobody closes the day. The AMO and TSO may differ by ROLE scope (D-225). restrictive: up. Stamped on attendance | D-209, D-225; G-man-029 | 1c |
| `cfg.day.checkout_latest_time` | T | time | 23:59 (A) | later than earliest | G W | F | B | both | C2 | Earlier than the evening sync tail rejects honest late syncs | docs/04 | 2e |
| `cfg.day.checkin_earliest_time` | T | time | 05:00 (A) | 00:00 to 12:00 | G W | F | B | both | C1 | | docs/06 | 1c |
| `cfg.day.checkin_gate` | S | enum(off, soft, hard) | soft | enum | G W | F | B | both | C2 | soft prompts and never blocks a sale; hard blocks Stock and Sale until check-in (unknown; confirm: Q-UI-04). Replaces the screenshot key `require_checkin_before_sale` | D-329; G-man-029 | 2e |
| `cfg.day.business_date_cutoff_time` | T | time | 00:00 (A) | 00:00 to 06:00 | G | O | S | srv | C3 | FD. Changing it mid-month splits a day in every aggregate. unknown; confirm: Q29 | D-20 | 0b |
| `cfg.day.month_close_grace_days` | S | int | 3 | 0 to 10 | G | S | B | srv | C3 | Rows on untrusted time claiming a month closed longer ago are parked (DQ-40) | G-fraud-02 | 1b |
| `cfg.day.sales_submit_dues_warning` | S | enum(off, warn, block) | warn | enum | G W T | F | B | both | C2 | `block` stops closing a day that has legitimate credit sales. The dialog shows the count of retailers with a due | D-173; G-man-030 | 2e |
| `cfg.day.sales_submit_requires_checkout` | S | bool | false (A: spec order is submit then check-out) | n/a | G | F | B | both | C1 | | docs/06 | 2e |
| `cfg.day.sales_submit_offline_queue` | S | bool | true | n/a | G | O | B | both | C2 (D-547: cannot be enforced by an invariant) | false: a dead zone at 17:30 blocks the day close | D-64; G-man-031 | 1b |
| `cfg.day.submit_settle_timeout_min` | S | int | 30 | 5 to 240 | G | O | B | srv | C2 | The server defers `sales_submitted` until its totals reach the device counts | D-64 | 1b |
| `cfg.day.submit_grace_h` | S | int | 10 | 0 to 24 | G | O | B | both | C1 | Hours after midnight during which yesterday's Sales Submit is still allowed | D-388 (doc 17) | 2e |
| `cfg.day.final_submit_confirm` | S | bool | true | n/a | G | F | B | dev | C1 | IMPROVEMENT: an explicit confirm before the irreversible submit; the manual shows none | D-198 | 3b |
| `cfg.day.final_submit_allow_not_set_routes` | S | enum(allow, warn, block) | allow | enum | G W | F | B | srv | C2 | "SR Not Set" is a normal state of an AMO route and does not block (PARITY; the lens default was warn) | G-man-071 | 3b |
| `cfg.day.final_submit_earliest_time` | T | time | none | 12:00 to 23:59 or none | G W | F | B | srv | C2 | No time gate is evidenced (the 14:26:05 sample is a Not Done row). unknown; confirm: MQ-41 | D-198 | 3b |
| `cfg.day.final_submit_requires_dss_ack` | S | bool | false | n/a | G | F | B | srv | C1 | Requires ticking the DSS-report advisory on the web Final Submit page | G-man-086, G-man-092 | 4c |
| `cfg.day.final_submit_delegate_roles` | S | list<role> | empty (off) | subset of dmo, wm | G W | S | B | srv | C3 | An acting TSO or DMO may final-submit. unknown; confirm: Q45 | D-262 | 3b |
| `cfg.day.final_submit_autoclose_time` | T | time | none (off) | 22:00 to 23:59 or none | G W | S | B | srv | C3 | The system closes a zone-day as `system_closed`. unknown; confirm: Q53 | D-262 | 3b |
| `cfg.day.reopen_roles` | S | list<role> | [admin] (A) | subset of roles | G W | S | B | srv | C3 | Too wide and "final" means nothing. restrictive: fewer roles. unknown; confirm: Q11 | D-55 | 3b |
| `cfg.day.reopen_window_days` | S | int | 3 (A) | 0 to 31 | G | F | B | srv | C2 | Beyond this a reopen needs two-person approval whatever the role | D-55 | 3b |
| `cfg.day.late_sync_after_final_policy` | S | enum(accept_and_flag, quarantine, reject) | accept_and_flag | enum | G | F | B | srv | C3 | `reject` loses real sales from a late phone. restrictive: none (never loosened to lose data) | D-55 | 3b |
| `cfg.day.attendance_missing_checkout_autoclose_time` | T | time | 23:30 (A) | later than `checkout_latest_time` | G | F | B | srv | C1 | A forgotten check-out is closed as "no check-out" and never blocks Sales Submit | D-329 | 2e |
| `cfg.day.take_action_after` | T | time | 17:00 | 12:00 to 23:00 | G W | F | B | srv | C1 | Daily Tracking "take action" opens after this time | docs/09 | 4a |
| `cfg.day.multi_visit_same_outlet_policy` | S | enum(allow, allow_after_zero_sale, block) | allow_after_zero_sale | enum | G | F | B | both | C2 | `allow` double-counts CPR unless the aggregate collapses; 68 duplicate keys exist in the sample | D-250 | 2a |
| `cfg.day.exception_reasons` | S | content(app.reason_code) | rain_flood, hartal, market_closed, dh_no_stock, breakdown, sick_no_leave, other | 1 to 15 active | G | F | B | both | C1 | Codes immutable; deactivate instead of delete | D-39; G-field-02 | 2e |
| `cfg.day.exception_requires_approval` | S | bool | true | n/a | G W | F | B | srv | C2 | An approved exception removes the route from Login %, Submit % (of logged-in) and Day-completion % denominators | D-39 | 2e |
| `cfg.day.exception_max_days` | S | int | 7 (A) | 1 to 31 | G | F | B | srv | C1 | Length of one exception | D-39 | 2e |
| `cfg.day.exception_approver_roles` | S | list<role> | [tso] | subset | G | F | B | srv | C2 | The TSO approves in the app (D-39) | D-39 | 2e |

#### 3.2.3 Selling rules, memo, credit, QC, DRP, stock, price, promotions and printing

`cfg.sale.*`, `cfg.memo.*`, `cfg.credit.*`, `cfg.qc.*`, `cfg.drp.*`, `cfg.stock.*`, `cfg.price.*`, `cfg.promo.*`, `cfg.print.*`.

| Key | K | Type | Default | Bounds | Scope | Ed | Eff | Dl | Cls | Note and risk if mis-set | Ref | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.sale.qty_entry_unit` | S | json by category | {Cigarette stick, Bidi stick, Lighter piece, Match dozen} (A: Match unit unknown; confirm, MQ-01) | per category | G | O | S | both | C3 | FD. Every volume figure is wrong by the pack size if mis-set. Cigarette and bidi in sticks is proven by the stock badge (6,500 sticks = 650 packs) | D-16, D-17, D-248; G-man-001 | 2a |
| `cfg.sale.max_line_qty_base` | S | json by outlet kind | {retail 20,000 sticks, wholesale 1,000,000} (A) | 1 to 10,000,000 | G Z | F | B | both | C1 | Soft ceiling plus anomaly flag, never a hard cap: 26,683 sample lines are 10,000 sticks or more, from wholesale buyers | D-260; G-feat-68 | 2a |
| `cfg.sale.max_lines_per_memo` | S | int | 60 | 40 to 200 (D-547: the observed maximum is 40) | G | O | B | both | C1 | July average 1.95 lines, p99 4, maximum 40 | D-246 | 2a |
| `cfg.sale.stock_check` | S | enum(off, warn, block) | warn | enum | G W Z | F | B | both | C2 | `block` with an unrecorded DH top-up stops selling; flagged `stock_negative` | D-321 | 2a |
| `cfg.sale.force_reasons` | S | content(app.reason_code) | internet_problem, location_change, no_outlet_location | 1 to 8 active | G | F | B | both | C1 | The AMO Manual Override uses the same list | D-95; G-man-017 | 2d |
| `cfg.sale.force_requires_photo` | S | bool | true | n/a | G T | F | B | both | C2 | false makes force sale a free bypass; camera only | D-75 | 2d |
| `cfg.sale.zero_sale_requires_confirm` | S | bool | true | n/a | G | F | B | dev | C0 | Zero-sale confirm dialog | D-36 | 2a |
| `cfg.sale.call_start_prompt` | S | bool | true | n/a | G | F | B | dev | C1 | "আপনি কি কল শুরু করতে চান?" before AV, KV, survey and sale | D-78 | 1a |
| `cfg.sale.outlet_photo_every_call` | S | bool | false | n/a | G | F | B | dev | C2 | true adds about 130,000 photos a day; the manual shows a camera only inside Force Sale | D-347; G-man-016 | 2c |
| `cfg.sale.offers_auto_apply` | S | bool | true | n/a | G | P | B | both | C1 | | docs/06 | 2a |
| `cfg.sale.suggested_qty_enabled` | S | bool | false | n/a | G W T | P | B | both | C1 | Hook only until the Q6 formula is confirmed | Q6 | 5c |
| `cfg.sale.require_printer_before_sale` | S | bool | false | n/a | G | F | B | dev | C2 | true blocks sales when the printer dies; printing is optional (PARITY) | D-77 | 1a |
| `cfg.sale.allow_price_type_override` | S | bool | false | n/a | G | F | B | both | C3 | The SR never chooses the price type | D-32; G-field-08 | 2a |
| `cfg.sale.price_compliance_sr` | S | bool | false | n/a | G | F | B | both | C1 | AMO control call only by default | D-334 | 3a |
| `cfg.sale.sort_by_distance` | S | bool | false | n/a | G ROLE | F | B | dev | C0 | IMPROVEMENT: sort the picker by distance to avoid the wrong neighbour in a 55 m cell | D-340 | 2d |
| `cfg.memo.edit_reasons` | S | content(app.reason_code) | `wrong_sku` plus the two reasons to capture from the live app; `wrong_outlet` dropped (outlet is read-only on edit) | 1 to 10 active | G | F | B | both | C1 | First reason "ভুল SKU নির্বাচিত।". unknown; confirm: MQ-18 | D-200 | 2b |
| `cfg.memo.edit_roles` | S | list<role> | [sr] (A: the AMO Edit button is grey on every screenshot) | subset | G | F | B | both | C2 | | G-man-012 | 2b |
| `cfg.memo.edit_requires_geofence` | S | bool | true | n/a | G T | F | B | both | C2 | PARITY; the server re-checks | docs/06 | 2b |
| `cfg.memo.edit_blocked_after_qc` | S | bool | true, at OUTLET level | n/a | G | F | B | both | C2 | Day scope of the lock unconfirmed | D-201 | 2b |
| `cfg.memo.edit_window_min` | S | int | 0 (until Sales Submit) | 0 to 720 | G T | F | B | both | C1 | Adds a time cap on top of the two rules above | D-86 | 2b |
| `cfg.memo.edit_after_print_policy` | S | enum(void_and_reprint, amend_reprint, block) | void_and_reprint | enum | G | F | B | both | C2 | The retailer holds the old paper; the void is an event | D-86 | 2b |
| `cfg.memo.edit_chain_max` | S | int | 3 | 1 to 10 | G | F | B | both | C1 | Supersede chain depth | D-86 | 2b |
| `cfg.memo.void_reasons` | S | content(app.reason_code) | retailer_cancelled, wrong_outlet, duplicate_entry, other | 1 to 10 active | G | F | B | both | C1 | unknown; confirm: Q43 | D-86 | 2b |
| `cfg.memo.print_void_slip` | S | bool | true | n/a | G | F | B | dev | C1 | Cancel slip "বাতিল" | D-86 | 2b |
| `cfg.memo.reprint_max` | S | int | 5 (A) | 0 to 20 | G T | F | B | dev | C1 | Doc 17 value (the lens had 3) | D-322 | 2b |
| `cfg.memo.reprint_watermark` | S | bool | true (marker text from the physical samples) | n/a | G | F | B | dev | C0 | | D-322 | 2b |
| `cfg.memo.number_format` | S | text | `<username>-<yyMMdd>-<seq3>` | must contain the sequence token | G | O | S | both | C3 | FD. Changing it mid-day duplicates printed numbers. unknown; confirm: Q5 (series continuity) | D-35 | 1a |
| `cfg.memo.seq_block_size` | S | int | 500 | 100 to 999 | G | O | S | both | C3 | FD, new binds only; disjoint blocks per bind ordinal | D-35 | 1a |
| `cfg.memo.rounding_mode` | S | enum(half_up_paisa, floor_paisa, half_even_paisa) | half_up_paisa (A) | enum | G | O | S | both | C3 | FD. Totals are summed unrounded and rounded once; must equal Apsis to the paisa (T-2-41). Stamped on memo | D-19 | 1a |
| `cfg.memo.show_due_balance_on_print` | S | bool | true (A) | n/a | G | F | B | dev | C0 | Previous-due line on the memo | docs/06 | 2b |
| `cfg.memo.due_balance_staleness_marker` | S | bool | true | n/a | G | F | B | dev | C0 | Marker "<date> পর্যন্ত" on a stale balance | G-field-09 | 2b |
| `cfg.memo.allow_negative_net` | S | bool | true | n/a | G | F | B | both | C2 | Zero sale plus QC can make the net negative; stored as a credit. unknown; confirm: MQ-04 | D-18 | 2a |
| `cfg.credit.enabled` | S | bool | true | n/a | G W D T Z O | F+TSO | B | both | C2 | `false` at outlet is a credit stop; TSO inside own territory; the DMO sees the count | docs/06 | 2a |
| `cfg.credit.max_due_mtk` | S | money_mtk | 0 = no limit (Apsis shows none) | 0 to 10,000,000,000 | G W T Z O | F+TSO | B | both | C2 | unknown; confirm: any business limit (D-320) | D-320 | 2a |
| `cfg.credit.max_days` | S | int | 0 = no limit | 0 to 180 | G W T Z O | F | B | both | C2 | | D-320 | 2a |
| `cfg.credit.block_on_overdue` | S | bool | false | n/a | G T Z | F | B | both | C2 | | D-320 | 2a |
| `cfg.credit.partial_payment_min_pct` | S | pct | 0 (collected must be strictly below the total) | 0 to 100 | G | F | B | dev | C1 | The credit dialog accepts an amount below the grand total | G-man-009 | 2a |
| `cfg.credit.allow_zero_payment` | S | bool | true (unknown; confirm) | n/a | G | F | B | dev | C1 | | G-man-009 | 2a |
| `cfg.credit.allow_partial_collection` | S | bool | false (PARITY: mark paid settles the whole memo) | n/a | G | F | B | both | C2 | A `due_collection` row is always written | D-37 | 2b |
| `cfg.credit.snapshot_tolerance_mtk` | S | money_mtk | 0 | 0 to 100000 | G | FIN | S | srv | C1 | Largest accepted gap between the due printed on paper and the server balance before `due_snapshot_mismatch` (DQ-54). Added at the editorial merge from doc 16 s5 | G-analyst-15; OI-16-28 | 2b |
| `cfg.credit.collection_partial_roles` | S | list<role> | empty | subset | G | F | B | both | C2 | | G-man-010 | 2b |
| `cfg.qc.fault_types` | S | content(app.qc_fault_type) | 11 codes, groups MFC and MKT, `applies_to` app or web (union of 6 app and 10 web labels) | codes immutable | G | F | B | both | C1 | Replaces `cfg.qc.fault_kinds` | D-34, D-159; G-man-003 | 2a |
| `cfg.qc.expired_stock_months` | S | int | 4 | 1 to 12 | G | F | B | both | C1 | Expired-stock threshold | D-34 | 2a |
| `cfg.qc.max_amount_basis` | S | enum(pending, per_sku_taka) | pending | enum | G | F | B | both | C2 | The max-QC basis is unknown (594.50 is not 10 x 8.00). unknown; confirm: MQ-03 | D-34; G-man-002 | 2a |
| `cfg.qc.max_qty_per_cell` | S | int | none (A) | 1 to 1,000,000 | G | F | B | srv | C1 | Web QC grid cell limit | G-man-089 | 4c |
| `cfg.qc.web_reentry_policy` | S | enum(replace, add, block) | replace (A) | enum | G | F | B | srv | C1 | Re-entering a web QC cell | G-man-089 | 4c |
| `cfg.qc.web_entry_roles` | S | list<role> | [tso, admin] (A) | subset | G | F | B | srv | C2 | | G-man-089 | 4c |
| `cfg.drp.kinds` | S | content(app.reason_code) | empty_pack, slide | 1 to 6 active | G | P | B | both | C1 | One label "স্লাইড সংগ্রহ", alias "Collect DRP Discount" | D-217 | 2a |
| `cfg.drp.shortcut_steps` | S | list<int> | [1, 5, 10, 20, 50] | 1 to 8 values | G | P | B | dev | C0 | Quick-add steps on the slide screen | G-man-004 | 2a |
| `cfg.stock.return_entry_enabled` | S | bool | true | n/a | G W | F | B | both | C1 | End-of-day return and reconciliation | F-SR-051 | 2b |
| `cfg.stock.print_stock_memo` | S | bool | true | n/a | G | F | B | dev | C0 | | docs/06 | 2a |
| `cfg.stock.max_issue_qty` | S | int | none (A) | 1 to 10,000,000 | G Z | F | B | both | C1 | Soft ceiling per SKU on stock load | G-man-007 | 2a |
| `cfg.stock.require_printed_slip` | S | bool | false (warns, doc 17); Q-UI-03 proposes blocking Sales Submit until printed or a supervisor overrides: unknown; confirm | n/a | G | F | B | both | C1 | The stock slip is a paper hand-over to the distributor | UI-SR-17 | 2e |
| `cfg.price.backdate_allowed` | S | bool | false | n/a | G | FIN | B | srv | C3 | Back-dated `sku_price` rows only through finance_admin plus finance_approver with a restated-memo count. Forward-dated prices have their own rails: `cfg.price.max_change_pct`, the mandatory preview and the same-day correction lane (s8.2b, D-589) | D-98; G-fraud-11 | 6a |
| `cfg.price.list_version` | S | content(marker) | n/a | n/a | G | FIN | B | both | C2 | Publishing a price list bumps it so the reach view shows delivery | G-cfg-14 | 2a |
| `cfg.promo.engine_enabled` | S | bool | true | n/a | G W D T Z | P | B | both | C2 | Brake if a rule misfires on launch day. A BRAKE-lane key (s7.6c): false is the stricter value, allowed under break-glass and inside the freeze windows (D-588) | D-33 | 2a |
| `cfg.promo.rules` | S | content(app.offer) | about 22 groups: bn and en text, valid_from and valid_to, qualifying brand or SKU set, ratio, reward SKU, scope | schema-validated; no overlapping non-stackable rules on one SKU and scope | G W D T Z O | P | B | both | C3 | Memo totals differing from Apsis cause retailer disputes. Applied by the memo business date, so a late sync gets the rule live that day. unknown; confirm: Q13 catalogue (2a entry condition) | D-33; G-feat-13 | 2a |
| `cfg.promo.max_discount_pct_per_memo` | S | pct | 50 (A) | 0 to 100 | G | P | B | both | C2 | Guards a rule typo giving stock away | D-33 | 2a |
| `cfg.promo.free_sample_enabled` | S | bool | true | n/a | G W | P | B | both | C1 | | D-333 | 5b |
| `cfg.promo.free_sample_max_per_outlet_day` | S | int | 2 (A) | 0 to 50 | G W | P | B | both | C1 | | D-333 | 5b |
| `cfg.promo.drp_offer_map` | S | content(app.offer) | 10 empty MaxR-10S packets give 1 reward pack of 10 sticks, a deduction of 80.00 (fixture) | schema-validated | G W | P | B | both | C2 | DRP kind to credit or reward SKU | D-33 | 2a |
| `cfg.print.template_version` | S | int | 1 | 1 to 999 | G | F | B | dev | C2 | FD. One golden print test per template kind | D-76; G-man-014 | 1a |
| `cfg.print.templates` | S | content(cfg.print_template) | 7 memo kinds (cash, credit with partial payment, with offer, DRP, zero sale, edited, stock memo) plus day summary | 32 columns (Font A) or 42 columns (Font B), by the font the template names (doc 17 s9.2; D-525) | G W D | F | B | dev | C2 | FD. Physical 58 mm samples are the oracle. unknown; confirm: Q57 | D-76; F-ADM-067 | 1a |
| `cfg.print.models` | S | list<text> | [RPP02N] | 1 to 5 | G | O | B | dev | C0 | | G-man-023 | 1a |
| `cfg.print.pairing_pins` | S | list<text> | ['0000', '1234'] | 1 to 5 | G | O | B | dev | C0 | Replaces `cfg.print.pin_hint` | G-man-023 | 1a |
| `cfg.print.disconnect_idle_s` | S | int | 120 | 30 to 600 | G | O | B | dev | C1 | Printer link released after idle | doc 17 | 1a |
| `cfg.print.status_query` | S | bool | false | n/a | G | O | B | dev | C1 | `DLE EOT 1` when the firmware supports it | doc 17 | 1a |
| `cfg.print.confirm_after_print` | S | bool | true | n/a | G | F | B | dev | C0 | "ছাপা ঠিক আছে?" | D-76 | 1a |
| `cfg.print.due_receipt` | S | content(cfg.print_template) | template v1 (IMPROVEMENT) | at most 32 columns | G W | F | B | dev | C1 | | G-field-09 | 2b |

#### 3.2.4 Media, sync, bundle, app behaviour, network and telemetry

`cfg.media.*`, `cfg.sync.*`, `cfg.bundle.*`, `cfg.app.*`, `cfg.net.*`, `cfg.telemetry.*`. Values for the photo, sync, bundle and app keys are doc 17 s13.4 values, which doc 17 derived from D-59 to D-84 and D-73 to D-75.

| Key | K | Type | Default | Bounds | Scope | Ed | Eff | Dl | Cls | Note and risk if mis-set | Ref | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.media.long_edge_px` | S | int | 1024 | 640 to 1280 (D-546) | G W | O | B | dev | C2 | 2048 would double the data pack | D-75 | 1a |
| `cfg.media.jpeg_quality` | S | int | 70 | 40 to 90 | G | O | B | dev | C1 | Compressor steps down to 40, then 800 px | D-75 | 1a |
| `cfg.media.photo_max_kb` | S | int | 150 | 60 to 200 (D-546); above 150 is C3 with a budget-impact line | G W | O | B | dev | C2 | Above 200 breaks the 3 MB a day budget with 13 photos on mobile (13 x 200 KB plus about 0.4 MB of other bytes; the ceiling is 200, D-546) | D-75, D-73 | 1a |
| `cfg.media.max_photos_per_device_day` | S | int | 200 | 20 to 1000 | G | O | B | both | C1 | Upload-slot cap per device per day (design is 17.6 photos per SR-day, doc 18 ST-09). Added at the editorial merge from doc 21 s6 | D-75; D-116 | 2c |
| `cfg.media.wifi_only_default` | S | bool | true | n/a | G W D T Z | O | B | dev | C2 | Evidence falls back to mobile after `evidence_mobile_fallback_h`; `false` on launch day makes a photo storm on mobile data (replaces the lens `upload_network_policy`) | D-75 | 2c |
| `cfg.media.evidence_mobile_fallback_h` | S | int | 6 | 0 to 48 (0 = never) | G W | O | B | dev | C1 | | D-75 | 2c |
| `cfg.media.user_may_toggle_wifi_only` | S | bool | true | n/a | G | O | B | dev | C0 | | docs/04 | 2c |
| `cfg.media.max_photos_per_visit` | S | int | 4 (A: survey, force, outlet, gift) | 1 to 10 | G | F | B | dev | C1 | | docs/04 | 2c |
| `cfg.media.sas_ttl_min` | S | int | 15 | 5 to 60 | G | S | B | srv | C2 | Write-only user-delegation SAS pinned to one path | D-75 | 2c |
| `cfg.media.local_queue_max_mb` | S | int | 50 | 10 to 200 | G | O | B | dev | C1 | Photos queued on the phone | doc 17 | 2c |
| `cfg.media.local_keep_days` | S | int | 2 | 1 to 14 | G | O | B | dev | C1 | Uploaded photos kept locally | doc 17 | 2c |
| `cfg.media.pending_photo_grace_days` | S | int | 7 | 1 to 30 | G | O | B | srv | C1 | After this a visit with a pending photo is flagged `missing` | G-scale-20 | 2c |
| `cfg.media.evidence_camera_only` | S | bool | true | n/a | G | S | B | dev | C2 | No gallery picker for force sale, outlet capture or base update | G-fraud-15 | 2c |
| `cfg.sync.trickle_enabled` | S | bool | true | n/a | G W D T Z WAVE | O | B | both | C2 | false is "end-of-day only", which R5 forbids; kept as a brake. A BRAKE-lane key (s7.6c): false is stricter, allowed under break-glass and inside the freeze windows (D-588) | D-59 | 1b |
| `cfg.sync.debounce_s` | S | int | 5 | 2 to 60 | G W | O | B | dev | C1 | The lens 10 s is retired | D-261 | 1b |
| `cfg.sync.family_hold_max_s` | S | int | 180 | 60 to 900 | G | O | B | dev | C1 | A visit and its children leave together | D-59 | 1b |
| `cfg.sync.batch_max_rows` | S | int | 200 | 50 to 500 | G | O | B | dev | C1 | Server cap 500 (D-116) | D-382 | 1b |
| `cfg.sync.batch_max_kb_raw` | S | int | 256 | 64 to 1024 | G | O | B | dev | C1 | | doc 17 | 1b |
| `cfg.sync.retry_backoff_s` | S | int | 2 (base of doubling with full jitter) | 1 to 60 | G | O | B | dev | C1 | Short base without jitter is a retry storm | doc 17 | 1b |
| `cfg.sync.retry_cap_s` | S | int | 300 | 60 to 900 | G | O | B | dev | C1 | | doc 17 | 1b |
| `cfg.sync.retry_max_inprocess` | S | int | 5 | 3 to 10 | G | O | B | dev | C1 | | doc 17 | 1b |
| `cfg.sync.row_max_retries` | S | int | 10 | 3 to 50 | G | O | B | dev | C1 | Then `rejected(retry_exhausted)` | doc 17 | 1b |
| `cfg.sync.family_skip_after` | S | int | 5 | 2 to 20 | G | O | B | dev | C1 | Poison-row skip-ahead | D-65 | 1b |
| `cfg.sync.periodic_min` | S | int | 15 | 15 to 120 | G W | O | B | dev | C2 | Android WorkManager floor; 120 delays catch-up | D-59 | 1b |
| `cfg.sync.periodic_requires_charging` | S | bool | false | n/a | G | O | B | dev | C1 | | docs/04 | 1b |
| `cfg.sync.periodic_requires_battery_not_low` | S | bool | true | n/a | G | O | B | dev | C1 | | docs/04 | 1b |
| `cfg.sync.login_jitter_s` | S | int | 120 | 0 to 600 | G W | O | B | dev | C1 | Automatic morning refresh only, never a manual login | doc 17 | 1b |
| `cfg.sync.max_clock_skew_min` | S | int | 10 | 2 to 60 | G | O | B | both | C2 | The server flags rows beyond this; the device prompts | D-20 | 1b |
| `cfg.sync.max_backdate_days` | S | int | 7 (A) | 1 to 30 | G | F | B | srv | C2 | Older rows are quarantined, not rejected. This is the app sync window only; the web window is `cfg.web.entry_backdate_days` (D-97). Counted in working days like the stale window (D-584); must stay below `cfg.retention.ingest_registry_days` (rule 9 of s2.6b, D-591) | D-97; DQ-09 | 1b |
| `cfg.sync.parked_ttl_days` | S | int | 7 (A) | 1 to 30 | G | O | S | srv | C1 | A PARK row (parent missing) becomes REJECT after this many days; shorter loses late children, longer keeps the quarantine page noisy. Added at the editorial merge from doc 16 s7 | D-350; DQ-03; OI-16-28 | 1b |
| `cfg.sync.orphan_pending_alert_h` | S | int | 24 | 6 to 72 | G | O | B | srv | C1 | A device whose rows wait for a user's next login is listed on sync-health | doc 17 | 1b |
| `cfg.sync.pending_reminder_time` | T | time | 16:30 | 14:00 to 20:00 | G W | O | B | dev | C1 | Reminder when rows are unsent | doc 17 | 2e |
| `cfg.sync.reason_texts` | S | content(i18n map) | REJECT-policy codes only, bn and en | codes from the contract | G | O | B | dev | C1 | FLAG-policy codes never appear (D-123) | D-123; G-man-102 | 1b |
| `cfg.sync.reconcile_types` | S | content(list) per role and app version | SR 5 rows (outlet, sale, stock, QC, promotion); AMO 8 or 9 | subset of record types | G ROLE | O | B | dev | C1 | Replaces the lens `cfg.app.reconcile_counters` | D-222; G-man-031 | 1b |
| `cfg.sync.resync_window_h` | S | int | 24 | 1 to 72 | G | O | B | both | C2 | Rows acked within this window are re-sent after a server generation change; raise to the PITR distance before minting a generation. Rule 11 of s2.6b: `resync_window_h / 24` below `cfg.retention.ingest_registry_days` (D-591) | D-63 | 1b |
| `cfg.sync.resync_jitter_s` | S | int | 900 | 0 to 3600 | G | O | B | dev | C1 | Spreads the re-send | D-406 | 1b |
| `cfg.sync.engine_mode` | S | enum(current, previous) | current | enum | G WAVE | R | B | dev | C3 | Keeps the previous engine for one release | D-79; G-sre-20 | 2e |
| `cfg.sync.wakelock_max_s` | S | int | 90 | 10 to 90 | G | O | B | dev | C1 | D-73 gate: at most 90 s each, 10 min a day | D-73 | 1b |
| `cfg.sync.upload_on_metered` | S | bool | true | n/a | G W | O | B | dev | C2 | false silently breaks R5 on mobile data; rows are small | R5 | 1b |
| `cfg.net.fallback_after_failures` | S | int | 3 | 1 to 10 | G | O | B | dev | C2 | Switch to the Front Door default hostname after this many TLS or DNS failures | D-81 | 2e |
| `cfg.bundle.stale_max_days` | S | int | 2 | 1 to 3 | G W | O | B | dev | C2 | Offline day start on an older bundle is allowed with a banner. unknown; confirm: the business accepts yesterday's prices. Counted in WORKING days (`dw.prior_working_day`) when `cfg.calendar.window_unit` is `working_days`, with a calendar-day hard ceiling `cfg.bundle.stale_max_cal_days_ceiling` (7) and per-break overrides `cfg.calendar.break_overrides`, so a Friday, Eid or a nine-day break does not strand an offline phone on the first morning (D-584) | D-70 | 2e |
| `cfg.bundle.delta_enabled` | S | bool | true | n/a | G | O | B | srv | C1 | | docs/04 | 1c |
| `cfg.bundle.delta_max_age_h` | S | int | 72 | 24 to 168 | G | O | B | srv | C1 | Beyond this a full bundle is served | doc 17 | 1c |
| `cfg.bundle.delta_min_interval_min` | S | int | 30 | 30 to 240 (D-546: at most 15 deltas a day) | G | O | B | dev | C1 | Foreground refresh interval | doc 17 | 1c |
| `cfg.bundle.max_gz_kb` | S | int | 2048 | 512 to 4096 | G | O | B | srv | C2 | The AMO bundle drops optional sections before outlets above it | D-72 | 3a |
| `cfg.bundle.page_threshold_rows` | S | int | 2000 (A) | 500 to 10000 | G | O | B | srv | C1 | Sections above this are paged | D-72 | 3a |
| `cfg.bundle.page_rows` | S | int | 1000 (A) | 200 to 5000 | G | O | B | srv | C1 | About 100 KB gz a page | D-72 | 3a |
| `cfg.bundle.regen_max_per_s` | S | int | 20 | 1 to 200 | G | O | B | srv | C2 | Caps regeneration after invalidations | D-100 | 1c |
| `cfg.bundle.d1_generation_time` | T | time | 22:00 | 20:00 to 23:30 | G | O | B | srv | C1 | Generates D+1 snapshots for Wi-Fi pre-fetch | D-71 | 2e |
| `cfg.bundle.refresh_time` | T | time | 03:30 | 02:30 to 05:00 | G | O | B | srv | C1 | Refresh for users dirtied since 22:00 | D-71 | 2e |
| `cfg.bundle.coverage_check_time` | T | time | 04:30 | 03:30 to 06:00 | G | O | B | srv | C1 | Coverage check; on failure `bundle_hold` serves yesterday's snapshot plus delta | D-71 | 2e |
| `cfg.bundle.coverage_min_pct` | S | pct | 99 | 90 to 100 | G | O | B | srv | C2 | | D-71 | 2e |
| `cfg.bundle.hold_below_pct` | S | pct | 95 | 80 to 100 | G | O | B | srv | C2 | Below this the morning falls back to the previous snapshot | G-sre-08 | 2e |
| `cfg.bundle.outlet_fields` | S | json by ROLE | Per role (SR: no NID, TIN, licence) | subset of outlet columns | ROLE | S | S | both | C3 | Adding `nid` to the SR bundle leaks PII to 8,500 phones | D-107, D-108 | 1a |
| `cfg.app.home_tiles` | S | list<tile_id> | Per role; per user designation; Sales Journey and KPI hidden until captured from the live app | ids from a fixed set; invariant: Attendance, Sale, Memo, Sales Submit always present (D-547) | ROLE WAVE U | O | B | dev | C1 | Hiding "Sales Submit" by mistake blocks day close. unknown; confirm: Q-UI-05 | D-342; G-man-055 | 1a |
| `cfg.app.drawer_items` | S | list<item_id> | TSO: the seven entries of the current drawer; no Settings (D-204); AMO and SR: none | ids from a fixed set; invariant: TSO always keeps Final Submit (D-547) | ROLE WAVE | O | B | dev | C1 | Drawer entries per role (`cfg.app.drawer_items.tso` in doc 17 s10.6). Hiding Final Submit blocks the zone close. Added at the editorial merge from doc 17 s10 | D-204; F-TSO-024 | 3b |
| `cfg.app.kpi_strip_items` | S | list | The six SR KPIs (visited, strike rate, issue, current stock, non-visit, no-sale) | ids from a fixed set | ROLE | O | B | dev | C0 | | G-man-054 | 1c |
| `cfg.app.outlet_list_label_format` | S | json screen to template | sale `{name} ({code}-{phone}-{cluster})`; review `{name} ({sub_channel})`; AMO `{name}-{code}-{phone}-{cluster}` | tokens validated | G ROLE | F | B | dev | C0 | | D-162; G-man-037 | 1a |
| `cfg.app.route_label_format` | S | text | `{name} ({visit_days})` | tokens validated | G | F | B | dev | C0 | Route name and visit-day label are separate fields (D-242) | G-man-037 | 1a |
| `cfg.app.alphabet_filter` | S | enum(latin, bangla, both) | both | enum | G ROLE | F | B | dev | C0 | First grapheme cluster, case-insensitive | D-345 | 1a |
| `cfg.app.default_locale` | S | enum(bn, en) | P: sr bn, amo bn, tso en / R: same, TSO gets a language switch | enum | G ROLE U | O | B | dev | C0 | | D-181 | 0c |
| `cfg.app.drawer_items.tso` | S | list | The seven drawer entries (Settings added if accepted) | ids from a fixed set | ROLE | O | B | dev | C0 | | D-204; G-man-027 | 3b |
| `cfg.app.logout_wipes_data` | S | json | {sr false, amo false, tso true}; a wipe happens only on a fully reconciled device | ROLE | n/a | S | B | dev | C3 | A wipe with pending rows loses sales; the app refuses regardless | D-69 | 2e |
| `cfg.app.logout_block_when_pending` | S | bool | true | n/a | G | S | B | dev | C3 | "N items not yet sent" with Sync now and Cancel | D-69; G-man-024 | 2e |
| `cfg.app.location_denied_policy` | S | enum(block_with_rationale) | block_with_rationale | enum | G | O | B | dev | C2 | Denied location blocks Sale and Attendance with a Bangla rationale and a Settings link | D-74 | 1a |
| `cfg.app.hold_to_confirm_ms` | S | int | 1000 (A) | 300 to 3000 | G | O | B | dev | C0 | Press-and-hold check-in; replaces `cfg.app.hold_ms` | G-man-029 | 2e |
| `cfg.app.local_history_days` | S | int | 7 | 1 to 30 | G | O | B | dev | C1 | Local Sale History window; purge by business date, never of unsynced rows | D-83 | 2b |
| `cfg.app.outbox_keep_days` | S | int | 3 | 1 to 14 | G | O | B | dev | C1 | Synced outbox rows kept | doc 17 | 1b |
| `cfg.app.rejected_keep_days` | S | int | 30 | 7 to 90 | G | O | B | dev | C1 | | doc 17 | 1b |
| `cfg.app.image_cache_mb` | S | int | 40 | 10 to 70 (D-546) | G | O | B | dev | C1 | Bounded LRU; replaces `cfg.media.thumbnail_cache_mb` | docs/04 | 2a |
| `cfg.app.health_warn_battery_pct` | S | int | 40 | 10 to 80 | G | O | B | dev | C0 | Device-health line on Home | G-field-15 | 2e |
| `cfg.app.oem_guidance` | S | content(list) | MIUI, Realme, Vivo guidance screens with deep links | json_schema: at most 12 items, 400 characters each, bn and en required, deep links from an allow-list (D-597) | G | O | B | dev | C0 | | doc 17 | 2e |
| `cfg.app.activity_log_sample_pct` | S | pct | 10 | 0 to 100 | G ROLE | O | B | dev | C1 | 100 produces about 0.4 M rows a day | G-scale-07 | 2e |
| `cfg.app.telemetry_enabled` | S | bool | true | n/a | G WAVE | O | B | dev | C1 | Telemetry rides inside sync batches only | D-136 | 2e |
| `cfg.telemetry.device_max_bytes_per_day` | S | int | 1024 | 256 to 4096 | G | O | B | dev | C1 | | D-136 | 2e |
| `cfg.telemetry.success_sample_pct` | S | pct | 5 | 0 to 100 | G | O | B | srv | C1 | 100 percent of errors, 429, 5xx and slow requests are kept | D-136 | 2e |
| `cfg.telemetry.slow_request_ms` | S | int | 2000 | 500 to 10000 | G | O | B | srv | C1 | | D-136 | 2e |

#### 3.2.5 Release, operational switches and flags

`cfg.release.*`, `cfg.ops.*`, `cfg.flag.*`. Every O key has a mandatory duration and auto-expires (D-130, D-445).

| Key | K | Type | Default | Bounds | Scope | Ed | Eff | Dl | Cls | Note and risk if mis-set | Ref | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.release.min_version` | S | semver | current pilot build | a published `app_release`; may only increase; at most `latest_version` | G WAVE W D T | R | B+P* | both | C3 | Blocks a NEW day's login only, never capture or upload (D-79, D-130). A typo locks 8,500 reps at 07:00; the console shows the live count of devices below it. restrictive: up | D-79, D-130 | 1c |
| `cfg.release.latest_version` | S | semver | none | a published `app_release` | G WAVE | R | B* | both | C1 | Drives the "update available" prompt | D-79 | 2e |
| `cfg.release.update_url` | S | url | Blob behind Front Door | https, host allow-list, SHA-256 present in `app_release` | G WAVE | R | B* | both | C2 | A wrong URL breaks the updater fleet-wide | D-10 | 2e |
| `cfg.release.update_prompt_policy` | S | enum(silent, prompt, force_after_date) | prompt | enum | G WAVE | R | B* | both | C2 | A forced gate is a design addition, not observed; never `force` over mobile data (R4) | D-79; G-man-022 | 2e |
| `cfg.release.update_wifi_only` | S | bool | true | n/a | G WAVE | R | B* | both | C2 | | G-scale-16 | 2e |
| `cfg.release.finish_offline_day_before_force` | S | bool | true | n/a | G | R | B* | dev | C2 | An open offline day finishes before a forced update | D-79 | 2e |
| `cfg.release.blocked_versions` | S | list<semver> | empty | published or known versions | G WAVE | R | B+P* | both | C3 | Blocks NEW captures on that build, upload continues; blocking the current build stops selling. BRAKE-lane key, direction `list_add`: adding a build is allowed under break-glass and inside the freeze windows; removing one is an ordinary C3 request (s7.6c, D-588) | D-130 | 2e |
| `cfg.release.wave_pct` | S | pct | 100 | 0 to 100 | WAVE | R | B | srv | C2 | Staged exposure inside a wave by stable device hash. BRAKE-lane key, direction `down`: lowering is allowed under break-glass and inside the freeze windows; raising is an ordinary C2 change (s7.6c, D-588) | D-10 | 6c |
| `cfg.release.apk_max_mb` | S | int | 30 per ABI (target 22) | 15 to 30 (D-546) | G | R | B | srv | C1 | CI gate reads it; D-73 | D-73 | 1c |
| `cfg.release.require_reproducible_build` | S | bool | true | n/a | G | R | B | srv | C3 | `app_release` publish is refused when the independent rebuild digest differs | G-fraud-16; D-122 | 2e |
| `cfg.ops.kill_switch` | O | enum(off, read_only, block_login) | off | enum; duration 15 min to `kill_switch_max_h` | G WAVE W D T Z | O | B+P* | both | C3 | `block_login` refuses a new day start; `read_only` refuses new captures; neither stops upload or wipes data (D-130). restrictive: enum order | D-130, D-445 | 2d |
| `cfg.ops.kill_switch_max_h` | S | int | 4 | 1 to 24 | G | S | B | srv | C2 | Auto-expiry ceiling (matches the 4 h break-glass limit, D-124) | D-124 | 2d |
| `cfg.ops.read_only_mode` | O | bool | false | duration required | G | O | B | srv | C3 | Writes get 503 with `Retry-After` during a migration; uploads queue on devices | doc 18 | 2d |
| `cfg.ops.sync_hold_s` | O | int | 0 | 0 to 900; duration required | G W D T Z WAVE | O | B+P | both | C3 | Randomised `hold_s` in responses; forgetting to reset delays the evening sync | doc 18; D-445 | 2d |
| `cfg.ops.sync_hold_by_version` | O | list<semver> | empty | duration 2 h default, 24 h maximum | G | O | B | srv | C3 | Edge 429 for one build stuck in an upload loop; rows stay on the device; the single exception to D-130 | D-130 | 2d |
| `cfg.ops.bundle_hold` | O | bool | false | duration required | G W | O | B | srv | C3 | Morning storm brake; devices fall back to `stale_max_days` | G-cfg-16 | 2d |
| `cfg.ops.maintenance_banner` | O | json {bn, en, from, to, severity} | null | 200 characters each | G W D T Z ROLE | O | B | both | C0 | Previewed on a phone frame; also on `GET /config/public` for global scope | G-cfg-16 | 2d |
| `cfg.ops.push_enabled` | S | bool | false in the pilot | n/a | G WAVE | O | B | srv | C3 | FCM data messages for urgent keys only, never an upload trigger. unknown; confirm: FCM acceptable (D-09, MUST-CONFIRM by 2d) | D-09 | 2d |
| `cfg.ops.push_jitter_s` | S | int | 120 | 0 to 600 | G | O | B | srv | C1 | At most 8,500 / 120 = 71 delta pulls a second | G-sre-07 | 2d |
| `cfg.ops.push_jitter_urgent_s` | S | int | 20 | 0 to 120 | G | O | B | srv | C2 | 8,500 / 20 = 425 pulls a second, served from the ETag cache | G-sre-07 | 2d |
| `cfg.ops.prescale_schedule` | T | json per wing | 06:15 and 16:45; api 8, worker 3, web 4; auth 6 on wave mornings | api 3 to 30, auth 2 to 12, worker 1 to 10, web 2 to 6 per wing | W | O | R | srv | C2 | DEPLOY-TIME (D-552): rendered into the deployment template by a pipeline run, read-only in the console with the date of the last deploy; an urgent change is a break-glass `az containerapp update` | D-133 | 4d |
| `cfg.ops.dashboard_refresh_min_s` | S | int | 60 | 30 to 300 | G | O | B | srv | C1 | Minimum auto-refresh of a dashboard tile | doc 18 | 4a |
| `cfg.ops.dashboard_default_date_rule` | RETIRED (D-544) | enum(last_final_submitted, today) | last_final_submitted for sales tiles, today for activity tiles | enum | G ROLE | O | B | srv | C0 | | G-field-19 | 4a |
| `cfg.ops.report_export_max_rows` | S | int | 200,000 (A) | 10,000 to 1,000,000 | G ROLE | O | B | srv | C1 | Above it the export is a background job | G-man-095 | 4b |
| `cfg.ops.report_concurrency_per_user` | S | int | 2 (A) | 1 to 5 | G | O | B | srv | C1 | | doc 18 | 4b |
| `cfg.flag.new_app_login_enabled` | S | bool | true from the wave's start date | n/a | G WAVE W D T | R | B+P* | both | C3 | Wave rollback is a flip to false at WAVE scope; captured rows keep uploading; kind wave | D-148 | 7b |
| `cfg.flag.parallel_run_mode` | S | enum(off, capture_only, print_test_watermark) | off | enum | WAVE T Z | R | B* | both | C3 | The test print carries "পরীক্ষামূলক - এটি রসিদ নয়" and no previous-due line; kind wave | D-152 | 7b |
| `cfg.flag.pilot_in_rollups` | S | bool | false | n/a | G | R | B | srv | C3 | Pilot accounts are excluded from national rollups; kind ops | D-144 | 7b |
| `cfg.flag.<name>` (12 keys) | S | bool | per flag | n/a | G ROLE WAVE W D T Z U DEV | R | B | both | C2 | `print_enabled`, `credit_ui`, `loyalty_ui`, `astha_ui`, `superstar_ui`, `promo_engine_v2`, `suggested_qty_ui`, `amo_control_call`, `amo_survey`, `anti_spoof_warnings`, `photo_upload_v2`, `tso_final_submit_new` (C3 because it gates the day close). Release flags are removed within two releases of 100 percent; a flag never changes the shape of captured data (D-99, D-447) | D-99, D-447 | 6c |

#### 3.2.6 Platform: API limits, aggregation, retention, SLA alerts, support and system rails

`cfg.api.*`, `cfg.agg.*`, `cfg.retention.*`, `cfg.sla.*`, `cfg.support.*`, `cfg.sys.*`.

| Key | K | Type | Default | Bounds | Scope | Ed | Eff | Dl | Cls | Note and risk if mis-set | Ref | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.api.rl.device_per_min` | S | int | 120 | 30 to 600 | G ROLE | O | B | srv | C2 | Too low throttles the evening sync; per device and per user, never per IP (carrier NAT) | D-116 | 1b |
| `cfg.api.rl.user_per_min` | S | int | 300 (web) | 60 to 2000 | G ROLE | O | B | srv | C1 | | D-116 | 4a |
| `cfg.api.max_batch_body_kb` | S | int | 1024 compressed | 256 to 4096 | G | O | B | srv | C2 | Server side of `batch_max_kb_raw`; 20:1 ratio guard | D-116 | 1b |
| `cfg.api.max_batch_decompressed_mb` | S | int | 8 | 2 to 32 | G | O | B | srv | C2 | | D-116 | 1b |
| `cfg.api.max_batch_rows` | S | int | 500 | 100 to 1000 | G | O | B | srv | C2 | | D-116 | 1b |
| `cfg.api.inflight_batches_per_replica` | S | int | 64 | 16 to 256 | G | O | B | srv | C2 | 503 with `Retry-After` 5 to 60 s above it; expected in flight 6 to 20 | doc 18 | 1b |
| `cfg.agg.poll_interval_s` | S | int | 5 (06:00 to 23:00 Dhaka) | 1 to 60 | G | O | B | srv | C1 | | D-140 | 1c |
| `cfg.agg.poll_interval_night_s` | S | int | 60 | 10 to 300 | G | O | B | srv | C1 | | D-140 | 1c |
| `cfg.agg.coalesce_s` | S | int | 20 (A) | 5 to 120 | G | O | S | srv | C1 | Evening coalescing window of the dirty-key recompute; the aggregates are never written per memo (D-61). Measured by T-4-54. Added at the editorial merge from doc 16 s8 | D-140; D-412; OI-16-28 | 1c |
| `cfg.agg.claim_batch` | S | int | 500 | 20 to 2000 | G | O | B | srv | C1 | | D-140 | 1c |
| `cfg.agg.claim_timeout_s` | S | int | 300 | 60 to 3600 | G | O | B | srv | C1 | Stale claims after a worker deploy | G-sre-17 | 1c |
| `cfg.agg.late_data_recompute_days` | S | int | 7 | 1 to 60 | G | O | B | srv | C2 | Below `sync.max_backdate_days`, accepted late rows never reach the facts (dependency rule, s2.6) | D-61 | 1c |
| `cfg.agg.rollup_min_interval_s` | S | int | 15 | 5 to 120 | G | O | B | srv | C1 | Rollups recomputed at most this often per touched node | doc 18 | 1c |
| `cfg.retention.transactions_hot_months` | S | int | 13 | 6 to 36 | G | O | S | srv | C2 | Then archive; statutory 7 years assumed (MUST-CONFIRM by 6c) | D-23 | 6c |
| `cfg.retention.geo_fix_months` | S | int | 6 | 3 to 24 | G | O | S | srv | C2 | Shortening below a report's horizon deletes evidence | D-23 | 6c |
| `cfg.retention.geo_fix_archive_months` | S | int | 24 | 6 to 84 | G | O | S | srv | C2 | Archive period of raw fixes after the hot window; verdicts live on in `fact_visit`. Added at the editorial merge from doc 16 s13 | D-23 | 6c |
| `cfg.retention.quarantine_months` | S | int | 12 (A) | 3 to 36 | G | O | S | srv | C2 | Hot period of `sync_rejected` rows; parked rows are never archived. Added at the editorial merge from doc 16 s13 | D-23; OI-16-28 | 6c |
| `cfg.retention.ingest_registry_days` | S | int | 45 (A) | 30 to 400 | G | O | S | srv | C2 | Pruning age of `app.ingest_registry`; must exceed `cfg.sync.max_backdate_days`, the 24-hour re-send window (D-63) and the clock tolerance; `voided` tombstones are kept. Added at the editorial merge from doc 16 s6. Enforced by dependency rule 9 of s2.6b: strictly greater than `max_backdate_days` (calendar equivalent) plus `resync_window_h` / 24 plus 1 day of clock tolerance; the GUI cannot set 30 with a backdate of 30 (D-591) | D-352; OI-16-28 | 1b |
| `cfg.retention.event_fact_months` | S | int | 25 (A) | 13 to 84 | G | O | S | srv | C2 | Hot period of event-grain `dw.fact_*` partitions before Parquet archive. Added at the editorial merge from doc 16 s13 | D-23; OI-16-28 | 6c |
| `cfg.retention.fix_round_days` | S | int | 730 | 365 to 1825 | G | O | S | srv | C2 | Raw fixes rounded to 3 decimals in dw after this | D-120 | 6c |
| `cfg.retention.media_days` | S | int | 730 (A; unknown; confirm: Q21) | 90 to 3650 | G | O | S | srv | C2 | Hot, Cool, Cold, Archive lifecycle; reference photos exempt | D-132 | 6c |
| `cfg.retention.activity_log_days` | S | int | 90 (A) | 30 to 365 | G | O | S | srv | C1 | | D-23 | 6c |
| `cfg.retention.sync_batch_response_h` | S | int | 48 (D-525, one value with doc 16 s6.3) | 24 to 72 | G | O | S | srv | C1 | Batch replay window | D-62 | 1b |
| `cfg.retention.audit_years` | S | int | 7 (A) | floor 7 | G | S | S | srv | C3 | Audit tables are never shortened below the floor | D-113 | 6c |
| `cfg.sla.login_pct_alert_time` | T | time | 09:00 (D-552: the single source of the Login % trigger time; doc 18 s6.6 reads it) | 08:00 to 10:30 | G W | O | B | srv | C1 | Time at which Login % below the threshold pages | G-sre-08 | 4a |
| `cfg.sla.login_pct_alert_threshold` | S | pct | 70 (A) | 30 to 100 (floor 30, D-597) | G W D T | O | B | srv | C2 | Baseline is calendar-aware (Friday, holidays). A threshold of 0 would mute the Sev1 login alert; the floor makes that impossible from the GUI; lowering it below 60 also starts the dead-signal watch of s7.9 (D-597) | D-135 | 4a |
| `cfg.sla.submit_pct_alert_time` | T | time | 21:00 (A) | 17:00 to 23:59 | G W | O | B | srv | C1 | Applies to Submit % (of logged-in) | D-45 | 4a |
| `cfg.sla.submit_pct_alert_threshold` | S | pct | 80 (A) | 30 to 100 (floor 30, D-597) | G W D T | O | B | srv | C2 | | D-45 | 4a |
| `cfg.sla.geo_valid_drop_alert_pts` | S | int | 10 (A) | 1 to 50 | G | O | B | srv | C2 | Percentage points against the same-weekday baseline; armed after a tightening | D-434 | 2d |
| `cfg.sla.geo_valid_rise_alert_pts` | S | int | 10 (A) | 1 to 50 | G | S | B | srv | C2 | Armed after a loosening; the one-sided watch missed this (G-fraud-09) | D-434 | 2d |
| `cfg.sla.crash_free_min_pct` | S | pct | 99.0 | 95 to 99.9 | G | R | S | srv | C1 | Below it the staged-rollout percentage of an app version pauses automatically. Added at the editorial merge from doc 20 s5 (OI-20-12) | D-456 | 6c |
| `cfg.sla.force_sale_rise_alert_pts` | S | int | 10 (A) | 1 to 50 | G | S | B | srv | C2 | | D-434 | 2d |
| `cfg.sla.force_sale_drop_alert_pts` | S | int | 10 (A) | 1 to 50 | G | S | B | srv | C2 | | D-434 | 2d |
| `cfg.sla.force_sale_pct_alert` | S | pct | 25 (A) | 1 to 100 | G W D T Z | F | B | srv | C1 | Absolute level | G-field-11 | 2d |
| `cfg.sla.login_pct_drop_alert_pts` | S | int | 15 (A) | 1 to 50 | G | O | B | srv | C2 | Armed after day-flow changes | D-434 | 2d |
| `cfg.sla.submit_pct_drop_alert_pts` | S | int | 15 (A) | 1 to 50 | G | O | B | srv | C2 | | D-434 | 2d |
| `cfg.sla.anomaly_min_sample` | S | int | 50 visits (A) | 10 to 200 (ceiling 200, D-597) | G | O | B | srv | C2 | A window below this is suppressed, not alerted (small zones are noisy). A ceiling of 200 stops a sample requirement of 1,000 from silencing the watch on every small zone | D-434 | 2d |
| `cfg.sla.anomaly_window_min` | S | int | 60 | 15 to 240 | G | O | B | srv | C2 | | D-434 | 2d |
| `cfg.sla.anomaly_eval_min` | S | int | 15 | 5 to 60 | G | O | B | srv | C1 | | D-434 | 2d |
| `cfg.sla.anomaly_watch_business_days` | S | int | 2 | 1 to 5 | G | O | B | srv | C2 | | D-434 | 2d |
| `cfg.sla.sync_p95_ms_alert` | S | int | 2000 | 200 to 10000 | G | O | B | srv | C1 | | D-135 | 1c |
| `cfg.sla.sync_error_rate_alert_pct` | S | pct | 2 | 0.1 to 20 | G | O | B | srv | C1 | | D-135 | 1c |
| `cfg.sla.quarantine_backlog_alert` | S | int | 500 (A) | 1 to 100000 | G | O | B | srv | C1 | docs/22 P-11: the outlet queue is under 500 a day | D-65 | 2e |
| `cfg.sla.config_ack_pct_alert` | S | pct | 90 (A) | 50 to 100 (floor 50, D-597) | G | O | B | srv | C1 | Alert when fewer than this share of online devices acked after the window | G-cfg-07 | 1c |
| `cfg.sla.config_ack_window_min` | S | int | 60 (A) | 5 to 1440 | G | O | B | srv | C1 | | G-cfg-07 | 1c |
| `cfg.sla.mock_gps_pct_alert` | S | pct | 5 (A) | 1 to 100 (floor 1, D-597) | G W D T Z | F | B | srv | C1 | | docs/05 | 2d |
| `cfg.sla.targets_missing_alert_day` | S | int | 26 | 20 to 31 | G | P | B | srv | C1 | Month-end readiness board alerts when M+1 targets are missing | G-field-17 | 5c |
| `cfg.support.contacts` | S | content(cfg.support.contacts) | AKTCL helpdesk numbers and hours (A placeholder); banner text keys | 1 to 10 | G W D T | O | B | both | C0 | Also served unauthenticated by `GET /config/public` | G-man-087 | 1c |
| `cfg.support.max_upload_mb` | S | int | 20 | 5 to 100 | G | O | B | dev | C1 | "PDA to Support" upload cap | G-man-025 | 2e |
| `cfg.support.public_key_id` | S | text | set at 2e from Key Vault | id of an existing key | G | S | B | dev | C3 | Id of the AKTCL support public key that encrypts the PDA to Support bundle; an unknown id makes the device refuse to send. Added at the editorial merge from doc 21 s5 | G-sec-14; G-man-025 | 2e |
| `cfg.support.pda_upload_wifi_only` | S | bool | true | n/a | G | O | B | dev | C1 | | G-man-025 | 2e |
| `cfg.sys.schedule_horizon_days` | S | int | 7 (A) | 1 to 30 | G | O | B | both | C1 | How far ahead future-dated values ship so an offline phone applies them on time | G-cfg-06 | 1c |
| `cfg.sys.config_delta_max_age_versions` | S | int | 500 (A) | 50 to 10000 | G | O | B | srv | C1 | A device further behind gets a full snapshot | s4 | 1c |
| `cfg.sys.config_accept_window_h` | S | int | 48 (A) | 0 to 168 | G | S | B | srv | C3 | The tolerance window of D-431 as amended by D-519: measured from the first change after the device's stamped version, not from the age of the device's own version; 0 disables it. Rule 8 of s2.6b: at least `cfg.bundle.stale_max_days` x 24; the clock counts working days only (D-584), and a C3 tightening may carry `no_grace` (s2.3b, D-571) | D-431 | 1c |
| `cfg.sys.c2_delay_min` | S | int | 10 | 0 to 60 | G | S | B | srv | C3 | Delayed apply with cancel for C2 | D-88 | 2d |
| `cfg.sys.request_expiry_h` | S | int | 72 | 12 to 168 | G | S | B | srv | C2 | Pending requests expire | D-88 | 2d |
| `cfg.sys.c3_max_per_hour` | S | int | 5 | 1 to 20 | G | S | B | srv | C3 | Applied C3 versions per hour fleet-wide | D-100 | 2d |
| `cfg.sys.canary_min_business_days` | S | int | 1 | 1 to 5 | G | S | B | srv | C3 | Canary must run this long before the wider change | D-433 | 2d |
| `cfg.sys.break_glass_mode` | S | enum(restore_or_restrict_only, restore_only, off) | restore_or_restrict_only | enum | G | S | B | srv | C3 | Break-glass may only revert to an applied version or move a value in the restrictive direction; the ONLY loosening path is the bounded emergency-widen lane of s7.6b (D-527) | D-436 | 2d |
| `cfg.sys.break_glass_max_h` | S | int | 4 | 1 to 8 | G | S | B | srv | C3 | Matches D-124 | D-124 | 2d |
| `cfg.sys.break_glass_review_h` | S | int | 24 | 1 to 72 | G | S | B | srv | C3 | Unreviewed after this escalates to every security_admin | D-436 | 2d |
| `cfg.sys.change_freeze_windows` | T | list of Dhaka ranges | 07:00 to 09:30 and 16:30 to 19:30 | 0 to 6 ranges | G | S | B | srv | C3 | Blocks C2 and C3 changes and bulk master-data operations; EXEMPT (D-527, D-542): break-glass, the emergency-widen lane and the temporary-relief path of s7.6b, and an `emergency_off` calendar declaration; break-glass and the lane are reviewed | D-100 | 2d |
| `cfg.sys.bulk_op_max_rows` | S | int | 5000 (A) | 100 to 50000 | G | O | B | srv | C2 | Rows in one bulk master-data operation | D-440 | 6a |
| `cfg.sys.dependency_rules` | S | json (read-only) | the rules of s2.6 | migration only | G | n/a | R | srv | C3 | Shown in the edit drawer; not editable from the GUI. Round 3 lists 14 rules (s2.6 and s2.6b); each has a test in T-0-61 and T-0-157 (D-591) | G-cfg-04 | 2d |

#### 3.2.7 Authentication, PII and security: `cfg.auth.*`, `cfg.pii.*`, `cfg.sec.*`

| Key | K | Type | Default | Bounds | Scope | Ed | Eff | Dl | Cls | Note and risk if mis-set | Ref | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.auth.access_ttl_min` | S | int | 60 (web 15 by ROLE) | 15 to 1440 | G ROLE | S | S | srv | C2 | The app never needs a live token to sell (local session) | D-101 | 0c |
| `cfg.auth.access_ttl_jitter_min` | S | int | 10 | 0 to 30 | G | S | S | srv | C1 | Spreads the hourly expiry wave | D-101 | 0c |
| `cfg.auth.min_refresh_interval_s` | S | int | 300 | 60 to 3600 | G | S | S | srv | C1 | | D-101 | 0c |
| `cfg.auth.refresh_ttl_days` | S | int | 30 (sliding) | 7 to 90 | G ROLE | S | S | srv | C3 | Below 7 logs everyone out over a nine-day Eid break. unknown; confirm: Q4 | D-101 | 0c |
| `cfg.auth.refresh_absolute_days` | S | int | 90 (since the last password login), plus or minus `cfg.auth.refresh_absolute_jitter_days` drawn per family at mint (D-518) | 30 to 180 | G ROLE | S | S | srv | C3 | | D-101 | 0c |
| `cfg.auth.upload_grant_idle_days` | S | int | 7 | 1 to 30 | G | S | S | srv | C2 | Idle life of the upload-only grant (aud `aron-upload`, password-free, batch and media only) after an explicit logout. Added at the editorial merge from doc 21 s2.7 | D-471 | 1c |
| `cfg.auth.refresh_rotation` | S | bool | true | n/a | G | S | S | srv | C1 | Rotation with reuse detection | D-101 | 0c |
| `cfg.auth.refresh_grace_s` | S | int | 60 | 0 to 300 | G | S | S | srv | C2 | Replay of a just-rotated refresh token inside this window returns the stored response; outside it the family is revoked. Added at the editorial merge from doc 21 s2.3 | D-101 | 0c |
| `cfg.auth.offline_unlock_max_days` | S | int | 7 | 1 to 14 | G ROLE | S | B | dev | C2 | Days a verifier login works with no network. Retires the lens `offline_session_max_days` (3) and the security 14. Counted in working days (D-584); a declared break that outlasts it is covered by `cfg.calendar.break_overrides` (a nine-day break would otherwise lock every offline phone on day 8) | D-68, D-265 | 1a |
| `cfg.auth.offline_unlock_max_attempts` | S | int | 10 | 3 to 20 | G | S | B | dev | C2 | Doubling cool-down | D-68 | 1a |
| `cfg.auth.password_min_len` | S | int | web 12 (PARITY); field roles 8 with a deny-list (proposed) | 8 to 64 | G ROLE | S | S | srv | C2 | 8,500 reps type on shared 4-inch screens. unknown; confirm: Q4 | D-102 | 0c |
| `cfg.auth.password_complexity` | S | json | {upper, lower, digit true; symbol false} | json_schema: at least 2 of upper, lower, digit, symbol are true (D-597) | G ROLE | S | S | srv | C2 | Web policy parity | D-102 | 0c |
| `cfg.auth.password_history_depth` | S | int | 10 | 0 to 24 | G | S | S | srv | C1 | Not one of the last 10 | D-102 | 0c |
| `cfg.auth.password_min_age_h` | S | int | 24 | 0 to 168 | G | S | S | srv | C1 | Not within 24 h of the last change | D-102 | 0c |
| `cfg.auth.password_max_age_days` | S | int | 0 = never (A) | 0 to 365 | G ROLE | S | S | srv | C2 | 90 days on 8,500 SRs is a reset wave the helpdesk cannot absorb | D-102 | 0c |
| `cfg.auth.password_denylist_enabled` | S | bool | true for field roles | n/a | G ROLE | S | S | srv | C1 | | D-102 | 0c |
| `cfg.auth.temp_password_ttl_h` | S | int | 24 | 1 to 168 | G | S | S | srv | C2 | Temporary password forces a change | D-102 | 0c |
| `cfg.auth.lockout_attempts` | S | int | 10 | 3 to 50 | G ROLE | S | S | srv | C2 | 3 on shared phones at 07:00 floods the helpdesk | D-102 | 0c |
| `cfg.auth.lockout_window_min` | S | int | 15 | 1 to 60 | G | S | S | srv | C1 | | D-102 | 0c |
| `cfg.auth.lockout_min` | S | int | 15 (doubling) | 1 to 1440 | G | S | S | srv | C1 | | D-102 | 0c |
| `cfg.auth.lockout_key_mode` | S | enum(username_device_ipclass, username_device, username) | username_device_ipclass | enum | G | S | S | srv | C3 | Username-only lockout is a wave-day denial of service against predictable usernames | G-fraud-12 | 0c |
| `cfg.auth.otp_length` | S | int | P: 4 (manual) / R: 4 | 4 to 8 | G | S | S | srv | C1 | The lens default 6 is retired | D-103 | 0c |
| `cfg.auth.otp_ttl_min` | S | int | 120 | 5 to 1440 | G WAVE | S | S | srv | C2 | The manual shows no expiry. unknown; confirm. Wave day may need 24 h | D-103 | 0c |
| `cfg.auth.otp_max_attempts` | S | int | 5 | 3 to 10 | G | S | S | srv | C2 | Then the OTP expires | D-103 | 0c |
| `cfg.auth.otp_max_active_per_user` | S | int | 1 | 1 to 3 | G | S | S | srv | C1 | | D-103 | 0c |
| `cfg.auth.otp_issue_per_user_per_h` | S | int | 3 | 1 to 20 | G | S | S | srv | C2 | OTP issues per user per hour. Added at the editorial merge from doc 21 s2.5 | D-103; D-473 | 0c |
| `cfg.auth.otp_failed_per_user_day` | S | int | 10 | 3 to 50 | G | S | S | srv | C2 | Failed OTP entries per user per day; above it binding is locked until a TSO clears it with an audit row. Added at the editorial merge from doc 21 s2.5 | D-473; T-3-78 | 0c |
| `cfg.auth.otp_visible_roles` | S | list<role> | [tso] | subset | G | S | S | srv | C2 | View-only panel; re-issue is an admin IMPROVEMENT | D-103; G-man-021 | 0c |
| `cfg.auth.reverify_on_new_version` | S | bool | P: true / R: false | n/a | G WAVE | S | S | both | C2 | DELIBERATE CHANGE: about 8,500 TSO lookups per release and a broken offline day; the pilot may set true | D-80 | 2e |
| `cfg.auth.max_devices_per_user` | S | int | 2 | 1 to 5 | G ROLE | S | S | srv | C2 | 1 makes every phone swap a TSO call | D-66 | 1c |
| `cfg.auth.max_users_per_device` | S | int | 3 | 1 to 10 | G | S | S | srv | C2 | Shared phones | D-66 | 1c |
| `cfg.auth.device_rebind_requires_otp` | S | bool | true | n/a | G | S | S | srv | C2 | | D-103 | 0c |
| `cfg.auth.revoked_device_grace_upload_h` | S | int | 72 (A) | 0 to 168 | G | S | S | srv | C2 | 0 leaves pending sales on a revoked phone unrecoverable | G-feat-63 | 6c |
| `cfg.auth.confirm_identity_on_first_capture` | S | bool | true (devices with more than one bound user) | n/a | G ROLE | S | B | dev | C2 | "আপনি কি <name>?" | D-66 | 2e |
| `cfg.auth.web_session_idle_min` | S | int | 30 | 5 to 480 | G ROLE | S | S | srv | C1 | | docs/09 | 4c |
| `cfg.auth.web_remember_me_days` | S | int | 0 (A: no self-service) | 0 to 30 | G ROLE | S | S | srv | C2 | | D-102 | 4c |
| `cfg.auth.mfa_required_roles` | S | list<role> | [admin] (recommended admin, top, wm) | subset | G | S | S | srv | C2 | TOTP; Entra SSO if AKTCL has it. Replaces the lens `web_mfa_roles` | D-114 | 4c |
| `cfg.auth.scope_token_version_check` | S | bool | true | n/a | G | S | S | srv | C2 | A scope change bumps `scope_version`; older tokens get 401 `scope_changed` | G-cfg-12 | 0c |
| `cfg.auth.hash_concurrency_per_replica` | S | int | 4 | 1 to 16 | G | S | B | srv | C2 | 503 plus `Retry-After` above it (Argon2id memory) | D-126 | 2e |
| `cfg.auth.notify_user_on_reset` | S | bool | true (if an employee phone exists) | n/a | G | S | B | srv | C1 | FS-21 | G-fraud-08 | 3b |
| `cfg.auth.biometric_unlock` | S | bool | false | n/a | G | O | B | dev | C1 | | G-fraud-04 | 2e |
| `cfg.auth.app_lock_idle_min` | S | int | 0 (supervisors with PII: 30) | 0 to 240 | G ROLE | S | B | dev | C1 | Idle minutes before the app asks for the local unlock again; 0 is parity (no re-authentication on resume). Added at the editorial merge from doc 21 s2.8 | G-fraud-04 | 2e |
| `cfg.pii.field_roles` | S | json {field: roles} | P: TSO web sees Address, NID, TIN, Trade Licence columns (null where blank or the placeholder `123`); phone visible to field roles / R: NID, TIN and licence for admin and pii_officer only | fields from the outlet PII set | G ROLE | S | S | both | C3 | Over-exposure is irreversible once on 8,500 phones. unknown; confirm: whether the live Excel carries NID or TIN (MUST-CONFIRM by 4c) | D-108, D-207; G-man-039 | 1a |
| `cfg.pii.mask_style` | S | enum(hide, last4) | P: hide for SR phones in the AMO view (11 asterisks); last4 elsewhere | enum | G ROLE | S | S | srv | C0 | | D-108; G-man-062 | 3a |
| `cfg.pii.reauth_min` | S | int | 15 | 1 to 120 | G ROLE | S | S | srv | C2 | Re-authentication age required for a PII export or a reveal. Added at the editorial merge from doc 21 s4 | D-121 | 4c |
| `cfg.pii.export_allowed_roles` | S | list<role> | [tso, dmo, wm, admin] (A) | subset | G | S | S | srv | C2 | Exports containing PII columns; every export is logged | D-108, D-121 | 4b |
| `cfg.pii.log_scrub_patterns` | S | list<regex> | NID, phone, TIN and SAS `sig=` patterns | valid regex | G | S | S | srv | C1 | | G-fraud-15 | 0c |
| `cfg.pii.export_rows_per_day` | S | int | 5000 | 100 to 100000 | G ROLE | S | S | srv | C2 | | D-121 | 4c |
| `cfg.pii.list_rows_per_hour` | S | int | 2000 | 100 to 50000 | G ROLE | S | S | srv | C2 | Masked lists, logged reveal | D-121 | 4c |
| `cfg.pii.export_approval_threshold_rows` | S | int | 1000 (A) | 100 to 100000 | G | S | S | srv | C2 | Above it an export needs approval | D-121 | 4c |
| `cfg.pii.export_watermark` | S | bool | true | n/a | G | S | S | srv | C2 | Watermark sheet identifies the exporter | D-121 | 4c |
| `cfg.sec.record_signature_mode` | S | enum(off, record, enforce) | record in the pilot, enforce before wave 1 | enum | G WAVE | S | B | both | C3 | `off` removes tamper evidence. restrictive: enum order | D-104 | 1b |
| `cfg.sec.attestation_required_for_trust` | S | bool | true | n/a | G | S | B | srv | C2 | A repack gets trust level low; never a hard block alone | D-105 | 1c |
| `cfg.sec.takeover_window_h` | S | int | 24 | 1 to 168 | G | S | B | srv | C2 | Reset plus OTP for one user by one actor inside this window holds the bind | D-112 | 3b |
| `cfg.sec.approver_cooling_h` | S | int | 24 | 0 to 168 | G | S | B | srv | C2 | A `config_approver` grant newer than this cannot approve C3 | D-435 | 2d |
| `cfg.sec.lockout_storm_usernames` | S | int | 500 | 50 to 5000 | G | S | B | srv | C2 | Distinct usernames locked in 15 minutes that raise the fleet alert (a spray, not a user error). Added at the editorial merge from doc 21 s2.5 | D-102; T-1-13 | 0c |
| `cfg.sec.flag_secure_pii_screens` | S | bool | true | n/a | G | S | S | dev | C1 | `FLAG_SECURE` on screens that show PII (blocks screenshots). Added at the editorial merge from doc 21 s5 | D-107 | 4c |

`cfg.sec.fraud.*` thresholds. Common attributes: K S, scope G (T where the Note says), editor S (never F), effect B, delivery srv, class C2, phase 2d. Every key carries a `floor` and a `ceiling` in `constraints` so the GUI cannot disable a rule. A dead-signal watch alerts `security_admin` when a signal kind's fleet count falls more than `dead_signal_drop_pct` week over week after a threshold change. Values and the signal catalogue FS-01 to FS-29 belong to doc 21 s7; this document owns class, editor and mechanics (D-109, D-267). The lens `cfg.fraud.*` names are retired.

| Key | Default | Floor to ceiling | Signal and note |
| --- | --- | --- | --- |
| `cfg.sec.fraud.radio_mismatch_km` | 5 | 1 to 50 | FS-25: the same cell seen at two positions this far apart within 30 min |
| `cfg.sec.fraud.cell_distance_max_km` | 10 | 1 to 50 | FS-25: fix farther than this from the cell's learned centroid |
| `cfg.sec.fraud.location_move_alert_m` | 300 | 50 to 2000 | FS-22, DQ-37: a move above this on a confirmed outlet needs TSO approval (D-111) |
| `cfg.sec.fraud.moves_per_outlet_quarter` | 2 | 1 to 10 | FS-22 |
| `cfg.sec.fraud.offline_share_x` | 2.5 | 1 to 10 | FS-20 offline outlier against zone peers |
| `cfg.sec.fraud.offline_outlier_days` | 10 | 3 to 30 | FS-20 |
| `cfg.sec.fraud.closure_requests_x` | 3.0 | 1 to 10 | FS-23 closure requests per SR-month against peers |
| `cfg.sec.fraud.dismissal_resample_pct` | 10 | 0 to 100 | FS-26: a random share of AMO dismissals is re-queued to the TSO |
| `cfg.sec.fraud.unprinted_share_pct` | 10 | 1 to 50 | FS-27 |
| `cfg.sec.fraud.drp_share_x` | 2.5 | 1 to 10 | FS-19 |
| `cfg.sec.fraud.web_entry_share_alert_pct` | 10 | 1 to 50 | FS-28 web-entry memos per route-day |
| `cfg.sec.fraud.web_entry_memos_per_route_day` | 20 | 5 to 200 | FS-28: above it a Data Entry by support or admin needs the zone TSO's confirmation (s8.4) |
| `cfg.sec.fraud.adjust_daily_total_mtk` | 200,000 Tk = 200,000,000 mtk | 10,000 Tk to 5,000,000 Tk | FS-29: above it every adjustment needs the approver whatever its size. The critic wrote "200,000" against an `_mtk` suffix; milli-taka is used here (OI-19-07) |
| `cfg.sec.fraud.adjust_approval_mtk` | 50,000 Tk = 50,000,000 mtk | 1,000 Tk to 500,000 Tk | FS-05, FS-29: a finance adjustment (dues write-off, loyalty adjustment) above it needs `finance_approver`. Added at the editorial merge from doc 21 s3 |
| `cfg.sec.fraud.min_visit_gap_s` | 90 | 10 to 600 | FS-08: 5 or more consecutive visits with gaps below it |
| `cfg.sec.fraud.user_risk_amber` | 20 | 5 to 59 | `user_risk.score_30d` amber from this score (green below); shown to the AMO and TSO as a badge (IMPROVEMENT) |
| `cfg.sec.fraud.user_risk_red` | 60 | 20 to 100 | `user_risk.score_30d` red from this score |
| `cfg.sec.fraud.user_interleave_min` | 30 | 5 to 240 | FS-21 two users capturing on one device |
| `cfg.sec.fraud.stock_sample_pct` | 10 | 1 to 100 | Interim stock-register sampling (G-fraud-07) |
| `cfg.sec.fraud.dead_signal_drop_pct` | 90 | 50 to 100 | The dead-signal watch |

#### 3.2.8 Programmes, targets, KPI definitions and calendar

`cfg.target.*`, `cfg.loyalty.*`, `cfg.astha.*`, `cfg.superstar.*`, `cfg.kpi.*`, `cfg.calendar.*`.

| Key | K | Type | Default | Bounds | Scope | Ed | Eff | Dl | Cls | Note and risk if mis-set | Ref | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.target.min_value` | S | int | 0 (fixed) | floor 0, not editable | G | n/a | R | srv | C3 | Shown so the rail is visible: a negative target produced -37,500 % in the current Astha report | D-31, D-50 | 5c |
| `cfg.target.allow_fractional_std` | S | bool | true | n/a | G | P | S | srv | C1 | Targets are fractional (Marise 100,000 x 15/17 = 88,235.29) | D-51 | 5c |
| `cfg.target.split_method` | S | enum(manual, formula) | manual (the lens `proportional_history` is retired) | enum | G | P | S | srv | C3 | No automatic split is evidenced; entry is route by variant by grid or Excel. unknown; confirm: Q12 | D-31, D-189 | 5c |
| `cfg.target.split_history_months` | S | int | 3 (A, formula only) | 1 to 12 | G | P | S | srv | C2 | | D-189 | 5c |
| `cfg.target.approval_levels` | S | content(cfg.target.approval_levels) | [{level 1, role WMO}] (A: only "WMO approval pending" is evidenced) | 1 to 5 levels | G W | P | S | srv | C3 | Zero levels lets anyone edit targets; renames `revision_approval_levels`. unknown; confirm: Q12, MQ-39 | D-31, D-179 | 5c |
| `cfg.target.product_types` | S | list | [variant] | subset of category, brand, variant, sku | G | P | S | srv | C2 | Target set header field | D-31 | 5c |
| `cfg.target.types` | S | list | [stt] | enum | G | P | S | srv | C1 | | D-31 | 5c |
| `cfg.target.template_version` | S | int | 1 | 1 to 999 | G | P | S | srv | C1 | Sample workbook version | G-man-090 | 5c |
| `cfg.target.upload_max_rows` | S | int | 12000 (A: 11,336 routes) | 100 to 50000 | G | P | S | srv | C1 | All-or-nothing upload | G-man-090 | 5c |
| `cfg.target.entry_window` | S | json | {open_days_before 10, close_days_after 5} (A) | 0 to 31 each | G | P | S | srv | C2 | When a month's targets may be entered | G-man-090 | 5c |
| `cfg.target.lock_after_month_start` | S | bool | true | n/a | G | S | S | srv | C3 | After the month starts a change goes through the revision workflow | D-98; G-fraud-11 | 5c |
| `cfg.target.revision_window_days_before_month_end` | S | int | 5 (A) | 0 to 31 | G | P | S | srv | C1 | | docs/10 | 5c |
| `cfg.target.achievement_pct_cap` | S | int | none (detail tables uncapped, 2 decimals; SR card uncapped); the lens 1000 is retired | 100 to 100000 or none | G | O | S | both | C1 | Raw value is always stored | D-50, D-196 | 4a |
| `cfg.target.supervisor_targets` | S | content(app.supervisor_target) | AMO daily and monthly call, control-call and joint-call targets | at least 0 | Z T | P+TSO | S | both | C1 | unknown; confirm: origin of the figures | G-man-057 | 3a |
| `cfg.loyalty.program_active` | S | bool | true (A) | n/a | G W D T | P | B | both | C2 | false hides the Loyalty tile; pending redemptions still sync. unknown; confirm: Q13 | D-41 | 5a |
| `cfg.loyalty.period` | S | enum(month, quarter) | month | enum | G | P | S | both | C3 | FD. Mid-period change re-buckets balances | D-41 | 5a |
| `cfg.loyalty.cash_rate_mtk_per_point` | S | money_mtk | 2000 (2 Tk per point) | 0 to 100,000 | G W | P | B* | both | C3 | 20 Tk would pay 10 times on confirm; the redemption stores the rate used. Replaces `cash_per_point` | D-41; G-man-042 | 5a |
| `cfg.loyalty.cash_max_points` | S | int | 199 | 0 to 100,000 | G W | P | B | both | C2 | Scope of the cap unknown. unknown; confirm: MQ-22 | G-man-042 | 5a |
| `cfg.loyalty.gift_catalog` | S | content(app.gift_catalog) | kitchen rack 200, chair 200, tornado fan 400 points; more below the fold of the manual | points 1 to 100,000; code immutable | G W D | P | B | both | C2 | A redemption stores gift code and points at the time | D-41, D-219 | 5a |
| `cfg.loyalty.earning_rules` | S | content(app.loyalty_earn_rule) | seed: POSM survey Q1.1 photo gives 50 points (not for an AMO survey); others unknown | schema-validated | G W | P | S | srv | C3 | Points are computed on the server; rules are future-dated and never recompute closed periods | D-41; G-feat-20 | 5a |
| `cfg.loyalty.expiry_days` | S | int | none (April league expires 2026-05-07; the rule is unknown) | 0 to 365 | G | P | S | srv | C3 | Nightly expiry ledger row. unknown; confirm | D-41; G-man-040 | 5a |
| `cfg.loyalty.redemption_requires_photo` | S | bool | true | n/a | G | P | B | both | C1 | "Gift Verify" photo | docs/06 | 5a |
| `cfg.loyalty.redemption_roles` | S | list<role> | [sr, amo] | subset | G | P | B | both | C1 | | docs/06 | 5a |
| `cfg.loyalty.negative_balance_policy` | S | enum(accept_and_flag, reject) | accept_and_flag | enum | G | P | B | srv | C2 | Accept when the device's balance was sufficient; reject `insufficient_points` when it knew it was not | D-266 | 5a |
| `cfg.loyalty.manual_adjust_max_points` | S | int | 1000 (A) | 0 to 100,000 | G | FIN | B | srv | C2 | Above it an adjustment needs two persons | F-ADM-035 | 5a |
| `cfg.astha.program_active` | S | bool | true (A) | n/a | G W | P | B | both | C2 | unknown; confirm: Q13 | D-41 | 5a |
| `cfg.astha.quarter_start_month` | S | int | 1 (Q-4 = Oct to Dec) | 1 to 12 | G | P | S | both | C3 | FD | docs/06 | 5a |
| `cfg.astha.tier_attribution` | S | enum(enrolment, sale_date) | enrolment (A) | enum | G | P | S | srv | C1 | Which tier a sale counts for when an outlet changes tier mid-quarter; the report shows the choice. Added at the editorial merge from doc 16 s10 | G-16-19; G-analyst-13 | 5a |
| `cfg.astha.tiers` | S | content(app.sub_channel) | Platinum, Gold, Diamond, Silver | master data | G | P | B | both | C1 | Sub-channels of channel Astha | D-258 | 5a |
| `cfg.astha.brands_in_scope` | S | list<brand_id> | Maxim, Black Diamond, Abul Bidi Style, Marise, Avon, Supreme, Special Abul Bidi, Abul Bidi Gold | ids exist | G W | P | B | both | C1 | | docs/06 | 5a |
| `cfg.astha.gift_catalog` | S | content(app.gift_catalog) | Ceiling Fan (56 inch), 24 pcs Dinner Set, 27 pcs Dinner Set, by tier | n/a | G W | P | B | both | C1 | | G-man-047 | 5a |
| `cfg.astha.gift_choice_roles` | S | list<role> | [tso] | subset | G | P | S | srv | C1 | The web Astha Gift Choice Panel | D-192 | 5a |
| `cfg.astha.gift_choice_lock` | S | enum(none, on_sr_photo) | on_sr_photo (A) | enum | G | P | S | srv | C1 | Choice locks once the SR's photo exists. unknown; confirm: MQ-26 | D-192 | 5a |
| `cfg.astha.one_photo_per_outlet` | S | bool | true | n/a | G | P | B | both | C1 | | docs/06 | 5a |
| `cfg.astha.target_entry_roles` | S | list<role> | [admin, tso] | subset | G | P | S | srv | C2 | | docs/10 | 5a |
| `cfg.astha.memo_target_month_filter` | S | enum(ignore, apply) | ignore (Apsis ignores the month chips on the memo target) | enum | G | P | S | srv | C1 | Recompute behaviour unproven (I-24) | D-231 | 5a |
| `cfg.astha.tier_requires_tso_approval` | S | bool | true | n/a | G | P | S | srv | C2 | The AMO sets the tier at verification; gifts follow the tier | G-fraud-23 | 3a |
| `cfg.superstar.program_active` | S | bool | false (A until Q13) | n/a | G W | P | B | both | C2 | | D-332 | 5b |
| `cfg.superstar.slabs` | S | content(app.program_enrolment) | empty | schema-validated | G W | P | S | srv | C2 | unknown; confirm: rules | D-332 | 5b |
| `cfg.superstar.criteria_met_rule` | S | json | {std_pct 100, memo_pct 100} (A) | 0 to 200 each | G W | P | S | srv | C2 | | D-332 | 5b |
| `cfg.kpi.bands` | S | list | [at least 100 green, 90 to 100 amber, 80 to 90 orange, below 80 red] | ascending, non-overlapping | G ROLE | P | B | both | C1 | The 4-band set of KPI tables | D-52 | 4a |
| `cfg.kpi.bar_bands` | S | list | green from 80, amber from 40, red below 40 (40 is a placeholder; observed green at 84 and 89, amber 56 to 70, red at 23 and below) | ascending | G ROLE | P | B | both | C1 | Bar colours on AMO and TSO cards. The register's 50 and 90 proposal is rejected. unknown; confirm: one capture between 25 and 55 percent | D-52; R-18 | 3a |
| `cfg.kpi.card_pct_cap` | S | int | 100 | 100 to 1000 | G | P | B | both | C1 | AMO and TSO summary cards only | D-50 | 3a |
| `cfg.kpi.submit_pct_denominator` | S | enum(logged_in_routes, target_routes) | logged_in_routes | enum | G ROLE | P | B | srv | C2 | Selects which figure a tile captioned "Submit %" shows; the API returns both with their basis | D-45 | 3b |
| `cfg.kpi.bsr_denominator` | S | enum(total_memos, target_outlets) | total_memos | enum | G | P | S | srv | C3 | FD. unknown; confirm: Q9 | D-47 | 4b |
| `cfg.kpi.cpr_counts_zero_sale_as_successful` | S | bool | false | n/a | G | P | S | srv | C3 | Changes CPR nationally. unknown; confirm: docs/22 P-05 | D-46, D-249 | 4a |
| `cfg.kpi.count_abandoned_visits` | S | bool | false | n/a | G | P | B | both | C2 | | D-38 | 2a |
| `cfg.kpi.target_route_kinds` | S | list | [sr] | subset of sr, amo | G | P | S | srv | C3 | AMO routes are outside Login %, Submit % (of logged-in) and Day-completion %; replaces the register's `login_include_amo_routes` | D-29 | 1c |
| `cfg.kpi.tilldate_basis.<surface>` (4 keys: `tso_target_status`, `amo_team_performance`, `amo_sales_summary`, `sr_ads`) | S | enum(calendar_incl_today, calendar_through_yesterday, working_incl_today, fixed_ratio) (D-525: exactly the value set of `dw.tilldate_target`, doc 16 s9.5) | calendar_incl_today (TSO 26/30), calendar_through_yesterday (AMO about 25/30), fixed_ratio 15 over 17 (AMO report), working_incl_today (SR, elapsed and remaining working days of the as-of date) | enum | G | P | S | both | C3 | A single global basis is rejected (R-21). unknown; confirm: meaning of 17 and 15, MQ-09, MQ-33 | D-51 | 3a |
| `cfg.kpi.tilldate_rounding.<surface>` (4 keys) | S | enum(ceil_per_item, round_half_up, none) | ceil_per_item, none, none, round_half_up | enum | G | P | S | both | C2 | TSO sums per-item ceilings (3944 gives 3420, 1425 gives 1236); SR ADS rounds half up | D-51, D-58 | 3a |
| `cfg.kpi.tilldate_fraction.amo_sales_summary` | S | json | {num 15, den 17} (A) | den 1 to 31 | G | P | S | srv | C2 | Used when the basis is `fixed_fraction` | D-51 | 3a |
| `cfg.kpi.daily_target_basis` | S | enum(calendar_days, working_days) | calendar_days | enum | G | P | S | both | C2 | Same switch as till-date for the TSO daily target | G-man-073 | 3b |
| `cfg.kpi.dues_buckets` | S | list<int> | [7, 30, 60] (A: 0-7, 8-30, 31-60, 61+ days) | ascending | G | P | S | srv | C1 | Opening balances land in an "opening" bucket | K-18 | 2b |
| `cfg.kpi.retention_definition` | S | text | none: unknown; confirm: Q10 | must name a KPI id of doc 16 s9 and a definition id; free text is refused (D-597) | G | P | S | srv | C2 | Never labelled "retention" on a tile until answered | D-54 | 4b |
| `cfg.calendar.weekend_days` | T | list<dow> | [Fri] (A: unknown; confirm whether Saturday is a selling day, Q27) | 0 to 3 days | G W D | F | S | both | C3 | FD for past dates. Changes Login % denominators and till-date targets for everyone | D-28, D-247 | 1c |
| `cfg.calendar.holidays` | T | content(cfg.holiday) | {date, name_bn, name_en, scope, selling_day}; national list to be entered (Eid break 27 to 31 May, 13 and 19 June seen in the sample) | date ranges | G W D T | F | B | both | C2 | A missing Eid day shows 0 % login on a holiday and pages SRE; edits to past dates are C3 | D-28, D-247 | 1c |
| `cfg.calendar.route_visit_day_exceptions` | T | content(app.route_day_override) | {route, date, planned, reason}; empty | n/a | R | F+TSO | B | both | C1 | Past dates are C3 and reported (G-fraud-21) | G-feat-10; D-28 | 2e |
| `cfg.calendar.retroactive_edit_class` | S | fixed C3 | not editable | n/a | G | n/a | R | srv | C3 | Exceptions and holiday rows with a date before today are two-person | G-fraud-21 | 2e |

#### 3.2.9 Outlets, routes, visits, surfaces for the TSO, content, language and the web back office

`cfg.outlet.*`, `cfg.route.*`, `cfg.visit.*`, `cfg.survey.*`, `cfg.rubric.*`, `cfg.content.*`, `cfg.task.*`, `cfg.leave.*`, `cfg.feedback.*`, `cfg.tso.*`, `cfg.i18n.*`, `cfg.ui.*`, `cfg.notify.*`, `cfg.web.*`, `cfg.sales_plan.*`, `cfg.report.*`, `cfg.dashboard.*`, `cfg.map.*`.

| Key | K | Type | Default | Bounds | Scope | Ed | Eff | Dl | Cls | Note and risk if mis-set | Ref | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.outlet.mobile_regex` | S | text | `^01[3-9][0-9]{8}$` | valid regex | G | F | B | dev | C2 (must pass the stored test-vector set, D-547) | Phones normalised to 11 digits | G-man-037; D-345 | 2c |
| `cfg.outlet.verify_requires_subchannel` | S | bool | true | n/a | G | F | B | both | C1 | AMO sets the sub-channel on verification | G-man-035 | 3a |
| `cfg.outlet.verify_requires_geo_class` | S | bool | true | n/a | G | F | B | both | C1 | 19 percent of outlets have no geo class (docs/22 P-14); the gap closes as AMOs verify | G-man-035 | 3a |
| `cfg.outlet.amo_request_approval` | S | enum(web_approve_required, amo_final) | web_approve_required | enum | G | F | B | srv | C2 | Pending, verified (AMO app or web), approved or rejected on the web only | D-43; G-man-033 | 3a |
| `cfg.outlet.close_block_if_dues` | S | enum(off, warn, block, write_off_queue) | warn | enum | G W | F | B | both | C2 | `write_off_queue` routes to finance (F-ADM-036); retires `close_with_dues_policy` | D-25 | 2c |
| `cfg.outlet.verify_roles` | S | list<role> | [amo, tso] | subset | G | F | B | srv | C2 | | G-man-034 | 3a |
| `cfg.outlet.approve_roles` | S | list<role> | [tso, admin] | subset | G | F | B | srv | C2 | The Outlet Approval Panel | G-man-034 | 3a |
| `cfg.outlet.reject_requires_reason` | S | bool | true (A) | n/a | G | F | B | srv | C1 | A rejection is shown to the SR (IMPROVEMENT) | D-43 | 3a |
| `cfg.outlet.wholesale_marking_roles` | S | list<role> | [tso] | subset | G | F | B | srv | C2 | | G-man-036 | 6a |
| `cfg.outlet.wholesale_unmark_allowed` | S | bool | false (A: the manual shows no unflag) | n/a | G | F | B | srv | C2 | | D-42 | 6a |
| `cfg.outlet.wholesale_price_type` | S | enum(cc, distributor, outlet) | cc | enum | G | F | B | srv | C2 | Price list of wholesale outlets. unknown; confirm: Q46, Q17 | D-32, D-42 | 6a |
| `cfg.outlet.sell_before_approval` | S | bool | true | n/a | G W | F | B | both | C2 | A new outlet may be sold to the same day with a provisional outlet uuid | D-326 | 2c |
| `cfg.outlet.verify_requires_onsite_fix` | S | bool | true (new outlets and large moves) | n/a | G | F | B | both | C2 | A verifier more than the radius from the proposed point is accepted but flagged `remote_verification` | D-111 | 3a |
| `cfg.outlet.supervisor_request_verifier` | S | enum(tso, web) | tso | enum | G | F | B | srv | C2 | AMO-originated requests are verified by the TSO | G-fraud-06 | 3a |
| `cfg.outlet.placeholder_pin_min_shared` | S | int | 3 (A: two shops in one building are legitimate; the one key of the concept, D-525) | 2 to 20 | G | O | B | srv | C2 | Outlets on such pins are `location_confirmed` false | D-253 | 2d |
| `cfg.route.allow_unplanned_day` | S | bool | true | n/a | G | F | B | both | C2 | Visits flagged `unplanned_day`; never inflate CPR | D-57 | 2a |
| `cfg.route.cover_max_days` | S | int | 7 | 1 to 31 | G | F | B | srv | C1 | Same-day cover assignment length | D-85 | 3a |
| `cfg.route.cover_requires_tso_approval` | S | bool | false | n/a | G W | F | B | srv | C2 | unknown; confirm: Q44 | D-85 | 3a |
| `cfg.visit.outcome_codes` | S | content(app.reason_code) | sold, zero_sale_stock_ok, closed, owner_absent, refused, competitor_exclusive, not_reached, abandoned | codes immutable | G | F | B | both | C1 | unknown; confirm: Q50 | D-38 | 2a |
| `cfg.visit.closed_streak_task` | S | int | 3 | 0 to 10 (0 = off) | G W | F | B | srv | C1 | Consecutive closed outcomes raise a task to the AMO | D-38 | 2a |
| `cfg.visit.abandon_min` | S | int | 120 | 15 to 480 | G | F | B | both | C1 | An opened visit with no outcome is abandoned after this | doc 17 | 2a |
| `cfg.survey.posm_questions` | S | content(app.survey_question) | Q1 yes/no required; Q1.1 photo conditional (50 points) | at most 30 active; key immutable | G W D T Z | P | B | both | C1 | Deactivate, never delete, a question with answers | G-man-043; G-feat-12 | 2a |
| `cfg.survey.amo_questions` | S | content(app.survey_question) | pending the AMO survey screenshot | at most 30 | G | P | B | both | C1 | Earns no points | D-348 | 3a |
| `cfg.survey.tso_visit_query_questions` | S | content(app.survey_question) | two free-text Bangla questions as printed, plus a built-in "delegate task" radio defaulting to No | n/a | G | P | B | both | C1 | | D-199 | 3b |
| `cfg.survey.photo_required` | S | bool | true | n/a | G T | P | B | both | C1 | | docs/06 | 2a |
| `cfg.rubric.joint_call` | S | content(app.assessment_rubric) | three known items plus two disabled placeholders | 1 to 50 items, scale 1 to 5 | G W | P | B | both | C1 | | D-349 | 3a |
| `cfg.rubric.require_all_rated` | S | bool | false | n/a | G | P | B | dev | C1 | | G-man-052 | 3a |
| `cfg.rubric.default_rating` | S | int | 1 (parity; null allowed) | 1 to 5 or null | G | P | B | dev | C1 | | D-349 | 3a |
| `cfg.rubric.distribution_brands` | S | list<brand_id> | 15 brands in the manual's order | ids exist and `sales_enable` | G W D T Z | P | B | both | C1 | Falls back to the zone sales plan if empty | G-man-050 | 3a |
| `cfg.content.avkv_items` | S | content(app.content_item) | none | media at most `max_item_mb` | G W D T Z | P | B | both | C1 | Downloaded Wi-Fi preferred, LRU cached | G-feat-12 | 2e |
| `cfg.content.max_item_mb` | S | int | 8 (A) | 1 to 20 (D-546) | G | O | B | srv | C2 | A 50 MB video pushed to 8,500 phones over mobile data breaks R4 | docs/04 | 2e |
| `cfg.content.download_network_policy` | S | enum(wifi_only, wifi_preferred, any) | wifi_only | enum | G W | O | B | dev | C2 | | docs/04 | 2e |
| `cfg.content.tutorial_videos` | S | content(app.tutorial_asset) | none (the SR list may be empty) | https urls | G ROLE | O | B | both | C0 | The four manuals and videos by role | G-feat-47 | 2e |
| `cfg.task.types` | S | content(app.task_type) | oos, general, irregular_visit | codes immutable | G | F | B | both | C1 | | G-man-049 | 5c |
| `cfg.task.statuses` | S | content(app.reason_code) | ongoing (চলমান), completed (সম্পন্ন) | n/a | G | F | B | both | C1 | Labels map to the status enum | D-167 | 5c |
| `cfg.task.description_max_len` | S | int | 500 (A) | 50 to 2000 | G | F | B | dev | C0 | | G-man-049 | 5c |
| `cfg.task.allow_past_due_date` | S | bool | false (A) | n/a | G | F | B | dev | C0 | | G-man-049 | 5c |
| `cfg.task.default_due_days` | S | int | 3 (A) | 1 to 30 | G | F | B | dev | C0 | | G-feat-19 | 5c |
| `cfg.task.overdue_escalation_days` | S | int | 2 (A) | 0 to 30 | G | F | B | srv | C1 | | G-feat-19 | 5c |
| `cfg.task.assign_roles` | S | list<role> | [amo, tso] | subset | G | F | B | srv | C1 | | docs/06 | 5c |
| `cfg.tso.assignable_task_types` | S | list | [irregular_visit] | subset of task types | G | F | B | both | C1 | Evidenced in the TSO manual | G-man-082 | 3b |
| `cfg.leave.types` | S | content(app.leave_type) | Casual, Sick, Earn; day caps unknown | n/a | G | F | B | both | C1 | unknown; confirm | G-man-074 | 3b |
| `cfg.leave.approver_role_by_applicant_role` | S | json | {tso: dmo} | roles valid | G | F | B | srv | C2 | SR and AMO leave is not built (D-337) | D-337 | 3b |
| `cfg.leave.max_consecutive_days` | S | int | none (the lens 30 is invented) | 1 to 90 or none | G | F | B | dev | C1 | Typed day count is authoritative (D-176) | D-176, D-197 | 3b |
| `cfg.leave.allow_past_days` | S | bool | true (unknown; confirm) | n/a | G | F | B | dev | C1 | | G-man-075 | 3b |
| `cfg.leave.block_overlap` | S | bool | false | n/a | G | F | B | srv | C1 | | G-man-075 | 3b |
| `cfg.leave.balance_enforced` | S | bool | false | n/a | G | F | B | srv | C1 | | G-man-075 | 3b |
| `cfg.feedback.categories` | S | content(app.reason_code) | [Suggestion] only (the lens Complaint and Bug are invented) | n/a | G | O | B | both | C0 | | G-man-083; D-197 | 3b |
| `cfg.feedback.max_images` | S | int | 1 | 1 to 5 | G | O | B | dev | C0 | | G-man-083 | 3b |
| `cfg.tso.periphery_radius_options_m` | S | list<int> | [50, 100, 300] | 1 to 6 values, 10 to 5000 | G W | F | B | dev | C0 | Replaces three lens spellings | G-man-078 | 3b |
| `cfg.tso.periphery_default_radius_m` | S | int | 50 (first option) | in the options | G | F | B | dev | C0 | | G-man-078 | 3b |
| `cfg.tso.periphery_max_markers` | S | int | 300 (A) | 50 to 2000 | G | O | B | dev | C1 | | G-man-078 | 3b |
| `cfg.tso.team_location_max_age_min` | S | int | 120 (A) | 5 to 1440 | G | F | B | both | C1 | Older fixes are greyed; applies to the AMO too | D-170 | 3a |
| `cfg.tso.visit_plan_max_outlets` | S | int | none (routes hold a median 64 and a maximum 214) | 1 to 500 or none | G | F | B | dev | C1 | The lens 30 is invented | D-175, D-197 | 3b |
| `cfg.tso.plan_backdate_days` | S | int | 0 | 0 to 7 | G | F | B | srv | C1 | | G-man-079 | 3b |
| `cfg.tso.plan_max_days_ahead` | S | int | 7 (A) | 1 to 60 | G | F | B | srv | C1 | | G-man-079 | 3b |
| `cfg.tso.visit_query_answer_max_chars` | S | int | 500 (A) | 50 to 2000 | G | O | B | dev | C0 | | D-199 | 3b |
| `cfg.tso.product_scope` | S | list<category_id> | all tobacco categories (A). unknown; confirm: Q14 (Digonto) | ids exist | ROLE W | P | S | both | C1 | | Q14 | 3b |
| `cfg.tso.dashboard_tiles` | S | content(list) | {category, unit_label, report_factor, decimals}: Cigarette (sticks), Bidi, Lighter (boxes on the TSO app, pieces on the web), Match (dozen) | json_schema: report_factor positive, from the fixed set `cfg.sys.report_factor_allowed` {1, 0.1, 0.01, 0.001, 12, 20, 24}; decimals 0 to 3; the drawer shows yesterday's tile before and after (D-597) | ROLE | P | B | both | C2 | | D-193 | 3b |
| `cfg.tso.dashboard_cards` | S | list | sales, channel, CPR, segment, brand, login | fixed ids | ROLE | P | B | both | C0 | | G-man-076 | 3b |
| `cfg.i18n.digit_script` | S | enum(follow_ui, latin, bn) | follow_ui (the printed memo follows by default; stored values are ASCII). unknown; confirm from the physical samples | enum | G ROLE | O | B | dev | C0 | Replaces `numeral_system` and the screenshot key `cfg.locale.digits` | D-344 | 1a |
| `cfg.i18n.grouping` | S | enum(western, lakh) | western | enum | G | O | B | dev | C0 | | G-man-103 | 1a |
| `cfg.i18n.date_style` | S | enum(iso_lists_long_pickers) | ISO in lists, long in pickers | enum | G | O | B | dev | C0 | | G-man-103 | 1a |
| `cfg.i18n.overrides` | S | content(app.i18n_override) | none | key exists in the bundled catalogue | G W ROLE | O | B | dev | C0 | The business fixes a Bangla label without a release; a missing key falls back to the bundled string | G-man-101 | 2a |
| `cfg.ui.date_format` | S | text | YYYY-MM-DD (web) | fixed formats | G | O | B | srv | C0 | | G-man-103 | 4a |
| `cfg.ui.outlet_badges` | S | content(cfg.ui_badge) | four dots: red, green, magenta = promotion groups 1 to 3 (names unknown), emblem = Astha. unknown; confirm: Q-UI-02 | n/a | G | F | B | both | C1 | Per-outlet flags travel in the bundle | D-341 | 2a |
| `cfg.notify.events` | S | list | [task_assigned] | subset of defined events | G | O | B | srv | C1 | Push only for these events. unknown; confirm: Q-UI-07 | D-84 | 3a |
| `cfg.web.entry_backdate_days` | S | int | 0 (today only; A) | 0 to 31 | G Z | F | B | srv | C2 | Web entry window; separate from the app sync window (D-97) | D-97; G-man-087 | 4c |
| `cfg.web.entry_cutoff_source` | S | enum(rolling, zone_data_entry_date) | rolling | enum | G | F | B | srv | C2 | The Sales Plan "Data Entry Date" is the lever only if this says so. unknown; confirm: MQ-44 | D-439 | 4c |
| `cfg.web.entry_unlock_roles` | S | list<role> | [finance_admin, support] | subset | G | S | B | srv | C2 | Who may raise an unlock grant | D-439 | 4c |
| `cfg.web.entry_unlock_max_days` | S | int | 7 (A) | 1 to 31 | G | S | B | srv | C2 | | D-439 | 4c |
| `cfg.web.entry_unlock_ttl_h` | S | int | 24 (A) | 1 to 168 | G | S | B | srv | C2 | | D-439 | 4c |
| `cfg.web.delete_section_data_roles` | S | list<role> | [tso, admin] (A) | subset | G | S | B | srv | C3 | | D-438; G-man-086 | 4c |
| `cfg.web.delete_section_data_scope` | S | enum(web_entry_only, include_app_memos) | web_entry_only | enum | G | S | B | srv | C3 | App memos need ops_admin and two persons | D-438 | 4c |
| `cfg.web.delete_section_data_before_final_only` | S | bool | true | n/a | G | S | B | srv | C3 | | D-40 | 4c |
| `cfg.web.delete_confirm_required` | S | bool | true | n/a | G | F | B | srv | C2 | IMPROVEMENT; the manual shows no confirm | D-438 | 4c |
| `cfg.web.entry_classes` | S | list<sub_channel> | [GT] (A: the sample shows one GT column) | subset | G Z | F | B | srv | C1 | unknown; confirm: channel or sub-channel columns | G-man-085 | 4c |
| `cfg.web.entry_validate_calls_le_target` | S | bool | true (A) | n/a | G | F | B | srv | C1 | Successful calls cannot exceed the target-outlet snapshot | D-437 | 4c |
| `cfg.web.entry_enabled_zones` | S | list<zone> | all | zone ids | G | F | B | srv | C1 | | G-man-085 | 4c |
| `cfg.web.entry_app_overlap_policy` | S | enum(exclusive_flag, replace, add) | exclusive_flag | enum | G | F | B | srv | C2 | Web rows and app rows for one route-day are never added | D-40 | 4c |
| `cfg.web.menu_by_role` | S | json role x menu x action | The seed matrix of s5.3 | menu ids from the page registry | ROLE | S | B | srv | C3 | The menu hides; the server enforces permissions per action | D-185, D-448; G-man-099 | 4c |
| `cfg.sales_plan.edit_roles` | S | list<role> | [tso, admin] | subset | G | F | B | srv | C2 | TSO edits own zones | G-man-088 | 6a |
| `cfg.report.default_range` | S | enum(month_to_yesterday) | month_to_yesterday | enum | G ROLE | O | B | srv | C0 | | G-man-063 | 4b |
| `cfg.report.include_today` | S | bool | false | n/a | G ROLE | O | B | srv | C0 | | G-man-063 | 4b |
| `cfg.report.max_range_days` | S | int | 366 (A) | 1 to 1000 | G ROLE | O | B | srv | C1 | | G-man-063 | 4b |
| `cfg.report.page_size_default` | S | int | 10 | in options | G | O | B | srv | C0 | | G-man-095 | 4b |
| `cfg.report.page_size_options` | S | list<int> | [10, 20, 50] | 1 to 6 values | G | O | B | srv | C0 | | G-man-095 | 4b |
| `cfg.report.lists.<name>` (6 keys: `location`, `date_grouping`, `product_type`, `active_status`, `classification_type`, `report_type`) | S | content(list) | values not shown in the manual | json_schema: at most 100 items, each from the registered code table; bn and en labels required (D-597) | G | O | B | srv | C0 | Report filter code lists | G-man-095 | 4b |
| `cfg.report.std_criteria_divisor` | S | int | 1000 ("STD Criteria ('000)") | 1 to 1,000,000 | G | O | B | srv | C0 | | G-man-095 | 4b |
| `cfg.report.amo_call_types` | S | list | [summary] | json_schema: values from the registered call types (D-597) | G | O | B | srv | C0 | | G-man-098 | 4b |
| `cfg.report.interactive_row_limit` | S | int | 50,000 | 1,000 to 500,000 | G ROLE | O | B | srv | C1 | Above it a report is an async export | doc 18 | 4b |
| `cfg.report.export_concurrency` | S | int | 8 | 1 to 32 | G | O | B | srv | C1 | Fleet-wide export jobs | doc 18 | 4b |
| `cfg.dashboard.tiles` | S | content(list) | The web tile set and order of Web p3 | fixed ids | G ROLE | P | B | srv | C0 | | G-man-096 | 4a |
| `cfg.dashboard.unit_labels` | S | json | {Lighter Pcs, Match Dozen, Cigarette Sticks, Bidi Sticks} | json_schema: label from the registered unit list (Sticks, Pcs, Dozen, Box, Packs); both bn and en required (D-597) | G | P | B | srv | C1 | Report units differ by surface | D-193 | 4a |
| `cfg.dashboard.channels` | S | list | GT, DCC, Astha, RCC, MT, HoReCa | subset of channels | G | P | B | srv | C0 | | G-man-096 | 4a |
| `cfg.dashboard.tile_info` | S | content(map bn and en) | Tooltips of the "i" icons | n/a | G | P | B | srv | C0 | | G-man-096 | 4a |
| `cfg.map.provider` | S | enum(google, maplibre) | google (parity). unknown; confirm: provider, quota, cost (MUST-CONFIRM by 3a) | enum | G | O | B | dev | C2 | MapLibre is the fallback | D-08; G-man-059 | 3a |
| `cfg.map.api_key_ref` | S | text | Key Vault reference | reference exists | G | O | S | srv | C2 | The key is restricted by package name and signing SHA | D-08 | 3a |
| `cfg.map.tile_cache_mb` | S | int | 20 | 5 to 100 | G | O | B | dev | C1 | Replaces `cfg.map.cache_mb` | D-08 | 3a |
| `cfg.map.3d_enabled` | S | bool | false | n/a | G | O | B | dev | C0 | 3D is not required | D-08 | 3a |

#### 3.2.10 Keys added by the round-2 gap resolution (D-500 to D-553)

The skeptic review of documents 14 to 21 added the keys below; each row follows the columns of s3.1 and is part of the seed that T-0-60 compares with `config_item`. Two keys are retired in the same pass: `cfg.ops.dashboard_default_date_rule` (D-544: `today` is the single default) and `cfg.geo.placeholder_min_shared` (D-525: `cfg.outlet.placeholder_pin_min_shared`). Existing keys whose bounds, class or default changed (the budget-linked keys, the invariants, the till-date enum, the print width, `cfg.retention.sync_batch_response_h`, the login-alert time and the pre-scale schedule) are changed in place in s3.2.1 to s3.2.9 and listed in s2.6, s3.3 and s3.4.

| Key | K | Type | Default | Bounds | Scope | Ed | Eff | Dl | Cls | Note | Ref | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.sync.checkout_upload_jitter_max_s` | S | int | 90 | 0 to 120 | G | O | B | dev | C1 | Random upload delay for check-out and Sales Submit when the 17:00 clock gate is the only reason for the upload; the offline-completed local state does not wait | D-505 | 2e |
| `cfg.sync.max_savepoints_per_tx` | S | int | 60 | 8 to 60 | G | O | B | srv | C2 | Ingest bisection cap: never one savepoint per record; above 64 subtransactions overflow the subxid cache | D-521 | 1b |
| `cfg.sync.resync_safety_margin_h` | S | int | 6 | 1 to 24 | G | O | B | srv | C2 | Margin subtracted from the server restore point when a device flips synced rows back to pending after a generation change | D-517 | 1b |
| `cfg.sync.digest_days` | S | int | 3 | 1 to 7 | G | O | B | both | C1 | Business dates covered by the count-and-bucket digest of a reconcile | D-517 | 1b |
| `cfg.sla.pending_rows_alert_h` | S | int | 4 | 1 to 24 | G | O | B | srv | C1 | A device holding pending rows with no contact for this long enters the held-rows list for the TSO and the helpdesk | D-511 | 2e |
| `cfg.release.auto_freeze_regression_pct` | S | pct | 20 | 5 to 50 | G | R | B | srv | C2 | A canary cohort that regresses on field CPU, engine starts or battery drop by more than this against the previous release freezes `wave_pct` and raises an alert | D-507 | 6c |
| `cfg.telemetry.bat17_floor_pct` | S | pct | 35 | 20 to 60 | G | O | B | srv | C1 | The p10 of the whole-device battery at 17:00 below this value raises SH-21 | D-507 | 4a |
| `cfg.telemetry.enabled` | O | bool | true | n/a | G W | O | B+P | dev | C1 | Remote brake: the daily telemetry object off for a wave or a canary cohort, mandatory duration; never touches capture, sync of records or printing | D-507 | 2e |
| `cfg.ops.prefetch_enabled` | O | bool | true | n/a | G W | O | B+P | dev | C1 | Remote brake: the Wi-Fi bundle pre-fetch off for a wave or a canary cohort, mandatory duration | D-507 | 2e |
| `cfg.day.sales_submit_locks_capture` | S | bool | true | n/a | G | F | B | both | C2 | True: a Sales Submit locks further capture on the route-day (ASSUMPTION: parity unknown, OI-17-14); a submit void unlocks it | D-539 | 2e |
| `cfg.day.submit_undo_window_min` | S | int | 120 | 15 to 720 | G | F | B | srv | C2 | Minutes after a Sales Submit in which L1 support with TSO confirmation, or the TSO, may void it; ops_admin may void until Final Submit | D-539 | 3b |
| `cfg.day.bulk_absent_max_routes` | S | int | 60 | 1 to 200 | G | F | B | srv | C1 | Routes an AMO may mark absent in one bulk action | D-551 | 3a |
| `cfg.route.vacancy_alert_days` | S | int | 3 | 1 to 31 | G | F | B | srv | C1 | A route with no assigned SR for this many planned days enters the vacancy list | D-551 | 3a |
| `cfg.user.dismissal_upload_grace_h` | S | int | 48 | 0 to 168 | G | S | B | srv | C2 | How long a disabled user's phone may still upload rows captured before the disable (the upload-only grant) | D-551 | 2e |
| `cfg.user.admin_checker_required` | S | bool | true | n/a | G | S | B | srv | C3 | Maker-checker for every user_admin action: the checker is not the maker | D-551 | 2e |
| `cfg.entry.paper_backfill_window_days` | S | int | 7 | 1 to 30 | G | F | B | srv | C2 | Days after the business date within which a paper memo may be backfilled by the supervised procedure | D-543 | 2e |
| `cfg.outlet.location_request_min_move_m` | S | int | 25 | 5 to 100 | G | F | B | both | C2 | No location request is raised when the proposed point is within this distance of the current or provisional point | D-545 | 2d |
| `cfg.outlet.location_request_max_accuracy_m` | S | int | 50 | 10 to 100 | G | F | B | both | C2 | A proposal from a fix less accurate than this, or a mocked fix, raises no request (the visit is still a force sale) | D-545 | 2d |
| `cfg.outlet.bulk_approve_consistent_m` | S | int | 30 | 10 to 100 | G | F | B | srv | C2 | Supervisor bulk approve: proposals within this distance of each other count as consistent | D-545 | 3a |
| `cfg.outlet.bulk_approve_min_evidence` | S | int | 3 | 2 to 10 | G | F | B | srv | C2 | Independent visits that must agree before a proposal joins a bulk approve | D-545 | 3a |
| `cfg.outlet.mobile_regex_test_vectors` | S | json | 10 valid and 10 invalid mobile numbers | at least 10 and 10 | G | F | B | srv | C2 | The stored test-vector set every `cfg.outlet.mobile_regex` must pass | D-547 | 2c |
| `cfg.sla.location_request_escalate_h` | S | int | 72 | 24 to 336 | G | F | B | srv | C1 | A location request unresolved this long goes to the TSO queue | D-545 | 3a |
| `cfg.sla.location_request_lapse_days` | S | int | 14 | 3 to 60 | G | F | B | srv | C1 | A location request unresolved this long lapses and its provisional point is retired | D-545 | 3a |
| `cfg.sla.location_request_backlog_alert` | S | json | {amo: 25, tso: 250} | amo 5 to 200, tso 50 to 2000 | G | O | B | srv | C1 | Open location requests per AMO and per TSO that raise a backlog alert (its own key; the quarantine alert keeps its 500) | D-545 | 3a |
| `cfg.sla.config_reach_urgent_min` | S | int | 5 | 2 to 30 | G | O | B | srv | C1 | Target for an urgent revert (kill-switch class, push on) to reach 95 percent of push-enabled devices | D-526 | 2d |
| `cfg.sla.config_reach_tail_h` | S | int | 24 | 1 to 168 | G | O | B | srv | C1 | Window of the tail metric: share of ALL active devices of the scope reached, with the unreached count by reason | D-526 | 2d |
| `cfg.kpi.leaderboard_min_switched_pct` | S | pct | 80 | 0 to 100 | G | P | B | srv | C1 | The leaderboard ranks only territories with at least this share of routes switched to Aron; below it the row shows partial | D-548 | 4a |
| `cfg.calendar.emergency_declare_roles` | S | list<role> | [ops_admin] | subset of roles | G | S | B | srv | C3 | Roles that may declare an emergency non-working day (kind emergency_off); L2 may propose | D-542 | 2e |
| `cfg.calendar.emergency_max_days` | S | int | 3 | 1 to 7 | G | F | B | srv | C2 | Length of one emergency declaration | D-542 | 2e |
| `cfg.geo.radius_emergency_max_m` | S | int | 250 | 100 to 500 | G | S | B | srv | C3 | Ceiling of the emergency-widen lane under break-glass | D-527 | 2d |
| `cfg.geo.emergency_widen_max_hours` | S | int | 6 | 1 to 12 | G | S | B | srv | C3 | An emergency widen auto-expires after this many hours | D-527 | 2d |
| `cfg.geo.radius_incident_ceiling_m` | S | int | 300 | 150 to 500 | G | S | B | srv | C3 | Ceiling of the temporary-relief path (two approvers, no canary, time-boxed) | D-527 | 2d |
| `cfg.sys.temporary_relief_max_h` | S | int | 12 | 1 to 24 | G | S | B | srv | C3 | A temporary-relief change auto-expires after this many hours | D-527 | 2d |
| `cfg.sys.break_glass_holders_min` | S | int | 2 | 2 to 6 | G | S | B | srv | C3 | Named break-glass holders required per shift on a wave day, with an audited hand-over | D-527 | 2d |
| `cfg.auth.refresh_absolute_jitter_days` | S | int | 15 | 0 to 30 | G ROLE | S | S | srv | C3 | Per-family random spread (plus or minus) of the absolute refresh lifetime drawn at mint so a wave cohort does not expire on one day | D-518 | 0c |
| `cfg.auth.refresh_renew_warn_days` | S | int | 75 | 30 to 170 | G ROLE | S | S | both | C2 | Day of the absolute lifetime from which the app warns and offers renewal by device-key proof or a password prompt on Wi-Fi | D-518 | 2e |
| `cfg.auth.offline_grace_after_absolute_expiry_days` | S | int | 7 | 0 to 14 | G ROLE | S | S | both | C3 | Offline unlock after the absolute refresh expiry with upload-only behaviour, so a dead-zone SR can still sell | D-518 | 2e |

#### 3.2.11 Keys added by the round-3 gap resolution (D-554 to D-601)

The third skeptic pass added the 49 keys below (46 kind S, 3 kind T; classes C0 2, C1 19, C2 17, C3 11). Each row follows the columns of s3.1 and is part of the seed that T-0-60 compares with `config_item`. Existing keys whose bounds, class or note changed (the SLA alert thresholds, `password_complexity`, `tso.dashboard_tiles`, `dashboard.unit_labels`, `oem_guidance`, `retention_definition`, `report.lists.<name>`, `amo_call_types`, the window keys `stale_max_days`, `max_backdate_days`, `offline_unlock_max_days`, the dependency-bound keys `ingest_registry_days`, `resync_window_h`, `config_accept_window_h`, and the brake-lane keys) are changed in place in s3.2.4 to s3.2.9, and the device-model scope level and the 14 dependency rules are in s2.2 and s2.6b.

| Key | K | Type | Default | Bounds | Scope | Ed | Eff | Dl | Cls | Note | Ref | Ph |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `cfg.sla.online_window_min` | S | int | 20 | 5 to 120 | G | O | B | srv | C1 | Online for the reach measure: any authenticated request inside this window; selling adds a batch or bundle request (s4.1b) | D-563 | 2d |
| `cfg.sla.config_reach_selling_min` | S | int | 15 | 5 to 60 | G | O | B | srv | C1 | Wall-clock bound for the selling cohort of an ordinary or urgent change with push off | D-563 | 2d |
| `cfg.sla.config_reach_selling_pct` | S | pct | 95 | 80 to 100 | G | O | B | srv | C1 | Share of the selling cohort that must hold the version inside the bound | D-563 | 2d |
| `cfg.sync.config_check_min_gap_min` | S | int | 5 | 1 to 60 | G | O | B | dev | C1 | Resume check: a conditional config request when online and the last contact is older than this; no timer | D-563 | 2d |
| `cfg.sync.config_check_max_per_day` | S | int | 24 | 4 to 96 | G | O | B | dev | C1 | Cap on resume checks per device and day (about 300 B each) | D-563 | 2d |
| `cfg.sys.config_apply_grace_min` | S | int | 10 | 1 to 60 | G | S | B | srv | C2 | Margin between serving a version and expecting its stamp (stamp regress, s2.3b) | D-571 | 2d |
| `cfg.sec.fraud.stamp_regress_min_rows` | S | int | 3 | 1 to 50 (floor 1) | G | S | B | srv | C2 | `config_stamp_regress` rows in one business day that raise FS-34 | D-571 | 2d |
| `cfg.sys.no_grace_tighten_allowed` | S | bool | true | n/a | G | S | B | srv | C3 | A C3 tightening may carry `no_grace` (accept window 0 from its effective instant) | D-571 | 2d |
| `cfg.calendar.window_unit` | S | enum(working_days, calendar_days) | working_days | n/a | G | O | S | both | C3 | Unit of `bundle.stale_max_days`, `sync.max_backdate_days` and `auth.offline_unlock_max_days` | D-584 | 2e |
| `cfg.bundle.stale_max_cal_days_ceiling` | S | int | 7 | 3 to 14 | G | O | B | dev | C2 | Calendar-day hard ceiling on bundle age whatever the working-day count | D-584 | 2e |
| `cfg.calendar.break_overrides` | S | json | [] | list of {from, to, stale_max_days, max_backdate_days, offline_unlock_max_days}; every value within rule 14 of s2.6b | G | F | B | both | C2 | Scheduled per-break windows (Eid, a nine-day break) delivered in the `scheduled` list | D-584 | 2e |
| `cfg.calendar.prefetch_next_working_day` | S | bool | true | n/a | G | O | B | srv | C1 | Job J2 builds the snapshot of the next WORKING day on the last working evening and phones pre-fetch it | D-584 | 2e |
| `cfg.price.max_change_pct` | S | pct | 15 | 1 to 100 | G | FIN | B | srv | C2 | A price type moving by more than this needs `finance_approver`; above 100 percent is refused unless two approvers sign | D-589 | 2e |
| `cfg.price.correction_window_h` | S | int | 24 | 1 to 72 | G | FIN | B | srv | C3 | Same-day correction lane: only rows published within this window | D-589 | 2e |
| `cfg.sys.brake_lane_review_h` | S | int | 24 | 1 to 72 | G | S | B | srv | C3 | Review deadline of a brake-lane move (s7.6c) | D-588 | 2d |
| `cfg.sys.brake_lane_keys` | S | json (read-only) | the table of s7.6c | migration only | G | n/a | R | srv | C3 | Keys that may be braked under break-glass and inside the freeze windows | D-588 | 2d |
| `cfg.sys.device_model_scoped_keys` | S | json (read-only) | the seven keys of s2.2 rule 8 | migration only | G | n/a | R | srv | C3 | Keys that may hold a `device_model` scope row | D-598 | 2d |
| `cfg.sys.report_factor_allowed` | S | list<number> | [1, 0.1, 0.01, 0.001, 12, 20, 24] | positive numbers | G | P | B | srv | C2 | Allowed `report_factor` values of the dashboard tile content keys | D-597 | 3b |
| `cfg.auth.held_bind_sla_min` | S | int | 30 | 5 to 240 | G | S | B | srv | C1 | Selling-hours SLA to release or reject a held bind | D-586 | 2e |
| `cfg.auth.held_bind_delegate_roles` | S | list<role> | [security_admin, ops_admin] | subset of roles | G | S | B | srv | C3 | Who may release when the DMO has not acted in 15 minutes | D-586 | 2e |
| `cfg.sla.held_bind_alert_min` | S | int | 45 | 10 to 480 | G | O | B | srv | C1 | Oldest held bind older than this raises SH-27 and a Sev3 | D-586 | 2e |
| `cfg.auth.unbind_block_if_pending_rows` | S | bool | true | n/a | G ROLE | S | S | srv | C2 | A bind that would displace a device with pending rows is warned and routed through the replace-device wizard (upload first) | D-585 | 3b |
| `cfg.support.directive_ttl_h` | S | int | 24 | 1 to 168 | G | O | B | srv | C1 | Expiry of a signed device directive | D-594 | 2e |
| `cfg.support.directive_types` | S | list<enum> | [send_pda, send_ping, redownload_bundle] | subset of the three plus `clear_media_cache` (C3 to add) | G | S | B | srv | C3 | Directive kinds support may queue | D-594 | 2e |
| `cfg.support.replay_max_rows` | S | int | 5000 | 100 to 20000 | G | O | B | srv | C2 | Rows per replay apply | D-587 | 2e |
| `cfg.support.ticket_categories` | S | content(list) | login, sync, print, route_empty, geo, dues_dispute, device, other | 1 to 30 items | G | O | B | srv | C0 | Categories of the ticket store and the contact log | D-599 | 2e |
| `cfg.sla.ticket_respond_min` | S | int | 15 | 5 to 240 | G | O | B | srv | C1 | First response SLA of a P1 ticket | D-599 | 2e |
| `cfg.sla.ticket_workaround_h` | S | int | 2 | 1 to 24 | G | O | B | srv | C1 | Workaround SLA of a P1 ticket | D-599 | 2e |
| `cfg.support.backfill_memos_per_keyer_h` | S | int | 20 (A) | 5 to 120 | G | O | B | srv | C1 | Planning figure for paper-memo backfill throughput; replaced by the pilot measurement | D-599 | 2e |
| `cfg.support.backfill_l1_enabled` | S | bool | true | n/a | G | S | B | srv | C2 | Trained L1 agents may enter paper-memo backfill, with TSO approval | D-599 | 2e |
| `cfg.master.revert_batch_window_days` | S | int | 14 | 1 to 60 | G | O | B | srv | C2 | A bulk batch can be reverted within this many days | D-595 | 6a |
| `cfg.retention.hold_max_days` | S | int | 365 | 30 to 3650 | G | S | B | srv | C3 | Expiry cap of a retention hold; renewable by a second approver | D-600 | 6c |
| `cfg.retention.hold_roles` | S | list<role> | [security_admin, pii_officer] | subset of roles | G | S | B | srv | C3 | Who may place or lift a hold | D-600 | 6c |
| `cfg.stock.save_mode` | S | enum(increment_only, replace_total) | increment_only | n/a | G | F | B | both | C1 | A stock Save posts only the entered increment; `replace_total` is the parity reading, kept for a confirmation answer (MQ-68) | D-580 | 2a |
| `cfg.stock.correct_total_enabled` | S | bool | true | n/a | G | F | B | both | C1 | The explicit "correct total" path that posts a signed adjustment | D-580 | 2a |
| `cfg.stock.resave_guard_window_min` | S | int | 5 | 0 to 60 | G | F | B | both | C1 | A second Save with the same values for the same SKUs within this window is refused as a double tap | D-580 | 2a |
| `cfg.cutover.apsis_complete_by_time` | T | time | 19:00 | 15:00 to 21:00 | WAVE | O | B | srv | C2 | T-1 deadline by which every wave SR has done the Apsis end-of-day upload and Sales Submit and the Apsis TSO Final Submit is done for the wave zones | D-556 | 7a |
| `cfg.cutover.final_delta_due_time` | T | time | 20:00 | 17:00 to 22:00 | WAVE | O | B | srv | C2 | T-1 time by which the final delta files are due (DL-1 to DL-5) | D-556 | 7a |
| `cfg.cutover.late_delta_time` | T | time | 06:00 | 04:30 to 07:00 | WAVE | O | B | srv | C2 | Day-T late delta (DL-1b) that re-imports dues, loyalty and outlet rows changed since the cut | D-556 | 7a |
| `cfg.sla.apsis_residual_alert` | S | int | 0 | 0 to 5 | G WAVE | O | B | srv | C1 | Apsis memos or collections on an `on_aron` route per route-day above this raise the residual alert and SH-26 (default: the first one alerts) | D-556 | 7b |
| `cfg.sla.sync_success_pct_alert` | S | pct | 97 | 80 to 100 | G | O | B | srv | C1 | Rollback trigger of doc 18 s7.5: batch success below this for 1 h pages Sev1 | D-596 | 4a |
| `cfg.sla.crash_rate_pct_alert` | S | pct | 2 | 0.5 to 10 | G | O | B | srv | C1 | Rollback trigger: crash rate above this pages Sev1 | D-596 | 4a |
| `cfg.sec.dek_cache_min` | S | int | 60 | 5 to 240 | G | S | B | srv | C2 | In-memory cache of an unwrapped PII data key per replica, so Key Vault `kv-pii` is not on the request path | D-574 | 0c |
| `cfg.auth.upload_access_ttl_min` | S | int | 360 | 60 to 1440 | G ROLE | S | B | srv | C3 | Access lifetime of the upload-only grant (`aud = aron-upload`), longer than the full grant so uploads survive a Key Vault signing outage | D-574 | 2e |
| `cfg.geo.country_bbox` | S | json | {lat_min 20.3, lat_max 26.9, lon_min 87.9, lon_max 92.8} | lat 15 to 30, lon 80 to 100 | G | O | B | srv | C2 | The ONE bounding box for `geo_out_of_country` (doc 16 DQ-22 and doc 21 s6.4 both read it) | D-576 | 1b |
| `cfg.sync.resync_late_max_days` | S | int | 14 | 7 to 30 | G | O | B | srv | C2 | Rows of a `resync` batch within this many days of the restore anchor are accepted and flagged `resync_late`, not quarantined; rule 9 of s2.6b counts it | D-572 | 1b |
| `cfg.sla.dss_stale_alert_s` | S | int | 120 | 30 to 900 | G | O | B | srv | C1 | The DSS "data as of" stamp older than this during selling hours raises the staleness alert | D-578 | 4c |
| `cfg.cutover.install_success_pct` | S | pct | 95 | 80 to 100 | WAVE | O | B | srv | C2 | Share of the wave's phones with the signed APK installed and launched on the pre-bind day; below it the wave go/no-go fails (D-562) | D-562 | 7a |
| `cfg.ui.visit_screen_buttons` | S | list<enum> | [force_sale, refresh] (1a); sale_history joins in 2b, points in 5a | subset of force_sale, refresh, sale_history, points, memo_history | ROLE | O | S | dev | C0 | Buttons of the visit screen; Sale History and Points stay hidden until their sub-milestones (F-SR-017, D-583) | D-583 | 1a |


### 3.3 Aliases retired

After merge only the right-hand name exists. Table A is binding (the plan's alias-retirement list). Table B is proposed here to finish the reconciliation the skeleton started; doc 14 confirms at merge (OI-19-03).

**A. Binding retirements**

| Retire | Use |
| --- | --- |
| `cfg.geo.max_speed_mps` | `cfg.geo.max_speed_kmh` |
| `cfg.geo.min_accuracy_m` | `cfg.geo.max_accuracy_m` (100, D-264) |
| `cfg.media.max_bytes` | `cfg.media.photo_max_kb` |
| `cfg.sync.retry_backoff` | `cfg.sync.retry_backoff_s` |
| `cfg.sync.max_clock_skew_s` | `cfg.sync.max_clock_skew_min` |
| `cfg.auth.pw_history`, `pw_min_len`, `pw_min_age_h` | `cfg.auth.password_history_depth`, `password_min_len`, `password_min_age_h` |
| `cfg.auth.offline_session_max_days` | `cfg.auth.offline_unlock_max_days` (D-265) |
| `cfg.loyalty.cash_per_point` | `cfg.loyalty.cash_rate_mtk_per_point` |
| `cfg.periphery.radius_options_m`, `cfg.tso.periphery_radius_options` | `cfg.tso.periphery_radius_options_m` |
| `cfg.app.hold_ms` | `cfg.app.hold_to_confirm_ms` |
| `cfg.i18n.numeral_system` | `cfg.i18n.digit_script` |
| `cfg.maps.provider` | `cfg.map.provider` |
| `cfg.app.reconcile_counters` | `cfg.sync.reconcile_types` |
| `cfg.print.pin_hint` | `cfg.print.pairing_pins` |
| `cfg.fraud.*` | `cfg.sec.fraud.*` (D-267) |
| `cfg.sync.debounce_s` of 10 | `cfg.sync.debounce_s` of 5 (D-261) |
| `cfg.geo.placeholder_min_shared` | `cfg.outlet.placeholder_pin_min_shared` (default 3, D-525) |
| `cfg.ops.dashboard_default_date_rule` | none: `today` is the one default of every tile, with an as-of stamp and a reason chip (D-544) |

**B. Proposed retirements**

| Retire | Use | Reason |
| --- | --- | --- |
| `cfg.sync.trickle_debounce_s` | `cfg.sync.debounce_s` | Same parameter (D-261) |
| `cfg.sync.batch_max_kb` | `cfg.sync.batch_max_kb_raw` | Doc 17 name; the compressed cap is `cfg.api.max_batch_body_kb` |
| `cfg.sync.retry_max_attempts` | `cfg.sync.retry_max_inprocess` | Doc 17 name |
| `cfg.sync.min_interval_min` | `cfg.sync.periodic_min` | Doc 15 F-SYS-011 and doc 17 describe the same 15-minute floor |
| `cfg.api.rate_limit_per_device_per_min`, `rate_limit_per_user_web_per_min` | `cfg.api.rl.device_per_min`, `cfg.api.rl.user_per_min` | D-116 names the family `cfg.api.rl.*` |
| `cfg.auth.web_mfa_roles` | `cfg.auth.mfa_required_roles` | D-114 |
| `cfg.media.upload_network_policy`, `cfg.media.wifi_wait_h` | `cfg.media.wifi_only_default`, `cfg.media.evidence_mobile_fallback_h` | D-75 and doc 17 |
| `cfg.media.upload_batch_max_mb` | none | Photos upload directly by SAS, one object per request; there is no media batch |
| `cfg.media.retention_days`, `cfg.media.thumbnail_cache_mb` | `cfg.retention.media_days`, `cfg.app.image_cache_mb` | One owner each |
| `cfg.bundle.pregen_time`, `cfg.bundle.ttl_h` | `cfg.bundle.d1_generation_time`, `refresh_time`, `coverage_check_time` | The D-71 job chain replaces the single 04:00 job |
| `cfg.bundle.history_days` | `cfg.app.local_history_days` (7) | D-83 |
| `cfg.sale.stock_insufficient_policy` | `cfg.sale.stock_check` | D-321 |
| `cfg.target.revision_approval_levels`, `cfg.target.tilldate_basis`, `cfg.kpi.tilldate_basis` (unsuffixed) | `cfg.target.approval_levels`, `cfg.kpi.tilldate_basis.<surface>` | Register and R-21 |
| `cfg.outlet.close_with_dues_policy` | `cfg.outlet.close_block_if_dues` | One key with four values |
| `cfg.day.require_checkin_before_sale` (screenshot note) | `cfg.day.checkin_gate` | `hard` equals "true" |
| `cfg.day.auto_final_submit_time` | `cfg.day.final_submit_autoclose_time` | D-262 |
| `cfg.memo.max_edits` | `cfg.memo.edit_chain_max` | D-86 |
| `cfg.memo.header_lines`, `cfg.memo.footer_lines` | `cfg.print.templates` | Template is data (D-76) |
| `cfg.app.outlet_list_index_script` | `cfg.app.alphabet_filter` | D-345 |
| `cfg.map.cache_mb`, `cfg.map.tile_provider` | `cfg.map.tile_cache_mb`, `cfg.map.provider` | One spelling |
| `cfg.web.entry_min_date` | `cfg.web.entry_cutoff_source` with a per-zone `cfg.web.entry_backdate_days` | The register offered both names; the verification shows three possible mechanisms (G-man-087) |
| `cfg.locale.digits` (screenshot note) | `cfg.i18n.digit_script` | UI-SR notes |
| `cfg.notify.*` (screenshot note) | `cfg.notify.events` | Q-UI-07 |

### 3.4 Reconciled values (where sources disagree)

| Key | Seen | Value here | Why |
| --- | --- | --- | --- |
| `cfg.sync.debounce_s` | 10 s (scale), 5 s (sync) | 5 | D-261 |
| `cfg.geo.max_accuracy_m` | 150 (lens), 100 (sync, data) | 100, bounds 30 to 300 | D-264 |
| `cfg.auth.offline_unlock_max_days` | 3 (lens), 14 (security) | 7, bounds 1 to 14 | D-265 |
| `cfg.auth.otp_length`, `otp_ttl_min` | 6 and 30 (lens), 4 (manual), 120 (D-103) | 4 and 120 | D-103; manual shows four boxes |
| `cfg.geo.fix_timeout_s` | 20 (lens), 15 (doc 17) | 15 | Doc 17 owns the capture budget |
| `cfg.memo.reprint_max` | 3 (lens), 5 (doc 17) | 5 | D-322 |
| `cfg.geo.radius_increase_escalation_m` | change of 50 m or 1.5 times (fraud critic), "above 150" (skeleton) | 150 as an end value | D-94 wins; OI-19-04 |
| `cfg.geo.mock_policy` | {flag, flag_and_warn, block_sale} and {flag, block_geo_valid, block_sale} | {silent_flag, warn_rep, block_sale}, default warn_rep | D-96 |
| `cfg.loyalty.negative_balance_policy` | block (lens), accept and flag (data) | accept_and_flag | D-266 |
| `cfg.target.split_method` | proportional_history (lens) | manual | D-189; no automatic split is evidenced |
| `cfg.kpi.submit_pct_denominator` | target_routes (lens), logged_in_routes (manuals) | logged_in_routes | D-45 |
| `cfg.day.final_submit_allow_not_set_routes` | warn (lens), allow (manual) | allow | PARITY (G-man-071) |
| `cfg.day.final_submit_earliest_time` | 17:00 (lens) | none | No gate is evidenced (C-42) |
| `cfg.tso.visit_plan_max_outlets`, `cfg.leave.max_consecutive_days` | 30 (lens) | none | Invented limits (C-41) |
| `cfg.feedback.categories` | Suggestion, Complaint, Bug (lens) | Suggestion | C-41 |
| `cfg.release.apk_max_mb` | 45 (lens) | 30 per ABI | D-73 |
| `cfg.outlet.placeholder_pin_min_shared` | 3 (doc 16, D-253 and DQ-48) and 2 (doc 19 first draft), two keys for one concept | 3, one key | D-525; `cfg.geo.placeholder_min_shared` retired |
| `cfg.kpi.tilldate_basis.<surface>` | calendar_days, working_days, fixed_fraction (doc 19) and calendar_incl_today, calendar_through_yesterday, working_incl_today, fixed_ratio (doc 16 SQL) | the doc 16 four values, which express AMO 25/30 against TSO 26/30 | D-525; T-6-60 lints registry against SQL |
| `cfg.retention.sync_batch_response_h` | 24 (doc 19), 48 (doc 16) | 48, bounds 24 to 72 | D-525 |
| `cfg.print.templates` width | 32 columns (doc 19), 42 (doc 17 Font B) | 32 or 42 by font | D-525 |
| `cfg.sla.login_pct_alert_time` | 10:00 (doc 19), 09:00 (doc 18 s6.6), 08:45 (RB-03) | 09:00 (08:45 is a human check, not an alert) | D-552 |
| `cfg.sale.max_lines_per_memo` floor | 20 | 40 (the observed maximum, docs/22 P-02) | D-547 |
| `cfg.agg.claim_batch` | 200 (lens) | 500 | D-140 |
| `cfg.sync.wakelock_max_s` | 60 (lens) | 90, ceiling 90 | D-73 (at most 90 s each) |
| `cfg.bundle.*` generation times | 04:00 (lens) | 22:00, 03:30, 04:30 | D-71 |
| `cfg.sale.qty_entry_unit` | pack (lens) | stick for cigarette and bidi | D-17; docs/22 P-04 |
| `cfg.sec.fraud.adjust_daily_total_mtk` | "200,000" with an `_mtk` suffix | 200,000 Tk = 200,000,000 mtk | Unit-consistent; OI-19-07 |
| `cfg.kpi.bar_bands` | red below 50, amber 50 to 90, green 90 and above (register) | green from 80, amber from 40, red below 40 | D-52; observed green at 84 and 89 |
| `cfg.qc.fault_kinds` | production and transport quantities (lens) | `cfg.qc.fault_types` (11 codes) | D-34 |

### 3.5 Lens and register keys deliberately not registered

| Key | Source | Not registered because |
| --- | --- | --- |
| `cfg.geo.store_fix_on_every_screen_open` | lens | A key that can enable a position stream contradicts CLAUDE.md rule 3 (D-74) |
| `cfg.qc.required_before_print` | lens | QC and Print are independent buttons in any order (D-77, D-203) |
| `cfg.day.final_submit_once_per_day` | lens | Primary key on `(zone_id, business_date)` (D-55) |
| `cfg.kpi.login_event_definition` | lens | Defined once by D-30 |
| `cfg.kpi.login_include_amo_routes` | register G-man-072 | The verification shows 4 target routes equal the four SR-kind routes; replaced by `cfg.kpi.target_route_kinds` (D-29) |
| `cfg.app.clear_on_final_submit` | lens | Purge is by business-date age and never of unsynced rows (D-83) |
| `cfg.flag.trickle_sync` | lens | Duplicates `cfg.sync.trickle_enabled` |
| `cfg.web.entry_min_date` | register | Folded into `cfg.web.entry_cutoff_source` (s3.3 B) |
| `cfg.maps.*`, `cfg.periphery.*`, `cfg.fraud.*` | manual and lens spellings | Aliases (s3.3 A) |

Proved by: T-0-60, T-0-61, T-0-63, T-6-60.

### 3.6 Counts

Counted from the tables of s3.2 by script (`rtm-check` in doc 20 recomputes them; family rows count as their members: `cfg.flag.<name>` 12, the two `tilldate` families 4 each, `cfg.report.lists.<name>` 6, the `cfg.sec.fraud.*` sub-table 20). The 25 keys that docs 16, 17, 20 and 21 requested were added at the editorial merge (OI-19-02; the 21 table rows sit at the end of the area tables they belong to).

| Measure | Count |
| --- | --- |
| Registry rows (table lines) | 625 (576 plus the 49 of s3.2.11) |
| Distinct keys | 649 (565 plus the 37 of s3.2.10 minus the 2 retired, plus the 49 of s3.2.11) |
| Kind S, T, O | 619, 22, 8 |
| Class C0, C1, C2, C3 by key class | 60, 238, 253, 96 (plus two scope-profile keys: `cfg.geo.radius_m` C1/2/3 and `cfg.day.checkout_earliest_time` C2>C3) |
| Keys that have a C3 tier at some scope | 101 (the 11 C3 keys of s3.2.11 on top of 90; 78 before the round-2 pass: 9 new C3 keys, the C3 tier of `fix_accuracy_mode` `high`, of `photo_max_kb` above 150 and of `fix_timeout_s` above 15; D-88 and the lens counted 34 before the manuals and critics added keys: OI-19-02) |

| Sub-milestone | Keys first consumed | Sub-milestone | Keys first consumed |
| --- | --- | --- | --- |
| 0b | 1 | 3b | 37 |
| 0c | 32 | 4a | 19 |
| 1a | 33 | 4b | 20 |
| 1b | 42 | 4c | 28 |
| 1c | 37 | 4d | 1 |
| 2a | 40 | 5a | 22 |
| 2b | 20 | 5b | 5 |
| 2c | 15 | 5c | 21 |
| 2d | 106 | 6a | 7 |
| 2e | 82 | 6c | 27 |
| 3a | 46 | 7b | 4 |
|  |  | 7a | 4 |

| Area | Keys | Area | Keys | Area | Keys |
| --- | --- | --- | --- | --- | --- |
| `cfg.agg` | 7 | `cfg.leave` | 6 | `cfg.rubric` | 4 |
| `cfg.api` | 6 | `cfg.loyalty` | 11 | `cfg.sale` | 15 |
| `cfg.app` | 20 | `cfg.map` | 4 | `cfg.sales_plan` | 1 |
| `cfg.astha` | 12 | `cfg.master` | 1 | `cfg.sec` | 28 |
| `cfg.auth` | 49 | `cfg.media` | 13 | `cfg.sla` | 39 |
| `cfg.bundle` | 15 | `cfg.memo` | 17 | `cfg.stock` | 7 |
| `cfg.calendar` | 9 | `cfg.net` | 1 | `cfg.superstar` | 3 |
| `cfg.content` | 4 | `cfg.notify` | 1 | `cfg.support` | 10 |
| `cfg.credit` | 9 | `cfg.ops` | 15 | `cfg.survey` | 4 |
| `cfg.cutover` | 4 | `cfg.outlet` | 20 | `cfg.sync` | 33 |
| `cfg.dashboard` | 4 | `cfg.pii` | 9 | `cfg.sys` | 21 |
| `cfg.day` | 30 | `cfg.price` | 4 | `cfg.target` | 14 |
| `cfg.drp` | 2 | `cfg.print` | 8 | `cfg.task` | 7 |
| `cfg.entry` | 1 | `cfg.promo` | 6 | `cfg.telemetry` | 5 |
| `cfg.feedback` | 2 | `cfg.qc` | 6 | `cfg.tso` | 12 |
| `cfg.flag` | 15 | `cfg.release` | 11 | `cfg.ui` | 3 |
| `cfg.geo` | 45 | `cfg.report` | 15 | `cfg.user` | 2 |
| `cfg.i18n` | 4 | `cfg.retention` | 13 | `cfg.visit` | 3 |
| `cfg.kpi` | 21 | `cfg.route` | 4 | `cfg.web` | 14 |

Proved by: T-0-60, T-0-63, T-6-60.

### 3.7 Where the manual contradictions land in the registry

The register's contradiction rows (C-01 to C-51) and inside-manual conflicts (I-01 to I-37) are decided by the plan (D-157 to D-244). The rows that change a key, a default or the existence of a key are:

| Row | Decision | Effect on the registry |
| --- | --- | --- |
| C-01, C-37, C-38 (units) | D-157, D-193, D-194 | `cfg.sale.qty_entry_unit` in sticks for cigarette and bidi; `cfg.dashboard.unit_labels` and `cfg.tso.dashboard_tiles` carry the per-surface report units; Match unit unknown |
| C-02, C-10 (memo net, discounts) | D-158, D-166 | `cfg.memo.allow_negative_net`, `cfg.qc.max_amount_basis`, `cfg.sale.offers_auto_apply`, `cfg.promo.*` |
| C-03, C-27 (QC types) | D-159, D-183 | `cfg.qc.fault_types` (11 codes); the lens `cfg.qc.fault_kinds` is retired |
| C-04 (call start) | D-160 | `cfg.sale.call_start_prompt` |
| C-05 (mark paid) | D-161 | `cfg.credit.allow_partial_collection` false, `collection_partial_roles` |
| C-07 (photo moves the outlet) | D-163 | `cfg.geo.first_capture_sets_location`, `outlet_location_change_approval`, `update_base_*` |
| C-08, C-09 (OTP) | D-164, D-165 | `cfg.auth.otp_length` 4, `otp_ttl_min`, `otp_visible_roles`, `reverify_on_new_version` (parity true, recommended false) |
| C-11 (task labels) | D-167 | `cfg.task.statuses` |
| C-14 (team location) | D-170 | `cfg.tso.team_location_max_age_min` (also the AMO) |
| C-15 (SR phone) | D-171 | `cfg.pii.mask_style` |
| C-16, C-30 (outlet lifecycle) | D-172, D-186 | `cfg.outlet.amo_request_approval`, `verify_roles`, `approve_roles`, `reject_requires_reason` |
| C-17, I-09 (dues) | D-173, D-216 | `cfg.day.sales_submit_dues_warning` = warn |
| C-18 (logout) | D-174 | `cfg.app.logout_wipes_data`, `cfg.app.logout_block_when_pending` |
| C-19, C-41 (invented limits) | D-175, D-197 | `cfg.tso.visit_plan_max_outlets`, `cfg.leave.max_consecutive_days` none; `cfg.feedback.categories` Suggestion only |
| C-21 (Submit % (of logged-in)) | D-177 | `cfg.kpi.submit_pct_denominator` = logged_in_routes |
| C-22, C-23 (targets) | D-178, D-179 | `cfg.target.product_types`, `cfg.target.approval_levels` |
| C-25 (locale) | D-181 | `cfg.app.default_locale` per role |
| C-26, C-28 (web Final Submit, delete) | D-182, D-184 | `cfg.web.delete_section_data_*`, `cfg.day.final_submit_*` |
| C-29, C-34, I-36 (menus) | D-185, D-190, D-243 | `cfg.web.menu_by_role` |
| C-31 (route kind) | D-187 | `cfg.kpi.target_route_kinds` |
| C-33 (target entry) | D-189 | `cfg.target.split_method` = manual |
| C-35 (reports) | D-191 | `cfg.report.*`, `cfg.ops.report_export_max_rows` |
| C-36 (gift choice) | D-192 | `cfg.astha.gift_choice_roles`, `gift_choice_lock` |
| C-39 (till-date) | D-195 | `cfg.kpi.tilldate_basis.<surface>`, `tilldate_rounding.<surface>` |
| C-40 (caps) | D-196 | `cfg.kpi.card_pct_cap`, `cfg.target.achievement_pct_cap` none |
| C-42 (Final Submit gate) | D-198 | `cfg.day.final_submit_earliest_time` none |
| C-43 (Visit Query) | D-199 | `cfg.survey.tso_visit_query_questions`, `cfg.tso.visit_query_answer_max_chars` |
| C-44, C-45 (edit) | D-200, D-201 | `cfg.memo.edit_reasons`, `cfg.memo.edit_blocked_after_qc` at outlet level |
| C-47 (QC and credit order) | D-203 | `cfg.qc.required_before_print` not registered |
| C-48 (TSO Settings) | D-204 | `cfg.app.drawer_items.tso` |
| C-50 (Sale History) | D-206 | `cfg.app.local_history_days` |
| C-51 (PII baseline) | D-207 | `cfg.pii.field_roles` parity baseline and recommended restriction |
| I-02, I-18 (check-out time) | D-209, D-225 | `cfg.day.checkout_earliest_time` inclusive at 17:00, per-role scope allowed |
| I-15 (reconciliation rows) | D-222 | `cfg.sync.reconcile_types` per role and version |
| I-16, I-17 (updater) | D-223, D-224 | `cfg.release.*` two-stage update, installed size versus APK size |
| I-24 (Astha month chips) | D-231 | `cfg.astha.memo_target_month_filter` |
| I-27 (leave days typed) | D-234 | `cfg.leave.*` keeps no derived-days rule |

Proved by: T-0-60, T-6-60.

## 4 Propagation to the field

R5 and R4 bind this section: a change must reach a phone as soon as it can, at no extra wake-up, socket or timer. The protocol is pull on the next natural request, ack on the next batch, and push only for urgent keys. The device side is doc 17 s6.7; this section fixes what the server sends, how relevance is computed, and what the admin sees.

### 4.1 The contract

| Step | Mechanism | Cost on the phone | Decision |
| --- | --- | --- | --- |
| 1 Signal | Every API response carries `X-Config-Version`, the highest version relevant to the caller's scope chain (s2.4). The bundle body carries `config` (resolved values, scheduled list, bounds). Requests carry the applied version in `X-Config-Version`, so the server also learns delivery without an ack row | header bytes on requests the app makes anyway | D-89, D-446 |
| 2 Pull | If the response version is newer than `sync_meta.config_version`, the app queues `GET /config/delta?since=<local>` (F-API-040, ETag) after the current request finishes, on the same connection. Offline: nothing happens until the next natural request | one GET of at most about 2 KB gz per relevant change | doc 17 s6.7 |
| 3 Resolve | The server resolves the caller's chain at `since` and at current (both cached by `(chain_hash, version)`), diffs, and returns changed keys with provenance plus the scheduled list. If `since` is older than `cfg.sys.config_delta_max_age_versions` it returns a full snapshot | none | s2.5 |
| 4 Apply | One SQLite transaction updates `ref_config`; open screens that read config (the check-out button) re-evaluate; values outside the shipped bounds are refused and the last good value is kept | none | doc 17 s6.7 |
| 5 Ack | Keys flagged `requires_ack` produce a `config_ack` outbox row (F-API-041, priority class 1) carried in the next batch or bundle request. The server upserts `cfg.config_ack` and `app.device.config_version`, at most once per version per device. A device with nothing else to send for `cfg.sync.periodic_min` posts the ack alone | none in the common case | D-446 |
| 6 Scheduled | A future-dated value ships in the `scheduled` list within `cfg.sys.schedule_horizon_days` and is applied at its instant on trusted time, offline | none | G-cfg-06 |
| 7 Urgent | Keys with push (kill switch, `min_version`, `blocked_versions`, `sync_hold_s`, any revert of a C3 key) also send an FCM data message `{type: "cfg", version, pull_after_s}` with jitter (120 s ordinary, 20 s urgent). `cfg.ops.push_enabled` is false in the pilot. If FCM is refused the change rides the next request | FCM data message, no socket held | D-09; G-sre-07 |
| 8 Public | `GET /config/public` (F-API-042) is unauthenticated, cached 60 s at Front Door, and serves global-scope `min_version`, `latest_version`, `update_url`, the maintenance banner, `cfg.support.contacts`, `default_locale` and the global version. A scoped kill switch or banner is returned in the body of the refused login, because an unauthenticated endpoint cannot know the caller's scope | none | D-89 |

Wire shapes (the load-bearing parts):

```json
// GET /config/delta?since=9388  ->  200 (ETag per chain_hash and range) or 304
{ "config_version": 9402,
  "changes": [ { "key": "cfg.geo.radius_m", "scope_type": "territory", "scope_id": 44, "value": 60,
                 "effective_from": "2026-10-04T05:00:00Z", "effective_to": null, "requires_ack": true,
                 "bounds": { "min": 20, "max": 2000 }, "dir": "down" } ],
  "scheduled": [ { "key": "cfg.day.checkout_earliest_time", "scope_type": "global", "scope_id": 0,
                   "value": "16:30", "effective_from": "2026-10-05T18:00:00Z" } ],
  "removed": [] }
// config_ack outbox record (record type owned by doc 17)
{ "type": "config_ack", "client_uuid": "<uuid v4>", "payload": { "config_version": 9402, "applied_keys": 1 } }
```

`bounds` and `dir` (the restrictive direction, s7.5) ship only for `requires_ack` and C3 keys. Doc 17 s6.7 lists the delta shape without them; they are an addition for doc 17 to adopt (OI-19-08).

### 4.1b Reach classes, what "online" means, and the resume check (D-563, G-qa-96, G-qa-106)

The first three statements of the R6 reach criterion disagreed: doc 14 said 95 percent of ONLINE phones within 15 minutes; this document and T-2-61 said 95 percent of ALL active devices "within 15 minutes of coming online", which any device that makes a request meets by definition; and doc 18 s6.1 called the SLO "ALL active devices (online or not)" while measuring online devices. With push off (D-09) and no polling, a config change reaches a phone only on its next natural request, so a wall-clock bound has to be stated per cohort and per risk class, and measured. This table is the ONE statement; docs 14, 18 and 20 cite it.

| Term | Definition |
| --- | --- |
| Active device | Bound, not revoked, with a request in the last 3 working days |
| Online | At least one authenticated request in the last `cfg.sla.online_window_min` (20) minutes |
| Selling | Online AND a `/sync/batch` or bundle request in that window (with the 10-minute trickle spacing of doc 17 s4, a selling phone makes one almost every cycle) |
| Idle | Active but not online: app closed, no signal, battery saver |
| Reach | Devices of the cohort whose applied `config_version` is at least v, divided by the cohort, measured at fixed wall-clock offsets from the commit |

| Class | Cohort | Bound (wall clock from commit) | Mechanism | Measured by |
| --- | --- | --- | --- | --- |
| Ordinary change | Selling | `cfg.sla.config_reach_selling_pct` (95) within `cfg.sla.config_reach_selling_min` (15) minutes: the visit sync that is already coming plus the delta pull behind it | Response header, then delta (s4.1 steps 1 to 4) | S9 (T-4-161), reach widget |
| Ordinary change | Idle | At the next foreground (the resume check below); shown as the share of ALL active devices reached at 24 h (`cfg.sla.config_reach_tail_h`) | Resume check, bundle | tail metric of s4.3 |
| Urgent (kill switch, `min_version`, blocked version, C3 revert), push OFF | Selling | Same as ordinary: 95 percent within 15 minutes. The pilot and any wave without push publish THIS figure, not 5 minutes | Next request | S9 with push off |
| Urgent, push ON | Push-enabled devices | 95 percent within `cfg.sla.config_reach_urgent_min` (5) minutes | FCM data message with 20 s jitter, then pull | S9 with push on |
| Any class | Silent (no request for longer than the bound) | No wall-clock bound exists. The server ENFORCES instead: a row captured under an old version is judged by D-431 and D-571, and a blocked or below-minimum build is refused at its first contact (bundle, day start or refresh); the device appears in "unreached by reason" with `silent since` and an age, and support can queue a directive (s5.4b, D-594) | Next contact | T-2-174, P7 |

Rollback and revert promises elsewhere use these bounds: doc 20 s7.5 says "5 minutes to push-enabled devices, 15 minutes to selling phones with push off, next foreground for idle phones", never "5 minutes to all online devices". A revert of a radius typo is an ordinary-class change (15 minutes) unless the sponsor signs it as urgent.

The resume check (F-SYS-092, F-API-083). On app resume to foreground, on a tap of "Refresh GPS" and on opening a visit, when connectivity is validated and the last server contact is older than `cfg.sync.config_check_min_gap_min` (5), the app sends ONE conditional request `GET /config/check` with `If-None-Match: <X-Config-Version>`: a 304 of about 300 bytes, or the delta inline. No timer, no extra wake-up, no socket (R4): it rides an event the user caused, at most once per gap and at most `cfg.sync.config_check_max_per_day` (24) times a day. A visit that is already open keeps the `radius_m_used` stamped when it opened; an SR who sees "not within range" after an admin widened the radius taps Refresh GPS, the check runs first and the re-check uses the new applied value, which is stamped on the visit (T-2-165). Offline, nothing happens, and the old value stays valid under D-431 and D-571.

FCM decision. D-09 stays MUST-CONFIRM by 2d and is also a checked entry condition of 7b and of wave 1 (doc 20 s7.1). S9 (T-4-161) runs with push on and with push off and writes both bounds into the day-one checklist; if FCM is not acceptable, the 15-minute urgent bound above is the published one.

### 4.2 Apply semantics on the device

| Rule | Detail |
| --- | --- |
| New captures only | A changed value never rewrites a captured row. A visit at 10:00 keeps `radius_m_used = 100`; one at 11:00 after the delta uses the new value (T-2-28 in doc 17) |
| Scheduled values apply offline | The device resolver picks the row effective at trusted now. When the clock is suspect (`time_untrusted` or skew above `cfg.sync.max_clock_skew_min`) a time-shaped or direction-bearing key applies the more restrictive of the old and the scheduled value (direction table s7.5) and the day is flagged `clock_suspect` (T-2-62) |
| Kill switches never block upload | `block_login` refuses a new day start; `read_only` and `blocked_versions` refuse new captures; neither stops the upload of captured rows or wipes data (D-130). The device shows the banner and the support code |
| Stamping | The device stamps its applied relevant version and the resolved values listed in `stamp_on` on each captured row (s2.4) |
| Offline class | The registry values with Dl `dev` or `both` are CACHED and apply OFFLINE; the console is ONLINE-ONLY; an acknowledgement is QUEUED |
| Bounds | A delivered value outside its shipped bounds is ignored; the device keeps the last good value and records `config_rejected` in its telemetry |

### 4.3 Delivery visibility in the console

For each `config_version` the admin sees (page P7 and the reach widget on P1):

| Field | Definition |
| --- | --- |
| `devices_targeted` | Computed at commit: active devices whose user chain intersects the change's scope (global: all active devices; zone 5012: devices of users assigned there for the business date; outlet: devices of the outlet's route assignees) |
| `devices_applied`, `applied_pct`, `full_ack_at` | Devices with `config_version >= v` from header observation or ack row, divided by targeted |
| `effective_in_field_pct` | Applied devices divided by devices seen since the commit (`last_seen_at >= committed_at`); it excludes genuinely offline phones so the admin sees delivery failures, not dead zones. It is a DIAGNOSTIC, not the reach SLO: measured on survivors it flatters exactly the phones that matter least. The SLO cohorts are the selling and idle cohorts of s4.1b, shown as two numbers beside it |
| `reach_pct_all`, `reach_tail` (D-526, G-qa-51) | THE reach measure: applied devices divided by ALL active devices of the scope (online or not) at 15 minutes, 24 hours (`cfg.sla.config_reach_tail_h`) and at the next bundle, with the unreached count by reason on the P1 widget and P7: offline, push off, old app, no bundle contact. An urgent revert (kill-switch class, push on, 20 s jitter) must reach 95 percent of PUSH-ENABLED devices within `cfg.sla.config_reach_urgent_min` (5) minutes; push is off by default in the pilot, so the pilot's urgent bound is the next request and the widget says so |
| Pending list | Targeted minus applied, grouped by zone, with user, route, app version, device `config_version`, "behind by n", `last_seen_at` and a "likely offline" badge when `last_seen_at` is older than 30 minutes |
| Reverse lookup | "Which radius is phone X using now?" is `cfg.resolve(keys, chain(user of X), now, X.config_version)`; the device page shows it with provenance |
| Alert | `cfg.sla.config_ack_pct_alert` (90) after `cfg.sla.config_ack_window_min` (60), evaluated on `reach_pct_all`; an urgent revert that is below 95 percent of push-enabled devices after 5 minutes raises a Sev2; with push off the Sev2 fires when the SELLING cohort is below 95 percent after 15 minutes (s4.1b, D-563; T-2-61, T-6-01, T-6-41) |
| Freshness | Reach tables read the primary (small, live-state, D-128); the as-of stamp is shown |

### 4.4 Load arithmetic

| Event | Arithmetic | Result |
| --- | --- | --- |
| Ordinary push, 8,500 devices, `push_jitter_s` 120 | 8,500 / 120 | at most 71 delta GETs a second |
| Urgent push, `push_jitter_urgent_s` 20 | 8,500 / 20 | at most 425 GETs a second for 20 s, served from Redis by `(chain_hash, from, to)`; 1,402 node chains bound the distinct bodies |
| Territory-scoped change | about 29 SRs in a territory (8,500 / 291) | about 29 pulls, not 8,500, because only they see a new relevant version |
| Delta body | one key with provenance about 150 B raw | far below the 2 KB ceiling; a first snapshot of the 266 device-delivered keys is about 20 KB raw, about 5 KB gz (ASSUMPTION: 75 B per key) |
| Morning bundle | config section about 5 KB gz inside a 60 KB gz p95 SR bundle | under 10 percent of the bundle, within the doc 17 budget |
| A device 500 or more relevant versions behind | `cfg.sys.config_delta_max_age_versions` | one full snapshot instead of a replay |

266 is the count of registry keys whose Dl is `dev` (112) or `both` (154); `rtm-check` recomputes it.

### 4.5 Failure behaviour

| Failure | Behaviour | Gate |
| --- | --- | --- |
| Device offline for days | Applies scheduled values on time; learns of unscheduled changes at its next request; rows stamp the old version; the server judges them under D-431 as amended by D-519 (the 48 h run from the change, not from the age of the device's version) | T-2-62, T-1-63 |
| FCM refused or no Google services | Urgent changes ride the next request; the reach view shows the lag | T-2-55 |
| Redis lost at 07:00 | L1 rebuilt per chain under a mutex from `cfg.config_snapshot`; no Postgres stampede | T-1-62 |
| Clock suspect | Restrictive value applies; day flagged | T-2-62 |
| Admin edits at 06:30 after pre-generation | Pre-built bundle plus delta, not regeneration (D-129) | T-2-65 |
| Key change that needs a new login (`Eff` S) | Takes effect at the next session or new business day; the reach view counts it as pending until then | T-2-61 |

Proved by: T-1-60, T-1-61, T-1-64, T-2-55, T-2-56, T-2-61, T-2-62, T-2-63, T-2-65.

## 5 Admin dashboard

The console is a Next.js area `/admin/*` of the web app, ONLINE-ONLY, reading the primary for live state (requests, reach, device page, switches) and the replica with an "as of" stamp for aggregates (D-128). Every action is an audited API call; every page is bilingual (bn, en) through the localisation layer.

### 5.1 Access model and permission bundles

Authority is a permission table bounded by geography, not one `admin` role (D-91; closes G-cfg-01). The seven roles of the schema stay as the user's identity and scope anchor; admin authority comes from grants.

| Bundle | Typical holder | Atomic permissions | Bounded by |
| --- | --- | --- | --- |
| `master_data` | Sales-ops data steward | master.geo, product, sales_plan, route, assignment, outlet, classification, offer, program, content, calendar | optional geography |
| `config_editor` with domain F | Field-operations manager | cfg.view, cfg.edit.F | optional geography |
| `config_editor` with domain O | IT or ops admin | cfg.view, cfg.edit.O | none |
| `config_editor` with domain P | Trade-marketing or programme manager | cfg.view, cfg.edit.P | none |
| `config_approver` | Named approvers, at least two per domain | cfg.approve | domain and optional geography; roster shown on P1 and P5 |
| `security_admin` | Security officer | master.user, master.scope, cfg.edit.S, permission.grant, device.view, device.revoke, audit.view | none |
| `finance_admin` | Finance operations | master.price, finance.adjust, entry.unlock | none |
| `finance_approver` | Finance controller | finance.approve (back-dating, large adjustments) | none |
| `release_mgr` | Release manager | cfg.edit.R, release.manage, wave.manage, flag.manage | none |
| `ops_admin` | Operations on call | cfg.edit.O, quarantine.review, day.reopen, day.void, submit.void, device.view, device.revoke, device.otp.issue, account.unlock, calendar.emergency, backfill.approve, audit.view | none |
| `importer` | Migration engineer | import.run | none |
| `pii_officer` | Privacy officer | pii.view, pii.export (approval of large exports) | none |
| role `tso` (bounded) | TSO | cfg.propose.geo, web.entry, web.final_submit, web.qc_entry, outlet.verify, outlet.approve, outlet.wholesale_mark, astha.gift_choice, master.sales_plan, master.target (own scope), device.otp.view, device.otp.issue and device.mark_replaced (own zones), account.unlock, password.reset_temp, submit.void, backfill.approve, assignment.propose (via `user_admin`) | own territory and zones |
| roles `dmo`, `wm`, `top` | Supervisors | cfg.view; DMO approves TSO leave; `top` read-only | own scope |
| WMO (designation, ASSUMPTION: mapped to role `wm` until MQ-48 answers) | Target approver | master.target_approve | own wing |
| `support_l1` | Helpdesk L1 (DEFAULT-ON, a web role and not an Azure role, D-540) | device.view, support.decode, device.directive, account.unlock (non-privileged accounts), quarantine.fix (non data-entry reasons), backfill.enter (trained agents, TSO approval, D-599), and five proposals that need a TSO confirmation: device.otp.issue, password.reset_temp, submit.void, assignment.propose, calendar.propose | none |
| `support_l2` (the "support" of docs 15, 19 and 21: Web Entry B1, entry unlock, `/support/decrypt`, cover) | Helpdesk L2, engineering on call | everything of `support_l1` without confirmation plus device.revoke, device.otp.issue, submit.void, quarantine.review, backfill.enter, entry.unlock (at most 7 days), password.reset_temp, support.decrypt (audited, PII-masked by default) | none |
| `user_admin` (geography-bounded; D-551) | TSO (own territory) and DMO (own division) | master.user.bounded and master.assignment.bounded: create, disable and attach scope and route within the holder's geography, with MAKER-CHECKER (`cfg.user.admin_checker_required`): a TSO proposes and a DMO or `master_data` checks; a DMO proposes and `security_admin` or `master_data` checks; the checker is never the maker | own territory or division |
| break-glass | At least TWO named holders per shift (`cfg.sys.break_glass_holders_min`), never shared, with an audited hand-over (D-527) | breakglass.use (restore or restrict, plus the bounded emergency-widen lane of s7.6b) | none |

Rules:

| # | Rule | Decision |
| --- | --- | --- |
| 1 | Every grant is bounded by `(scope_type, scope_id)`; a bounded editor sees blast radius only for their scope and cannot propose above it | D-91 |
| 2 | A grant or revoke is itself a C3 change request of type `grant`: two persons, audited, never applied outside the workflow | D-91, D-435 |
| 3 | Two-person means two different people, the approver holds `config_approver` for the key's domain, and for geo, fraud, auth, PII and day keys the approver is outside the requester's reporting chain | D-435 |
| 4 | A `config_approver` grant newer than `cfg.sec.approver_cooling_h` (24) cannot approve C3 (FS-29) | D-435 |
| 5 | No shared accounts; MFA is mandatory for the admin bundles (`cfg.auth.mfa_required_roles`); Entra SSO where AKTCL has it | D-113, D-114 |
| 6 | The approver roster and the break-glass holders are unknown; confirm with the business (Q23); the proceed-with default is two named approvers per domain drawn from AKTCL operations and security, which the pilot cannot start without | D-91 |

### 5.1b Authority matrix for support and operations (D-540, G-qa-71, G-qa-83)

Docs 14, 15, 20 and 21 gave the helpdesk and the TSO different powers (doc 20 s7.8 let L1 unlock accounts, bulk-issue OTPs, fix route assignments and quarantine rows, but this document made L1 read-only, doc 21 s3.3 gave `device.otp.issue` to ops_admin only, doc 14 D-138 defaulted to \"L2 engineering runs lookups\", and a \"support\" bundle was used that no table defined), so the helpdesk could not execute its own scripts. This table is the ONE authority; docs 14, 15, 20 and 21 cite it and do not restate it. A cell says who may do the action, with `C` meaning only with a confirmation from the zone TSO inside the stated window.

| Action | support_l1 | support_l2 | TSO (own scope) | DMO (own division) | ops_admin | master_data | security_admin |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Device lookup, support-code decode (P19, P11 read) | yes | yes | own zones | yes | yes | no | yes |
| Issue or re-issue a device OTP | C (or the pre-approved bulk list of a pre-bind day) | yes | own zones | no | yes | no | yes |
| Account unlock | yes (not privileged accounts) | yes | own SRs and AMOs | no | yes | no | yes |
| Temporary password reset | C (TSO-confirmed and audited like the OTP; or the pre-approved bulk list of a wave day; D-593) | yes | own SRs and AMOs | no | yes | no | yes |
| Mark a device replaced, revoke a device (the replace-device wizard F-ADM-078 runs both, D-586) | C (propose, TSO confirms) | yes (revoke after upload) | mark replaced, own zones | no | yes | no | yes |
| Release or reject a held bind (F-ADM-079, F-API-068) | read only | read only | read only; never the maker | yes, own division | yes, as delegate (`cfg.auth.held_bind_delegate_roles`) | no | yes |
| Send a device directive: PDA, ping, redownload bundle (F-ADM-085, D-594) | yes | yes | own zones | no | yes | no | yes |
| Replay a decrypted device bundle (F-ADM-080, D-587): dry run, then apply | no | enter and dry run | approve (second person), own zone | no | approve and apply | no | no |
| Revert a bulk master-data batch (F-ADM-082, D-595) | no | no | no | no | yes | yes, with a checker | no |
| Place or lift a retention hold (F-ADM-083, D-600) | no | no | no | no | no | no | yes (with `pii_officer`) |
| Same-day price correction (F-ADM-081, D-589) | no | no | no | no | no | no | `finance_admin` plus `finance_approver` |
| Route assignment or scope fix | C (propose) | C | propose via `user_admin` | check or propose | no | yes | yes |
| Quarantine fix-and-accept | yes (non data-entry reasons) | yes; four-eyes with a second person for data-entry reasons | own zone | no | yes | no | no |
| Submit void (D-539) | C, inside `cfg.day.submit_undo_window_min` | yes, inside the window | yes, own zone | no | yes, until Final Submit | no | no |
| Reopen a final-submitted zone, on-behalf Final Submit | no | two-person | yes, per `cfg.day.reopen_roles` and the window | no | yes | no | no |
| Declare an emergency non-working day (D-542) | no | propose | no | no | yes (`cfg.calendar.emergency_declare_roles`); retroactive needs a second approver | no | no |
| Paper-memo backfill (D-543) | no | enter | approve | no | approve | no | no |
| User create, disable, attach scope (D-551) | no | no | propose, own territory | check or propose, own division | no | check (assignment) | global |
| Config edits and approvals | per domain, unchanged (s5.1, s7) | | propose geo | | O domain | | S domain |

On-behalf actions are never silent: every cell above writes an audit row with the actor, the confirmer and the reason (P16). A disabled user stops capturing at once but their phone may still upload what was captured before the disable (D-551, doc 17 s4.6). T-6-42 and T-7-85 run every script of doc 20 s7.8 with the REAL persona of the \"Who acts\" column of doc 18 s7.6 and fail on a script that needs a permission the persona does not hold (T-2-158).

### 5.2 Page map

Config console pages P1 to P18 (F-ADM-(037+n) is page Pn), the Support desk P19 (F-ADM-072, an exception to the numbering rule), the Replay console P20 (F-ADM-080, D-587) and the TSO web back-office pages B1 to B10. All are ONLINE-ONLY. Phases here are authoritative over doc 15's "first usable version" column (OI-19-01).

| Page | Route | Feature | Bundles (view, edit, approve) | Phase | Gate |
| --- | --- | --- | --- | --- | --- |
| P1 Config home | `/admin/config` | F-ADM-038; F-ADM-077 emergency-widen and temporary-relief lanes | cfg.view | 1c minimal (version, reach widget, pending), 2d (switches, watches, break-glass queue, emergency lanes), 6b (readiness board) | T-1-65, T-6-60, T-6-10 |
| P2 Geofence radius management | `/admin/config/geofence` | F-ADM-039 (F-ADM-012) | F, TSO (propose or apply per `tso_radius_mode`), approvers | 1c (global and territory, no map), 2d (map, bulk edit, geo_class, outlet override, what-if, density), 7b (calibration report on pilot fixes) | T-1-61, T-2-67 |
| P3 Rules and thresholds | `/admin/config/keys` | F-ADM-040 (F-ADM-013) | by key domain | 1c (check-out time, min_version, radius), 2d (geo, day, memo, sale, credit, media, sync), 6b (all 649 keys) | T-1-65, T-2-60 |
| P4 Operational switches | `/admin/config/ops` | F-ADM-041 | O, break-glass | 2d | T-2-63 |
| P5 Change requests and approvals | `/admin/config/requests` | F-ADM-042 | requesters, approvers | 2d | T-2-60, T-2-68 |
| P6 History and rollback | `/admin/config/history` | F-ADM-043 | view all; revert needs editor and the class rules | 2d (revert), 6b (compare, rollback-to-version, export) | T-2-64, T-6-61 |
| P7 Reach and pending devices | `/admin/config/reach` | F-ADM-044 | cfg.view | 1c | T-1-65, T-2-61 |
| P8 Programme setup | `/admin/programs/*` | F-ADM-045 | P | 2a (promotions and DRP), 5a (Astha, Diamond League), 5b (Superstar, free samples) | T-5-60, T-5-61 |
| P9 Master data CRUD | `/admin/master/*` | F-ADM-046; F-ADM-076 user wizard; F-ADM-075 paper-memo backfill; F-ADM-082 revert batch (D-595); minimal F-ADM-005 prices, F-ADM-006 sales plan, F-ADM-036 dues write-off, F-ADM-070 reactivation, F-ADM-071 SR transfer (D-590) | master_data, `user_admin` (TSO, DMO), security_admin (users), support_l2 (backfill) | 2e (users, routes, assignments, outlets for the pilot, the minimal SR lifecycle wizard, paper-memo backfill, and the day-one tools F-ADM-005, 006, 036, 070, 071 in minimal form: D-590), 6a (all 58 entities, revert batch) | T-6-62, T-2-151, T-2-159, T-2-172, T-6-150 |
| P10 Targets | `/admin/targets/*` | F-ADM-047 | TSO, WMO, P | 5c | T-5-63 |
| P11 Device management and OTP | `/admin/devices` | F-ADM-048; F-ADM-078 replace-device wizard; F-ADM-079 held-binds queue (D-586) | ops_admin, security_admin, TSO (OTP view), support_l1 (read) | 0c (OTP view), 2e (device list, support lookup, revoke, the replace-device wizard, the held-binds queue), 6c (full) | T-0-70, T-2-58, T-2-167 |
| P12 Release management | `/admin/release` | F-ADM-049 | release_mgr | 2e (publish, latest, min_version), 6c (staged rollout, blocked versions, adoption) | T-2-56, T-7-62 |
| P13 Sync health | `/admin/sync-health` | F-ADM-050 | ops_admin, cfg.view | 1c (one tile), 4a (full) | T-1-01, T-4-58 |
| P14 Quarantine review | `/admin/quarantine` | F-ADM-051 (F-ADM-030) | ops_admin, TSO | 2e | T-1-53 |
| P15 Day control | `/admin/day` | F-ADM-052 (F-ADM-029); F-ADM-074 submit void; F-ADM-073 emergency non-working day | ops_admin, support_l2, TSO (own zone), support_l1 (C) | 3b (submit void and reopen), 2e (emergency declaration) | T-3-61, T-3-150, T-4-154 |
| P16 Audit viewer | `/admin/audit` | F-ADM-053 (F-ADM-034); F-ADM-083 retention-hold tab (D-600) | audit.view | 2d (config history list), 6b (unified viewer, chain verification) | T-0-64, T-6-152 |
| P17 Import and reconciliation | `/admin/import` | F-ADM-054 (F-ADM-032) | importer | 7a | T-7-80 |
| P18 Flags and waves | `/admin/config/flags` | F-ADM-055 (F-ADM-031) | release_mgr | 6c (flag matrix), 7b (waves, rollback) | T-7-60, T-7-62 |
| P19 Support desk | `/admin/support` | F-ADM-072; F-ADM-084 ticket store; F-ADM-085 device directive (D-594, D-599) | support_l1, support_l2, TSO (own zones), ops_admin | 2e (decode, device card, last 50 requests, the minimal ticket store, the directive button), 6c (search by memo, outlet, batch; full ticket reports) | T-2-58, T-2-157, T-7-85, T-2-169, T-2-170 |
| P20 Replay console | `/admin/support/replay` | F-ADM-080 | support_l2 (enter, dry run), TSO own zone (second approval), ops_admin | 2e | T-2-168, T-7-85 |
| B1 Web Entry | `/web/data-entry/web-entry` | F-WEB-050 (F-ADM-024) | TSO, support | 4c | T-4-61 |
| B2 Final Submit (web) with audited void | `/web/data-entry/final-submit` | F-WEB-051, F-ADM-058 | TSO | 4c | T-4-62 |
| B3 Astha Web Entry | `/web/data-entry/astha` | F-WEB-048 | TSO | 5a | T-5-62 |
| B4 QC entry and QC reports | `/web/qc/*` | F-WEB-052, F-ADM-023 | TSO, admin | 4c | T-4-64 |
| B5 Sales Plan | `/web/sales-plan` | F-ADM-006 | TSO (own zones), admin | 2e minimal (view and save for the pilot and wave zones; D-590), 6a full | T-6-63, T-2-172 |
| B6 Wholesale marking | `/web/outlets/wholesale` | F-ADM-056 | TSO | 6a | T-6-63 |
| B7 SR Device OTP panel | `/web/sr-device-otp` | F-TSO-022, F-ADM-022 | TSO (view) | 0c | T-0-70 |
| B8 Set Target and approval list | `/web/targets/*` | F-ADM-014, F-ADM-059 | TSO, WMO | 5c | T-5-63 |
| B9 Astha Gift Choice Panel | `/web/astha/gift-choice` | F-TSO-020 | TSO | 5a | T-5-62 |
| B10 Outlet Approval Panel | `/web/outlets/approvals` | F-WEB-032, F-API-055 | TSO verify, approve; admin | 3a (approve, reject, so SR to AMO to web closes), 4c (badges, bulk limits) | T-3-11 |

Pages P1 to P7, P11 to P16 and P18 are specified below; P2 in full in s6, P8 to P10 and P17 in s8, B1 to B10 in s8.

### 5.3 Role, menu and action matrix as data (closes G-man-099)

Menus and permissions are data: `cfg.web.menu_by_role` holds `{role: [{menu, page, actions[]}]}`, edited only by `security_admin` (C3). The menu hides entries; the server enforces permissions per action, so a hidden menu is never the control (D-448). The seed is the union of the TSO sidebar (15 items, 41 pages, verified against the PDF; the sidebar is clipped in screenshots, so differing screenshots are not different builds, D-185, D-243) and the spec's other pages (docs/09).

| TSO menu item | Pages | TSO actions (write, within own scope) | Backing tool or key |
| --- | --- | --- | --- |
| Dashboard | 1 | read; date-range Filter | `cfg.dashboard.*` |
| Retailer | 1 | read; edit sections of Browse Retailer where role allows | `cfg.pii.field_roles` |
| QC | 4 | QC Entry (market), Warehouse QC Entry, two QC reports | B4, `cfg.qc.*` |
| Sales Plan | 1 | edit own zones (row save by tick; cross cancels) | B5, `cfg.sales_plan.edit_roles` |
| Products | 6 | read | F-ADM-004 |
| Route Planning | 1 | read (Browse Routes) | F-API-020b |
| Data Entry | 3 | Web Entry Save; Final Submit with Delete Section Data; Astha Web Entry | B1 to B3 |
| Reports | 13 | read; Get Data, Get Excel | doc 15 s6 |
| Target | 3 | Set Target (Apply, Download Sample, Upload Excel); allocation and revise lists | B8 |
| Supervisory Module | 1 | read AMO call reports | F-WEB-054 |
| Outlet | 2 | Outlet Approval Panel (Verify, Reject, Approve with confirm); SR outlet reports | B10 |
| Retailer Wholesale Outlet | 1 | wholesale marking | B6 |
| Credentials | 1 | change own password | D-102 |
| SR Device OTP | 1 | view OTP list | B7 |
| Astha Gift Panel | 2 | per-outlet gift choice; Gift Choice Report | B9 |

Thirteen spec pages are absent from the TSO manual (Task Planner, By-Route Geo Capture, Campaign Gift Redemption, Discount Report, By Outlet By Day, Online/Offline Sales, Free Sample, TSO Top Sheet Performance, TSO Daily Tracking Dashboard, Tutorial, Performance Leaderboard, Superstar Program, Daily Tracking Dashboard). ASSUMPTION: they belong to DMO, WM, WMO, `top` and admin menus, with `top` read-only (D-336); unknown; confirm with the business (MQ-48, MUST-CONFIRM by 4a). The seed grants them to those roles and to nobody else, so a wrong guess hides a page and never exposes data.

### 5.4 Page specifications

Common elements of every console page: scope selector bounded by the viewer's grants; "as of" stamp; reason box with a 10-character minimum on any change; blast-radius panel before submit; link to the audit trail of the object; bilingual labels from the catalogue; no data is lost on a failed save (draft kept in the request table).

| Page | Layout and data | Actions and rules | Rails |
| --- | --- | --- | --- |
| P1 Config home | Tiles: current `config_version` and last change; my pending and awaiting-me requests; changes in the last 24 h; reach widget (applied % of the last five versions); watches running with KPI sparkline against baseline; break-glass items awaiting review; scheduled changes with cancel; active switches with countdown; change-freeze countdown; month-end readiness board from `cfg.sla.targets_missing_alert_day` (zones without next-month targets, Astha quarter and programme periods ending within 14 days, holiday calendar coverage for 60 days, `route_day` rows for the 1st; G-field-17) | Read-only landing; every tile links to its page | none |
| P3 Rules and thresholds | Left: areas with change badges. Centre: key table with resolved value at the selected scope, provenance (inherited or override here), default, parity value where the manual differs ("Apsis behaviour: ..."), bounds, class badge, Eff code, Ack and FD markers, Dl (reaches phones or server-only), last change. Right: edit drawer with a typed control per `value_type` (Dhaka time picker, list editor, JSON editor with schema errors inline, enum radio) | Stage several keys into one change set; effective-from (now, tomorrow 00:00 Dhaka, a date); search by key, bn or en description and "where is this used"; deprecated keys hidden unless asked | Bounds, dependencies (s2.6), class, blast radius, class escalation notices (s7.2) |
| P4 Operational switches | One card per O key: kill switch (off, read_only, block_login), read-only mode, sync hold, version hold, bundle hold, banner (phone-frame preview). Duration and scope are mandatory | Countdown and "extend" (a new request); queued-upload growth from telemetry beside read-only mode so nobody forgets it | Auto-expiry; break-glass for one named holder; every break-glass creates a review due in `break_glass_review_h` |
| P5 Change requests and approvals | Tabs: Mine, Awaiting me, Scheduled, All. Diff per change: key, named scope, old to new, effective window, class and escalation reason, blast radius, what-if (geo), dependency warnings, canary status, approver cooling and chain checks shown as blockers | Submit, adopt a TSO proposal, approve, reject with note, cancel, schedule, create canary request, promote canary | `two_person` check, cooling, chain separation, canary rule, freeze windows |
| P6 History and rollback | Timeline per key and scope as bars on a time axis with who and why; per actor; per day; compare two versions for a chosen chain | Revert any applied version (a new request restoring the previous values; skips canary, is pushed); rollback-to-version N (s7.7); export CSV or JSON | Class rules apply to the revert by max risk; nothing is deleted |
| P7 Reach | Per version: targeted, applied, `effective_in_field_pct`, last 5 versions; pending list by zone with user, route, app version, "behind by n", last seen, likely-offline badge | Drill to device page (P11); export | none |
| P11 Devices and OTP | List and search by user, code, device; columns: user, role, route, zone, app version, `config_version` and "behind by n", last sync, pending rows (X-Pending-Rows telemetry), integrity badges (mock seen in 7 days, rooted, developer options), trust level, status, `clock_suspect`. OTP tab: the SR Device OTP panel (B7) plus issued, used and expired log | Revoke with pending-row warning: default "revoke after upload" within `revoked_device_grace_upload_h`, "revoke now" needs a reason (C2); OTP re-issue (admin improvement); bulk pre-issue per zone with a printed sheet (F-ADM-069); SR lifecycle wizard (s8.8) | OTP reveal is logged; support_l1 is read-only |
| P12 Release management | Upload an APK per ABI: signature, SHA-256, size against `apk_max_mb`, version name read; publish to Blob; adoption chart by version over time from `device.app_version`; live count of devices below a proposed `min_version` | Set `latest_version` (C1), `min_version` (C3: dropdown of published versions at or above current plus "this will lock out N devices tomorrow morning"), `blocked_versions` (C3, same count), `wave_pct` slider; version-scoped sync hold (P4) | Reproducible-build verification before publish; C3 rules |
| P13 Sync health | Tiles: Login %, "Submit % (of logged-in)", "Day-completion %", final-submit status by zone, trickle ack p50 and p95, batch error rate, `submit_pending_rows`, quarantine backlog, aggregation staleness and dead items, config ack %, photos pending over 24 h; drill zone, route, device. Reads the replica except live-state tiles; degraded mode (zone level, 120 s refresh) when replica lag exceeds 60 s | Re-drive dead items; open quarantine | Calendar-aware baselines (Friday, holidays) |
| P14 Quarantine review | Filter by reason code, zone, date; payload shown PII-masked; counts by reason | Re-map outlet or SKU, accept, discard, return to device; bulk by reason; four-eyes for data-entry-class reasons (G-fraud-24) | Every action audited; accept creates the row through the normal ingest path, never by SQL |
| P15 Day control | Final-submitted zone-days, late-sync queue after final (`after_final_submit`), missing check-outs, `system_closed` zone-days, submitted route-days with their submit time and rows since | Reopen (reason, window), on-behalf Final Submit with reason (G-sre-23); SUBMIT VOID of one route-day with a reason and the confirmer (D-539, F-ADM-074): the route-day returns to in-field, the phone unlocks capture on its next response; DECLARE an emergency non-working day at wing, division, territory or zone scope with a reason code and a length up to `cfg.calendar.emergency_max_days` (D-542, F-ADM-073); all audited | `cfg.day.reopen_roles`, `reopen_window_days`; reopen beyond the window is two-person; submit void inside `cfg.day.submit_undo_window_min` for L1 with confirmation; a declaration is exempt from the freeze and needs a second approver only when retroactive |
| P16 Audit viewer | Unified: `cfg.config_change_audit`, master-data `@AUDIT`, grants, PII reveals, exports, OTP views; filter by actor, key, scope, date | Export; chain-verification status (last verified row, head hash) | Read-only; audit.view only |
| P19 Support desk (D-541, G-qa-72) | One search box accepting username, phone, employee code, memo number, outlet code, `batch_uuid` or a support code `SC-eee-bbb-ccc-ddd-k`. A support code is decoded into the error or state name (from the table of doc 17 s10.7), the build number modulo 1000, the config version modulo 1000 and the 3-digit CRC `ddd` of the device uuid, which narrows the match to the devices whose CRC agrees (the first draft said "last 4 characters of device_uuid"; the authoritative form is the CRC, D-541). Result: the user card and the device card with the fields support needs for "my route is empty": today's route assignment or assignments and `route_day` state and login time, the bundle `valid_for_business_date` and a stale flag, the last bundle download, printer paired, free storage and battery band, clock skew, app and config version and "behind by n", pending rows and `X-Last-Sync-Error`; the last 50 requests with status, `batch_uuid`, rows and reason codes; a deep link to the quarantine list filtered to the user or batch; a "why does this tile show yesterday" panel that reads the `date_reason` chip of doc 16 s9.6; "Create ticket", which posts to the AKTCL ticket tool (MUST-CONFIRM by 6c; default a shared mailbox with a pre-filled body) | Actions follow s5.1b (unlock, OTP, submit void, proposals needing a TSO confirmation); every search is audited with the search term hashed because phones and employee codes are PII; phone and outlet owner are masked unless `pii.view`; at most 30 searches per agent per minute | rate limit, masked PII, audited searches |
| P18 Flags and waves | Matrix: flags by scope (global, each wave, selected territory) with resolved value and provenance; wave membership (territories, devices pinned by `device.wave_id`); per-wave overrides | "Flip for wave 2 only" is a scoped request; wave rollback is `new_app_login_enabled` false at WAVE scope | C3 flags: two-person; break-glass allowed for rollback (restrictive) |

Proved by: T-1-65, T-2-58, T-2-60, T-4-65, T-6-60.

### 5.4b Round-3 page additions (D-586, D-587, D-594, D-595, D-599, D-600)

| Page and tool | Layout and data | Actions and rules | Rails |
| --- | --- | --- | --- |
| P11 replace-device wizard (F-ADM-078, D-586; G-qa-125) | Step 1: pick the user; the card shows the bound devices, their last contact, `X-Pending-Rows` and the held-rows state. Step 2: the old device is handled as "upload first" (default: the device moves to the new state `replaced`, which keeps the upload-only grant of `cfg.auth.revoked_device_grace_upload_h` exactly like `revoked`; the rows arrive as `source = revoked_device` and are parked for the zone TSO) or "lost or dead, revoke now" (reason, C2; pending rows are recoverable only through the PDA replay P20). Step 3: the OTP for the new phone is issued (wave TTL on a wave day). Step 4: the CHECKER, a DMO, `security_admin` or a delegate, approves the replacement in the queue BEFORE the SR binds, so the bind arrives pre-approved and is not held (the wizard supplies the second person that D-112 demands; the takeover rule itself is unchanged). Step 5: the SR binds, the wizard shows done, and the old device's pending rows count to zero or the parked count | Maker: TSO own zone, `support_l2`, `ops_admin`, `support_l1` with TSO confirmation (s5.1b). A user already holding `cfg.auth.max_devices_per_user` devices is warned and the wizard replaces, never silently unbinds (D-585) | Audit rows per step; the checker is never the maker; a replacement approved but not bound within 24 h expires |
| P11 held-binds queue (F-ADM-079, F-API-078; G-qa-125) | A tab and a tile on P15: every bind held by the takeover rule (D-112), a replacement of a bound device (D-486) or a device previously bound to the actor: age, user, zone, maker, device model, reason, the old device and its pending-row count | One-click release or reject by a DMO or `security_admin`; a delegate (`cfg.auth.held_bind_delegate_roles`) may act when the DMO has not acted within 15 minutes or is marked absent; SLA `cfg.auth.held_bind_sla_min` (30) in selling hours; the SR sees "waiting for approval" and the queue shows the age; an alert at `cfg.sla.held_bind_alert_min` (45) raises SH-27 and a Sev3 to `security_admin`; L1 and L2 see the queue read-only so they can answer "my phone will not bind" | Runbooks RB-45 (phone replaced) and RB-46 (bind held); the wave-cohort acknowledgement of D-486 is unchanged |
| P19 ticket store (F-ADM-084, D-599; G-qa-138) | A minimal built-in store behind "Create ticket": category (`cfg.support.ticket_categories`), opened, first-response and resolved timestamps, owner, severity, linked user, device, memo, outlet and batch, an SLA timer that shows red at `cfg.sla.ticket_respond_min` (15) and `cfg.sla.ticket_workaround_h` (2), and a contact log by category (SR, AMO, TSO, retailer dispute) that feeds the D-553 contact-rate measurement. An external ticket tool, if AKTCL chooses one, receives these rows by export; a shared mailbox alone cannot time an SLA or categorise a contact and is no longer the default | Create, assign, respond, resolve, reopen; daily known-issue board | Audited; PII masked; rows are read-only after 90 days |
| P19 directive button (F-ADM-085, F-API-079, D-594; G-qa-133) | On the device card: "Ask the phone to send its PDA", "Ping" (return device state), "Redownload the bundle" (`cfg.support.directive_types`). Each button creates one SIGNED, idempotent directive for that device with an expiry `cfg.support.directive_ttl_h` (24) | The directive is delivered in the next response of any request, in a 401 body or in a config delta, and is acted on at the next foreground contact (never in the background); the app acknowledges it with a `directive_ack` record; a directive for a device that never makes contact stays `pending` and expires, shown on the card | Audited with actor and reason; L1, L2, TSO own zones, `ops_admin`, `security_admin`; no directive can change data, grant, delete or run code |
| P20 Replay console (F-ADM-080, F-API-077, D-587; G-qa-126) | Step 1: select a ticket and a decrypted device bundle (the plaintext of F-API-066 stays in memory, never written to disk). Step 2: the DRY RUN reads every record of the bundle and returns, per record type, counts by verdict: `new`, `duplicate_same`, `duplicate_different`, `voided`, `over_window`, `scope_violation`, `replay_excess` (rows the bundle holds beyond the counts the device last claimed, doc 21 s5.4) with a sample of rows (PII masked) and the business dates they would change. Step 3: reason and ticket id by the maker; the zone TSO gives the second approval. Step 4: APPLY runs the rows through the ordinary ingest path under the ORIGINAL USER's identity and scope, `entry_source = support_replay`, the record-signature check applies, and the result is shown as accepted, duplicate and rejected counts | Mandatory dry run; at most `cfg.support.replay_max_rows` (5,000) per apply; rows over the backdate window need `ops_admin` as a third approver and are flagged `replay_late`; `replay_excess` rows are listed and NEVER applied; applying the same bundle twice equals applying it once because the client uuids are the idempotency keys | Audit row per step; support cannot approve own replay; runbook RB-50 |
| P9 revert batch (F-ADM-082, F-API-081, D-595; G-qa-134) | On every bulk-operation result and on the history list of a batch: "Revert this batch" builds the inverse change from the audit `before` values of the batch | The same preview, freeze, bulk cap and maker-checker rules as the original; allowed within `cfg.master.revert_batch_window_days` (14); rows edited by somebody else since the batch are listed and skipped unless the reviewer forces them; the wholesale mark can be reverted through this path even while `cfg.outlet.wholesale_unmark_allowed` is false, because a wrong 500-outlet mark leaks margin (the wholesale price type is `cc`) | New `batchUuid`, linked to the original; T-6-150 |
| P16 retention-hold tab (F-ADM-083, F-API-082, D-600; G-qa-139) | List of holds: scope (user, outlet, route, zone, business-date range or `all_of_user`), the data classes held (raw fixes, photos, quarantine, audit exports), reason, case id, approver, created and expiry | Created by `security_admin` or `pii_officer` with a second approver; expiry at most `cfg.retention.hold_max_days` (365), renewable; lifting is audited; every archival and deletion job consults `app.retention_hold` before it drops or archives a partition or a blob | Doc 16 s13.1; T-6-152 |

## 6 Geofence management

The radius is the sponsor's named example of R6. This section specifies how it is scoped, delivered to a phone, edited on P2, calibrated and governed. The check itself is docs/05 (Haversine against the stored outlet location, offline, re-checked by the server); this section adds the management of its parameters. Facts from the data profile that shape it: 80 percent of outlets share a roughly 55 m cell with at least one other outlet, 35 percent with five or more, the densest cell holds 383 (P-10); 34,454 outlets sit on 11,222 identical placeholder pins and 1,093 have no coordinates (P-09). A 100 m radius therefore mostly proves "in this market", so geo-validation is one signal among several (D-254), and the radius needs a density view and a calibration step before wave radii are set (D-93).

### 6.1 Scope levels and delivery to the phone

| Level | Editor | Class | What it is for | How the phone receives it |
| --- | --- | --- | --- | --- |
| Global | F, approvers | C3 | The national default (100 m) | Folded into the class map below |
| Wing, division | F, approvers | C3 | Regional calibration | Folded into the class map |
| Territory | F; TSO per `tso_radius_mode` | C2 | Dense urban or hill territory | Folded into the class map |
| House | F | C2 | Rarely used; kept for the data model | Folded into the class map |
| geo_class (optionally with a parent geography) | F | C2 | "Hill outlets inside wing 4 get 150 m", the docs/05 rationale for urban versus rural | Folded into the class map |
| Zone | F, TSO per mode | C2 | A zone whose market is unusual | Folded into the class map; a zone value beats every geo_class row |
| Outlet | F, TSO per mode | C1 | A shop inside a market with poor GPS | Sparse override map |

Class escalation applies on top (D-433): a change that increases the resolved value and ends above `cfg.geo.radius_increase_escalation_m` (150) is C3; an outlet value above `cfg.geo.outlet_override_max_ratio` (3.0) times the resolved zone value needs an approver.

The server pre-resolves, per user chain, a `geo` block in the bundle and in every delta:

```json
"geo": { "class_map_m": { "Hill": 150, "Urban": 100, "SemiUrban": 100, "Rural": 150, "none": 100 },
         "outlet_override_m": { "9912": 25, "10450": 60 },
         "max_accuracy_m": 100, "accuracy_tolerant": false, "fix_timeout_s": 15, "refresh_max": 3,
         "mock_policy": "warn_rep", "no_location_policy": "force_sale_required",
         "bounds": { "min": 20, "max": 2000 } }
```

The device rule is `radius(outlet) = outlet_override_m[outlet.id] ?? class_map_m[outlet.geo_class or "none"]`. The server resolves the class map by running the resolver once per class (four classes plus `none` for the 19 percent of outlets with no class, P-14) on the user's zone chain, so precedence is applied once, on the server, and the phone has no precedence logic. Size: five integers plus at most `cfg.geo.outlet_override_max_per_zone` (50) entries of about 8 B each, so at most 400 B in a zone-wide AMO bundle and under 20 KB fleet-wide at 2,000 overrides (G-cfg-13). Scheduled changes ship as a second `geo` block with `effective_from`. Offline class: CACHED, applies OFFLINE.

### 6.2 P2 Geofence radius management

| Element | Specification |
| --- | --- |
| Filter | Wing, Division, Territory, House, Zone, Route, bounded by the viewer's grants |
| Map | Outlets of the selected scope as points (clustered above 2,000); each with its resolved radius circle; circle colour by provenance: grey default, blue territory, teal zone, purple geo_class, orange outlet override, red dashed scheduled change not yet in force; a dot ring marks `location_confirmed` false. Hover shows the resolved value and its source; click opens the outlet panel. Google Maps tiles are loaded only when the page opens (D-08); no map is shown on dashboards |
| Scope panel | Resolved radius at the selected level with provenance; counts of outlets, routes and devices; last five changes; for 7 and 30 days: geo-valid %, force-sale %, mock %, median distance, and the distance histogram in 25 m bins from `server_distance_m`, so the admin sees that, for example, 18 percent of visits land between 100 and 150 m before choosing 150 |
| Density view | Layer and table from the nightly neighbour counts (s6.3): share of outlets with another outlet within each radius of `cfg.geo.density_neighbour_radii_m`, median nearest-neighbour distance, densest cell, share of pins that are placeholders. A banner reads "At R m, x percent of outlets have a neighbour inside the circle: the gate proves the market, not the shop" when the share at the chosen radius is at or above `cfg.geo.density_warn_pct` (70) |
| Edit at a scope | New value (bounded by `radius_min_m` and `radius_max_m`, and for an outlet by the 3.0 ratio); effective from (now, tomorrow 00:00 Dhaka, a date); reason of at least 10 characters; live blast radius that shows EFFECT and not only target (D-512, G-qa-35): "Targets 1 territory, 4 zones, 37 routes, 1,812 outlets, 41 devices. Resolved value will actually change for 340 outlets (the 19 percent of class none and the classes without a row); 1,463 outlets are SHADOWED by geo_class rows (Urban at wing 4: 1,102, Rural: 361) and keep their value; 9 outlets keep their own override." Each shadowing row is a link, and the drawer offers "also set the geo_class rows" in the same change set |
| What-if | Mandatory in the request: for the last 30 days at this scope, the share of visits that would have been geo-valid at the candidate value, the newly valid count, and the discrimination share (s6.3). Pre-pilot there are no fixes (the Apsis dump has none unless Q18 says so): the panel says "no fixes yet" and the density view still works from the retailer list |
| Bulk edit | Multi-select zones or territories in a table sortable by force-sale %, one value, one change set, one approval; a bulk edit above `cfg.sys.bulk_op_max_rows` (5,000 rows) is refused |
| Outlet override | From the outlet panel: value, reason, optional expiry (`effective_to`), the shop photo from `outlet_photo`; "clear override" closes the row; list of all overrides in scope with age, exportable; overrides older than `cfg.geo.outlet_override_review_days` (90) are listed for review |
| Policies tab | `no_location_policy`, `mock_policy`, `max_accuracy_m`, `accuracy_tolerant`, `fix_timeout_s`, `refresh_max`, plausibility and integrity thresholds (editor S, class C2 or higher), same edit and approval mechanics |
| Calibration tab | The report of s6.4; one click turns a selected suggestion into a change set |
| Effect check | The approval request, the P7 reach view and the audit row carry `outlets_changed`, `outlets_shadowed_by_geo_class` and `overrides_kept` computed at submit and recomputed at approval; an edit whose `outlets_changed` is 0 is refused with "this change affects no outlet: it is shadowed by geo_class rows" unless the editor confirms (T-2-65 asserts effect, not only targeted-device counts). The alternative of placing territory above geo_class when the geo_class row's parent is wider than the territory is NOT adopted: precedence stays as s2.2 so a calibrated class row is never silently overridden |
| Rails | C3 at G, W, D and any increase above 150: two-person plus mandatory canary (one zone for at least one business day; the page offers "create canary request for zone ..." and later "promote canary"); two-sided anomaly watch armed on `geo_valid_pct` and `force_sale_pct` for the scope; alert, not auto-revert (a revert during a selling day would create two rule sets in a day; the alert pages the approver, who reverts in one click) |

### 6.3 Density and the what-if arithmetic

Definitions (the server computes them; doc 16 s8 owns the dw columns, OI-19-09):

| Quantity | Definition |
| --- | --- |
| `neighbors_within_<r>(outlet)` | Count of other active outlets within r metres of the outlet's stored location, by PostGIS `ST_DWithin` on a spatial index, recomputed nightly for r in `cfg.geo.density_neighbour_radii_m` |
| Density index at R | share of outlets in scope with `neighbors_within_R >= 1` |
| Universe U(scope, window) | `sr_call` visits, not abandoned, with a stored fix that is not mocked and has `accuracy_m <= resolved max_accuracy_m` |
| `geo_valid(R)` | `count(v in U with server_distance_m(v) <= R) / count(U)`; shown for all outlets and for `location_confirmed` outlets only |
| Self-check | at the current radius `geo_valid(R)` must equal the recorded server verdict share within 0.5 points; the page shows both so a mis-specified what-if is visible |
| `newly_valid(R_new)` | visits with `R_old < server_distance_m <= R_new` |
| Discrimination share at R | share of valid visits whose fix has no other outlet within R of it: low in a 55 m cell, high in a rural beat |

### 6.4 Calibration plan (closes G-cfg-23 and G-field-11; D-441)

| Step | When | Rule |
| --- | --- | --- |
| 1 Collect | Pilot (7b) at the default 100 m global value, with the pilot's fixes stored for every visit | The dump has no fixes; this is the only source (Q18 may add some) |
| 2 Report | After at least 14 calendar days and at least `cfg.geo.calibration_min_visits` (500) per (geo_class by territory) cell; a cell below the minimum inherits the national class row and shows "insufficient data" | Nightly job; D-93 forbids calibrating earlier |
| 3 Per cell | visits, share honest (not mock, accuracy within bound, location confirmed), distance p50, p90, p95, p99, force-sale %, location-unconfirmed share, density index at 50, 100 and 150 m, and `geo_valid(R)` for R in 50, 75, 100, 150, 200, 300 | |
| 4 Suggest (ASSUMPTION) | The smallest R on the ladder 50, 75, 100, 150, 200, 300 for which `geo_valid(R)` over honest visits reaches `cfg.geo.calibration_target_valid_pct` (95), capped at `radius_max_m`. A suggestion above 150 shows "escalates to C3"; a density index at or above 70 percent shows "discrimination low" | The suggestion is informational; nothing applies automatically |
| 5 Decide | The field-operations manager sets values per geo_class and wing in one change set; canary in one zone for one business day; two-sided watch | The first wave's radii are these values (D-93; MUST-CONFIRM by 7c, Q7) |
| 6 Keep | Monthly re-run. Outlets with at least 5 visits where at least 70 percent of fixes cluster within 150 m of a point farther than R from the stored location are listed as "move candidates": the cure is an outlet location correction through verification (FS-22 rules), not a looser radius | Closes the placeholder-pin tail over time |

### 6.5 No-location, placeholder pins and Update Base

| Outlet state | Gate behaviour | Location after a field capture |
| --- | --- | --- |
| Location present, `location_confirmed` true | Normal gate | A photo from a force sale or a Manual Override raises a location-change request; the call proceeds with `photo_validated` true and `geo_validated` false; the device uses a provisional, flagged location until approved (D-95, D-163) |
| Location present, `location_confirmed` false (placeholder pin, 34,454 outlets) | Normal gate; a failure goes to Force Sale, not a punitive reject | The first force-sale fix becomes the provisional location at once; the AMO alone confirms it (D-111); `location_confirmed` becomes true on that verification |
| No coordinates (1,093 outlets) | `cfg.geo.no_location_policy`: `force_sale_required` (default) opens the call as a force sale with reason `no_outlet_location`; `allow_unvalidated` sells with `geo_validated` false; `block` refuses | Same as the row above |

Update Base (AMO): the AMO's own fix must be within `cfg.geo.update_base_max_distance_m` (100) of the chosen point; the move may not exceed `cfg.geo.update_base_max_move_m` (1000); a move above `cfg.sec.fraud.location_move_alert_m` (300) on a confirmed outlet needs TSO approval; at most `cfg.geo.update_base_max_per_outlet_month` (1) a month; no mocked fix; a photo is required; offline, the map tiles are missing so the AMO accepts the current fix and adjusts numerically (HYBRID, D-08). Every move writes `outlet_location_history` with its source (doc 16 s4).

### 6.6 TSO bounded edit mode

| Mode (`cfg.geo.tso_radius_mode`) | TSO may | Flow | Rails |
| --- | --- | --- | --- |
| propose (default) | Create a proposal at territory, zone or outlet scope inside the bounds | Status `proposal`; an F editor adopts or rejects; the adopted request follows the normal class rules | The TSO sees only P2 for the own territory; no what-if is hidden from the TSO |
| apply | Edit territory, zone and outlet inside the bounds directly | Class C2 delayed apply (10 minutes, cancel) or C1 at outlet; any increase above 150 is C3 and needs a `config_approver` outside the TSO's chain | Two-sided anomaly watch; a rise in geo-valid % after a loosening alerts `security_admin` |

Authority is unknown; confirm with the business (Q24, MUST-CONFIRM by 2d); the default is propose because a TSO is the person whose KPIs a looser radius flatters (G-fraud-09).

Proved by: T-1-61, T-1-63, T-2-65, T-2-66, T-2-67, T-2-68, T-3-60, T-7-84 (pilot readiness: radius reviewed before wave 1).

## 7 Change workflow and safety rails

The failure to prevent is one person, or one typo, changing what 8,500 reps do in the morning: a radius of 10 m, a `min_version` that no phone has, a loosened radius that makes everyone look good. The rails are layered: bounds in the database, class rules, blast-radius preview, two-person approval, canary, anomaly watch in both directions, restrictive-only emergency access, change-rate limits and freeze windows, one-click undo, and an audit that cannot be edited. All of it is ONLINE-ONLY server logic landing in 2d, with the minimal console of 1c carrying bounds, reason, audit and reach only (D-90).

### 7.1 Risk classes

| Class | Meaning | Who edits | Approval | Applies | Canary | Watch | Keys (s3.6) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C0 | Content, cosmetic | One editor of the domain | none; reason optional | on submit | no | no | 59 |
| C1 | Operational | One editor of the domain | none; reason mandatory | on submit | no | only if the key lists KPIs | 198 |
| C2 | Sensitive | One editor of the domain | The requester's submit is a self-approval; any `config_approver` of the domain may approve early | after `cfg.sys.c2_delay_min` (10 min) with a visible cancel, or at once with a second approver | recommended | if the key lists KPIs | 206 |
| C3 | Critical: stops selling, rewrites history or exposes PII | Editor plus a different approver | Two-person (D-88) | at `apply_at`, never inside a freeze window | mandatory at territory scope or wider for canary-required keys | armed automatically | 75, plus two scope-profile keys |

A class may be raised from the GUI (`risk_class_raised`, itself a C3 change) and never lowered (D-88).

### 7.2 Dynamic class escalation (D-433)

The class of a single change can exceed its key's class. The effective class of a change set is the maximum over its changes.

| Trigger | Effective class | Source |
| --- | --- | --- |
| `cfg.geo.radius_m` increases the resolved value and ends above `cfg.geo.radius_increase_escalation_m` (150), at any scope and by any editor | C3 | D-94 |
| An outlet radius above `cfg.geo.outlet_override_max_ratio` (3.0) times the resolved zone value | needs an approver, whatever the class | D-93 |
| A key with a scope profile (`C1/2/3`, `Ca>Cb`) set at a wider scope | the wider tier | s3.1 |
| A change to a `future_dated_only` key | refused unless `effective_from` is a future Dhaka midnight | D-98 |
| An exception or holiday row dated before today | C3 and listed in the retroactive-edits report | G-fraud-21 |
| A grant, revoke or `risk_raise` | C3 | D-435 |
| A change set larger than `cfg.sys.bulk_op_max_rows` rows | refused | D-440 |
| A budget-linked key raised above its default (`photo_max_kb`, `fix_timeout_s`, `fix_accuracy_mode` = high and the others of s2.6) | C3 with the computed budget-impact line | D-546 |
| A radius edit whose `outlets_changed` is 0 because geo_class rows shadow it | refused unless confirmed | D-512 |

### 7.3 Request lifecycle

| From | To | Action and guards |
| --- | --- | --- |
| (new) | `proposal` | A TSO in propose mode (s6.6) creates a proposal; it carries `proposed_by` |
| `proposal` | `draft` | An F editor adopts it (`request_adopted`); the editor becomes `requested_by` |
| (new) or `draft` | `pending_approval` | Submit: bounds, dependency rules, blast radius and what-if are computed and stored; class escalation applied; C0 and C1 skip to `approved` |
| `pending_approval` | `approved` | C3: a different `config_approver` of the domain, outside the requester's reporting chain for geo, fraud, auth, PII and day keys, whose grant is older than `cfg.sec.approver_cooling_h`; C2: the requester (self) or an approver |
| `approved` | `scheduled` | `apply_at` is in the future, or inside a freeze window (`cfg.sys.change_freeze_windows`): the request waits for the window's end |
| `approved` or `scheduled` | `applied` | The apply transaction takes a version, closes and inserts rows, writes `config_version_scope`, publishes `cfg:ver`. Guards: no more than `cfg.sys.c3_max_per_hour` (5) applied C3 versions in the hour, canary rule satisfied |
| any open state | `rejected`, `cancelled` | Approver or requester, with a note; C2's delay window allows cancel |
| any open state | `expired` | After `cfg.sys.request_expiry_h` (72) |
| `applied` | `reverted` | A revert request (s7.7) was applied |

Canary rule. A C3 change on a canary-required key at territory scope or wider must name `canary_of`: an applied request at a narrower scope (one zone for a territory-wide change; one territory for a wing-wide change) that ran at least `cfg.sys.canary_min_business_days` (1) business day without its watch breaching, or it must carry `break_glass`. Canary-required keys: `cfg.geo.radius_m`, `no_location_policy`, `mock_policy`, `rooted_policy`; `cfg.day.checkout_earliest_time`, `late_sync_after_final_policy`, `final_submit_delegate_roles`; `cfg.promo.rules`; `cfg.sale.allow_price_type_override`; `cfg.loyalty.cash_rate_mtk_per_point`; `cfg.sync.engine_mode`. Reverts skip the canary.

### 7.4 Blast-radius preview

Computed at submit and shown on the edit drawer and on P5; recomputed at approval. Counts: zones, routes, outlets, users, devices (active devices of users assigned in the scope for the business date, at most two per user), `overrides_kept` (narrower-scope rows that keep their own value and so are not affected), `outlets_changed` (outlets whose resolved value will actually differ) and `outlets_shadowed_by_geo_class` with the shadowing row (s2.2 rule 7, D-512), and scheduled rows the change overwrites. For a budget-linked key (s2.6) the preview adds the computed budget-impact line. A global change is shown in red.

| Scope | Zones | Routes | Outlets | SRs |
| --- | --- | --- | --- | --- |
| Global | 1,051 | 11,336 | 734,789 | 8,500 |
| Wing (average of 10) | 105 | 1,134 | 73,479 | 850 |
| Division (average of 50) | 21 | 227 | 14,696 | 170 |
| Territory (average of 291) | 3.6 | 39 | 2,525 | 29 |
| Zone (average of 1,051) | 1 | 10.8 | 699 | 8.1 |

Arithmetic from docs/22 P-13 and docs/01, assuming even distribution (ASSUMPTION); the outlet figure is the 1 October retailer list of active outlets, of which about 460,000 are on a day's plan (P-17). The preview uses the real counts; this table is the sanity check for T-2-65.

### 7.5 Restrictive direction (D-432)

Every key with a direction carries `restrictive_dir` in the registry seed (`up`: a higher value is stricter; `down`: lower is stricter; `enum_order`: the enum order in the registry is least to most strict; `none`). The direction drives three behaviours: the scheduled-value fallback on a device with a suspect clock (s4.2), what break-glass may apply (s7.6), and which way the two-sided watch alerts. A key with direction `none` keeps its last known value on a suspect clock and can only be restored by break-glass.

| Family | Direction | Stricter means |
| --- | --- | --- |
| `cfg.geo.radius_m`, `max_accuracy_m` | down | smaller radius, tighter accuracy |
| `cfg.geo.mock_policy`, `no_location_policy`, `rooted_policy` | enum_order | silent_flag < warn_rep < block_sale; allow_unvalidated < force_sale_required < block; ignore < flag < block_login |
| `cfg.geo` plausibility thresholds and `cfg.sec.fraud.*` (a rule that flags when a value exceeds X) | down; `jitter_threshold_m`, `perfect_accuracy_threshold_m` up | the rule flags more |
| `cfg.day.checkout_earliest_time` | up | later check-out opens |
| `cfg.day.sales_submit_dues_warning`; `cfg.sale.stock_check` | enum_order | off < warn < block |
| `cfg.day.reopen_roles`, `final_submit_delegate_roles`, `cfg.pii.field_roles`, `cfg.web.delete_section_data_roles` | down (fewer roles or fields) | fewer holders |
| `cfg.credit.enabled`, `cfg.sale.force_requires_photo`, `cfg.memo.edit_requires_geofence`, `edit_blocked_after_qc`, `cfg.web.delete_section_data_before_final_only` | true is stricter | |
| `cfg.credit.max_due_mtk`, `max_days`, `cfg.memo.reprint_max`, `edit_window_min`, `edit_chain_max` | down | |
| `cfg.auth.lockout_attempts`, `access_ttl_min`, `refresh_ttl_days`, `offline_unlock_max_days`, `otp_ttl_min`, `max_devices_per_user`, `max_users_per_device` | down | |
| `cfg.auth.password_min_len` | up | |
| `cfg.pii.export_rows_per_day`, `list_rows_per_hour`, `cfg.web.entry_backdate_days`, `entry_unlock_max_days` | down | |
| `cfg.sec.record_signature_mode` | enum_order | off < record < enforce |
| `cfg.ops.kill_switch`, `read_only_mode`, `sync_hold_s`, `bundle_hold` | enum_order or up | engaged is stricter; allowed under break-glass for at most `break_glass_max_h` |
| BRAKE-lane keys (D-588, s7.6c): `cfg.release.blocked_versions` | `list_add` | adding a build to the list is the restrictive move |
| `cfg.release.wave_pct` | down | a lower exposure is stricter |
| `cfg.promo.engine_enabled`, `cfg.sync.trickle_enabled`, every `cfg.flag.*` registered with kind `brake` | `false_stricter` | false is the stricter value |
| `cfg.price.list_version` | `list_restore` | restoring the previous published list version is the restrictive move |
| Other `cfg.release.*`, `cfg.sync.*`, `cfg.bundle.*`, `cfg.media.*` and most operational keys | none | restore only |

### 7.6 Break-glass (D-436)

| Aspect | Rule |
| --- | --- |
| Holder | At least TWO named accounts per shift (`cfg.sys.break_glass_holders_min`) holding `breakglass.use` (ops_admin or security_admin), never shared (D-91, D-113), with an audited hand-over at each shift change; a single named account was a single point of failure at 07:30 on wave morning (D-527) |
| Allowed (`cfg.sys.break_glass_mode` = `restore_or_restrict_only`) | Restore a previously applied version of any key; apply a value in the restrictive direction (including the brake-lane moves of s7.6c: add a build to `blocked_versions`, lower `wave_pct`, switch `promo.engine_enabled` or `sync.trickle_enabled` off, restore the previous price list); engage a kill switch, read-only mode, a sync hold or a bundle hold |
| Refused | Loosening a geo, day, credit, fraud, PII or auth key; any grant change; any fraud threshold; a key with direction `none` except as a restore |
| Exempt from | Two-person approval, canary, freeze windows, the C3 hourly limit |
| Never exempt from | Bounds, dependency rules, audit |
| After | An alert to every `config_approver` immediately; `review_due_at` = now + `cfg.sys.break_glass_review_h` (24); a reviewer other than the actor; unreviewed after the deadline escalates to every `security_admin`; operational switches expire by `cfg.sys.break_glass_max_h` (4) |

### 7.6b Emergency widen lane and temporary relief (D-527, G-qa-76, G-qa-77, G-qa-52)

Break-glass refuses loosening, and a radius increase above 150 m is C3 and waits for a canary of one business day and for the freeze windows. A monsoon that drifts GPS at 08:00, a provider bug, or a wrong calibrated radius on wave day 1 therefore has no legitimate fix before the next day plus two approvals, and the only relief is force sale, so force sales and location requests spike exactly when the first visits fail. Two bounded paths close it; both are audited, scope-limited, auto-expiring and arm a two-sided anomaly watch.

| Path | Who and how many approvers | Bounds | Exempt from | After |
| --- | --- | --- | --- | --- |
| Emergency widen lane (under break-glass; F-ADM-077) | one break-glass holder acts at once; ONE approver after the fact (a `config_approver` outside the holder's chain) within `cfg.sys.break_glass_review_h` | the radius only (and `fix_timeout_s`, `max_accuracy_m` within the same ceilings); at most `cfg.geo.radius_emergency_max_m` (250); scope at most one territory unless two approvers act first; expires after `cfg.geo.emergency_widen_max_hours` (6, bound 1 to 12); cannot be extended, only re-requested | the two-person rule, the canary, the freeze windows, the C3 hourly limit | an alert to every approver; the anomaly watch is armed on `geo_valid_pct` and `force_sale_pct` (a rise beyond the alert is expected and is shown as such); the review must happen inside 24 h or it escalates to every `security_admin` |
| Temporary relief (O-kind, F-ADM-077) | TWO approvers BEFORE it applies, no canary; for any scope | at most `cfg.geo.radius_incident_ceiling_m` (300) and never above `cfg.geo.radius_max_m`; expires after `cfg.sys.temporary_relief_max_h` (12, bound 1 to 24); mandatory reason with an incident id | the canary and the freeze windows (not the two-person rule) | the same two-sided watch; at expiry the previous value returns by itself; making it permanent is an ordinary C3 request |

Every other rule keeps its break-glass refusal (credit, fraud thresholds, PII, auth, grants). T-6-10 and T-2-68 gain the cases: a loosen at 08:00 inside the freeze through the lane, expiry after 6 h, a refused 300 m request in the lane, and a hand-over between two holders (T-2-160).

### 7.6c The brake lane: documented brakes must be usable at 08:00 on wave day (D-588, G-qa-127)

The registry describes `cfg.promo.engine_enabled` as the "brake if a rule misfires on launch day", `cfg.release.blocked_versions` as the answer to a corrupting build and `cfg.release.wave_pct` as the exposure control. Under the first draft of s7.5 all of them had direction `none`, so they were restore-only under break-glass and, being C2 or C3, frozen out between 07:00 and 09:30 and 16:30 and 19:30. At 08:00 on wave day the only available levers were the global read-only kill switch (stops every build in scope) and a sync hold (stops upload, the opposite of what a build that corrupts captured data needs). The brake lane closes that.

| Brake (key) | Direction | What it stops | Who, how | Exempt from | After |
| --- | --- | --- | --- | --- | --- |
| Add a build to `cfg.release.blocked_versions` | `list_add` | New captures on that build; upload continues (D-130) | one break-glass holder, or `release_mgr` with one approver outside the freeze | the freeze windows, the canary, the two-person rule, the C3 hourly limit | review within `cfg.sys.brake_lane_review_h` (24) |
| Lower `cfg.release.wave_pct` | `down` | Further exposure of a release inside a wave | `release_mgr` or a holder | the freeze windows, the canary | review in 24 h; raising again is an ordinary C2 change |
| `cfg.promo.engine_enabled` = false | `false_stricter` | A misfiring promotion rule (the fallback is the manual price at list, the sale never blocks) | `P` editor or a holder | the freeze windows, the canary | review in 24 h; switching it on again is an ordinary C2 change |
| `cfg.sync.trickle_enabled` = false | `false_stricter` | Mid-day trickle load; capture and the end-of-day upload continue (R5 is degraded, so the Sev2 of doc 18 s6.6 fires and the move expires in `cfg.sys.break_glass_max_h`) | `O` editor or a holder | the freeze windows | auto-expires; review in 24 h |
| `cfg.flag.*` of kind `brake` set false | `false_stricter` | The feature behind the flag | `release_mgr` or a holder | the freeze windows | review in 24 h |
| `cfg.price.list_version` restored to the previous version | `list_restore` | A wrong price list live on every phone (s8.2b) | one holder with `finance_approver` informed, effective at once | the freeze windows, the canary | review in 24 h; the correction itself then follows the same-day lane |

Rules: a brake move never loosens a rule; it is limited to the scope the holder names; it is audited like every change; it arms the two-sided anomaly watch for the KPIs of the key; the review is by someone other than the actor and unreviewed brakes escalate to every `security_admin`. The list is `cfg.sys.brake_lane_keys` (read-only, migration-only). Gates T-2-173 (an 08:00 block of a build and an 08:00 promo-engine switch-off, both inside the freeze) and the extended T-6-10.

### 7.7 Revert and rollback-to-version

| Operation | Algorithm | Notes |
| --- | --- | --- |
| Revert version v | A new change set: close the rows created in v, re-insert the rows v closed with `effective_from` now; set `is_revert_of = v` | Inherits the class by max risk, skips the canary, is pushed (`B+P`), counts against the hourly C3 limit unless break-glass. Nothing is deleted; both versions stay in history (T-2-64) |
| Rollback to version N | For every (key, scope) touched by a version above N, restore the value `cfg.resolve(known_version = N)` gives at that scope; a scope that had no row at N is closed so it inherits again. Content-type keys restore through the content table's own history | Shows the full diff before submit; same approval by max risk. T-6-61: the resolved snapshot at N is reproduced exactly for 10 random chains |
| Not revertible mid-day | `future_dated_only` keys (rounding mode, quantity unit, numbering): the revert is a new future-dated change from the next Dhaka midnight | A day is never split (D-98) |
| Captured rows | A revert never alters a captured row; rows keep their stamps (s2.4) | D-22 |

### 7.8 Immutable audit

`cfg.config_change_audit` is append-only: INSERT only for the API role, UPDATE, DELETE and TRUNCATE revoked from every role but the migration role, a trigger that raises on UPDATE, and `pgaudit` on the platform administrator role. Each row's `row_hash = sha256(prev_hash || canonical_json(row))`; a nightly job re-verifies the day's chain and a mismatch pages `security_admin` as Sev1. The head hash is written daily to the WORM Blob container (7-year retention, ASSUMPTION, D-113) and to Log Analytics under separate RBAC so a database administrator cannot rewrite both. Audited: every action in the table's action list, plus OTP views, PII reveals and exports (P16). Gate: T-0-64.

### 7.9 Two-sided anomaly watch (D-434; closes G-fraud-09)

| Parameter | Value |
| --- | --- |
| Armed | When an applied change set contains a key whose `anomaly_watch` is non-empty; scope is the affected scope |
| KPIs | Geo keys: `geo_valid_pct` and `force_sale_pct`. Day-flow keys: Login % and "Submit % (of logged-in)" |
| Baseline | Median of the same KPI at the same scope in the same clock window on the last 4 trading days of the same weekday (excluding holidays and exception days from the calendar); with fewer than 2 such days, the last 5 trading days. Friday and holidays are never baselines (docs/22 P-03) |
| Window and cadence | Rolling `cfg.sla.anomaly_window_min` (60), evaluated every `cfg.sla.anomaly_eval_min` (15) between 07:00 and 21:00 Dhaka for `cfg.sla.anomaly_watch_business_days` (2) |
| Minimum sample | At least `cfg.sla.anomaly_min_sample` (50) visits in the window; below it the window is suppressed and shown as "insufficient volume". If the affected scope stays below the sample for three consecutive windows (a pilot of 10 to 20 routes, a single zone), the watch widens to the nearest ancestor scope with enough volume and is labelled as widened (G-19-08) |
| After a loosening | Alert when `geo_valid_pct` rises by `geo_valid_rise_alert_pts` (10) or `force_sale_pct` falls by `force_sale_drop_alert_pts` (10) |
| After a tightening | Alert when `geo_valid_pct` falls by `geo_valid_drop_alert_pts` (10) or `force_sale_pct` rises by `force_sale_rise_alert_pts` (10) |
| Day-flow keys | Alert when Login % or Submit % (of logged-in) falls by `login_pct_drop_alert_pts` or `submit_pct_drop_alert_pts` (15) |
| Routing | Requester and approvers; `security_admin` as well for rises after a loosening; the alert names the version and carries a one-click revert link |
| Response | Alert only, no auto-revert: reverting during a selling day would create two rule sets in one day, and the person who can revert is paged (ASSUMPTION) |
| Dead-signal watch | Separate: a fraud signal kind whose fleet count falls by `cfg.sec.fraud.dead_signal_drop_pct` week over week after a threshold change alerts `security_admin` |

### 7.10 Change-rate limit and freeze windows (D-100)

| Rule | Value |
| --- | --- |
| Applied C3 versions fleet-wide | at most `cfg.sys.c3_max_per_hour` (5) an hour; the sixth is refused with a clear message (T-7-61); break-glass exempt |
| Freeze windows | `cfg.sys.change_freeze_windows`: 07:00 to 09:30 and 16:30 to 19:30 Dhaka on working days (ASSUMPTION: no freeze on a non-working day) block C2 and C3 changes and bulk master-data operations; approved requests wait for the end of the window; EXEMPT: break-glass, the emergency-widen lane and the temporary-relief path of s7.6b, the brake lane of s7.6c (D-588), and an `emergency_off` calendar declaration (D-527, D-542), all reviewed. Reason: a change at 16:55 is a rushed change at the moment 9,842 phones check out and the supervisors read their numbers (doc 18 s5.6). The radius itself travels in the config delta and does not invalidate a pre-built bundle (s2.5, s4.5), which is why the lane above may loosen it at 08:00 without a bundle cost; the first draft's bundle-invalidation reason applied to bulk master-data changes only |
| `scope_version` bumps | Coalesced per user per 5 minutes; bundle regeneration capped at `cfg.bundle.regen_max_per_s` (20) |

### 7.11 Named failure modes and the rail that stops each

| Failure mode | Rail | Gate |
| --- | --- | --- |
| Radius 10 m or 5,000 m typed at any scope | Dynamic bounds trigger and static floor and ceiling | T-0-61 |
| `min_version` typo, or a version no phone has | Must be a published release, at most `latest_version`; live "locks out N devices" count; C3 two-person | T-0-61, T-2-60 |
| Kill switch left on overnight | Mandatory duration, auto-expiry, countdown on P1 | T-2-63 |
| A TSO loosens the radius in the own territory | Propose mode; increase above 150 is C3 with an approver outside the TSO's chain; two-sided watch | T-3-60, T-2-66 |
| Two colluding approvers loosen a key | Chain separation, cooling, hourly limit, watch and outlier report; the residual is accepted (D-124) | T-2-60, T-2-66 |
| Fraud thresholds raised until the catalogue is dark | Editor S only, floors and ceilings, dead-signal watch | T-6-10 |
| Break-glass used to loosen | Refused (`restore_or_restrict_only`) | T-6-10 |
| Back-dated price or target to restate history | `future_dated_only`, finance maker-checker, month lock | T-6-62 |
| Retroactive holiday or exception to shrink denominators | C3, retroactive-edits report | T-6-62 |
| Rounding mode or numbering changed mid-day | `future_dated_only` | T-0-62 |
| An admin flips C3 keys 50 times | Hourly limit | T-7-61 |
| Edit at 16:55 | Freeze window | T-2-68 |
| An offline phone judged by a value it never received | D-431 device-known value | T-1-63 |
| A user grants themselves approval | Grants are C3, cooling, no self-approval | T-2-60 |
| A PII column added to the SR bundle | `cfg.bundle.outlet_fields` and `cfg.pii.field_roles` are C3 | T-4-70 to T-4-76 (doc 21) |
| Outlet overrides inflate bundles | Cap per zone | T-2-67 |

Proved by: T-0-61, T-0-62, T-0-64, T-2-60, T-2-63, T-2-64, T-2-65, T-2-66, T-2-67, T-2-68, T-6-10, T-6-61, T-7-61.

## 8 Master-data and back-office tools

The TSO web portal is a back-office write tool, not only a report viewer (G-man-099; D-190): the manual shows eleven TSO write actions and docs/09 names their pages only. This section states the behaviour of the master-data console (P9) and of each back-office tool, with PARITY where a manual page shows it, IMPROVEMENT where it is added, and ASSUMPTION where the manuals are silent. Table and column contracts are doc 16 s3 to s5; feature rows are doc 15 s7. All tools are ONLINE-ONLY and idempotent by a client uuid or batch uuid so a double click cannot double a number (register s2.6).

### 8.1 Standard behaviour of every master-data entity (the 58 of doc 15 s7)

| # | Rule | Decision |
| --- | --- | --- |
| S1 | Every write carries `@AUDIT` columns and an `app.audit_log` row (actor, entity, id, before, after, reason); audit tables are append-only | D-113 |
| S2 | Optimistic concurrency: the form sends the row version; a stale save returns 409 with the other editor's change shown | F-API-035 |
| S3 | Never hard-delete: a status (active, inactive, closed, merged, archived) or an end date; codes are immutable; archived outlets still own history, dues and loyalty | D-25 |
| S4 | Effective-dated entities (price, assignment, calendar, target, offer) are edited by adding a row with `valid_from`, never by overwriting | D-98 |
| S5 | PII columns are masked in lists; reveal is logged and budgeted | D-107, D-121 |
| S6 | CSV import with dry run: per-row errors, all-or-nothing by default, idempotent by `batchUuid`, unparseable rows to quarantine | D-251 |
| S7 | A TSO writes only inside the own scope; the server intersects every write with the user's scope and resolves every foreign key through `ScopeContext` | D-106 |
| S8 | A master-data change invalidates the affected bundle snapshots by scope, debounced 60 s and capped by `cfg.bundle.regen_max_per_s`; assignments and scope changes bump `scope_version` | D-100, D-129 |
| S9 | Bulk operations: preview of rows and devices affected, `batchUuid`, at most `cfg.sys.bulk_op_max_rows` (5,000), not inside a freeze window | D-440 |
| S10 | A destructive or KPI-affecting action needs a reason of at least 10 characters | D-88 |

### 8.2 Future-dating and back-dating (closes G-fraud-11; D-98)

| Entity | Normal rule | Back-dating | Preview |
| --- | --- | --- | --- |
| `sku_price` | `valid_from` at or after the next Dhaka midnight when the source is admin | `finance_admin` proposes, `finance_approver` approves (`cfg.price.backdate_allowed` must be true for the window) | "Re-flags N memos for price mismatch"; printed memos are never changed |
| Targets | Free editing before the month starts; afterwards only the revision workflow (`cfg.target.lock_after_month_start`) | Closed months are read-only except `finance_approver`; the leaderboard shows a "restated" marker | Count of achievement figures that change |
| Calendar: weekend days, holidays, route-day exceptions | Future dates normal | A row dated before today is C3 and appears in the retroactive-edits report (FS-23 evidence) | Denominators that change (Login %, Submit % (of logged-in), Day-completion %, Daily Tracking) |
| Route assignment | Effective-dated; the bundle follows at the next delta | A past-dated change moves user-level KPIs only through the audited re-attribution event (F-ADM-063, TSO plus finance approval); route KPIs never change | Rows to re-attribute |
| Offers and promotions | `valid_from` at or after the next Dhaka midnight; a live rule changes by a new version | Refused | Memos the rule would have affected (simulator) |
| Synced transactional rows | Never updated except enrichment columns; a trigger blocks UPDATE and DELETE (`tx_no_rewrite`) | Corrections are new rows or events (D-22) | n/a |

### 8.2b Price-change rails (F-ADM-081, D-589; G-qa-128)

Only BACK-dated prices needed a second person. A forward-dated `sku_price` row (valid from the next Dhaka midnight) was one `finance_admin` action with no checker, no plausibility test and no preview, yet it prices every memo on 8,500 phones: the catalogue holds values such as 7.935 Tk, so a typed 79.35 is easy to make. A wrong price live at 00:00 could be fixed only through the back-dating route (C3, two roles) or by waiting for the next midnight.

| Rail | Rule |
| --- | --- |
| Mandatory preview | Before any publish the drawer prints, per SKU and price type: old and new value to three decimals, the percent jump, outlets, devices and users whose bundle changes, and the lines of the last 7 days that would have been priced differently. A jump above `cfg.price.max_change_pct` is shown red |
| Plausibility guard | `cfg.price.max_change_pct` (15): any price type that moves by more than this against the live row, up or down, needs `finance_approver` even when forward-dated. A move above 100 percent (the decimal-slip class) is refused with `ERR_PRICE_IMPLAUSIBLE` unless two approvers sign |
| Same-day correction lane | `finance_admin` plus `finance_approver`, effective at once, restricted to correcting rows published within `cfg.price.correction_window_h` (24) hours. Phones accept it as a price delta (doc 17 E-17). Memos already created at the wrong price are NOT rewritten and printed memos never change; they are flagged `price_corrected_after` and the preview shows their count and value, so the dispute list exists before the first retailer calls |
| Price-list brake | Under break-glass one holder may restore `cfg.price.list_version` to the previous version (s7.6c), effective at once, reviewed within 24 h; the proper correction follows in the lane above |
| Audit and reach | The change is a `list_version` bump, so the reach view shows delivery; every action is audited with before and after |
| Phase and gates | Minimal in 2e (the pilot needs a price revision path, D-590), full in 6a. T-2-166 |

### 8.3 Web Entry (B1; closes G-man-085; D-437)

PARITY elements (Web p19): read-only Date label, Wing to Zone selectors, "Select Classifications" with removable tags, "Select Route", read-only "Target Outlet" (37 in the sample), editable "Successful Call" (5), per SKU Issue, Return, Memos inputs, a derived Sale, one input per selected classification, a "Brand Data" button. Successful Call is one route-level number; only the class columns are per sub-channel (verification).

| Rule | Class | Key |
| --- | --- | --- |
| The route-day entry is its own source with its own tables (`web_entry_route_day`, `web_entry_line`, `web_entry_line_class`, doc 16 s5) and a client uuid per submission | PARITY of fields; IMPROVEMENT for the uuid | D-40 |
| Sale = Issue minus Return per SKU | ASSUMPTION (inferred, not on the page); confirm with a TSO | D-437 |
| Return is at most Issue; no negative number; the class quantities of a SKU sum to its Sale | ASSUMPTION | D-437 |
| Successful Call is at most the Target Outlet snapshot of the route-day | ASSUMPTION | `cfg.web.entry_validate_calls_le_target` |
| Quantities are in the SKU's base unit (sticks, pieces, dozens) with the unit shown on every cell | D-16 | D-16 |
| One entry per route-day; a re-save replaces the previous version with an audit row, before Final Submit only | IMPROVEMENT | D-437 |
| Save button | PARITY: the manual shows a green Save (disk icon) on Web Entry (manual S-18); only Astha Web Entry has none, where an explicit Save is the IMPROVEMENT (s8.5, D-537, G-qa-67) | D-437, D-537 |
| Astha-channel outlets are NOT keyed in Web Entry (rule R-050: Astha quantities go through Astha Web Entry only): "Select Classifications" offers the non-Astha classes (Astha tiers excluded) or a DQ rule flags the same outlet-day in both grids, so Astha STD is never double counted in `fact_daily_outlet` and `agg_daily_route` | PARITY of the manual caption; the flag is an IMPROVEMENT | D-537 |
| Web Entry is an AGGREGATE tool (route-day per SKU: Issue, Return, Memos, Successful Call). It carries no memo, outlet, price, discount, paid or due, so it cannot restore a credit memo, a retailer's due, outlet-level STD or loyalty. A dead or lost phone is recovered by the SUPERVISED PAPER-MEMO BACKFILL (F-ADM-075, `backfill.enter` and `backfill.approve`, D-543): support keys each printed memo by its `memo_no` with outlet, lines, price and paid amount; the server recomputes the totals and holds the entry on a difference (DQ-14); the zone TSO approves (four-eyes, `entry_source = 'manual'`); the memo uuid is derived from the printed number so a double keying creates nothing twice; within `cfg.entry.paper_backfill_window_days` (7) of the business date; if the phone later uploads the same memo_no the device row wins and the manual row is marked superseded | IMPROVEMENT; closes G-feat-45 for the dead-phone case | D-543 |
| "Brand Data" is hidden until its purpose is captured from the live app (hypothesis: brand-level memo counts for BSR) | unknown; confirm: MQ-46 | |
| Web rows and app rows for the same route-day are never added: with `cfg.web.entry_app_overlap_policy` = `exclusive_flag`, entering a route-day that already has app memos is blocked with their count; if app rows arrive after a web entry, aggregates use the app rows, the web rows are marked `shadowed`, and the TSO sees a flag | D-40; the app-wins rule is an ASSUMPTION (the device is the system of record) | D-40 |
| Data Entry by `support` or `admin` above `cfg.sec.fraud.web_entry_memos_per_route_day` (20) memos needs the zone TSO's confirmation; a TSO's own entry above it raises an Exceptions item for the DMO (FS-28) | IMPROVEMENT; closes G-fraud-24 | FS-28 |
| How often Web Entry is used today decides whether it is needed before the pilot (4c) or can wait (6a) | unknown; confirm: MQ-46 | |

### 8.4 Web Final Submit, audited void and the back-date window (B2; closes G-man-086 and G-man-087; D-438, D-439)

PARITY elements (Web p20): a Bangla banner (the back-date rule), Wing to Zone single-select with Filter, a table Route, SR Name, Status, Actions, an advisory "Before Final Submit, Please Checkout Sales Data From DSS Report. If Everything OK, Then Proceed to Final Submit", a "Date of Data Entry" picker and Submit. A red "Delete Section Data" shows only on a route whose status is "exist" and the manual shows no confirm and no success message.

| Rule | Class | Key or decision |
| --- | --- | --- |
| Final Submit is one server rule for the app and the web: `POST /day/final-submit {zoneId, businessDate, clientUuid}` (F-API-009), once per zone per day by primary key; the same uuid returns the first success, another uuid gets 409 with the same Bangla text; `GET /day/final-submit/preview` (F-API-039) feeds the already-submitted alert at Filter | PARITY | D-55, D-82, D-184 |
| "SR Not Set" routes are listed and do not block | PARITY | `cfg.day.final_submit_allow_not_set_routes` = allow |
| An explicit confirm before the irreversible submit | IMPROVEMENT | `cfg.day.final_submit_confirm` |
| DSS advisory text is a catalogue string; ticking an acknowledgement is off by default | PARITY | `cfg.day.final_submit_requires_dss_ack` |
| "Delete Section Data" is an audited void, never a SQL delete: a modal with the counts to be voided (web-entry rows, app memos), a reason of at least 10 characters, a scope choice and a confirm; the manual shows no confirm | DELIBERATE CHANGE for the confirm and the void semantics (D-182) | D-438 |
| Default scope is web-entry rows only. Voiding app-synced memos needs `ops_admin`, a two-person approval (C3) and `cfg.web.delete_section_data_scope` = `include_app_memos` | IMPROVEMENT | D-438 |
| Effect: a `data_void` row (route, business date, scope, reason, voided_by, summary), affected rows `status = void`, the voided client uuids stay in `ingest_registry` as tombstones, the business date is re-aggregated by a dirty key, and a Final Submit Log entry is written | D-22 | F-API-048 |
| Sync interplay and the route-day void barrier (D-577, G-qa-113): a tombstone per `client_uuid` already in `ingest_registry` only covers rows the server has seen. A void therefore also writes a BARRIER on the route-day, `(route_id, business_date, voided_at, scope)`, and ingest rejects every record whose route and business date match and whose `captured_at_trusted` is earlier than `voided_at` with `voided_by_admin`, whether or not its uuid is known: the unsent rows of the voided route-day on the SR's phone return as rejected, appear on the device-vs-server screen and never resurrect or double a number. Rows captured AFTER `voided_at` (the SR keeps selling) stay legitimate. The void modal shows the server counts AND the route's pending-row count (the `X-Pending-Rows` of the last request or its entry in the SH-24 held-rows list) with the line "n rows still on the SR's phone were captured before this void and will be rejected"; the default advice is "ask the SR to sync first, then void". A final-submitted zone-day never rejects a late row: rows captured for it are ACCEPTED, aggregated and flagged `after_final_submit` (D-55, D-579); `day_closed` is not a device reject code | D-22, D-55, D-577, D-579 | T-4-62, T-4-166, T-3-159 |
| Delete is offered only before Final Submit | PARITY with the manual's single appearance; guard | `cfg.web.delete_section_data_before_final_only` |
| What "exist" counts and what Delete removes in the live app are unknown; the default reading is "at least one web-entry or app row for the route-day" | unknown; confirm: MQ-43 (MUST-CONFIRM by 4c) | D-40 |
| The back-date cut-off: `earliest_entry_date(zone) = today - cfg.web.entry_backdate_days` (resolved at zone scope, default 0, so today only) when `cfg.web.entry_cutoff_source` is `rolling`, or `zone.data_entry_date + 1` when it is `zone_data_entry_date`. The banner is built from that date and `cfg.support.contacts`. The mechanism is unknown: the banner date is one day after the Sales Plan "Data Entry Date" in the sample, which fits both | unknown; confirm: MQ-44 (MUST-CONFIRM by 4c) | D-97, D-439 |
| "Phone support, who edits the DB" becomes an audited `entry_unlock_grant` (zone or route, date range of at most `cfg.web.entry_unlock_max_days` (7), expiry within `cfg.web.entry_unlock_ttl_h` (24), reason, `used_at`) raised by `finance_admin` or support (F-API-049); a grant over 7 days or covering a closed month needs `finance_approver` | IMPROVEMENT | D-439 |
| The app sync window (`cfg.sync.max_backdate_days`, 7) is a different surface and is documented separately in docs/09 | | D-97 |

### 8.5 Astha Web Entry, web QC, Sales Plan and wholesale marking (B3 to B6)

| Tool | Behaviour | Gap and phase |
| --- | --- | --- |
| Astha Web Entry (B3) | Outlet by SKU quantity grid for the Astha-channel outlets of the selected route (Web p21; no Save button in the manual, an explicit Save here). Quantities in sticks. Submissions carry a client uuid. Astha-channel outlets are keyed ONLY here and never in plain Web Entry (R-050, D-537). Feeds `fact_daily_outlet`, the outlet-brand aggregate and Astha quarter achievement. The overlap rule with app memos for the same outlet-day is the Web Entry rule of s8.3. Why Astha outlets are excluded from plain Web Entry is unknown; ASSUMPTION: outlet-level STD is needed for tier targets and gifts; confirm with a TSO | G-man-046, 5a |
| Web QC (B4) | QC Entry (market) and Warehouse QC Entry: a grid of SKU by fault type for a zone, route (market only) and date; fault types come from `cfg.qc.fault_types` filtered to `applies_to` web (10 labels, with the overlap map to the 6 app labels; groups MFC and MKT stay stable codes); rows are `qc_summary_entry(kind market or warehouse, zone, route, date, sku, fault_type, qty)`; web QC and app QC share one fact with a `source` column and are never double counted; reports `qc` (json, xlsx, pdf) and `qc-route` (QC Type Market or Warehouse); re-entry follows `cfg.qc.web_reentry_policy` (replace), cell limit `cfg.qc.max_qty_per_cell`, roles `cfg.qc.web_entry_roles` | G-man-089, 4c |
| Sales Plan (B5) | Per zone row: Email, Address, PDA Contact No. (meaning of "PDA" unknown), enabled SKUs as chips with a tri-state picker tree, Data Entry Date (Web p9 to p11). A pencil opens edit; the green tick saves one zone atomically with an audit row; the red cross cancels; nothing is saved before the tick (PARITY). "Apply to all zones of this territory" is a bulk action with a device preview (the caption implies territory scope). Plan changes reach phones at the next bundle; an offline phone keeps the old plan and the server flags `sku_not_in_plan`, never rejects. TSOs edit their own zones (`cfg.sales_plan.edit_roles`). New zone columns `email`, `address`, `pda_contact_no`, `data_entry_date` are doc 16's | G-man-088, 6a |
| Wholesale marking (B6) | Wholesale Status Yes or No filter, outlet search, a table with select-all (indeterminate state), a floating basket with a live count, a Selected Outlets dialog, Submit (Web p42 to p45). `POST /outlets/outlet-kind/bulk {batchUuid, outletIds[], kind, reason}` (F-API-045), idempotent by `batchUuid`, one audit row per outlet and an `outlet_kind_history` row. Selection persists across pages; "select all" means all rows matching the current filter, with the count shown and a confirm above 500. Unmark is behind `cfg.outlet.wholesale_unmark_allowed` (default false; the manual shows no unflag). Effect is fixed before building (D-42): price list `cfg.outlet.wholesale_price_type` (cc), target-outlet counting and the geo gate unchanged, soft quantity ceiling by kind; outlet kind stays separate from the channel enum | G-man-036, 6a; unknown; confirm: Q17, Q46 |

### 8.6 Targets, gift choice, outlet approval and OTP (B7 to B10, P10)

| Tool | Behaviour | Gap and phase |
| --- | --- | --- |
| Set Target and approval list (B8, P10) | Wing, Division, Territory, month picker, Apply; a manual grid (route by variant) or Download Sample and Upload Excel. The sample workbook has route code, route name, variant code, STD target and optional memo target (`cfg.target.template_version`). Validation: at least 0, numeric, known route and variant, territory in scope, no duplicates, month; all-or-nothing with a downloadable per-row error sheet. The file is stored in Blob (`media_object` purpose `target_upload`) and linked to the `target_set` header (name, product type variant, type stt, start, end, status, source, submitted_by). Every set needs approval, first "WMO approval pending" (levels in `cfg.target.approval_levels`, one level by default). Entry window `cfg.target.entry_window`; after the month starts, changes go through the revision workflow. No automatic split is evidenced; `cfg.target.split_method` is manual | G-man-090, 5c; MUST-CONFIRM by 5c (Q12) |
| Astha Gift Choice Panel (B9) | Route-scoped list of Astha outlets with a per-row gift dropdown (Ceiling Fan 56 inch, 24 pcs Dinner Set, 27 pcs Dinner Set) and a Gift Choice Report with a Gift Status filter. Explicit save; the choice locks when the SR's photo exists (`cfg.astha.gift_choice_lock`); one gift per outlet per quarter; the choices travel in the SR bundle so the Astha photo list works offline. Whether the manual panel autosaves, and whether a choice can change, are unknown; confirm: MQ-26 | D-192, 5a |
| Outlet Approval Panel (B10) | One panel serves New, Close, Info and location-change requests through an Outlet Type filter with Verify, Reject and Approve (an Approve confirm dialog is evidenced). Rows carry origin role, `moved_m`, and a `remote_verification` badge; a supervisor-originated request is verified by the TSO (`cfg.outlet.supervisor_request_verifier`); a rejection needs a reason (`cfg.outlet.reject_requires_reason`) and is shown to the SR; approving a closure sets status closed (with `cfg.outlet.close_block_if_dues` applied); bulk approve is disabled for location moves; approving a new outlet assigns the code (F-ADM-037) | D-43, 3a minimal, 4c full |
| SR Device OTP panel (B7) | View only: filters Wing to Zone, View, search, refresh; columns are the union of the SR manual's eight and the web manual's five (Sr No., Field Force ID, Field Force Name, Username, Zone ID or Zone Code, Zone, Create Time, OTP); the manual has no issue button, so re-issue and bulk pre-issue (F-ADM-069) are admin improvements; the code is stored encrypted and reversible (AES-GCM, key wrapped in Key Vault) so the TSO can read it; every reveal is audited (`otp_viewed`, aggregated per actor, zone and minute); visible to `cfg.auth.otp_visible_roles` | D-103, 0c |

### 8.7 Programme setup, content management and the master-data page (P8, P9, P17)

| Page | Specification | Gap and phase |
| --- | --- | --- |
| P8 Programme setup | Tabs. Promotions: a rule builder over `cfg.promo.rules` (qualifying brand or SKU set, ratio, reward SKU, scope, validity, stackable, priority) that refuses overlapping non-stackable rules on one SKU and scope, and the test-a-memo simulator (F-ADM-061): enter quantities for an outlet and see applied offers, offer discount, DRP discount and the printed totals; the fixture is 10 empty MaxR-10S packets giving 1 reward pack, a deduction of 80.00 (D-33). Free samples. Astha: quarter, brands, per-outlet per-brand STD targets and one memo target, gift catalogue, tiers. Diamond League: period, earning rules (seed: POSM survey Q1.1 photo gives 50 points), catalogue, cash rate and cap, expiry. Superstar: slabs, criteria, enrolment. Every tab edits future-dated rows with a diff against the live rule set | D-33, D-41, 2a, 5a, 5b |
| Content management | Survey questions, joint-call rubric, AV/KV items, tutorial videos and manuals, support contacts, i18n overrides, print templates: each is a content-type key edited here, with both bn and en text required before publish, a phone-frame preview, an `effective_from`, deactivate instead of delete, media at most `cfg.content.max_item_mb`, and a version bump through `cfg.bump_version` so phones receive it on the next delta. A business edit needs no release | G-cfg-11, G-feat-12, G-feat-47, 6a |
| P9 Master data | Left: the 58 entities in groups (geography, products, routes and assignments, users and scope, outlets, classifications, offers and programmes, calendar, content). Centre: a table with search and filters; right: a form with S1 to S10 behaviour. Users page: create, disable, employee code, designation, temporary password (24 h, forced change), reset by TSO or support. Outlets page: PII-gated columns, status changes with reason, reactivation (F-ADM-070), merge | F-ADM-001 to F-ADM-011, 2e minimal, 6a |
| SR lifecycle wizard (in P9 and P11; F-ADM-076) | Onboarding: create user, assign scope and route with dates, issue a temporary password, pre-issue the OTP, watch the device bind. Offboarding: disable the user (the phone may still upload rows captured before the disable, `cfg.user.dismissal_upload_grace_h`, doc 17 s4.6), revoke devices after upload (`revoked_device_grace_upload_h`), reassign routes, check dues and loyalty stay with outlets. Holders: `user_admin` (TSO own territory, DMO own division) with maker-checker, `security_admin` and `master_data` globally (s5.1b, D-551). Every step is audited. The MINIMAL wizard (create, bind, disable-with-upload, reassign) lands in 2e because day-one quarantines are mostly assignment and scope errors and only a few HQ stewards could fix them against 1,051 AMOs and 291 TSOs; the full wizard stays 6c | G-feat-63, D-551, 2e minimal, 6c full |
| Absence and vacancy (F-AMO-047, D-551) | An absent SR is a `day_exception` per route per day raised by the AMO and approved by the TSO (D-337 gives no SR or AMO leave). The AMO gets a BULK "mark absent" for several routes at once (`cfg.day.bulk_absent_max_routes`, 60); a route with no assigned SR for `cfg.route.vacancy_alert_days` (3) planned days enters a VACANCY list on P9 and P15; a vacancy longer than `cfg.day.exception_max_days` (7) or `cfg.route.cover_max_days` is converted to a cover or a reassignment by the TSO, not renewed exception after exception | G-man-032, D-337, D-551, 3a |
| P17 Import console | Run an import (dump shape of docs/11), staged by table, with control totals and the quarantine report; per-wave temporary-password batch (F-ADM-068); parallel-run comparison against the Apsis daily data; rollback of an `import_run_id` | F-ADM-032, F-ADM-054, 7a |

### 8.8 Bulk operations, the readiness board and the staging mirror

| Item | Rule | Decision |
| --- | --- | --- |
| Bulk operation | One request, one `batchUuid`, preview of rows and devices, refused above `cfg.sys.bulk_op_max_rows` or inside a freeze window; the result lists accepted and rejected rows | D-440 |
| Month-end readiness board (on P1) | From `cfg.sla.targets_missing_alert_day` (26): zones without next-month targets, Astha quarter and programme periods ending within 14 days, holiday calendar coverage for the next 60 days, `route_day` rows for the 1st. An alert goes to the programme owner | D-449; G-field-17 |
| Revert a batch (F-ADM-082, D-595) | A bulk operation can be reverted: the inverse is built from the audit `before` values of its `batchUuid`, with the same preview, freeze window, bulk cap and maker-checker, a new `batchUuid`, and a window of `cfg.master.revert_batch_window_days` (14); rows changed since by someone else are listed and skipped unless forced. Day-one errors are mostly assignment and scope errors and a wrong wholesale mark leaks margin, so the revert is the supported undo; gate T-6-150 applies 5,000 assignment changes, reverts them and asserts the affected columns are identical to the starting state | D-595; G-qa-134 |
| Staging mirror | A change set exported from production is importable into staging, never the reverse; secrets, PII and `cfg.sec.*` values are excluded | D-444; G-cfg-17 |

Proved by: T-4-61, T-4-62, T-4-63, T-4-64, T-5-62, T-5-63, T-6-62, T-6-63.

## 9 Releases, waves and flags

The release console (P12) and the flag and wave matrix (P18) are how a person moves 8,500 reps between app versions and between the old and the new app without a release of the app itself, and how a wave is rolled back (R3, D-148). Distribution is AKTCL's own signed APK channel (D-10, MUST-CONFIRM by 0c); a Play track cannot self-install.

### 9.1 Release console (P12)

| Step | Behaviour | Rail |
| --- | --- | --- |
| Upload | One APK per ABI per flavour (SR, AMO, TSO; D-01). The server verifies the signature, computes SHA-256, reads the version name, and checks size against `cfg.release.apk_max_mb` (30 per ABI; target 22) | Size gate; reject an unsigned or wrongly signed file |
| Verify | An independent rebuild of the release tag must reproduce the CI digest per ABI before publish (`cfg.release.require_reproducible_build`) | Publish refused on mismatch (T-7-10 in doc 21) |
| Publish | A human action by `release_mgr`; creates the `app_release` row (version, flavour, ABI, APK URL behind Front Door, SHA-256, size, Bangla and English release notes, status `published`) | A published row is immutable; a bad build is withdrawn, never edited |
| Stage | `wave_pct` per wave: a device is in the rollout when `hash(device_uuid, release_id) mod 100 < wave_pct` and its wave matches. `GET /app/update-check?version=&role=&abi=` (F-API-029) answers per device | A percentage can rise, never fall below what already installed (no downgrade) |
| Promote | Set `latest_version` (C1: prompts), then `min_version` (C3: dropdown of published versions at or above the current one, with the live count "this will lock out N devices tomorrow morning" computed from devices below it seen in the last 14 days) | Two-person; canary through `wave_pct`; `min_version` blocks a new day's login only |
| Hold | `cfg.ops.sync_hold_by_version` (P4): an edge 429 for one build stuck in an upload loop; default duration 2 h, maximum 24 h; rows stay on the device | The only exception to "upload is never blocked" (D-130) |
| Auto-freeze (D-507, G-qa-31) | When a canary cohort (at least 100 device-days, stratified by model) regresses on field CPU, engine starts or whole-device battery drop by more than `cfg.release.auto_freeze_regression_pct` (20) against the previous release (sync-health SH-19 to SH-21, doc 17 s8.10), `wave_pct` is FROZEN, the slider is disabled and a Sev2 alert names the metric; only a named `release_mgr` may clear the freeze, with a reason | Automatic; no promotion while frozen |
| Remote brakes | `cfg.ops.prefetch_enabled`, `cfg.telemetry.enabled` and `cfg.geo.radio_env_enabled` can be switched off for a wave or a canary cohort from P4 (C1, mandatory duration) without a release | Capture, record sync and printing are never touched |
| Roll back | There is no downgrade (the local database is not downgradable and a reinstall wipes pending rows). The path is a roll-forward rescue release within 24 h, `blocked_versions` to stop new captures on the bad build, and the previous sync engine behind `cfg.sync.engine_mode` for one release | D-79, G-sre-20 |

Adoption chart: share of active devices by `app_version` over time from `app.device.app_version`, per flavour and wave.

### 9.2 Kill-switch and version semantics (D-130, D-445)

| Switch | Refuses | Never | Duration | Who |
| --- | --- | --- | --- | --- |
| `cfg.release.min_version` | A new business day's login on a build below it; an open offline day finishes (`finish_offline_day_before_force`) | Capture, upload, local data | until changed | release_mgr, C3 |
| `cfg.release.blocked_versions` | New captures on the listed builds | Upload of captured rows | until changed | release_mgr, C3 |
| `cfg.ops.kill_switch` `read_only` | New captures | Upload; wipe | at most `kill_switch_max_h` (4) | ops, C3, break-glass allowed |
| `cfg.ops.kill_switch` `block_login` | A new day start | Upload; wipe | same | ops, C3, break-glass allowed |
| `cfg.ops.read_only_mode` | Server writes (503 plus `Retry-After`), for a migration | Local capture | mandatory | ops, C3 |
| `cfg.ops.sync_hold_s` | Uploads for a randomised time returned in `hold_s` | Local capture | mandatory | ops, C3 |
| `cfg.ops.sync_hold_by_version` | Uploads of one build at the edge (429) | Local capture; rows stay | 2 h default, 24 h maximum | ops, C3 |
| `cfg.ops.bundle_hold` | Bundle generation; devices fall back to `stale_max_days` | Selling on the cached bundle | mandatory | ops, C3 |
| `cfg.flag.new_app_login_enabled` false (wave scope) | A new-app login for the wave's devices | Upload of rows already captured | until changed | release_mgr, C3, break-glass allowed |

Every O key has a mandatory duration, auto-expires, and shows a countdown on P1 and P4 (G-cfg-16).

### 9.3 Waves and the wave data model (closes G-cfg-22)

| Element | Definition |
| --- | --- |
| `app.rollout_wave` | `id, name, status (planned, pre_bind, live, stable, rolled_back, closed), start_date, planned_users` (doc 16 owns the DDL) |
| `app.rollout_wave_member` | `wave_id, scope_type (territory or zone), scope_id`; a territory belongs to at most one live wave |
| `app.device.wave_id` | Optional pin; otherwise a device inherits the wave of its user's territory or zone (the most specific member wins) |
| Scope | `scope_type = 'wave'` in `cfg.scope_level` (precedence 20) so any wave-scoped key can differ per wave: `cfg.auth.otp_ttl_min` (24 h on a wave day), `cfg.sync.login_jitter_s`, `cfg.release.min_version`, `cfg.release.wave_pct`, `cfg.sync.trickle_enabled`, flags |
| Wave plan | Waves never start within three days of a month end, Eid or a holiday, nor on a Thursday; they start Sunday to Tuesday so T-1 is a trading day (D-147, D-311); each wave has a pre-bind day T-1 (D-126). The calendar and sizes are doc 14's |
| Programme exclusion | Wave 1 excludes zones with Astha, Diamond League or Superstar outlets until 5a to 5c exit (doc 14 s3, ASSUMPTION; MUST-CONFIRM by 5a) |

Wave rollback (D-148): flip `cfg.flag.new_app_login_enabled` to false at WAVE scope (C3, push, break-glass allowed because it is restrictive for the new app); new-day login on the new app is refused for the wave's devices with the support banner; already captured rows keep uploading; data captured in the new app during the wave is exportable in the Apsis dump shape by an export job (doc 14, doc 20), because AKTCL cannot set the Apsis app read-only itself (how the old app is made read-only per wave is unknown; confirm: MUST-CONFIRM by 7b). Gate T-7-60.

### 9.4 Feature flags

Flags are `cfg.flag.<name>` keys; the kind (`release`, `ops`, `wave`) is registry metadata (D-447). Defaults follow the phase in which the feature ships; a flag is never used to change the shape of captured data (that is a `schema_version` bump, D-99).

| Flag | Kind | Class | Purpose | Ph |
| --- | --- | --- | --- | --- |
| `new_app_login_enabled` | wave | C3 | Gates the new app for a wave; the rollback lever | 7b |
| `parallel_run_mode` | wave | C3 | `off`; `capture_only` (the new app captures, rows marked `parallel`, excluded from aggregates and balances); `print_test_watermark` (test print "পরীক্ষামূলক - এটি রসিদ নয়", no previous-due line) (D-152) | 7b |
| `pilot_in_rollups` | ops | C3 | Pilot accounts are excluded from national rollups when false (D-144) | 7b |
| `print_enabled`, `credit_ui`, `loyalty_ui`, `astha_ui`, `superstar_ui` | release | C2 | Hide a tile until its programme is ready; wave 1 can run without the programme tiles | 6c |
| `promo_engine_v2`, `suggested_qty_ui`, `amo_control_call`, `amo_survey`, `photo_upload_v2` | release | C2 | Staged exposure of a new behaviour; removed within two releases of 100 percent | 6c |
| `anti_spoof_warnings` | ops | C2 | Turns the SR-visible mock warning on or off; no other fraud flag is ever SR-visible (D-123) | 6c |
| `tso_final_submit_new` | release | C3 | Gates the TSO Final Submit client path | 6c |

Proved by: T-2-56, T-2-63, T-7-60, T-7-62.

## 10 Admin API

Offline class: `GET /config/delta` is ONLINE-FIRST (pulled on the next natural request; the device then applies from its cache and works OFFLINE), the ack is QUEUED in the outbox, `GET /config/public` is cached at the edge; every other endpoint is ONLINE-ONLY. JSON under `/v1`, error envelope `{code, message, details, request_id}` with stable `ERR_CFG_*` codes, scope and permission enforced on the server, `Idempotency` by `client_uuid` or `batchUuid` on every write. F-API ids are doc 15's; rows marked "needs F-id" are new admin endpoints doc 15 must mint (OI-19-10).

| Endpoint | Purpose | Permission | F-API | Phase |
| --- | --- | --- | --- | --- |
| `GET /config/public` | Unauthenticated, Front Door cached 60 s: global `min_version`, `latest_version`, `update_url`, banner, `support.contacts`, `default_locale`, version | none | F-API-042 | 0c |
| `GET /config/delta?since=<v>` | Changed keys, scheduled list, removed; ETag | device token | F-API-040 | 1c |
| `POST /config/ack` and the `config_ack` outbox record | Applied version | device token | F-API-041 | 1c |
| `GET /sync/bundle` (`config` and `geo` blocks) | Resolved snapshot at bundle time | device token | F-API-005 | 1c |
| `GET /admin/config/items?area=` | Registry rows with bounds, class, direction | cfg.view | F-API-037 | 1c |
| `GET /admin/config/resolve?scope_type=&scope_id=&parent=&as_of=&known_version=&keys=` | Resolved values with provenance for any node | cfg.view | F-API-037 | 1c |
| `PUT /admin/config` | Creates a change request (minimal console: applies at once for C0 and C1) | domain editor | F-API-037 | 1c |
| `GET /admin/config/whatif?key=&scope=&value=&days=` | Re-evaluates stored fixes under a candidate radius | F editor, TSO own scope | F-API-058 | 2d |
| `GET /admin/config/blast-radius?scope_type=&scope_id=` | Zones, routes, outlets, users, devices, overrides kept | cfg.view | F-API-059 | 2d |
| `GET /admin/config/density?scope=` and `GET /admin/config/calibration?scope=` | Density index and the calibration report | F editor | F-API-060 | 2d, 7b |
| `POST /admin/config/requests`, `GET .../requests?status=`, `POST .../{id}/approve`, `reject`, `cancel`, `adopt`, `break-glass` | Request workflow | per class | F-API-061 | 2d |
| `GET /admin/config/versions?from=&to=`, `GET .../versions/{v}`, `POST .../versions/{v}/revert`, `POST .../rollback-to/{v}` | History, revert, rollback | editor; approvals by class | F-API-062 | 2d, 6b |
| `GET /admin/config/reach/{version}`, `GET .../reach/{version}/pending?zone=` | Delivery view | cfg.view | F-API-063 | 1c |
| `GET` and `POST /admin/permissions` | Roster; a grant is a C3 request of type `grant` | security_admin | F-API-064 | 1c |
| `GET /admin/audit?actor=&key=&scope=&from=&to=` | Unified audit | audit.view | F-API-037 | 2d, 6b |
| `GET /admin/devices`, `POST /admin/devices/{id}/revoke` | Device list and revoke | ops_admin, security_admin | F-API-036 | 2e |
| `GET` and `POST /admin/device-otp` | View, re-issue, bulk pre-issue | TSO (view), ops_admin | F-API-036 | 0c, 7b |
| `/admin/releases`, `POST /admin/releases/{id}/publish`, `GET /app/update-check` | Release console and update check | release_mgr | F-API-029 | 2e, 6c |
| `/admin/migration/*` (waves, import, compare, export) | Waves, import runs, parallel-run compare | release_mgr, importer | F-API-033 | 7a, 7b |
| `/admin/flags` | Flag matrix by scope | release_mgr | F-API-065 | 6c |
| `GET /ops/sync-health`, `GET` and `POST /ops/quarantine` | Sync health and quarantine actions | ops_admin | F-API-028 | 1c, 2e |
| `POST /web-entry/route-day`, `/web-entry/outlet-sku`, `/web-entry/qc` | Web Entry, Astha Web Entry, QC entry | TSO, support | F-API-050, 051, 052 | 4c, 5a |
| `POST /day/final-submit`, `GET /day/final-submit/preview` | Final Submit and its preview | TSO | F-API-009, F-API-039 | 3b |
| `POST /admin/data-void` | Audited void | TSO, ops_admin | F-API-048 | 4c |
| `POST /admin/entry-unlock` | Entry unlock grant | finance_admin, support | F-API-049 | 4c |
| `POST /outlets/outlet-kind/bulk` | Wholesale marking | TSO | F-API-045 | 6a |
| `POST /outlet-requests/:uuid/verify`, `reject`, `approve` | Outlet approval | AMO, TSO | F-API-055 | 3a |
| `POST /targets/upload`, `GET /targets/sample` | Target workbook | TSO | F-API-054 | 5c |
| `POST /day/cover`, `GET /day/exceptions`, `POST /support/ping` | Cover, exceptions, device ping | AMO, TSO, support | F-API-043, 044, 046 | 3a, 2e |
| `POST /day/submit-void`, `POST /day/mark-absent/bulk` | Submit void of one route-day (reason, confirmer); AMO bulk mark absent | TSO, support_l1 (C), support_l2, ops_admin; AMO | F-API-069, F-API-076 | 3b, 3a |
| `GET /sync/generation`, `POST /sync/digest` | Restore point of the server generation; count-and-bucket digest | device token | F-API-070, F-SYS-080 | 1b |
| `GET /support/search`, `POST /support/decode` | P19: search by user, phone, employee code, memo, outlet, batch; decode a support code | support_l1, support_l2, TSO, ops_admin | F-API-071 | 2e, 6c |
| `POST /calendar/emergency` | Emergency non-working-day declaration (kind emergency_off) | ops_admin; support_l2 proposes | F-API-072 | 2e |
| `POST /entries/paper-memo`, `POST /entries/paper-memo/{id}/approve` | Supervised paper-memo backfill | support_l2 enters; TSO, ops_admin approve | F-API-073 | 2e |
| `POST /outlet-requests/bulk-approve` | Approve all consistent location proposals within X m | AMO, TSO | F-API-074 | 3a |
| `POST /admin/config/emergency-widen`, `POST /admin/config/temporary-relief` | The two lanes of s7.6b | break-glass holder; two approvers | F-API-075 | 2d |
| `GET /config/check` with `If-None-Match` | The resume check: 304 or the delta inline (F-SYS-092, D-563) | device token | F-API-083 | 2d |
| `POST /admin/support/replay/dry-run`, `POST /admin/support/replay/apply`, `POST /admin/support/replay/{id}/approve` | Replay console P20 (D-587) | support_l2 enters, TSO and ops_admin approve | F-API-077 | 2e |
| `GET /admin/binds/held`, `POST /admin/binds/{id}/release` and `reject` | Held-binds queue (F-API-068 plus the queue read, D-586) | DMO, security_admin, delegates; L1 and L2 read | F-API-078 | 2e |
| `POST /admin/devices/{id}/replace` | Replace-device wizard steps (F-ADM-078) | TSO own zone, support_l2, ops_admin; L1 with TSO confirmation | F-API-086 | 2e |
| `POST /admin/devices/{id}/directive`, `GET /admin/devices/{id}/directives`; directive in any device response | Signed per-device directive (D-594) | support_l1, support_l2, TSO own zones, ops_admin | F-API-079 | 2e |
| `POST /admin/price/preview`, `POST /admin/price/publish`, `POST /admin/price/correct` | Price rails (D-589) | finance_admin; finance_approver | F-API-080 | 2e |
| `POST /admin/bulk/{batchUuid}/revert` | Revert a bulk batch (D-595) | master_data with checker; ops_admin | F-API-081 | 6a |
| `POST /admin/retention-holds`, `GET` and `DELETE /admin/retention-holds/{id}` | Retention holds (D-600) | security_admin with pii_officer | F-API-082 | 6c |
| `POST /support/tickets`, `PATCH /support/tickets/{id}`, `GET /support/tickets?status=` | Minimal ticket store (D-599) | support_l1, support_l2, TSO own zones | F-API-084 | 2e |

Error codes (the round-2 pass adds `ERR_CFG_INVARIANT`, `ERR_CFG_BUDGET_IMPACT`, `ERR_CFG_NO_EFFECT`, `ERR_DAY_SUBMIT_VOID_WINDOW`, `ERR_DAY_FINAL_SUBMITTED`): `ERR_CFG_BOUNDS`, `ERR_CFG_DEPENDENCY`, `ERR_CFG_FUTURE_DATED_ONLY`, `ERR_CFG_FREEZE`, `ERR_CFG_CANARY_REQUIRED`, `ERR_CFG_TWO_PERSON`, `ERR_CFG_COOLING`, `ERR_CFG_CHAIN`, `ERR_CFG_RATE_LIMIT`, `ERR_CFG_BREAK_GLASS_RESTRICTED`, `ERR_CFG_SCOPE`, `ERR_CFG_STALE_VERSION`. Pagination and `updated_since` apply to every list read (docs/09). Rate limits: `cfg.api.rl.user_per_min`.

Proved by: T-0-61, T-1-61, T-2-60, T-2-65, T-4-61.

## 11 Phase plan for admin

What lands in each sub-milestone (phase table of doc 14 s2). The keys column is the number of registry keys first consumed there (s3.6); the registry itself is seeded in full in 0b.

| Sub-milestone | Pages and tools | Mechanics | Keys | Gates | Confirmations due |
| --- | --- | --- | --- | --- | --- |
| 0a | none | CI drift check scaffold; `btree_gist` allow-list check (G-19-01) | 0 | T-0-63 | D-156 |
| 0b | CLI `cfg set --key --scope --value --reason` for engineers (itself audited) | Schema `cfg` (M-45), registry of all 649 keys (600 before the round-3 pass) with bounds, class, scope, direction, `cfg.resolve`, audit trigger and hash chain, compatibility view, permission tables with the D-91 bundles | 1 | T-0-60, T-0-61, T-0-62, T-0-64 | none |
| 0c | B7 and P11 OTP view (minimal); permission grants for named holders | `X-Config-Version` middleware, `GET /config/public`, `scope_version`, auth keys | 31 | T-0-70 | D-101, D-102, D-103 |
| 1a | none | Device resolver and `geo` block in the bundle (doc 17) | 32 | T-1-60 | none |
| 1b | none | Config delta pull and ack on the device; fixed wire headers | 40 | T-1-64 | D-67 |
| 1c | Minimal console: P1, P2 (global and territory, no map), P3 (radius, check-out time, `min_version`), P7 reach widget, P13 one tile; single approver | Scope-relevant header, delta, `config_ack`, `radius_m_used` stamped, as-of re-check (D-431) | 37 | T-1-61, T-1-62, T-1-63, T-1-65 | D-66, D-245 |
| 2a | P8 Promotions tab and simulator | Promotion, sale, memo, credit, stock, QC keys | 37 | T-2-41 (doc 20) | D-33 catalogue (entry) |
| 2b | none | Correction keys | 20 | T-2-21 (doc 17) | D-86 |
| 2c | none | Media and outlet keys | 15 | none | none |
| 2d | P2 full (map, bulk edit, geo_class, outlet override, what-if, density), P4, P5, P6 (revert), P16 list | Two-person, canary, delayed apply, two-sided watch, break-glass, freeze windows, FCM urgent path, class escalation | 95 | T-2-55, T-2-60 to T-2-68 | D-09, D-91, D-94, D-95, D-110 |
| 2e | P11 device list, support lookup and revoke; P12 publish; P14 quarantine; P9 minimal (users, routes, assignments, outlets) | Updater, stale-bundle, day-close, content and telemetry keys | 64 | T-2-56, T-2-58 | D-10, D-13, D-70, D-80 |
| 3a | B10 approval (approve, reject); supervisor targets; map | AMO keys | 46 | T-3-11 | D-08, D-37, D-43 |
| 3b | P15 day control; reopen and late-sync rules | TSO, leave and final-submit keys | 35 | T-3-60, T-3-61 | D-29, D-45, D-55, D-262 |
| 4a | P13 full | KPI, SLA, calendar-aware baselines, dashboard keys | 17 | T-4-60 | D-28 |
| 4b | none | Report and export keys | 20 | none | D-47, D-54 |
| 4c | B1, B2, B4, B10 full, menu matrix (`cfg.web.menu_by_role`), OTP panel final | Back-date window, unlock grants, audited void, PII budgets | 27 | T-4-61 to T-4-65 | D-40, D-97, D-108, D-114, D-182, D-207 |
| 4d | none | Pre-scale schedule; config storm in the load tests | 1 | T-2-55 repeated | none |
| 5a | P8 Astha and Diamond League, B3, B9 | Programme keys | 22 | T-5-60, T-5-62 | D-41, D-192 |
| 5b | P8 Superstar and free samples | | 5 | none | none |
| 5c | P10, B8 | Target keys, month-end readiness board data | 21 | T-5-61, T-5-63 | D-31, D-179 |
| 6a | P9 complete (58 entities), B5, B6, content management, bulk tools | Back-dating rules, retention of `sku_price` rows | 6 | T-6-62, T-6-63 | D-42 |
| 6b | Full console: P3 all keys, P1 readiness board, P6 compare, rollback, export, P16 unified viewer, risk-class raise, scheduled values | Registry editing at scale | 0 | T-6-60, T-6-61 | D-113 |
| 6c | P11 full, P12 staged rollout, P18 flag matrix, SR lifecycle wizard | Retention, flag and rollout keys | 25 | T-6-43 (doc 20), T-7-62 | D-23, D-132 |
| 7a | P17 import console, temporary-password batch | Importer placeholder-pin rule | 0 | T-7-80 | D-119, D-153 |
| 7b | P18 waves and rollback, bulk OTP pre-issue, per-wave overrides, calibration report on pilot fixes | Wave, parallel-run and pilot flags | 3 | T-7-60, T-7-62 | D-126, D-147, D-148, D-152 |
| 7c | Wave 1 radii from the calibration report; freeze windows live; rollback drill | | 0 | T-7-61 | D-05, D-93, D-127 |
| 7d, 7e | Release flags removed within two releases of 100 percent; parallel-run flags retired at decommission | | 0 | none | none |

Proved by: T-0-60 to T-7-62 as listed.

Round-3 additions by sub-milestone (D-554 to D-601). 2d: the resume check (F-SYS-092), stamp regress (F-SYS-091), the device-model scope, the brake lane, reach classes, 11 keys. 2e: the replace-device wizard, the held-binds queue, the replay console P20, the directive button and the minimal ticket store on P19, price rails, the day-one tools of D-590 in minimal form (F-ADM-005, 006, 036, 070, 071), working-day windows and break overrides, 24 keys. 3b: the unbind-with-pending-rows rule (D-585). 4a: the two rollback-trigger alerts. 6a: revert batch. 6c: retention holds. 7a: the three cutover time keys (D-556). 7b: the residual-alert key.

## 12 Gaps owned, decisions and gates minted here

### 12.1 The 36 master gaps owned by this document (doc 14 s7), each closed in the text

Severity and phase are the register's. "V" is the verification result for register rows (C confirmed, P partly confirmed; the corrected statement of the verification files is the one closed).

| Master (aliases) | Sev | Ph | Title in one line | Closed in | Decision | Gate | V |
| --- | --- | --- | --- | --- | --- | --- | --- |
| G-cfg-02 | blocker | 0b | No runtime config store; about 240 parameters hard-coded in prose | s2, s3 (649 keys; 600 before the round-3 pass, 565 before round 2) | D-87 | T-0-60 | |
| G-cfg-01 | blocker | 1c | One admin role; no permission model | s5.1 | D-91, D-435 | T-2-60 | |
| G-cfg-04 (G-scale-15) | blocker | 2d | No blast-radius or mis-set protection | s2.6, s7 | D-88, D-433 | T-0-61, T-2-60, T-2-65 | |
| G-man-086 | blocker | 4c | Web Final Submit "Delete Section Data" is a destructive delete beside idempotent sync. Corrected statement: the page and a per-route Delete exist, the delete's scope and effect are not shown; the blocker stays as a design risk | s8.4 | D-40, D-182, D-438 | T-4-62 | P |
| G-data-20 | major | 0b | No config tables; radius only per territory, no override, effective dating, audit or ack | s2.1 | D-87 | T-0-61, T-0-62 | |
| G-cfg-03 | major | 1c | Who may edit the radius, at what scope | s6.1, s6.6 | D-93, D-94 | T-3-60 | |
| G-cfg-05 | major | 1c | Mid-day changes and late syncs: which value applies to a row | s2.3, s2.4 | D-431 | T-1-63 | |
| G-cfg-06 | major | 2d | Offline devices cannot learn of a scheduled change | s4.1 step 6, s4.2 | D-89 | T-2-62 | |
| G-cfg-08 | major | 2d | Urgent changes have no faster path than the next request | s4.1 steps 7 and 8 | D-09, D-89 | T-2-55, T-2-64 | |
| G-cfg-13 | major | 2d | Outlet radius override can blow up bundle size | s6.1 (sparse map, cap 50 a zone) | D-442 | T-2-67 | |
| G-cfg-16 | major | 2d | Operational switches with no expiry | s3.2.5, s9.2 | D-445 | T-2-63 | |
| G-cfg-23 (G-field-11) | major | 2d | Geofence density and calibration: placeholder pins, 55 m cells, no fixes before the pilot | s6.2 to s6.5 | D-93, D-254, D-441 | T-2-67, T-7-84 | |
| G-fraud-09 (G-fraud-10) | major | 2d | Scope owners can loosen controls unobserved; the watch is one-sided; break-glass can loosen; fraud thresholds editable by the policed function | s7.2, s7.5, s7.6, s7.9, s3.2.7 | D-94, D-267, D-434, D-436 | T-2-66, T-6-10 | |
| G-sre-07 | major | 2d | FCM push fan-out becomes a delta storm | s4.1, s4.4 | D-09 | T-2-55 | |
| G-sre-22 | major | 2d | No change-freeze windows or bulk-operation limits | s7.10, s8.1 S9 | D-100, D-440 | T-2-68, T-7-61 | |
| G-cfg-22 | major | 2e | Wave membership and flags have no data model | s9.3, s9.4 | D-447 | T-7-60, T-7-62 | |
| G-man-085 (G-feat-30) | major | 4c | Web Entry route-day aggregate entry has no model. Corrected: Successful Call is one route-level number, not per sub-channel; Sale = Issue minus Return is inferred | s8.3 | D-437 | T-4-61 | P |
| G-man-087 | major | 4c | Back-date cut-off for web entry with a "call support" override | s8.4 | D-97, D-439 | T-4-63 | C |
| G-man-089 (G-feat-32) | major | 4c | Web QC market and warehouse entry, two reports, 10-reason taxonomy | s8.5 | D-34, D-183 | T-4-64 | C |
| G-man-099 | major | 4c | Role by menu by action matrix; the TSO portal is wider than 37 pages. Corrected: 15 items and 41 pages are right; "different roles" and "the build changed" are not evidenced (the sidebar is clipped) | s5.3 | D-185, D-190, D-448 | T-4-65 | P |
| G-man-046 | major | 5a | Astha Web Entry outlet by SKU grid | s8.5 | D-41 | T-5-62 | C |
| G-man-090 | major | 5c | Target entry: grid, Excel sample, upload, stored file | s8.6 | D-31, D-189 | T-5-63 | |
| G-cfg-11 | major | 6a | Business-editable content has no versioning path | s2.7, s8.7 | D-89 | T-2-69 | |
| G-feat-12 | major | 6a | Survey, AV/KV and campaign content management absent | s8.7 | D-89 | T-2-69 | |
| G-fraud-11 (G-fraud-21) | major | 6a | Retroactive master-data changes rewrite KPI history | s8.2 | D-98 | T-6-62 | |
| G-man-036 | major | 6a | Retailer Wholesale Outlet bulk marking is only a page name | s8.5 | D-42 | T-6-63 | |
| G-cfg-07 | major | 6b | No delivery visibility of which devices run which config | s4.3, P7 | D-446 | T-1-65, T-2-61 | |
| G-feat-63 | major | 6c | SR onboarding and offboarding runbook features | s8.7 lifecycle wizard | D-91 | T-6-62 | |
| G-cfg-09 | minor | 0b | Risk classification of keys is not in the spec | s3 (Cls column), s7.1 | D-88 | T-0-60 | |
| G-cfg-21 | minor | 0b | JSON Schema validation inside Postgres may be unavailable | s2.6 | D-87 | T-0-61 | |
| G-fraud-24 | minor | 4c | Data Entry and support replays lack four-eyes and share signals | s8.3 (FS-28 rule) | D-109 | T-4-61 | |
| G-field-17 | minor | 5c | Month-end readiness: targets for M+1, programme periods, route-day rows | s8.8 | D-449 | T-5-63 | |
| G-feat-47 | minor | 6a | Tutorial and manual content management | s8.7 | D-89 | T-2-69 | |
| G-man-088 | minor | 6a | Sales Plan is TSO-operated with zone contact fields and a Data Entry Date | s8.5 | D-190 | T-6-63 | |
| G-cfg-14 | minor | 6b | Price-list and promo-set publication has no reach visibility | s2.7 | D-446 | T-2-61 | |
| G-cfg-19 | minor | 6b | Dead and unused keys accumulate | s2.1 (`deprecated_at`), T-6-60 | D-87 | T-0-63, T-6-60 | |

### 12.2 Gaps owned elsewhere that this document touches

| Gap | Owner | What this document supplies |
| --- | --- | --- |
| G-fraud-02 (G-sec-12, G-cfg-20) clock trust for time-shaped config | 17 | Suspect-clock fallback by restrictive direction (s4.2, s7.5); `cfg.day.month_close_grace_days` |
| G-sec-11 (G-cfg-15, G-feat-50) unified audit | 21 | Hash-chained config audit and the P16 viewer (s7.8, s5.4) |
| G-qa-09 (G-scale-17, G-cfg-17) staging windows and prod-to-staging config | 20 | One-way staging mirror (s8.8) |
| G-cfg-10 reason codes are enums | 16 | Reason codes as content-type keys (s2.7, s3) |
| G-cfg-12 scope change does not reach a token | 21 | `scope_version` and `cfg.auth.scope_token_version_check` (s2.7) |
| G-cfg-18 defaults that are business decisions | 14 | Each is a registry row marked `unknown; confirm` with its proceed-with default (s3, Open items) |
| G-man-017, 018, 021, 022, 029, 039, 062, 066, 067, 100 | 15, 16, 17, 21 | Their config keys and, for 017, 018 and 021, the console behaviour (s3.2.1, s6.5, s8.6) |

### 12.3 Decisions minted (D-430 to D-449; the doc 14 author copies them into DECISIONS.md)

| ID | Decision | Status | Why and source | Docs |
| --- | --- | --- | --- | --- |
| D-430 | `user` and `device` scope levels exist only for `cfg.flag.*` and `cfg.app.default_locale`, above outlet; `geo_class` rows may name a parent geography (global, wing, division, territory, house); a zone value beats every geo_class row | DEFAULT | D-87 lists the chain without them; per-user pilots and "Hill outlets in one wing" need them | 16, 17 |
| D-431 | The server re-check resolves the value the device knew (its stamped `config_version`) when the stamped value equals what that version contained and the capture is within `cfg.sys.config_accept_window_h` (48) of the FIRST CHANGE AFTER that version (D-519: the time the device could have known, not the age of its own version, which made every capture of a long-offline honest phone stale); otherwise it uses the as-of value and flags `config_stale` or `config_value_mismatch` | DEFAULT; MUST-CONFIRM (by 2d): the business accepts judging an honest offline phone by the value it held | Refines D-87; closes G-cfg-05 | 16, 17, 21 |
| D-432 | Every key with a direction carries `restrictive_dir`; it drives the suspect-clock fallback, what break-glass may apply and the alert direction; keys without a direction keep the last known value and are restore-only | DEFAULT | Doc 17 s6.7 delegates the direction per key to doc 19 | 17, 21 |
| D-433 | Dynamic class escalation (s7.2) and the canary-required key list (s7.3) | DEFAULT | D-88, D-93, D-94 | 20, 21 |
| D-434 | Two-sided anomaly watch: baseline, window, minimum sample, widening, thresholds and routing of s7.9 | DEFAULT | G-fraud-09 | 18, 20, 21 |
| D-435 | Permission model: atomic permissions, bundles, grants as C3 requests, approver outside the requester's reporting chain for geo, fraud, auth, PII and day keys, approver cooling 24 h | DEFAULT; MUST-CONFIRM (by 2d): approver roster and break-glass holder (Q23) | D-91; G-fraud-08 | 21 |
| D-436 | Break-glass: `restore_or_restrict_only`, one named holder, exempt from two-person, canary, freeze and the hourly limit, refused for loosening and grants, reviewed within 24 h, switches expire within 4 h | DEFAULT | D-100, D-124 | 21, 18 |
| D-437 | Web Entry derivations: Sale = Issue minus Return, Return at most Issue, class quantities sum to Sale, Successful Call at most the Target Outlet snapshot, replace-with-audit on re-save, explicit Save, app rows win on overlap | ASSUMPTION; MUST-CONFIRM (by 4c) with a TSO (MQ-46) | G-man-085 | 15, 16 |
| D-438 | "Delete Section Data" is an audited void with a confirm, a reason of at least 10 characters, default scope web-entry rows, app memos only for `ops_admin` with two-person approval, only before Final Submit | DEFAULT (DELIBERATE CHANGE: the manual shows no confirm); MUST-CONFIRM (by 4c) | G-man-086; D-182 | 15, 16 |
| D-439 | Web entry cut-off: rolling `today - cfg.web.entry_backdate_days` by default, or the zone's Data Entry Date plus one day when `cfg.web.entry_cutoff_source` says so; audited unlock grants of at most 7 days and 24 h, over 7 days or a closed month needing `finance_approver` | DEFAULT; MUST-CONFIRM (by 4c): MQ-44 | G-man-087; D-97 | 15, 16 |
| D-440 | Bulk operations: one `batchUuid`, preview of rows and devices, at most `cfg.sys.bulk_op_max_rows` (5,000), not inside a freeze window | DEFAULT | G-sre-22; G-man-036 | 16, 18 |
| D-441 | Radius calibration method: report per geo_class by territory after 14 days and 500 visits, the smallest ladder value reaching 95 percent of honest visits, informational only; first-wave radii come from it | DEFAULT; MUST-CONFIRM (by 7c): radius values (Q7) | D-93; G-field-11 | 14, 20, 21 |
| D-442 | Outlet-override governance: at most 50 a zone, at most 3.0 times the resolved zone value without an approver, optional expiry, review list after 90 days | DEFAULT | G-cfg-13 | 17, 18 |
| D-443 | Release publication: signature, SHA-256 and size gate, reproducible-rebuild match, human publish, staged rollout by device hash, `min_version` only with the live lock-out count, no downgrade | DEFAULT | D-79, D-122 | 17, 20, 21 |
| D-444 | The staging mirror is one-way (production to staging); secrets, PII and `cfg.sec.*` values are never exported | DEFAULT | G-cfg-17 | 18, 20 |
| D-445 | Every O key has a mandatory duration and auto-expires; version hold defaults to 2 h and is capped at 24 h; `cfg.ops.kill_switch_max_h` is 4 | DEFAULT | D-130 | 18 |
| D-446 | `X-Config-Version` is the highest version relevant to the caller's scope chain; keys flagged `requires_ack` produce a `config_ack` outbox row; the reach view combines header observation and acks | DEFAULT | Doc 17 s6.7; G-sre-07 | 17, 18 |
| D-447 | Flags are `cfg.flag.<name>`; the kind (release, ops, wave) is registry metadata; release flags are removed within two releases of 100 percent | DEFAULT | D-99 against D-144, D-148, D-152 | 14, 17, 20 |
| D-448 | The menu hides and the server enforces; `cfg.web.menu_by_role` is C3 and seeded with the union of the TSO sidebar and the spec pages, other roles' extra pages granted to DMO, WM, WMO, top and admin only | DEFAULT; MUST-CONFIRM (by 4a): other roles' menus (MQ-48) | D-185; G-man-099 | 15, 21 |
| D-449 | Month-end readiness board on P1 from `cfg.sla.targets_missing_alert_day` | DEFAULT | G-field-17 | 14, 15 |

### 12.3b Round-2 decisions applied by this document (D-500 to D-553, the text is in DECISIONS.md section M3)

D-507 (auto-freeze, remote brakes), D-512 (geo_class shadowing and effect preview), D-518 (absolute refresh jitter keys), D-519 (D-431 amended), D-525 (registry reconciliation), D-526 (config reach), D-527 (emergency widen lane, temporary relief, two break-glass holders), D-539 (submit void keys), D-540 (authority matrix), D-541 (P19), D-542 (emergency non-working day), D-543 (paper backfill), D-544 (one default date), D-545 (location request keys), D-546 (budget-linked ceilings), D-547 (invariants), D-551 (user_admin and wizard), D-552 (alert and pre-scale keys).

### 12.4 Gaps minted here (block G-19-01 to G-19-40)

| ID | Sev | Gap | Owner | Ph |
| --- | --- | --- | --- | --- |
| G-19-01 | minor | `btree_gist` may not be on the Azure Database for PostgreSQL Flexible Server allow-list; the exclusion constraint falls back to a serialisable unique-open-row check | 16 | 0a |
| G-19-02 | minor | The registry holds 649 keys (600 before the round-3 pass, 565 before round 2) and 101 with a C3 tier (90, then 78 before); D-88 and the skeleton counted 245 and 34 from the lens alone | 14 | 0b |
| G-19-03 | minor | Doc 17 s6.7 lists the delta shape without `bounds`, `dir`, `effective_to` and the scheduled list | 17 | 1b |
| G-19-04 | major | The what-if and density tools need `server_distance_m`, `device_distance_m`, accuracy and `location_confirmed` on the visit fact and `neighbors_within_<r>` on the outlet dimension; none is in a table plan yet | 16 | 2d |
| G-19-05 | minor | Values of the lens-security fraud thresholds (for example `credit_share_x`, `edits_per_month`) are not in this registry | 21 | 2d |
| G-19-06 | major | Web Entry derivations are inferred, not shown (Sale = Issue minus Return; "Brand Data"; how often the page is used) | 15 | 4c |
| G-19-07 | minor | The meanings of the Sales Plan "Data Entry Date" and "PDA" column and of the "exist" status are unknown | 15 | 4c |
| G-19-08 | minor | A pilot of 10 to 20 routes is below the anomaly watch sample; the watch widens to a parent scope | 18 | 2d |
| G-19-09 | minor | "WMO" is a designation in the manuals and not in the role enum | 21 | 4c |
| G-19-10 | minor | New admin endpoints and console sub-tools need F-API and F-ADM ids | 15 | 1c |

### 12.4b Round-2 gaps closed in this document

| Gap | Where | Gate |
| --- | --- | --- |
| G-qa-35 | s2.2 rule 7, s6.2, s7.4 | T-2-65 |
| G-qa-43 | s2.3 | T-1-63 |
| G-qa-50 | s3.3, s3.4, s3.2 rows | T-6-60 |
| G-qa-51 | s4.3 | T-2-61, T-6-01, T-6-41 |
| G-qa-52, G-qa-77 | s7.6, s7.6b, s7.10 | T-2-68, T-6-10, T-2-160 |
| G-qa-71 | s5.1, s5.1b | T-2-158, T-6-42, T-7-85 |
| G-qa-72 | s5.2, s5.4 (P19) | T-2-157 |
| G-qa-73 | s5.2, s5.4 (P15), key `cfg.calendar.emergency_*` | T-4-154 |
| G-qa-75 | key `cfg.ops.dashboard_default_date_rule` retired | T-4-41 |
| G-qa-76 | keys `cfg.outlet.location_request_*`, `cfg.sla.location_request_*` | T-4-152 |
| G-qa-78 | s2.6 budget-linked keys | T-2-156 |
| G-qa-79 | s2.6 invariants | T-0-61 (extended), T-2-69 |
| G-qa-83 | s5.1 (`user_admin`), s8.7 | T-2-159 |
| G-qa-84 | keys `cfg.sla.login_pct_alert_time`, `cfg.ops.prescale_schedule` | T-7-55 |

### 12.4c Round-3 gaps closed in this document (skeptic review, D-554 to D-601)

| Gap | Where | Gate |
| --- | --- | --- |
| G-qa-96, G-qa-106 (R6 reach criterion stated three ways; push off and undefined "online") | s4.1b, s4.3 | T-2-165, T-2-174, T-4-161 |
| G-qa-107 (device-supplied `config_version` stamp) | s2.3b | T-2-162 |
| G-qa-113 (route-day void barrier) | s8.4 | T-4-166 |
| G-qa-123 (stale, backdate and unlock windows in calendar days) | s3.2.4, s3.2.7, s3.2.11 | T-2-163 |
| G-qa-125 (takeover hold, replace a phone) | s5.1b, s5.2, s5.4b | T-2-167 |
| G-qa-126 (replay tool) | s5.2 (P20), s5.4b, s10 | T-2-168 |
| G-qa-127 (brakes unusable in the freeze) | s7.5, s7.6, s7.6c, s7.10 | T-2-173, T-6-10 (extended) |
| G-qa-128 (price rails) | s8.2b | T-2-166 |
| G-qa-129 (day-one tools land late) | s5.2 (P9, B5), s11 | T-2-172 |
| G-qa-130 (dependency rules incomplete) | s2.6b | T-0-157 |
| G-qa-132 (L1 cannot reset a password) | s5.1b | T-2-158 (extended) |
| G-qa-133 (no server-to-device channel) | s4.1b, s5.4b | T-2-169 |
| G-qa-134 (no revert for a bulk batch) | s8.8, s5.4b | T-6-150 |
| G-qa-136 (safe-range holes) | s3.2.6 to s3.2.9 rows | T-0-61 (extended) |
| G-qa-137 (no device-model scope) | s2.2 rule 8 | T-2-156 (extended) |
| G-qa-138 (ticket store) | s5.4b | T-2-170 |
| G-qa-139 (retention hold) | s5.4b | T-6-152 |

### 12.5 Gate register for this document

Gates are named by family and sub-milestone; doc 20 s3 places each id and assigns the verifier (never the author). IDs T-x-60 to T-x-69 are the config range.

| Gate | Ph | Test | Pass criterion |
| --- | --- | --- | --- |
| T-0-60 | 0b | Registry completeness | All 649 keys exist in `config_item` with default, bounds, class, scope levels, editor domain, Dl, direction and phase; the seed equals the s3 tables |
| T-0-61 | 0b | Bounds and references at the database | `radius_m` 10 and 5,000 are rejected even bypassing the API; fraud thresholds below their floors are rejected; a `min_version` that is not a published release is rejected; dynamic bounds follow `radius_min_m` and `radius_max_m` |
| T-0-62 | 0b | Exclusion and future-dated trigger | Two open rows for one key, scope and parent cannot coexist; close-and-insert in one transaction succeeds; `rounding_mode` effective before the next Dhaka midnight is refused |
| T-0-63 | 0a | Code and registry drift | CI finds no `cfg.` literal in `/api` or `/app` that is not in the seed; no retired alias of s3.3 appears; a registered key nobody reads is reported weekly |
| T-0-64 | 0b | Audit immutability and chain | UPDATE and DELETE are denied to the API role; every write path produces exactly one audit row; the hash chain verifies; a tampered row is detected |
| T-1-60 | 1a | Bundle carries config | The pilot SR's bundle has `config.version`, resolved values with provenance, the `geo` block and the scheduled list |
| T-1-61 | 1c | Change reaches the device | An admin sets the radius for territory T to 150; within one sync the next visit stores `radius_m_used` 150 and the new version; a visit captured before keeps 100 |
| T-1-62 | 1c | Resolver performance | All keys for an 18-pair chain below 2 ms p95, one key below 0.3 ms; L0 hit rate above 99 percent in the morning-storm load test |
| T-1-63 | 1c | Device-known re-check | (D-519) Fixtures: a visit back-dated before a radius change is judged with the old value, after it with the new one; a stale-offline phone is judged by the value it held when the change it missed is at most 48 h old and flagged `config_stale` beyond; a visit stamped with a 30-day-old version whose value changed 2 hours earlier is accepted under the old value and one whose value changed 5 days earlier is judged under the new value, for a radius TIGHTENED and a radius LOOSENED; two changes after the stamped version start the clock at the first; a lying stamp is flagged `config_value_mismatch` |
| T-1-64 | 1b | Header and relevance | Every response carries `X-Config-Version`; a change to one zone changes the header only for that zone's devices; `GET /config/public` works unauthenticated and is cached |
| T-1-65 | 1c | R6 demonstration | The sponsor changes the radius, the check-out time and `min_version` in the minimal console; each needs a reason, is bounded (a radius of 10 is refused), is audited, reaches the pilot phone, and the reach widget shows 1 of 1 applied |
| T-2-60 | 2d | Two-person, cooling, chain | A requester cannot approve their own C3 request (constraint and UI); a second approver can; a grant under 24 h old cannot approve; an approver in the requester's chain cannot approve a geo key; audit shows both |
| T-2-61 | 2d | Reach SLO | (D-526) After a global C1 change at least 95 percent of ALL active devices of the scope apply it within 15 minutes of coming online and the tail metric shows the share reached within 24 h and the unreached count by reason; an urgent revert with push on reaches 95 percent of push-enabled devices within 5 minutes; every device that pulls the next morning's bundle is on the new version |
| T-2-62 | 2d | Offline scheduled apply | A phone in airplane mode holding a scheduled check-out change enables check-out at the new time with no network; a skewed clock applies the restrictive value and flags the day |
| T-2-63 | 2d | Kill-switch semantics | `block_login` at zone scope refuses a new-day login with the banner; a batch of pending rows is still accepted; local data is intact; the switch auto-expires |
| T-2-64 | 2d | Revert | One click produces a new version, pushes it, the device resolves the previous value, nothing is deleted, both versions are in history |
| T-2-65 | 2d | Blast-radius accuracy | Preview counts equal `devices_targeted` computed at commit, with zero difference, for global, wing, territory and zone scopes, AND `outlets_changed` and `outlets_shadowed_by_geo_class` equal a brute-force resolve of every outlet before and after (a territory edit under geo_class rows shows its shadowed outlets; an edit that changes none is refused, D-512); the pre-built bundle plus delta path serves an edit made after pre-generation |
| T-2-66 | 2d | Two-sided anomaly watch | A synthetic rise of 15 points in geo-valid % after a loosening, and a drop of 15 points after a tightening, each raise an alert naming the version within 15 minutes; a window below the sample is suppressed; a small scope widens |
| T-2-67 | 2d | Precedence and cap | An outlet at 25 m inside a zone at 150 m inside a territory at 100 m resolves 25, 150 and 100 for the outlet, its neighbours and elsewhere; `(geo_class Hill, parent wing)` beats a territory value and loses to a zone value; the 51st override in a zone is refused |
| T-2-68 | 2d | Canary, freeze, break-glass | A global C3 radius request without a canary is refused unless break-glass; an approved C3 request inside 07:00 to 09:30 waits for 09:30; break-glass may restore or restrict and is refused when loosening, but the emergency-widen lane (s7.6b) widens one territory to at most 250 m inside the freeze, expires after 6 h and needs one approver afterwards, and the temporary-relief path needs two approvers before it applies |
| T-2-69 | 2d | Content versioning | Editing a reason code bumps `config_version`; the device shows the new label after its next request; historical memos keep the old code |
| T-3-60 | 3b | Bounded permission | A TSO in propose mode creates a proposal an F editor adopts; in apply mode edits a zone of the own territory within bounds, not a neighbour's, not beyond bounds; audit records the scope |
| T-3-61 | 3b | Reopen rules | A role outside `reopen_roles` cannot reopen; a reopen beyond `reopen_window_days` needs two persons; late rows after final land per `late_sync_after_final_policy`; a submit void by a TSO, by L1 with TSO confirmation inside `cfg.day.submit_undo_window_min`, and by ops_admin before Final Submit returns the route-day to in-field and unlocks the phone, is refused for L1 without confirmation and for a final-submitted zone until it is reopened (D-539) |
| T-4-60 | 4a | KPI keys | Changing `cfg.kpi.bands` re-colours the dashboard without a release; `submit_pct_denominator` switches the labelled KPI and both KPIs stay available by their own names |
| T-4-61 | 4c | Web Entry rules | Return above Issue, class sum not equal to Sale and calls above the target snapshot are refused; re-save replaces with an audit row; an entry on a route-day that has app memos is blocked; support entry above 20 memos needs TSO confirmation |
| T-4-62 | 4c | Final Submit and void | The web and the app share one rule (first wins, same uuid replays); Delete is a void with a reason and a confirm; a voided uuid uploaded later is rejected `voided_by_admin` and never resurrects; app memos need ops_admin and two persons; Delete is refused after Final Submit |
| T-4-63 | 4c | Back-date window and unlock | An entry before the earliest date is refused with the banner; an active grant allows it once within its range and expiry; a grant over 7 days needs `finance_approver`; every use is logged |
| T-4-64 | 4c | Web QC | Market and warehouse entries land in one fact with a source column and are not double counted with app QC; re-entry follows the policy |
| T-4-65 | 4c | Menu enforcement | A hidden menu is never the control: calling an action the role lacks returns `ERR_CFG_SCOPE` or 403 whatever the menu says; a change to `cfg.web.menu_by_role` is C3 |
| T-5-60 | 5a | Rule effective dating | A promotion rule effective tomorrow is in today's bundle under scheduled, applies to tomorrow's memos offline and never to today's; a late sync of today's memo uses today's rule |
| T-5-61 | 5a | Loyalty rate safety | Changing `cash_rate_mtk_per_point` is C3; redemptions store the rate used; a revert does not alter past redemptions |
| T-5-62 | 5a | Astha Web Entry and gift choice | Outlet by SKU entry carries a client uuid and does not double with app memos; the gift choice locks when the SR's photo exists |
| T-5-63 | 5c | Target upload and approval | An upload with one bad row is rejected whole with a per-row error sheet; a valid upload creates a `target_set` pending WMO approval; after the month starts a change goes to the revision workflow |
| T-6-60 | 6b | Dead keys | A registry key with no code reader is listed; a deprecated key is hidden by default and shows `replaced_by`; a REGISTRY-VERSUS-SQL lint fails on any enum value that exists in only one document (the till-date bases of doc 16 s9.5 against `cfg.kpi.tilldate_basis`, the `entry_source` values, the reason lists) and on two keys that name one concept (D-525) |
| T-6-61 | 6b | Rollback-to-version | Rolling back to version N reproduces the resolved snapshot of N for 10 random chains exactly |
| T-6-62 | 6a | Future and back-dating | A back-dated price by `master_data` is refused; by `finance_admin` with `finance_approver` accepted with a restated-memo count; a target edit after month start routes to revision; a retroactive holiday is C3 |
| T-6-63 | 6a | Sales Plan and wholesale | A Sales Plan zone saves atomically; "apply to territory" previews devices; the wholesale batch is idempotent by `batchUuid` and writes one audit row per outlet |
| T-2-156 | 2e | R4 at config extremes (D-546) | The perf protocol of doc 17 s8.8 is run with each budget-linked key of s2.6 at its CEILING (photo 200 KB, long edge 1,280, fix timeout 30 s, refresh_max 5, fix accuracy high, image cache 70 MB, content item 20 MB, delta interval 30 min) and the result is the budget-impact line shown in the preview; a ceiling that breaks a gate fails the registry (T-0-60) |
| T-2-157 | 2e | Support desk decode and search (D-541) | A support code generated by the app decodes to the right error, build, config version and device CRC; search by username, phone, employee code, memo number, outlet code and batch uuid finds the device and the last 50 requests in under 2 minutes for an L1 persona; every search is audited and PII is masked |
| T-2-158 | 2e | Persona authority (D-540) | Every script of doc 20 s7.8 is run with the persona that owns it in doc 18 s7.6; an action the persona lacks in s5.1b fails the gate; a TSO confirmation turns an L1 proposal into an applied OTP, submit void or assignment |
| T-2-159 | 2e | User wizard and maker-checker (D-551) | A TSO creates a user in own territory and cannot apply without a DMO or `master_data` checker; the checker is never the maker; a disabled user's phone uploads rows captured before the disable as `parked_user_disabled`; a bulk "mark absent" of 60 routes works and 61 is refused |
| T-2-160 | 2d | Emergency lanes (D-527) | See T-2-68: lane widen inside the freeze, auto-expiry, 300 m refused in the lane, two-holder hand-over audited |
| T-4-154 | 4a | Emergency non-working day (D-542) | A declaration at zone scope at 08:00 sets `planned` false for the zone's route-days that are not logged in, keeps logged-in routes counting, suppresses SH-01 and Sev1 for the scope, reaches phones by delta, is audited, and a retroactive one needs a second approver |
| T-7-60 | 7b | Wave rollback by flag | `new_app_login_enabled` false for wave 2 blocks new-day login for wave 2 devices only, pushes within 5 minutes to online devices, and all pending rows still upload |
| T-7-61 | 7c | Change-rate limit | The sixth applied C3 version within an hour is refused with a clear message; break-glass is exempt and reviewed |
| T-7-62 | 7b | Flag matrix and version hold | The matrix resolves a flag per wave with provenance; a version-scoped hold from the console stops one build at the edge and auto-expires |

Round-3 gates owned by this document (placed and verified in doc 20 s3.15):

| Gate | Ph | Test | Pass criterion |
| --- | --- | --- | --- |
| T-0-157 | 0b | Dependency rules at the database (D-591) | Each of the 14 rules of s2.6b rejects a violating write bypassing the API, including `ingest_registry_days` 30 with `max_backdate_days` 30 and `config_accept_window_h` 24 with `stale_max_days` 3 |
| T-2-162 | 2d | Stamp regress (D-571) | A stamp older than a version served 3 hours before capture is `config_stamp_regress` and judged as-of; the same stamp on a never-served phone keeps the D-431 tolerance; a no-grace tighten judges an honest offline phone strictly and the preview counts it |
| T-2-165 | 2d | Radius widened while the SR stands out of range (D-563) | An admin widens the radius at 10:00; the SR out of range at 10:02 taps Refresh GPS; the resume check runs, the new value applies and the visit is geo-valid with the new `radius_m_used`; offline the visit stays out of range |
| T-2-173 | 2d | Brake lane inside the freeze (D-588) | At 08:00 a build is added to `blocked_versions` and the promo engine is switched off by a holder; both apply at once and are reviewed; a loosening is still refused |
| T-2-174 | 2d | Reach classes and the silent phone (D-563) | S9 with push on and with push off measures the selling cohort at 15 minutes, the urgent push-on cohort at 5, the idle cohort at the next foreground; a phone silent for 6 hours is listed with `silent since`, judged by D-431 and D-571 on its rows and refused at its first contact if the build is blocked |

Gates cited from other documents' ranges: T-2-55, T-2-56, T-2-58 (SRE), T-6-10 (fraud), T-3-11, T-0-70 and T-4-70 to T-4-76 (security), T-7-84 (pilot).

## Open items

Where this document disagrees with the skeleton, it still follows the skeleton and records the disagreement here. Every MUST-CONFIRM decision that this document applies is listed with its proceed-with default (rule 3).

| ID | Item | Why open | Owner role | Needed by | What proceeds meanwhile |
| --- | --- | --- | --- | --- | --- |
| OI-19-01 | Page phases in doc 15 (its "first usable version" column, OI-15-15) differ from s5.2 and s11 | Doc 15 was written in parallel | doc 15 author | 1c | s5.2 and s11 are authoritative Resolved at the editorial merge: doc 15 now carries the s11 phases for F-ADM-043, 045, 046 and 053. |
| OI-19-02 | Registry counts: 565 keys and 78 with a C3 tier, against 245 and 34 in the skeleton and D-88 (lens count) | Manuals and critics added keys | doc 14 author | 0b | D-88 stays as the rule; counts update at merge (G-19-02) Resolved at the editorial merge: the counts are now 565 keys and 78 with a C3 tier after the 25 keys requested by docs 16, 17, 20 and 21 were added; D-88 in DECISIONS.md carries the amendment. |
| OI-19-03 | Proposed alias retirements of s3.3 B, and doc 15 and doc 17 spellings (`cfg.sync.min_interval_min`, `cfg.outlet.close_with_dues_policy`, `cfg.sync.batch_max_kb`) | The skeleton retired only 16 aliases | doc 14 author | 0b | The registry uses the right-hand names Confirmed at the editorial merge (DECISIONS.md change log, D-92); docs 15 and 16 were changed to the right-hand names. |
| OI-19-04 | D-94 reads "any increase above 150 is C3"; the fraud critic wrote "by more than 50 m or 1.5 times" | Two formulations | doc 21 and doc 14 | 2d | The skeleton reading: an increase that ends above 150 m; a ratio key can be added if wanted |
| OI-19-05 | The fraud critic asks that `amo_only` and `auto` be removed from `cfg.geo.outlet_location_change_approval`; they are kept as C3 values | Business may want `amo_only` for placeholder pins | doc 21 | 2d | Default `amo_then_web`; the two values stay C3 |
| OI-19-06 | The plan's naming table writes flags as `cfg.flag.<kind>.<name>`; D-144, D-148, D-152 and docs 14, 17, 18 write `cfg.flag.<name>` | Skeleton is internally inconsistent | doc 14 author | 0b | `cfg.flag.<name>` with the kind as metadata (D-447) |
| OI-19-07 | The fraud critic's `adjust_daily_total_mtk` of "200,000" carries an `_mtk` suffix; 200,000 milli-taka is 200 Tk | Unit slip | doc 21 | 2d | 200,000 Tk = 200,000,000 mtk |
| OI-19-08 | Doc 17 s6.7 lists the delta shape without `bounds`, `dir`, `effective_to` and the `scheduled` list (G-19-03) | Written in parallel | doc 17 author | 1b | s4.1 shape |
| OI-19-09 | Doc 16 must carry `server_distance_m`, `device_distance_m`, accuracy and `location_confirmed` on the visit fact, `neighbors_within_<r>` on the outlet dimension, `cfg.config_version_scope`, `app.role_ref` and `app.rollout_wave_member` (G-19-04) | Contracts from this document | doc 16 author | 2d | The what-if and density views wait for the columns; the registry and the console do not Resolved at the editorial merge: doc 16 s4.6 maps `neighbors_within_<r>` to `dw.agg_outlet_density`, s8.4 adds `device_distance_m`, `gps_accuracy_m` and `location_confirmed` to `fact_visit`, s3.9 lists `cfg.config_version_scope` and `app.role_ref`, and `app.rollout_wave(_member)` is the name used everywhere. |
| OI-19-10 | Doc 15 must mint F-API and F-ADM ids for the admin endpoints marked "needs F-id" and the console sub-tools (readiness board, SR lifecycle wizard) (G-19-10) | The skeleton reserved F-API-047 to 079 for doc 15 | doc 15 author | 1c | Rows are cited by path Resolved at the editorial merge: F-API-058 to F-API-065 minted in doc 15 s8 and cited in s10; the console sub-tools (readiness board, SR lifecycle wizard) remain pages of F-ADM-038 and F-ADM-046. |
| OI-19-11 | Values of the lens-security fraud thresholds beyond the 16 listed (G-19-05) | Doc 21 owns the values | doc 21 | 2d | The 16 keys exist; the family prefix and rules are fixed |
| OI-19-12 | Approver roster, break-glass holder and who holds each domain (Q23); D-91, D-435 | Only AKTCL can name people | sponsor | 2d | Two named approvers per domain proposed from operations and security; the pilot cannot start field-affecting edits without them |
| OI-19-13 | D-431 tolerance: the business accepts judging an honest offline phone by the value it held for up to 48 h | A rule choice | sales ops, security | 2d | 48 h (`cfg.sys.config_accept_window_h`); 0 disables it |
| OI-19-14 | Radius values per territory and geo class (Q7; D-93, D-441) | No fixes exist before the pilot | field operations | 7c | 100 m global; calibration report after 14 days of pilot fixes |
| OI-19-15 | TSO radius authority (Q24; D-94) | Business decision | field operations | 2d | `propose` |
| OI-19-16 | No-location policy and placeholder-pin handling (Q25; D-95, D-253) | Business decision | field operations | 2d | `force_sale_required`; first force-sale fix sets a provisional location |
| OI-19-17 | FCM acceptable in the field app (Q22, Q28; D-09); radio-environment privacy (D-110) | Platform and legal | engineering, legal | 2d | `cfg.ops.push_enabled` false; `cfg.geo.radio_env_enabled` false until sign-off |
| OI-19-18 | Web back-office behaviours: what "exist" and Delete remove (MQ-43), the cut-off mechanism (MQ-44), Web Entry derivations and use (MQ-46, D-437), Delete confirm (D-438), unlock rules (D-439), PII baseline of the TSO export (D-207, D-108), Entra and TOTP (D-114), other roles' menus (MQ-48, D-185, D-448), the "Brand Data" button and the "Data Entry Date" (G-19-06, G-19-07) | Manuals show screens, not rules | TSO panel, sales ops, security | 4a to 4c | Defaults of s8; the menu grants unknown pages to DMO, WM, WMO, top and admin only |
| OI-19-19 | Targets and programmes: approver chain and live target while a set is pending (D-31, D-179, Q12), programme catalogue and scope (D-41, D-192, Q13), wholesale effects and price type (D-42, D-32, Q46, Q17) | Business decisions | programme owner | 5a to 6a | One level (WMO); programmes off by flag until confirmed; wholesale priced `cc` |
| OI-19-20 | KPI keys: Saturday and holidays (D-28, Q27), AMO routes in Login % (D-29), Submit % (of logged-in) web basis (D-45), CPR edge cases (D-46), BSR (D-47), retention (D-54), till-date bases and bar bands (D-51, D-52), final-submit gates, delegation and reopen (D-55, D-198, D-262, Q11, Q45, Q53) | Business decisions | sales ops | 3a to 4b | The defaults of s3.2.2 and s3.2.8 |
| OI-19-21 | Selling-rule keys: units and rounding (D-16, D-17, D-19, MQ-01), memo number series (D-35, Q5), printer samples (D-76, Q57), promotion catalogue (D-33, Q13), QC cap basis (D-34, MQ-03), negative net (D-18), visit outcomes (D-38, Q50), edit and void reasons (D-200, D-86, MQ-18, Q43), cover rules (D-85, Q44) | Business decisions and physical samples | sponsor's delegate | 1a to 3a | The defaults of s3.2.3 |
| OI-19-22 | Platform keys: stale-bundle acceptance (D-70), OTP re-verify (D-80), distribution channel (D-10), telemetry residency (D-13), map provider (D-08), retention and photo retention (D-23, D-132, Q21), audit retention (D-113) | Business and legal decisions | engineering, legal | 2e to 6c | The defaults of s3.2.4 to s3.2.6 |
| OI-19-23 | The document is about 260 KB against the 80 to 140 KB budget | The registry holds 649 keys (600 before round 3, 565 before round 2) with parity and recommended values; compressing it would drop manual keys the register says must not be dropped | doc 14 author | merge | Kept whole; the s3 tables are the machine-readable seed source for T-0-60 |
| OI-19-24 | Gate placement: doc 20 s3 already defines T-4-61 (Web Entry with the back-date cut-off folded in) and places T-7-62 at 7c; this document lists T-4-63 (back-date window and unlock) separately, T-7-62 at 7b, and adds the per-zone override cap to T-2-67, freeze windows to T-2-68, device-known re-check to T-1-63 and the future-dated trigger to T-0-62 | Written in parallel | doc 20 author | 2d | Doc 20's ids and phases stand; the extra assertions are test cases inside those gates Resolved at the editorial merge: T-4-63 is registered in doc 20 s3.11 (4c). |
| OI-19-25 | Approver and break-glass rosters now need TWO holders per shift and a named data steward, security steward and ops_admin L2 on wave days (D-527, D-553) | only AKTCL can name people | sponsor | 2d | the pilot cannot start field-affecting edits without them |
| OI-19-26 | The ticket tool behind "Create ticket" on P19 (D-541) | an AKTCL choice | IT | 6c | a shared mailbox with a pre-filled body |
| OI-19-27 | Whether a Sales Submit locks capture in Apsis (OI-17-14); `cfg.day.sales_submit_locks_capture` default true with the void as the safety valve (D-539) | unknown; confirm with the business | sales ops | 2e | locked, voidable |
| OI-19-28 | The budget-impact coefficients of s2.6 (high-accuracy fixes about 3 x balanced energy) are ASSUMPTION until T-2-156 measures them | no device data | QA lead | 2e | the line shows the formula and the assumption |
| OI-19-29 | Invariant lists for `cfg.app.home_tiles` per role beyond SR and AMO (TSO, DMO home) are unknown until the manuals' home screens are confirmed | MQ-48 | sales ops | 4a | SR and AMO invariants only |
| OI-19-30 | FCM acceptability decides whether the 5-minute urgent bound exists at all (D-09, s4.1b); with push off the published urgent bound is 15 minutes for selling phones | Platform and legal | engineering, legal | 2d; checked again at 7b and wave 1 entry | the 15-minute figure; S9 runs with push on and off |
| OI-19-31 | The business must confirm that working-day windows (D-584) and the calendar-day ceiling of 7 are acceptable for the first morning after a break, and which breaks AKTCL declares in `calendar.break_overrides` | A rule choice | sales ops | 2e | `window_unit` = `working_days`, ceiling 7 |
| OI-19-32 | Price rails: the 15 percent jump threshold and the 24-hour correction window are ASSUMPTIONS; finance may want per-price-type thresholds | A finance choice | FIN | 2e | 15 percent, 24 hours |
| OI-19-33 | Stock Save semantics in Apsis (replace or add on re-save) unknown (MQ-68, D-580); the increment-only default stands until confirmed | unknown; confirm with the business | sales ops | 2a | `increment_only` with a correct-total path |
| OI-19-34 | Whether AKTCL adopts an external ticket tool or keeps the built-in store of P19 (D-599) | an AKTCL choice | IT | 6c | the built-in store |
| OI-19-35 | Held-bind SLA, delegate roles and the DMO roster per division (D-586) | only AKTCL can name people | sponsor | 2e | 30 minutes; `security_admin` and `ops_admin` as delegates |

## Traceability

### Requirements

| Req | Where this document answers it |
| --- | --- |
| R1 DATA | Every captured row stores the config version and the resolved values used (s2.4), so any dashboard or report can be rebuilt from stored columns without the transaction log; config and its audit are structured tables (s2.1), mirrored to `dw.fact_config_change` (doc 16); reach and delivery are tables, not logs (s4.3) |
| R2 FEATURES | Every manual config key is registered with its parity value (s3, s3.5); the TSO back-office write tools and the 41-page menu are specified (s5.3, s8); manual contradictions are decided in s3.4 and s3.5 |
| R3 SCALE | Relevance-scoped delta pulls (s2.4, s4.4), cached resolution (s2.5), freeze windows and hourly limit (s7.10), bundle regeneration caps (s7.10), version-scoped hold (s9.1) |
| R4 BATTERY | No polling, no socket; the ack rides the next batch; FCM only for urgent keys with jitter (s4.1); config deltas are about 2 KB |
| R5 OFFLINE AND IMMEDIATE SYNC | Header-triggered pull on the next natural request, scheduled values applied offline, kill switches never block upload (s4.1, s4.2, s9.2) |
| R6 ADMIN CONFIG | Console, geofence management, rails, audit, propagation (s1.1 maps each clause) |
| Process: plan first, build phase by phase | s11 assigns pages, tools, keys and gates to every sub-milestone; s12.5 is the gate register |

### Identifiers

| Kind | Ids used | Handled in |
| --- | --- | --- |
| F-ADM | 038 to 055 (P1 to P18); 001 to 011, 070, 071 (P9); 006 (B5); 012, 013 (s6, s5.4); 014, 015, 059 (B8); 016 to 021, 061 (P8); 022, 069 (B7); 023 (B4); 024, 057, 058 (B1, B2); 026 (content); 027 (P12); 029, 030, 031, 032, 034; 033, 035, 036; 056 (B6); 060, 062 to 068 | s5.2, s8, s9 |
| F-API | 005, 009, 028, 029, 033, 036, 037, 039 to 046, 048 to 052, 054, 055 | s10 |
| F-WEB, F-TSO, F-SYS | F-WEB-032, 048, 050, 051, 052, 054; F-TSO-020, 022; F-SYS-053 | s8, s4 |
| G (owned) | 36 masters and their aliases | s12.1 |
| G (touched) | G-fraud-02, G-sec-11, G-qa-09, G-cfg-10, G-cfg-12, G-cfg-18, G-man-017 to 022, 029, 039, 062, 066, 067, 100 | s12.2 |
| G (minted) | G-19-01 to G-19-10 | s12.4 |
| D (existing) | D-01, 05, 08, 09, 10, 13, 15 to 17, 19 to 23, 25, 28 to 35, 38 to 40, 42, 43, 45 to 47, 49 to 52, 54, 55, 58, 59, 62 to 66, 68 to 70, 73 to 80, 82 to 87, 89 to 100, 101 to 124, 126, 127, 129, 130, 132, 133, 135 to 138, 140, 144, 147, 148, 150, 152, 157 to 207 (as cited), 208 to 269 (as cited) | s2 to s12 |
| D (minted) | D-430 to D-449 | s12.3 |
| T | s12.5 and the cited gates of other ranges | s12.5 |
| Q and MQ | Q4, Q5, Q6, Q7, Q9 to Q14, Q17, Q21 to Q25, Q27 to Q30, Q43 to Q46, Q50, Q53, Q57; MQ-01, 03, 04, 09, 18, 22, 26, 33, 39, 41 to 44, 46, 48 | Open items, s3 |
| Config keys | Every key of the registry | s3.2.1 geo; s3.2.2 day; s3.2.3 sale, memo, credit, qc, drp, stock, price, promo, print; s3.2.4 media, sync, bundle, app, net, telemetry; s3.2.5 release, ops, flag; s3.2.6 api, agg, retention, sla, support, sys; s3.2.7 auth, pii, sec; s3.2.8 target, loyalty, astha, superstar, kpi, calendar; s3.2.9 outlet, route, visit, survey, rubric, content, task, leave, feedback, tso, i18n, ui, notify, web, sales_plan, report, dashboard, map |

### Added at the editorial merge

**Master gaps owned by this document (36):** G-cfg-02, G-cfg-01, G-cfg-04 (G-scale-15), G-man-086, G-data-20, G-cfg-03, G-cfg-05, G-cfg-06, G-cfg-08, G-cfg-13, G-cfg-16, G-cfg-23 (G-field-11), G-fraud-09 (G-fraud-10), G-sre-07, G-sre-22, G-cfg-22, G-man-085 (G-feat-30), G-man-087, G-man-089 (G-feat-32), G-man-099, G-man-046, G-man-090, G-cfg-11, G-feat-12, G-fraud-11 (G-fraud-21), G-man-036, G-cfg-07, G-feat-63, G-cfg-09, G-cfg-21, G-fraud-24, G-field-17, G-feat-47, G-man-088, G-cfg-14, G-cfg-19.

**Decisions that DECISIONS.md assigns to this document and that it applies without an inline citation:** D-03, D-301, D-302, D-309. Most are "See D-nn" aliases of a decision cited above or decisions raised by doc 14; the substance was not re-verified row by row.

### Added by the round-2 gap resolution (D-500 to D-553)

| Kind | Ids | Handled in |
| --- | --- | --- |
| Gaps | G-qa-35, G-qa-43, G-qa-50, G-qa-51, G-qa-52, G-qa-71, G-qa-72, G-qa-73, G-qa-75, G-qa-76, G-qa-77, G-qa-78, G-qa-79, G-qa-83, G-qa-84 | s2.2, s2.3, s2.6, s3, s4.3, s5.1b, s5.2, s5.4, s6.2, s7.4, s7.6b, s7.10, s8.3, s8.7 |
| Decisions | D-507, D-512, D-518, D-519, D-525, D-526, D-527, D-539, D-540, D-541, D-542, D-543, D-544, D-545, D-546, D-547, D-551, D-552 | s12.3b |
| Gates | T-2-156, T-2-157, T-2-158, T-2-159, T-2-160, T-4-154, T-3-150 and the extended T-0-61, T-1-63, T-2-61, T-2-65, T-2-68, T-3-61, T-6-60 | s12.5 |
| Features | F-ADM-072 to F-ADM-077; F-API-069 to F-API-076; F-SR-080, F-AMO-047 | s5.2, s7.6b, s8.7, s10 |
| Config keys | cfg.sync.checkout_upload_jitter_max_s, cfg.sync.max_savepoints_per_tx, cfg.sync.resync_safety_margin_h, cfg.sync.digest_days, cfg.sla.pending_rows_alert_h, cfg.release.auto_freeze_regression_pct, cfg.telemetry.bat17_floor_pct, cfg.telemetry.enabled, cfg.ops.prefetch_enabled, cfg.day.sales_submit_locks_capture, cfg.day.submit_undo_window_min, cfg.day.bulk_absent_max_routes, cfg.route.vacancy_alert_days, cfg.user.dismissal_upload_grace_h, cfg.user.admin_checker_required, cfg.entry.paper_backfill_window_days, cfg.outlet.location_request_min_move_m, cfg.outlet.location_request_max_accuracy_m, cfg.outlet.bulk_approve_consistent_m, cfg.outlet.bulk_approve_min_evidence, cfg.outlet.mobile_regex_test_vectors, cfg.sla.location_request_escalate_h, cfg.sla.location_request_lapse_days, cfg.sla.location_request_backlog_alert, cfg.sla.config_reach_urgent_min, cfg.sla.config_reach_tail_h, cfg.kpi.leaderboard_min_switched_pct, cfg.calendar.emergency_declare_roles, cfg.calendar.emergency_max_days, cfg.geo.radius_emergency_max_m, cfg.geo.emergency_widen_max_hours, cfg.geo.radius_incident_ceiling_m, cfg.sys.temporary_relief_max_h, cfg.sys.break_glass_holders_min, cfg.auth.refresh_absolute_jitter_days, cfg.auth.refresh_renew_warn_days, cfg.auth.offline_grace_after_absolute_expiry_days (new); cfg.outlet.placeholder_pin_min_shared and cfg.kpi.tilldate_basis.<surface> (reconciled); cfg.geo.placeholder_min_shared and cfg.ops.dashboard_default_date_rule (retired) | s3.2.10, s3.3, s3.4 |

### Added by the round-3 gap resolution (D-554 to D-601)

| Kind | Ids | Handled in |
| --- | --- | --- |
| Gaps | G-qa-96, G-qa-106, G-qa-107, G-qa-113, G-qa-123, G-qa-125, G-qa-126, G-qa-127, G-qa-128, G-qa-129, G-qa-130, G-qa-132, G-qa-133, G-qa-134, G-qa-136, G-qa-137, G-qa-138, G-qa-139 | s2.2, s2.3b, s2.6b, s3.2.11, s4.1b, s5.1b, s5.2, s5.4b, s7.5, s7.6c, s8.2b, s8.4, s8.8, s10 |
| Decisions | D-556, D-563, D-571, D-577, D-579, D-580, D-584, D-585, D-586, D-587, D-588, D-589, D-590, D-591, D-593, D-594, D-595, D-597, D-598, D-599, D-600 | s12.3b (round 3) |
| Gates | T-0-157, T-2-162, T-2-165, T-2-173, T-2-174 owned here; T-2-166 to T-2-172, T-3-159, T-4-166, T-6-150, T-6-152 cited | s12.5 |
| Features | F-ADM-078 to F-ADM-085; F-API-077 to F-API-086; F-SYS-090 to F-SYS-092, F-SYS-094, F-SYS-095 | s5.2, s5.4b, s10 |
| Config keys | the 49 keys of s3.2.11 and the changed rows listed there | s3.2.11 |
