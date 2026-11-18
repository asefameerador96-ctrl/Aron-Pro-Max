"""Orchestration: profile (dry run), stage, transform, verify, reconcile, finalize, cleanup. Counts and checksums only; never a data row."""
import json
import os
import re
import sys
import tempfile
import time
from pathlib import Path

from . import gates, safety, staging
from .db import Db, DbError
from .mysqldump import events

HERE = Path(__file__).resolve().parent.parent
SQL = HERE / "sql"

PLACEHOLDERS = {"@C_OUTLETS@": "outlets", "@C_ROUTES@": "routes", "@C_SKU@": "sku", "@C_GEO@": "geo_config", "@C_SALES@": "sales",
                "@C_GEO_VISIT@": "visit_geo", "@C_ATT@": "attendance", "@C_SUMMARY@": "section_summary", "@C_OUTLET_ROUTE@": "outlet_route"}
FACTS = [("trans_sales_by_outlet", "20_fact_sales.sql", "sales_line"), ("live_geo_data_by_outlets", "21_fact_geo.sql", "visit_geo"),
         ("master_ff_attendance_records", "22_fact_attendance.sql", "attendance"), ("trans_section_summary", "23_section_summary.sql", "route_day_vendor")]
TYPED = {t for t, _, _ in FACTS}
# BIG tables are staged, transformed and (except sales) dropped one at a time; everything else is staged first (masters and small tables).
BIG = {"live_geo_data_by_outlets", "master_ff_attendance_records", "trans_section_summary", "trans_by_outlet_promotion_data", "master_sr_target_scope", "trans_sales_by_outlet"}
KEEP_STAGING = {"trans_sales_by_outlet", "master_outlet_section_mapping"} | {"master_outlets", "master_routes", "master_sku", "master_field_forces", "master_geo_config"}
ORDER = ["master_locations", "master_dep", "master_routes", "master_sku", "master_field_forces", "master_outlets", "master_outlet_section_mapping",
         "master_geo_config", "master_ff_attendance_records", "trans_section_summary", "master_sr_target_scope", "master_sr_targe_configuration",
         "master_promotion", "trans_by_outlet_promotion_data", "live_geo_data_by_outlets", "trans_sales_by_outlet"]
STATE_RANK = {"pending": 0, "staged": 1, "transformed": 2, "verified": 3, "failed": -1}
STAGE = "apsis_stage"
ALLOWED_TZ = {"UTC", "Asia/Dhaka"}


def raw_tables(mapping):
    """Every allowed table that is not one of the four typed facts is archived raw (reviewed columns, whole row, jsonb)."""
    return sorted(set(mapping["allowed_tables"]) - TYPED, key=lambda t: order_key(t))


def log(msg):
    print(msg, flush=True)


def order_key(t):
    return ORDER.index(t) if t in ORDER else 50


def find_files(dump_dir, allowed):
    """{table: path}. Only files whose stem is an allowed table are used; the rest are listed in 'refused' (count only, never the name)."""
    files, refused = {}, 0
    for p in sorted(Path(dump_dir).iterdir()):
        if not p.is_file():
            continue
        m = re.match(r"^(.*)\.(sql|tsv)(\.gz)?$", p.name)
        if not m:
            continue
        try:
            files[safety.table(m.group(1), allowed)] = p
        except safety.Refused:
            refused += 1
    return files, refused


# ------------------------------------------------------------------ dry run / profile (no database at all)
def _hour_shares(hours):
    n = sum(hours)
    if not n:
        return None
    utc_window = sum(hours[2:14]) / n       # field activity 08:00-20:00 Dhaka = 02:00-14:00 UTC
    dhaka_window = sum(hours[8:20]) / n     # ... or 08:00-20:00 as stored if the vendor stored Dhaka local time
    hint = "dhaka_local" if dhaka_window > utc_window + 0.15 else "utc" if utc_window > dhaka_window + 0.15 else "unclear"
    return {"rows_with_time": n, "share_if_stored_utc": round(utc_window, 4), "share_if_stored_dhaka": round(dhaka_window, 4), "likely": hint}


def _spec(v):
    return v if isinstance(v, dict) else {"direct": v}


def resolve(cfg, avail, cols_of):
    """canonical column -> ('direct', col) | ('ref', fk, master_table, key, value) | None, using the columns the vendor table (and masters) really have."""
    out = {}
    for c, spec in cfg.get("columns", {}).items():
        sp = _spec(spec)
        v = staging.pick(sp.get("direct", []), avail)
        if v:
            out[c] = ("direct", v)
            continue
        r = sp.get("ref")
        out[c] = None
        if r:
            fk = staging.pick(r["fk"], avail)
            mc = cols_of(r["table"])
            mk, mv = staging.pick(r["key"], mc), staging.pick(r["value"], mc)
            if fk and mk and mv:
                out[c] = ("ref", fk, r["table"], mk, mv)
    return out


def coverage(cfg, res):
    """Every target column with its source column (names only). Unmapped is an ERROR unless listed in the table's absent_ok."""
    absent_ok = set(cfg.get("absent_ok", []))
    rows, errors = [], []
    for c, r in res.items():
        src = None if r is None else (r[1] if r[0] == "direct" else f"{r[1]} via {r[2]}.{r[4]}")
        rows.append({"target": c, "source": src, "status": "mapped" if r else ("intentionally_absent" if c in absent_ok else "UNMAPPED")})
        if r is None and c not in absent_ok:
            errors.append(c)
    return rows, errors


def _tz_evidence(rows_iter, di, ti):
    """Date column vs the date of the timestamp in each candidate zone, among the rows where the two zones disagree (stored time 18:00-24:00)."""
    from datetime import datetime, timedelta
    disc = utc_ok = local_ok = 0
    for d, t in rows_iter:
        if not d or not t or d.startswith("0000") or t.startswith("0000") or len(t) < 19:
            continue
        try:
            dt = datetime.fromisoformat(t[:19].replace(" ", "T"))
        except ValueError:
            continue
        as_utc = (dt + timedelta(hours=6)).date().isoformat()
        as_local = dt.date().isoformat()
        if as_utc == as_local:
            continue
        disc += 1
        utc_ok += as_utc == d[:10]
        local_ok += as_local == d[:10]
    return disc, utc_ok, local_ok


def profile(dump_dir, mapping, max_key_check_rows=5_000_000, expected=None, charset="utf-8"):
    """Counts only: rows per table, per chunk, null counts, zero dates, column NAMES with a suggested keep/drop class, mapping coverage (target column and source
    column names), time-zone evidence (hour-of-day histogram and date-column agreement), mojibake hints, key duplicates (small tables), rows against the vendor's count."""
    allowed = set(mapping["allowed_tables"])
    files, refused = find_files(dump_dir, allowed)
    rep = {"tables": {}, "files_refused_not_on_allow_list": refused, "tables_missing": sorted(allowed - set(files))}
    schemas = {}
    for table, path in sorted(files.items(), key=lambda kv: order_key(kv[0])):
        t0 = time.time()
        ts, rows, per_chunk, nulls, zero, seen, dup, bad, mojibake = None, 0, {}, {}, 0, set(), 0, 0, 0
        cd_idx = key_idx = None
        cols, hours, pairs, pair_stats = [], [0] * 24, [], {}
        for ev in events(path, charset=charset, strict=False):
            if ev[0] == "schema":
                ts = ev[1]
                cols = ts.column_names()
                schemas[table] = cols
                nulls = {c: 0 for c in cols}
                cands = mapping["tables"].get(table, {}).get("chunk_date", [])
                cd = staging.pick(cands, cols) if cands else None
                cd_idx = cols.index(cd) if cd else None
                key_idx = [cols.index(p) for p in ts.pk] if ts.pk else None
                dates = [i for i, c in enumerate(ts.columns) if c.base == "date"]
                stamps = [i for i, c in enumerate(ts.columns) if c.base in ("datetime", "timestamp")]
                pairs = [(d, t) for d in dates for t in stamps][:3]
                pair_stats = {p: [0, 0, 0] for p in pairs}
                continue
            for r in ev[2]:
                rows += 1
                for i, v in enumerate(r):
                    if v is None:
                        nulls[cols[i]] += 1
                    else:
                        if v.startswith("0000-00-00"):
                            zero += 1
                        if "�" in v:
                            bad += 1
                        if "à¦" in v or "à§" in v:
                            mojibake += 1
                ch = staging.chunk_of(r[cd_idx]) if cd_idx is not None else "all"
                per_chunk[ch] = per_chunk.get(ch, 0) + 1
                if cd_idx is not None and r[cd_idx] and not r[cd_idx].startswith("0000") and len(r[cd_idx]) >= 19 and r[cd_idx][10] in " T" and r[cd_idx][11:13].isdigit():
                    hours[int(r[cd_idx][11:13]) % 24] += 1
                for (di, ti), st in pair_stats.items():
                    d, n, l = _tz_evidence([(r[di], r[ti])], di, ti)
                    st[0] += d; st[1] += n; st[2] += l
                if key_idx is not None and rows <= max_key_check_rows:
                    k = hash(tuple(r[i] for i in key_idx))
                    dup += k in seen
                    seen.add(k)
        if ts is None:
            rep["tables"][table] = {"error": "no CREATE TABLE in the file: a schema-only profile is needed"}
            continue
        cands = mapping["tables"].get(table, {}).get("chunk_date", [])
        info = {"rows": rows, "rows_expected_by_vendor": (expected or {}).get(table),
                "columns": [{"name": c.name, "type": c.base + c.args, "suggested": staging.suggest(c.name)} for c in ts.columns],
                "chunk_date_column_found": (staging.pick(cands, cols) is not None) if cands else None,
                "chunks": dict(sorted(per_chunk.items())), "null_counts": {c: n for c, n in nulls.items() if n}, "zero_dates": zero,
                "undecodable_rows": bad, "mojibake_rows": mojibake, "has_primary_key": bool(ts.pk),
                "duplicate_keys": dup if key_idx is not None and rows <= max_key_check_rows else None,
                "float_money_columns": [c.name for c in ts.columns if c.base in ("float", "double", "real") and re.search(r"amount|price|value|total|mrp|tp", c.name, re.I)],
                "hour_histogram_stored": hours if sum(hours) else None, "time_zone_hint": _hour_shares(hours),
                "date_column_agreement": [{"date_column": cols[d], "timestamp_column": cols[t], "rows_where_zones_disagree": st[0],
                                           "share_if_stored_utc": round(st[1] / st[0], 4) if st[0] else None, "share_if_stored_dhaka": round(st[2] / st[0], 4) if st[0] else None}
                                          for (d, t), st in pair_stats.items()],
                "seconds": round(time.time() - t0, 1)}
        rep["tables"][table] = info
    for table, info in rep["tables"].items():
        cfg = mapping["tables"].get(table)
        if cfg and "columns" in info:
            rows_, errs = coverage(cfg, resolve(cfg, schemas[table], lambda t: schemas.get(t, [])))
            info["mapping_coverage"] = rows_
            info["mapping_errors"] = errs
    return rep


def suggest_review(prof):
    """A column review (drop/keep lists) from the profile's SUGGESTIONS: a starting point the owner/lead edits and commits; never applied by itself."""
    out = {"_comment": "Reviewed column list. Every column of every table must be in keep or drop. Edit, review and commit before `run`.", "tables": {}}
    for t, info in prof["tables"].items():
        if "columns" not in info:
            continue
        out["tables"][t] = {"drop": [c["name"] for c in info["columns"] if c["suggested"] != "keep"],
                            "keep": [c["name"] for c in info["columns"] if c["suggested"] == "keep"]}
    return out


# ------------------------------------------------------------------ canonical views
def build_views(db, mapping, stage=STAGE):
    """apsis_stage.c_<canonical> over each mapped table and r_<table> (raw row as jsonb) over every staged table.
    Returns ({table: [canonical columns with no source and not intentionally absent]}, {table: coverage rows}, {table: has_date})."""
    allowed = set(mapping["allowed_tables"])
    missing, cover, has_date = {}, {}, {}
    have = set(db.run("SELECT table_name FROM information_schema.tables WHERE table_schema = :'s' AND table_type = 'BASE TABLE'", {"s": stage}).split())
    cols_of = {}

    def cols(t):
        if t not in cols_of:
            cols_of[t] = [c for c in db.run("SELECT column_name FROM information_schema.columns WHERE table_schema = :'s' AND table_name = :'t'",
                                            {"s": stage, "t": t}).split("\n") if c] if t in have else []
        return cols_of[t]
    for table in sorted(have):
        safety.table(table, allowed)
        cfg = mapping["tables"].get(table, {})
        cd = staging.pick(cfg.get("chunk_date", []), cols(table)) if cfg.get("chunk_date") else None
        has_date[table] = cd is not None
        dexp = f"{safety.quote(cd)}::text" if cd else "NULL::text"
        db.run(f"DROP VIEW IF EXISTS {stage}.r_{table}; CREATE VIEW {stage}.r_{table} AS SELECT {dexp} AS d, to_jsonb(s) - '_row_uuid' - '_chunk' - '_stmt' AS j, _row_uuid, _chunk FROM {stage}.{safety.quote(table)} s")
    for table, cfg in mapping["tables"].items():
        safety.table(table, allowed)
        if not cfg.get("columns"):
            continue
        canon = safety.ident(cfg["canonical"])
        avail = cols(table)
        res = resolve(cfg, avail, cols)
        sel, joins, mapped_src = [], [], []
        for n, (c, r) in enumerate(res.items()):
            safety.ident(c, "canonical column")
            if r is None:
                sel.append(f"NULL::text AS {c}")
            elif r[0] == "direct":
                sel.append(f"f.{safety.quote(r[1])} AS {c}")
                mapped_src.append(r[1])
            else:
                _, fk, mt, mk, mv = r
                safety.table(mt, allowed)
                joins.append(f"LEFT JOIN (SELECT DISTINCT ON ({safety.quote(mk)}) {safety.quote(mk)} AS k, {safety.quote(mv)} AS v FROM {stage}.{safety.quote(mt)} "
                             f"ORDER BY {safety.quote(mk)}, _row_uuid) j{n} ON j{n}.k = f.{safety.quote(fk)}")
                sel.append(f"j{n}.v AS {c}")
                mapped_src.append(fk)
        if cfg.get("extra"):
            arr = "ARRAY[" + ", ".join("'" + safety.ident(x) + "'" for x in mapped_src) + "]::text[]" if mapped_src else "ARRAY[]::text[]"
            sel.append(f"to_jsonb(f) - {arr} - '_row_uuid' - '_chunk' - '_stmt' AS extra")
        if table in have:
            src = f"SELECT {', '.join(sel)}, f._row_uuid, f._chunk FROM {stage}.{safety.quote(table)} f {' '.join(joins)}"
            rows_, errs = coverage(cfg, res)
            cover[table] = rows_
            if errs:
                missing[table] = errs
        else:
            names = list(res) + (["extra"] if cfg.get("extra") else [])
            src = f"SELECT {', '.join(('NULL::jsonb' if n == 'extra' else 'NULL::text') + ' AS ' + n for n in names)}, NULL::uuid AS _row_uuid, NULL::text AS _chunk WHERE false"
        db.run(f"DROP VIEW IF EXISTS {stage}.c_{canon}; CREATE VIEW {stage}.c_{canon} AS {src}")
    return missing, cover, has_date


def render(text, raw_table=None):
    for ph, canon in PLACEHOLDERS.items():
        text = text.replace(ph, f"{STAGE}.c_{canon}")
    return text.replace("@C_RAW@", f"{STAGE}.r_{safety.ident(raw_table)}") if raw_table else text


_SQLTMP = None


def run_sql_file(db, name, variables, raw_table=None):
    global _SQLTMP
    if _SQLTMP is None:
        _SQLTMP = Path(tempfile.mkdtemp(prefix="apsis-sql-"))        # private per process: no predictable path
    f = _SQLTMP / ("r_" + name)
    f.write_text(render((SQL / name).read_text(encoding="utf-8"), raw_table), encoding="utf-8")
    return db.file(f, variables)


# ------------------------------------------------------------------ control table (all values are psql variables)
def ctl_set(db, table, chunk, **kw):
    safety.ident(table, "table")
    safety.chunk(chunk)
    allowed = {"state": "text", "rows_parsed": "bigint", "rows_in": "bigint", "rows_out": "bigint", "rows_skipped": "bigint", "stmt_ordinal": "int",
               "checksum": "jsonb", "run_id": "bigint", "error": "text"}
    vars = {"t": table, "c": chunk}
    cols, vals, sets = ["vendor_table", "chunk_key"], [":'t'", ":'c'"], []
    for k, v in kw.items():
        if k not in allowed:
            raise ValueError("unknown control column")
        cols.append(k)
        if v is None:
            vals.append("NULL"); sets.append(f"{k} = NULL")
        else:
            vars[f"v_{k}"] = json.dumps(v) if k == "checksum" else str(v)
            vals.append(f":'v_{k}'::{allowed[k]}"); sets.append(f"{k} = :'v_{k}'::{allowed[k]}")
    if kw.get("state") in ("transformed", "verified", "failed"):
        sets.append("finished_at = now()")
    else:
        sets.append("started_at = COALESCE(apsis_ctl.chunk.started_at, now())")
    db.run(f"INSERT INTO apsis_ctl.chunk ({', '.join(cols)}) VALUES ({', '.join(vals)}) ON CONFLICT (vendor_table, chunk_key) DO UPDATE SET {', '.join(sets)}", vars)


def ctl_get(db, table, chunk):
    out = db.run("SELECT state, stmt_ordinal, rows_in, rows_parsed, COALESCE(checksum::text, '{}') FROM apsis_ctl.chunk WHERE vendor_table = :'t' AND chunk_key = :'c'",
                 {"t": table, "c": chunk}).strip()
    if not out:
        return None
    s, o, r, p, cs = out.split("|", 4)
    return {"state": s, "stmt_ordinal": int(o), "rows_in": int(r), "rows_parsed": int(p), "meta": json.loads(cs)}


def start_run(db, source_tz, confirm, qty_unit="base", dry=False, note=""):
    """Refuses a zone outside the allow-list, and refuses when verified chunks exist from a run with other settings: the archive would hold a mix."""
    if source_tz not in ALLOWED_TZ:
        raise ValueError("source time zone must be one of " + ", ".join(sorted(ALLOWED_TZ)) + " (free text such as BST or UTC+6 is refused)")
    prev = db.run("SELECT DISTINCT r.source_tz || '|' || r.qty_unit FROM apsis_ctl.chunk c JOIN apsis_ctl.run r ON r.id = c.run_id "
                  "WHERE c.state = 'verified' AND c.chunk_key <> '_stage' AND c.vendor_table NOT LIKE '\\_%'").split()
    if prev and prev != [f"{source_tz}|{qty_unit}"]:
        raise ValueError("settings changed (source time zone or quantity unit) while verified chunks exist: run `reset-transform` first")
    return int(db.scalar("INSERT INTO apsis_ctl.run (source_tz, qty_unit, confirm_locations, dry_run, note) VALUES (:'tz', :'qu', :'cl'::boolean, :'dr'::boolean, :'n') RETURNING id",
                         {"tz": source_tz, "qu": qty_unit, "cl": str(confirm).lower(), "dr": str(dry).lower(), "n": note}))


# ------------------------------------------------------------------ staging with resume and truncation detection
def _stage_count(db, t):
    if db.scalar("SELECT to_regclass(:'r') IS NOT NULL", {"r": f"{STAGE}.{safety.quote(t)}"}) != "t":
        return None
    return int(db.scalar(f"SELECT count(DISTINCT _row_uuid) FROM {STAGE}.{safety.quote(t)}"))


def _reset_stage(db, t):
    db.run(f"DROP TABLE IF EXISTS {STAGE}.{safety.quote(t)} CASCADE")
    db.run("DELETE FROM apsis_ctl.chunk WHERE vendor_table = :'t'", {"t": t})


def stage_all(db, dump_dir, mapping, review, run_id, only=None, log=log, charset="utf-8"):
    stager = staging.TableStager(db, mapping, review)
    files, refused = find_files(dump_dir, set(mapping["allowed_tables"]))
    db.run("INSERT INTO apsis_ctl.report (run_id, name, value) VALUES (:'r'::bigint, 'refused_files', :'v'::jsonb) ON CONFLICT (run_id, name) DO UPDATE SET value = EXCLUDED.value",
           {"r": str(run_id), "v": json.dumps({"n": refused})})
    if refused:
        log(f"{refused} file(s) not on the allow-list were ignored: this fails the sign-off report")
    for table in sorted(files, key=order_key):
        if only and table not in only:
            continue
        chunks = db.run("SELECT chunk_key, state FROM apsis_ctl.chunk WHERE vendor_table = :'t' AND chunk_key <> '_stage'", {"t": table}).split()
        if chunks and all(c.split("|")[1] == "verified" for c in chunks):
            log(f"stage {table}: all chunks verified, nothing to do")
            continue
        tsv = str(files[table]).endswith((".tsv", ".tsv.gz"))
        st = ctl_get(db, table, "_stage")
        staged = _stage_count(db, table)
        if st and st["state"] == "staged":
            per = {r.split("|")[0]: int(r.split("|")[1]) for r in db.run(f"SELECT _chunk, count(DISTINCT _row_uuid) FROM {STAGE}.{safety.quote(table)} GROUP BY 1").split()} if staged is not None else {}
            rec = {r.split("|")[0]: int(r.split("|")[1]) for r in db.run("SELECT chunk_key, rows_in FROM apsis_ctl.chunk WHERE vendor_table = :'t' AND chunk_key <> '_stage'", {"t": table}).split()}
            if staged == st["rows_in"] and per == rec:
                log(f"stage {table}: already staged and intact")
                continue
            log(f"stage {table}: staged rows no longer match the control table (staged {staged}, recorded {st['rows_in']}): re-staging from the dump")
            _reset_stage(db, table)
            st = None
        elif st and (staged is None or tsv):          # a TSV file has no statement checkpoints: it restarts from the beginning
            _reset_stage(db, table)
            st = None
        skip, base = (st["stmt_ordinal"], st["rows_parsed"]) if st else (0, 0)
        if not st:
            _reset_stage(db, table)

        def cp(ordinal, parsed, _t=table):
            ctl_set(db, _t, "_stage", state="pending", stmt_ordinal=ordinal, rows_parsed=parsed, run_id=run_id)
        info = stager.stage_file(files[table], table, checkpoint=cp, skip_statements=skip, parsed_before=base, tsv=tsv, charset=charset)
        total = _stage_count(db, table) or 0
        meta = {"has_pk": info["has_pk"], "key_cols": info["key_cols"], "dropped_columns": info["dropped_columns"], "charset": charset,
                "is_fact": table in TYPED or table in ("trans_by_outlet_promotion_data",)}
        ctl_set(db, table, "_stage", state="staged", rows_in=total, rows_parsed=info["rows_parsed"], checksum=meta, run_id=run_id)
        for r in db.run(f"SELECT _chunk, count(DISTINCT _row_uuid) FROM {STAGE}.{safety.quote(table)} GROUP BY 1").split():
            ch, n = r.split("|")
            ctl_set(db, table, ch, state="staged", rows_in=int(n), run_id=run_id)
        db.run("INSERT INTO apsis_ctl.report (run_id, name, value) VALUES (:'r'::bigint, :'n', :'v'::jsonb) ON CONFLICT (run_id, name) DO UPDATE SET value = EXCLUDED.value",
               {"r": str(run_id), "n": "dropped_columns:" + table, "v": json.dumps({"dropped": info["dropped_columns"]})})
        log(f"stage {table}: {info['rows_parsed']} rows read, {total} distinct, columns dropped by the review: {len(info['dropped_columns'])}")


# ------------------------------------------------------------------ transform and verify
_TZ = "(SELECT source_tz FROM apsis_ctl.run ORDER BY id DESC LIMIT 1)"
_DATE = {  # canonical view -> (sql date expression of the business date, key column, usable-source column)
    "sales": (f"apsis_map.business_date(sale_date::text, {_TZ})", "outlet_code", "sale_date"),
    "visit_geo": (f"app.dhaka_date(apsis_map.mysql_ts(captured_at::text, {_TZ}))", "outlet_code", "captured_at"),
    "attendance": (f"app.dhaka_date(apsis_map.mysql_ts(check_in_at::text, {_TZ}))", "user_code", "check_in_at"),
    "section_summary": ("apsis_map.mysql_date(summary_date::text)", "route_code", "summary_date"),
}


def metrics_stage(db, table, chunk, mapping, qty_unit, has_date=False):
    safety.chunk(chunk)
    h = "('x'||substr(md5(_row_uuid::text),1,15))::bit(60)::bigint"
    v = {"c": chunk, "qu": qty_unit, "hd": "true" if has_date else "false"}
    if table not in TYPED:
        q = (f"SELECT jsonb_build_object('rows', count(*) FILTER (WHERE ok), 'hash', COALESCE(sum({h}::numeric) FILTER (WHERE ok), 0), 'rejected', count(*) FILTER (WHERE NOT ok)) "
             f"FROM (SELECT DISTINCT ON (_row_uuid) _row_uuid, (:'hd' <> 'true' OR apsis_map.in_window(apsis_map.mysql_date(d))) AS ok FROM {STAGE}.r_{safety.ident(table)} "
             f"WHERE _chunk = :'c' ORDER BY _row_uuid) x")
        return json.loads(db.scalar(q, v))
    canon = mapping["tables"][table]["canonical"]
    dexp, key, col = _DATE[canon]
    usable = f"{key} IS NOT NULL AND btrim({key}::text) <> '' AND {dexp} IS NOT NULL"
    if canon == "sales":
        usable += " AND (qty IS NULL OR btrim(qty::text)='' OR apsis_map.num(qty::text) IS NOT NULL) AND (amount IS NULL OR btrim(amount::text)='' OR apsis_map.num(amount::text) IS NOT NULL)"
    extra = ""
    if canon == "sales":
        extra = (", 'qty_base', COALESCE(sum(round(COALESCE(apsis_map.num(qty::text),0) * CASE WHEN :'qu'='pack' THEN COALESCE((SELECT base_per_pack FROM apsis_map.sku_map k "
                 "WHERE k.code_norm=apsis_map.norm_code(x.sku_code::text)),1) ELSE 1 END)) FILTER (WHERE apsis_map.in_window(_bd)),0), "
                 "'gross_mtk', COALESCE(sum(round(COALESCE(apsis_map.num(amount::text),0) * 1000)) FILTER (WHERE apsis_map.in_window(_bd)),0)")
    q = (f"SELECT jsonb_build_object('rows', count(*) FILTER (WHERE apsis_map.in_window(_bd)), 'hash', COALESCE(sum({h}::numeric) FILTER (WHERE apsis_map.in_window(_bd)),0), "
         f"'rejected', count(*) FILTER (WHERE NOT apsis_map.in_window(_bd)){extra}) FROM (SELECT c.*, {dexp} AS _bd FROM (SELECT DISTINCT ON (_row_uuid) * FROM {STAGE}.c_{canon} "
         f"WHERE _chunk = :'c' AND {usable} ORDER BY _row_uuid) c) x")
    return json.loads(db.scalar(q, v))


def metrics_hist(db, table, hist, chunk):
    safety.chunk(chunk)
    if hist not in ("sales_line", "visit_geo", "attendance", "route_day_vendor", "raw_archive"):
        raise safety.Refused("refused: unknown archive table")
    h = "('x'||substr(md5(row_uuid::text),1,15))::bit(60)::bigint"
    extra = ", 'qty_base', COALESCE(sum(qty_base),0), 'gross_mtk', COALESCE(sum(gross_mtk),0)" if hist == "sales_line" else ""
    where = "chunk_key = :'c'" + (" AND vendor_table = :'vt'" if hist == "raw_archive" else "")
    return json.loads(db.scalar(f"SELECT jsonb_build_object('rows', count(*), 'hash', COALESCE(sum({h}::numeric),0), "
                                f"'rejected', (SELECT count(*) FROM apsis_hist.reject WHERE vendor_table = :'vt' AND chunk_key = :'c'){extra}) FROM apsis_hist.{hist} WHERE {where}",
                                {"c": chunk, "vt": table}))


def transform_all(db, mapping, run_id, source_tz, qty_unit, fill_locations=False, confirm_locations=False, only=None, log=log):
    missing, cover, has_date = build_views(db, mapping)
    db.run("INSERT INTO apsis_ctl.report (run_id, name, value) VALUES (:'r'::bigint, 'mapping_coverage', :'v'::jsonb) ON CONFLICT (run_id, name) DO UPDATE SET value = apsis_ctl.report.value || EXCLUDED.value",
           {"r": str(run_id), "v": json.dumps(cover)})
    vars_ = {"run_id": str(run_id), "src_tz": source_tz, "qty_unit": qty_unit, "confirm": "true" if confirm_locations else "false"}
    if not db.scalar("SELECT count(*) FROM apsis_ctl.chunk WHERE vendor_table = '_masters' AND state = 'verified'") == "1":
        gone = [t for t in ("master_outlets", "master_routes", "master_sku") if t in missing]
        if gone:
            raise DbError("masters: a canonical column has no source in " + ", ".join(gone) + ": edit mapping/columns.json")
        run_sql_file(db, "10_masters_map.sql", vars_)
        _masters_hard_stop(db, run_id)
        if fill_locations:
            if "master_geo_config" in missing:
                log("fill-locations skipped: master_geo_config has no source for " + ",".join(missing["master_geo_config"]))
            else:
                run_sql_file(db, "15_fill_locations.sql", vars_)
        ctl_set(db, "_masters", "_masters", state="verified", run_id=run_id)
    if (c := ctl_get(db, "master_outlet_section_mapping", "_stage")) and c["state"] == "staged" and "master_outlet_section_mapping" not in missing:
        run_sql_file(db, "24_outlet_route.sql", vars_)
    status = {}
    plan = [(t, f, h) for t, f, h in FACTS] + [(t, "25_raw_archive.sql", "raw_archive") for t in raw_tables(mapping)]
    plan.sort(key=lambda x: order_key(x[0]))
    for t, sqlf, hist in plan:
        if only and t not in only:
            continue
        stg = ctl_get(db, t, "_stage")
        if not stg or stg["state"] != "staged":
            continue
        if t in missing:
            ctl_set(db, t, "_stage", state="failed", error="missing column: " + ",".join(missing[t]))
            log(f"transform {t}: stopped, no source for canonical column(s) {','.join(missing[t])} (not listed as intentionally absent): edit mapping/columns.json")
            status[t] = "missing:" + ",".join(missing[t])
            continue
        for row in db.run("SELECT chunk_key, state, rows_in FROM apsis_ctl.chunk WHERE vendor_table = :'t' AND chunk_key <> '_stage' ORDER BY 1", {"t": t}).split():
            ch, state, rows_in = row.split("|")
            if state == "verified":
                continue
            # the staged rows must still be the rows recorded at staging (a loss is detected here too, never verified as an empty chunk)
            have = int(db.scalar(f"SELECT count(DISTINCT _row_uuid) FROM {STAGE}.{safety.quote(t)} WHERE _chunk = :'c'", {"c": ch}) or 0)
            if have != int(rows_in):
                ctl_set(db, t, ch, state="failed", error=f"staging holds {have} rows, {rows_in} recorded", run_id=run_id)
                ctl_set(db, t, "_stage", state="pending", stmt_ordinal=0, rows_parsed=0)         # the next run re-stages this table from the dump
                raise DbError(f"{t} chunk {ch}: staged rows ({have}) differ from the recorded count ({rows_in}); re-run to re-stage")
            hd = bool(has_date.get(t))
            try:
                run_sql_file(db, sqlf, dict(vars_, chunk=ch, vt=t, has_date="true" if hd else "false"), raw_table=t if sqlf.startswith("25") else None)
                ctl_set(db, t, ch, state="transformed", run_id=run_id)
                a = {k: int(v) for k, v in metrics_stage(db, t, ch, mapping, qty_unit, hd).items()}
                b = {k: int(v) for k, v in metrics_hist(db, t, hist, ch).items()}
                a = {k: a[k] for k in b if k in a}
                if a != b:
                    raise DbError(f"{t} chunk {ch}: checksum mismatch (counts or sums differ between staging and archive)")
                skipped = int(rows_in) - b["rows"] - b["rejected"]
                ctl_set(db, t, ch, state="verified", rows_out=b["rows"], rows_skipped=skipped, checksum=b, run_id=run_id)
                log(f"verified {t} {ch}: {b['rows']} rows, {b['rejected']} rejected, {skipped} skipped")
            except DbError as e:
                ctl_set(db, t, ch, state="failed", error=str(e), run_id=run_id)
                raise
        status[t] = "verified"
        if t in BIG and t not in KEEP_STAGING and all(r.split("|")[1] == "verified" for r in db.run("SELECT chunk_key, state FROM apsis_ctl.chunk WHERE vendor_table = :'t' AND chunk_key <> '_stage'", {"t": t}).split()):
            db.run(f"DROP TABLE IF EXISTS {STAGE}.{safety.quote(t)} CASCADE")
            log(f"staging of {t} dropped after verification")
    return status


def _masters_hard_stop(db, run_id):
    """A master table that matched nothing is never loaded through: scope ids would be silently NULL for the whole table."""
    mg = json.loads(db.scalar("SELECT value::text FROM apsis_ctl.report WHERE run_id = :'r'::bigint AND name = 'masters_gap'", {"r": str(run_id)}))
    for k, v, m in (("outlets", "vendor_outlets", "outlets_matched"), ("routes", "vendor_routes", "routes_matched"), ("skus", "vendor_skus", "skus_matched")):
        if mg[v] > 0 and mg[m] == 0:
            raise DbError(f"masters: none of the {mg[v]} vendor {k} matched ours: check the code format and the mapping before loading facts")


# ------------------------------------------------------------------ reconcile, finalize, cleanup
def reconcile(db, run_id, mapping, expected, out_path=None):
    text = (SQL / "reconcile.sql").read_text(encoding="utf-8")
    parts = re.split(r"^-- name: (\w+)\s*$", text, flags=re.M)
    rep = {name: json.loads(db.scalar(body.strip().rstrip(";"))) for name, body in zip(parts[1::2], parts[2::2])}
    rep["masters_gap"] = json.loads(db.scalar("SELECT value::text FROM apsis_ctl.report WHERE name = 'masters_gap' ORDER BY run_id DESC LIMIT 1"))
    rep["rejects_by_table"] = {r.split("|")[0]: int(r.split("|")[1]) for r in db.run("SELECT vendor_table, count(*) FROM apsis_hist.reject GROUP BY 1").split()}
    refused = db.scalar("SELECT COALESCE((value ->> 'n')::int, 0) FROM apsis_ctl.report WHERE name = 'refused_files' ORDER BY run_id DESC LIMIT 1")
    chunks, meta = {}, {}
    for r in db.run("SELECT vendor_table, sum(rows_parsed) FILTER (WHERE chunk_key = '_stage'), sum(rows_in) FILTER (WHERE chunk_key = '_stage'), "
                    "sum(rows_in) FILTER (WHERE chunk_key <> '_stage'), sum(rows_out) FILTER (WHERE chunk_key <> '_stage'), sum(rows_skipped) FILTER (WHERE chunk_key <> '_stage'), "
                    "bool_and(state = 'verified') FILTER (WHERE chunk_key <> '_stage'), bool_or(state = '_x') "
                    "FROM apsis_ctl.chunk WHERE vendor_table NOT LIKE '\\_%' GROUP BY 1 ORDER BY 1").split("\n"):
        if not r:
            continue
        t, parsed, rin, crin, rout, rskip, allv, _ = r.split("|")
        chunks[t] = {"rows_parsed": int(parsed or 0), "rows_in": int(rin or 0), "chunk_rows_in": int(crin or 0), "rows_out": int(rout or 0),
                     "rows_skipped": int(rskip or 0), "all_verified": allv == "t"}
        meta[t] = json.loads(db.scalar("SELECT COALESCE(checksum::text, '{}') FROM apsis_ctl.chunk WHERE vendor_table = :'t' AND chunk_key = '_stage'", {"t": t}) or "{}")
    cov = {}
    for r in db.run("SELECT value::text FROM apsis_ctl.report WHERE name = 'mapping_coverage' ORDER BY run_id").split("\n"):
        if r:
            cov.update(json.loads(r))
    out = gates.evaluate(rep, chunks, meta, expected, mapping, refused_files=int(refused or 0), coverage=cov)
    payload = json.dumps(out, indent=1, sort_keys=True)
    if out_path:
        Path(out_path).write_text(payload + "\n", encoding="utf-8")
    db.run("INSERT INTO apsis_ctl.report (run_id, name, value) VALUES (:'r'::bigint, 'reconciliation', :'v'::jsonb) ON CONFLICT (run_id, name) DO UPDATE SET value = EXCLUDED.value, created_at = now()",
           {"r": str(run_id), "v": payload})
    return out


def finalize(db):
    """After the load: read indexes, autovacuum back on, ANALYZE (a SECURITY DEFINER function owned by the migrate role: the job role owns no archive table)."""
    db.run("SELECT apsis_hist.finish_load()")


REQUIRED_SIGNOFF = [f"q{n}" for n in range(21, 34)]


def cleanup(db, signoff):
    """After sign-off: drops the staging copy. Refuses unless the latest reconciliation is ok AND the owner's sign-off file lists every opening-balance
    question (Q21-Q33) as answered or waived. The dump itself is deleted only after this (runbook). No hand edits of apsis_ctl are ever needed."""
    so = json.loads(Path(signoff).read_text(encoding="utf-8")) if signoff else {}
    bad = [q for q in REQUIRED_SIGNOFF if so.get(q) not in ("answered", "waived")]
    if bad or not str(so.get("signed_by", "")).strip():
        raise DbError("cleanup refused: the owner's sign-off file must list " + ", ".join(REQUIRED_SIGNOFF) + " as answered or waived and name who signed")
    # the passing report must describe the CURRENT state: it belongs to the latest run and is newer than every checkpoint change; a later run, a
    # re-transform or a rollback invalidates it
    ok = db.scalar("SELECT COALESCE((value ->> 'ok')::boolean, false) AND run_id = (SELECT max(id) FROM apsis_ctl.run) "
                   "AND created_at >= COALESCE((SELECT max(GREATEST(started_at, finished_at)) FROM apsis_ctl.chunk), '-infinity'::timestamptz) "
                   "FROM apsis_ctl.report WHERE name = 'reconciliation' ORDER BY run_id DESC LIMIT 1")
    if ok != "t":
        raise DbError("cleanup refused: no passing reconciliation report for the current run state (a later run, reset or rollback invalidates an earlier report)")
    for kind, q in (("VIEW", "SELECT quote_ident(table_name) FROM information_schema.views WHERE table_schema = 'apsis_stage'"),
                    ("TABLE", "SELECT quote_ident(table_name) FROM information_schema.tables WHERE table_schema = 'apsis_stage' AND table_type = 'BASE TABLE'")):
        for name in db.run(q).split():
            db.run(f"DROP {kind} IF EXISTS {STAGE}.{name} CASCADE")


def reset_transform(db, mapping):
    """Settings changed or a re-transform is wanted: chunks go back to 'staged' where staging still exists, else the table is re-staged."""
    for t in sorted(mapping["allowed_tables"]):
        if _stage_count(db, t) is not None:
            db.run("UPDATE apsis_ctl.chunk SET state = 'staged', rows_out = 0, rows_skipped = 0, checksum = NULL WHERE vendor_table = :'t' AND chunk_key <> '_stage'", {"t": t})
        else:
            db.run("DELETE FROM apsis_ctl.chunk WHERE vendor_table = :'t'", {"t": t})
    db.run("DELETE FROM apsis_ctl.chunk WHERE vendor_table = '_masters'")


def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(prog="apsis-migration")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("profile", "run", "reconcile", "finalize", "cleanup", "reset-transform"):
        s = sub.add_parser(name)
        s.add_argument("--dsn", help="libpq connection string WITHOUT a password (use PGPASSWORD / an Entra token in the environment)")
        s.add_argument("--db-role", help="SET ROLE for the session: apsis_migrator")
        s.add_argument("--dump-dir")
        s.add_argument("--mapping", default=str(HERE / "mapping" / "columns.json"))
        s.add_argument("--review", help="reviewed column list (mapping/column_review.json)")
        s.add_argument("--expected-counts", default=str(HERE / "mapping" / "expected_counts.json"))
        s.add_argument("--source-tz", default=None, help="REQUIRED for run: UTC or Asia/Dhaka, decided from the dry run's evidence and signed by the owner")
        s.add_argument("--charset", default="utf-8", help="character set of the dump; the file is read strictly and a decode failure stops the run (Q2)")
        s.add_argument("--qty-unit", choices=["base", "pack"], default="base")
        s.add_argument("--fill-locations", action="store_true")
        s.add_argument("--confirm-locations", action="store_true")
        s.add_argument("--only", nargs="*")
        s.add_argument("--dry-run", action="store_true")
        s.add_argument("--out")
        s.add_argument("--run-id", type=int)
        s.add_argument("--out-dir", help="with profile: directory OUTSIDE the repository for the four profile files")
        s.add_argument("--refs", default=str(HERE / "mapping" / "profile_refs.json"))
        s.add_argument("--cap", type=int, default=100000, help="with profile: distinct-value cap per column (memory bound)")
        s.add_argument("--signoff", help="with cleanup: the owner's sign-off file (Q21-Q33 answered or waived)")
    a = ap.parse_args(argv)
    mapping = staging.load_mapping(a.mapping)
    expected = json.loads(Path(a.expected_counts).read_text())["rows"] if Path(a.expected_counts).exists() else {}
    if a.cmd == "profile":
        from . import profiler
        if not a.out_dir:
            sys.exit("refused: --out-dir is required (outside the repository)")
        try:
            profiler.check_out_dir(a.out_dir)
        except safety.Refused as e:
            sys.exit(str(e))
        full = profiler.profile_dump(a.dump_dir, mapping, profiler.load_refs(a.refs), charset=a.charset, cap=a.cap, order=ORDER, log=lambda m: print(m, file=sys.stderr, flush=True))
        profiler.write_outputs(a.out_dir, full)
        print(f"profile: {len(full['tables'])} table(s), {len(full['refused'])} refused, {len(full['tables_missing'])} missing; written to the output directory")
        for r in full["refused"]:
            print(f"REFUSED {r['table']}: {r['refused']}", file=sys.stderr)
        return 0 if full["ok"] else 3
    if os.environ.get("APSIS_PHASE") != "2":
        sys.exit("refused: phase 1 is PRESERVE + PROFILE only; the transform, reconciliation, cleanup and finalize are parked until the lead enables phase 2")
    if a.cmd in ("run", "reconcile") and a.source_tz and a.source_tz not in ALLOWED_TZ:
        sys.exit("refused: --source-tz must be one of " + ", ".join(sorted(ALLOWED_TZ)))
    if a.dsn and re.search(r"password\s*=", a.dsn, re.I):
        sys.exit("refused: the connection string must not contain a password (use PGPASSWORD or an Entra token in the environment)")
    if a.db_role != "apsis_migrator":
        sys.exit("refused: pass --db-role apsis_migrator (the tool never defaults to an administrator login)")
    if a.cmd == "run" and not a.source_tz:
        sys.exit("refused: --source-tz is required (UTC or Asia/Dhaka); there is no default: decide it from the dry run's evidence and have the owner sign it")
    db = Db(a.dsn, role=a.db_role, sync_commit_off=(a.cmd == "run"))
    if db.scalar("SELECT current_user = 'apsis_migrator' AND NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN (current_user, session_user) AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb))") != "t":
        sys.exit("refused: the session is not running as the restricted role apsis_migrator, or the login holds superuser/createrole/bypassrls rights")
    if a.cmd == "finalize":
        finalize(db); return 0
    if a.cmd == "cleanup":
        cleanup(db, a.signoff); log("staging dropped"); return 0
    if a.cmd == "reset-transform":
        reset_transform(db, mapping); return 0
    if a.cmd == "reconcile":
        rep = reconcile(db, a.run_id or int(db.scalar("SELECT max(id) FROM apsis_ctl.run")), mapping, expected, a.out)
        print("reconciliation ok" if rep["ok"] else "reconciliation FAILED: " + "; ".join(rep["problems"]))
        return 0 if rep["ok"] else 2
    review = staging.load_review(a.review)
    tz = a.source_tz
    run_id = start_run(db, tz, a.confirm_locations, a.qty_unit, note="only=" + ",".join(a.only) if a.only else "")
    only = set(a.only) if a.only else None
    # small tables and masters first, then one big table at a time (stage, transform, verify, drop its staging; the sales staging stays for the reconciliation)
    files, _ = find_files(a.dump_dir, set(mapping["allowed_tables"]))
    ordered = sorted(files, key=order_key)
    st = {}
    for group in [[t for t in ordered if t not in BIG]] + [[t] for t in ordered if t in BIG]:
        group = [t for t in group if not only or t in only]
        if not group:
            continue
        stage_all(db, a.dump_dir, mapping, review, run_id, set(group), charset=a.charset)
        st.update(transform_all(db, mapping, run_id, tz, a.qty_unit, a.fill_locations, a.confirm_locations, set(group)))
    db.run("UPDATE apsis_ctl.run SET finished_at = now() WHERE id = :'r'::bigint", {"r": str(run_id)})
    log("run %d done: %s" % (run_id, ", ".join(f"{k}={v}" for k, v in st.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
