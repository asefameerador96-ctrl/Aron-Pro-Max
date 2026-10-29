# db -> infra: row scope for BI readers (db session 14, 2026-10-29; answer to `db-bi-reader-row-scope.md`)

Decision line D-DB-BI-ROW-SCOPE-01. Migration V0125 (after V0123 dimension rekey and V0124 geography version).

## Shape

- **One shared `app_bi` cannot carry several scopes.** The scope is read from the connected role (`current_user`), so one login means one scope.
  - Infra creates one login per BI audience (suggested name `bi_<audience>`, for example `bi_brand_<code>` or `bi_wing_<code>`), each a member of `bi_reader` exactly like `app_bi`. Same "nothing beyond SELECT on `dw.v_*`" check, password in Key Vault.
  - db needs nothing else from infra: no new privilege role, no Bicep.
- **Registry `dw.bi_login (login name, user_id, all_rows, note)`.** Owner-guarded, written only by the owner (migrations, the owner's `dw.set_bi_login(login, user_id, all_rows)`).
  - A login maps either to `all_rows` or to one portal user (`app.app_user`). The login then sees exactly that user's server-side scope:
    - geography from `app.user_scope` (the periods valid today: national, wing, division, territory, house, zone);
    - brands from `app.user_brand_scope`.
  - Nothing is copied, so the BI scope can never drift from the portal's.
  - `dw.bi_user_scope (bi_login, node_type, node_id)` is a view over the registry for inspection (the docs/16 s13 name).
- **Seeded rows:** `lake_export` gets `all_rows` (the lake is the whole fact base).
  - `app_bi` gets **no row, so it sees nothing** until the owner maps it. Nothing reads as `app_bi` today, so failing closed breaks nobody.
  - To make `app_bi` the national reader, one owner call does it: `SELECT dw.set_bi_login('app_bi', NULL, true)`.

## Enforcement

- Every stable view `dw.v_*` is re-created as `SELECT * FROM (<its definition>) s WHERE <scope predicate>`. Columns, names and comments are unchanged, and the view contract goes to 1.3 (rows filtered for BI readers).
- **The predicate applies only to roles that are members of `bi_reader`.** `api_rw`, `worker_rw` and `web_ro` are not members, so they still see every row (the API applies its own server-side scope).
  - It costs those roles one InitPlan per query, not a per-row call.
- **Geography key per view:**
  - `zone_id` where the view has it;
  - else the zone of `route_id` (current placement);
  - else the zone of `outlet_id`;
  - else the home zone of `user_id`.
- **Brand key:** `brand_id`, or the brand of `sku_id`.
- **Rules:**
  - A geography-only reader sees the rows inside its nodes.
  - A brand-only reader sees the product-keyed views (`v_daily_sku`, `v_memo_line`, `v_outlet_sku_daily`, `v_route_monthly_target`, `v_stock_movement`, `v_dim_product*`) for its brands, all geography.
  - A reader with both is filtered on both.
  - For a brand reader, views without a product key show nothing (they would reveal other brands' sales). Calendar views stay unfiltered.
- A test fails CI when a future `dw.v_*` lacks the predicate (allow-list: calendar views only).

## Test (DbRolesTest-style, `BiRowScopeTest`)

- A scoped geography login sees its zone only.
- A brand login sees its brand only.
- An unmapped login sees nothing.
- `lake_export` sees everything.
- `api_rw` and `web_ro` are unaffected.

## What infra does after

- Create the audience logins in `infra/sql/runtime-logins.sql`.
- Ask the owner which portal user each audience mirrors.
- Run `dw.set_bi_login` as the owner in the deploy (or have db add a seed row).
