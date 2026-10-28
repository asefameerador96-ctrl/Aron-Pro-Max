# infra to db: row scope for BI readers (`dw.bi_user_scope`, D-374), db #14 (infra session 7, 2026-10-28)

**From:** infra lane, data-architecture review 2026-10-25 finding 12 part 2 (D-INFRA-LAKE-01 built the logins). **To:** db. **Priority:** before any BI tool or outside reader is pointed at the database; nothing reads as `app_bi` today.

## What exists (infra row 7, on INT since train 50)

- Login `app_bi` (BI tools) and login `lake_export` (the worker's nightly lake export, row 8), both members of the V0014 privilege role `bi_reader`: SELECT on `dw.v_*` (V0020, V0118: no `memo_no` column of `dw.v_memo_flags`), EXECUTE on the invoker-rights `dw` functions, session limits from V0020 (10 min statement, 30 s idle in transaction). `infra/sql/runtime-logins.sql` fails the deploy if either login can reach more than that.
- Passwords in Key Vault (`aron-db-pw-app-bi`, `aron-db-pw-lake-export`), read URLs `aron-db-bi-read-url`, `aron-db-lake-read-url`.

## The gap

Every `bi_reader` member sees every row of every `dw.v_*` view: all brands, all wings. D-374 (docs/16 s13, row "Row level security") designs `dw.bi_user_scope (bi_login, node_type, node_id)`, maintained from `app.user_scope`, so a brand or wing reader sees only its own rows; the portal already scopes Brand User and Wing Manager on the server (CLAUDE.md standing rule 16), and BI must not be a way around that.

## Ask (db decides the shape; infra's suggestion)

1. A table `dw.bi_user_scope (bi_login text, node_type text, node_id ...)` (or brand_id for brand readers), maintained by the worker from `app.user_scope` or by an admin flow, plus an "all rows" marker for the national/lake readers.
2. Row-level security, or scoped views, on the `dw` objects behind the `v_*` views, keyed on `current_user` against `dw.bi_user_scope`, so `app_bi` (and any later per-team BI login) sees only its nodes. `lake_export` stays national (the lake is the whole fact base) and is marked "all rows".
3. A test like `DbRolesTest`: a scoped BI login sees its brand or wing only, an unscoped one sees nothing, `lake_export` sees everything.
4. If one shared `app_bi` cannot carry several scopes, say so: infra then creates one login per BI audience (for example `bi_brand_<code>`) in `runtime-logins.sql`, passwords in Key Vault like the others. Tell infra which logins and which privilege role each joins.

## What infra does after

Adds any new logins to `infra/sql/runtime-logins.sql` (with the same "nothing beyond SELECT on `dw.v_*`" check), their Key Vault passwords in `infra/scripts/db-login-secrets.sh`, and a `check_infra.py` test; no Bicep change is expected.
