"""Orchestration: profile (dry run), stage, transform, verify, reconcile, finalize, cleanup. Counts and checksums only; never a data row."""
import json
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
RAW = ["trans_by_outlet_promotion_data", "master_sr_target_scope", "master_sr_targe_configuration", "master_promotion"]
FACT_TABLES = {t for t, _, _ in FACTS} | set(RAW)
KEEP_STAGING = {"trans_sales_by_outlet", "master_outlet_section_mapping"}       # read again by reconcile.sql
ORDER = ["master_locations", "master_dep", "master_routes", "master_sku", "master_field_forces", "master_outlets", "master_outlet_section_mapping",
         "master_geo_config", "master_ff_attendance_records", "trans_section_summary", "master_sr_target_scope", "master_sr_targe_configuration",
         "master_promotion", "trans_by_outlet_promotion_data", "live_geo_data_by_outlets", "trans_sales_by_outlet"]
STATE_RANK = {"pending": 0, "staged": 1, "transformed": 2, "verified": 3, "failed": -1}
STAGE = "apsis_stage"


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


def profile(dump_dir, mapping, max_key_check_rows=5_000_000, expected=None):
    """Counts only: rows per table, per chunk, null counts, zero dates, column names with a suggested keep/drop class, the hour-of-day
    histogram of the date column (to choose --source-tz), key duplicates (small tables), rows against the vendor's count."""
    allowed = set(mapping["allowed_tables"])
    files, refused = find_files(dump_dir, allowed)
    rep = {"tables": {}, "files_refused_not_on_allow_list": refused, "tables_missing": sorted(allowed - set(files))}
    for table, path in sorted(files.items(), key=lambda kv: order_key(kv[0])):
        t0 = time.time()
        ts, rows, per_chunk, nulls, zero, seen, dup, bad = None, 0, {}, {}, 0, set(), 0, 0
        cd_idx = key_idx = None
        cols, hours = [], [0] * 24
        for ev in events(path):
            if ev[0] == "schema":
                ts = ev[1]
                cols = ts.column_names()
                nulls = {c: 0 for c in cols}
                cands = mapping["tables"].get(table, {}).get("chunk_date", [])
                cd = staging.pick(cands, cols) if cands else None
                cd_idx = cols.index(cd) if cd else None
                key_idx = [cols.index(p) for p in ts.pk] if ts.pk else None
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
                ch = staging.chunk_of(r[cd_idx]) if cd_idx is not None else "all"
                per_chunk[ch] = per_chunk.get(ch, 0) + 1
                if cd_idx is not None and r[cd_idx] and not r[cd_idx].startswith("0000") and len(r[cd_idx]) >= 19 and r[cd_idx][10] in " T" and r[cd_idx][11:13].isdigit():
                    hours[int(r[cd_idx][11:13]) % 24] += 1
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
                "undecodable_rows": bad, "has_primary_key": bool(ts.pk),
                "duplicate_keys": dup if key_idx is not None and rows <= max_key_check_rows else None,
                "float_money_columns": [c.name for c in ts.columns if c.base in ("float", "double", "real") and re.search(r"amount|price|value|total|mrp|tp", c.name, re.I)],
                "hour_histogram_stored": hours if sum(hours) else None, "time_zone_hint": _hour_shares(hours), "seconds": round(time.time() - t0, 1)}
        rep["tables"][table] = info
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
def _spec(v):
    return v if isinstance(v, dict) else {"direct": v}


def build_views(db, mapping, stage=STAGE):
    """apsis_stage.c_<canonical> over each mapped table; returns {table: [required canonical columns with no source]}."""
    missing = {}
    have = set(db.run("SELECT table_name FROM information_schema.tables WHERE table_schema = :'s'", {"s": stage}).split())
    cols_of = {}

    def cols(t):
        if t not in cols_of:
            cols_of[t] = [c for c in db.run("SELECT column_name FROM information_schema.columns WHERE table_schema = :'s' AND table_name = :'t'",
                                            {"s": stage, "t": t}).split("\n") if c] if t in have else []
        return cols_of[t]
    for table, cfg in mapping["tables"].items():
        safety.table(table, set(mapping["allowed_tables"]))
        canon = safety.ident(cfg["canonical"])
        avail = cols(table)
        raw = canon.endswith("_raw")
        if raw:
            cd = staging.pick(cfg.get("chunk_date", []), avail)
            dexp = f"{safety.quote(cd)}::text" if cd else "NULL::text"
            src = (f"SELECT {dexp} AS d, to_jsonb(s) - '_row_uuid' - '_chunk' - '_stmt' AS j, _row_uuid, _chunk FROM {stage}.{safety.quote(table)} s" if table in have
                   else "SELECT NULL::text AS d, NULL::jsonb AS j, NULL::uuid AS _row_uuid, NULL::text AS _chunk WHERE false")
            db.run(f"DROP VIEW IF EXISTS {stage}.c_{canon}; CREATE VIEW {stage}.c_{canon} AS {src}")
            continue
        sel, joins, miss, aliases = [], [], [], []
        for n, (c, spec) in enumerate(cfg.get("columns", {}).items()):
            safety.ident(c, "canonical column")
            sp = _spec(spec)
            aliases.append(c)
            v = staging.pick(sp.get("direct", []), avail)
            if v:
                sel.append(f"f.{safety.quote(v)} AS {c}")
                continue
            r = sp.get("ref")
            if r and table in have:
                fk, mt = staging.pick(r["fk"], avail), r["table"]
                mk, mv = staging.pick(r["key"], cols(mt)), staging.pick(r["value"], cols(mt))
                if fk and mk and mv:
                    safety.table(mt, set(mapping["allowed_tables"]))
                    joins.append(f"LEFT JOIN (SELECT DISTINCT ON ({safety.quote(mk)}) {safety.quote(mk)} AS k, {safety.quote(mv)} AS v FROM {stage}.{safety.quote(mt)} "
                                 f"ORDER BY {safety.quote(mk)}, _row_uuid) j{n} ON j{n}.k = f.{safety.quote(fk)}")
                    sel.append(f"j{n}.v AS {c}")
                    continue
            sel.append(f"NULL::text AS {c}")
            if c in cfg.get("required", []):
                miss.append(c)
        if table in have:
            src = f"SELECT {', '.join(sel)}, f._row_uuid, f._chunk FROM {stage}.{safety.quote(table)} f {' '.join(joins)}"
        else:
            src = f"SELECT {', '.join('NULL::text AS ' + a for a in aliases)}{', ' if aliases else ''}NULL::uuid AS _row_uuid, NULL::text AS _chunk WHERE false"
        db.run(f"DROP VIEW IF EXISTS {stage}.c_{canon}; CREATE VIEW {stage}.c_{canon} AS {src}")
        if miss and table in have:
            missing[table] = miss
    return missing


def render(text, raw_view=None):
    for ph, canon in PLACEHOLDERS.items():
        text = text.replace(ph, f"{STAGE}.c_{canon}")
    return text.replace("@C_RAW@", f"{STAGE}.c_{raw_view}") if raw_view else text


def run_sql_file(db, name, variables, raw_view=None):
    tmp = Path(tempfile.gettempdir()) / "apsis-migration-sql"
    tmp.mkdir(exist_ok=True)
    f = tmp / ("r_" + name)
    f.write_text(render((SQL / name).read_text(encoding="utf-8"), raw_view), encoding="utf-8")
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
    """Refuses when verified chunks exist from a run with other settings: the archive would hold a mix."""
    prev = db.run("SELECT DISTINCT r.source_tz || '|' || r.qty_unit FROM apsis_ctl.chunk c JOIN apsis_ctl.run r ON r.id = c.run_id "
                  "WHERE c.state = 'verified' AND c.vendor_table IN (SELECT unnest(:'facts'::text[]))", {"facts": "{" + ",".join(sorted(FACT_TABLES)) + "}"}).split()
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


def stage_all(db, dump_dir, mapping, review, run_id, only=None, log=log):
    stager = staging.TableStager(db, mapping, review)
    files, refused = find_files(dump_dir, set(mapping["allowed_tables"]))
    if refused:
        log(f"{refused} file(s) not on the allow-list were ignored")
    for table in sorted(files, key=order_key):
        if only and table not in only:
            continue
        chunks = db.run("SELECT chunk_key, state FROM apsis_ctl.chunk WHERE vendor_table = :'t' AND chunk_key <> '_stage'", {"t": table}).split()
        if chunks and all(c.split("|")[1] == "verified" for c in chunks):
            log(f"stage {table}: all chunks verified, nothing to do")
            continue
        st = ctl_get(db, table, "_stage")
        staged = _stage_count(db, table)
        if st and st["state"] == "staged":
            # the staged table must still hold exactly the rows recorded when it was staged (a restore, failover, manual drop or truncation)
            per = {r.split("|")[0]: int(r.split("|")[1]) for r in db.run(f"SELECT _chunk, count(DISTINCT _row_uuid) FROM {STAGE}.{safety.quote(table)} GROUP BY 1").split()} if staged is not None else {}
            rec = {r.split("|")[0]: int(r.split("|")[1]) for r in db.run("SELECT chunk_key, rows_in FROM apsis_ctl.chunk WHERE vendor_table = :'t' AND chunk_key <> '_stage'", {"t": table}).split()}
            if staged == st["rows_in"] and per == rec:
                log(f"stage {table}: already staged and intact")
                continue
            log(f"stage {table}: staged rows no longer match the control table (staged {staged}, recorded {st['rows_in']}): re-staging from the dump")
            _reset_stage(db, table)
            st = None
        elif st and (staged is None):
            _reset_stage(db, table)
            st = None
        skip, base = (st["stmt_ordinal"], st["rows_parsed"]) if st else (0, 0)
        if not st:
            _reset_stage(db, table)

        def cp(ordinal, parsed, _t=table):
            ctl_set(db, _t, "_stage", state="pending", stmt_ordinal=ordinal, rows_parsed=parsed, run_id=run_id)
        info = stager.stage_file(files[table], table, checkpoint=cp, skip_statements=skip, parsed_before=base, tsv=str(files[table]).endswith((".tsv", ".tsv.gz")))
        total = _stage_count(db, table) or 0
        meta = {"has_pk": info["has_pk"], "key_cols": info["key_cols"], "dropped_columns": info["dropped_columns"], "undecodable_rows": info["undecodable_rows"],
                "is_fact": table in FACT_TABLES}
        ctl_set(db, table, "_stage", state="staged", rows_in=total, rows_parsed=info["rows_parsed"], checksum=meta, run_id=run_id)
        for r in db.run(f"SELECT _chunk, count(DISTINCT _row_uuid) FROM {STAGE}.{safety.quote(table)} GROUP BY 1").split():
            ch, n = r.split("|")
            ctl_set(db, table, ch, state="staged", rows_in=int(n), run_id=run_id)
        db.run("INSERT INTO apsis_ctl.report (run_id, name, value) VALUES (:'r'::bigint, :'n', :'v'::jsonb) ON CONFLICT (run_id, name) DO UPDATE SET value = EXCLUDED.value",
               {"r": str(run_id), "n": "dropped_columns:" + table, "v": json.dumps({"dropped": info["dropped_columns"]})})
        log(f"stage {table}: {info['rows_parsed']} rows read, {total} distinct, columns dropped by the review: {len(info['dropped_columns'])}")


# ------------------------------------------------------------------ transform and verify
def metrics_stage(db, table, chunk, mapping, qty_unit):
    safety.chunk(chunk)
    canon = mapping["tables"][table]["canonical"]
    h = "sum(('x'||substr(md5(_row_uuid::text),1,15))::bit(60)::bigint)::numeric"
    v = {"c": chunk, "qu": qty_unit}
    base = "SELECT DISTINCT ON (_row_uuid) * FROM " + f"{STAGE}.c_{canon} WHERE _chunk = :'c' "
    if canon == "sales":
        q = (f"SELECT jsonb_build_object('rows', count(*), 'hash', COALESCE({h},0), "
             f"'qty_base', COALESCE(sum(round(COALESCE(apsis_map.num(qty::text),0) * CASE WHEN :'qu'='pack' THEN COALESCE((SELECT base_per_pack FROM apsis_map.sku_map k WHERE k.code_norm=apsis_map.norm_code(c.sku_code)),1) ELSE 1 END)),0), "
             f"'gross_mtk', COALESCE(sum(round(COALESCE(apsis_map.num(amount::text),0) * 1000)),0)) FROM ({base} AND outlet_code IS NOT NULL AND btrim(outlet_code::text) <> '' "
             f"AND (qty IS NULL OR btrim(qty::text)='' OR apsis_map.num(qty::text) IS NOT NULL) AND (amount IS NULL OR btrim(amount::text)='' OR apsis_map.num(amount::text) IS NOT NULL) "
             f"AND apsis_map.business_date(sale_date::text, (SELECT source_tz FROM apsis_ctl.run ORDER BY id DESC LIMIT 1)) IS NOT NULL ORDER BY _row_uuid) c")
    elif canon in ("visit_geo", "attendance", "section_summary"):
        col = {"visit_geo": "captured_at", "attendance": "check_in_at", "section_summary": "summary_date"}[canon]
        key = {"visit_geo": "outlet_code", "attendance": "user_code", "section_summary": "route_code"}[canon]
        usable = (f"apsis_map.mysql_date({col}::text) IS NOT NULL" if canon == "section_summary"
                  else f"apsis_map.mysql_ts({col}::text, (SELECT source_tz FROM apsis_ctl.run ORDER BY id DESC LIMIT 1)) IS NOT NULL")
        q = (f"SELECT jsonb_build_object('rows', count(*), 'hash', COALESCE({h},0)) FROM ({base} AND {key} IS NOT NULL AND btrim({key}::text) <> '' AND {usable} ORDER BY _row_uuid) c")
    else:
        q = f"SELECT jsonb_build_object('rows', count(*), 'hash', COALESCE({h},0)) FROM ({base} ORDER BY _row_uuid) c"
    return json.loads(db.scalar(q, v))


def metrics_hist(db, hist, chunk, vt=None):
    safety.chunk(chunk)
    h = "sum(('x'||substr(md5(row_uuid::text),1,15))::bit(60)::bigint)::numeric"
    extra = ", 'qty_base', COALESCE(sum(qty_base),0), 'gross_mtk', COALESCE(sum(gross_mtk),0)" if hist == "sales_line" else ""
    if hist not in ("sales_line", "visit_geo", "attendance", "route_day_vendor", "raw_archive"):
        raise safety.Refused("refused: unknown archive table")
    where = "chunk_key = :'c'" + (" AND vendor_table = :'vt'" if vt else "")
    return json.loads(db.scalar(f"SELECT jsonb_build_object('rows', count(*), 'hash', COALESCE({h},0){extra}) FROM apsis_hist.{hist} WHERE {where}", {"c": chunk, "vt": vt or ""}))


def transform_all(db, mapping, run_id, source_tz, qty_unit, fill_locations=False, confirm_locations=False, only=None, log=log):
    missing = build_views(db, mapping)
    vars_ = {"run_id": str(run_id), "src_tz": source_tz, "qty_unit": qty_unit, "confirm": "true" if confirm_locations else "false"}
    if not db.scalar("SELECT count(*) FROM apsis_ctl.chunk WHERE vendor_table = '_masters' AND state = 'verified'") == "1":
        run_sql_file(db, "10_masters_map.sql", vars_)
        _masters_hard_stop(db, run_id)
        if fill_locations:
            if "master_geo_config" in missing:
                log("fill-locations skipped: master_geo_config has no source for " + ",".join(missing["master_geo_config"]))
            else:
                run_sql_file(db, "15_fill_locations.sql", vars_)
        ctl_set(db, "_masters", "_masters", state="verified", run_id=run_id)
        facts_and_raw = "{" + ",".join(sorted({"master_ff_attendance_records"} | set(RAW))) + "}"
        db.run("UPDATE apsis_ctl.chunk SET state = 'verified', rows_out = rows_in, finished_at = now() WHERE vendor_table LIKE 'master\\_%' "
               "AND vendor_table <> ALL (:'fr'::text[]) AND chunk_key <> '_stage' AND state = 'staged'", {"fr": facts_and_raw})
    if (c := ctl_get(db, "master_outlet_section_mapping", "_stage")) and c["state"] == "staged" and "master_outlet_section_mapping" not in missing:
        run_sql_file(db, "24_outlet_route.sql", vars_)
    status = {}
    plan = [(t, f, h) for t, f, h in FACTS] + [(t, "25_raw_archive.sql", "raw_archive") for t in RAW]
    plan.sort(key=lambda x: order_key(x[0]))
    for t, sqlf, hist in plan:
        if only and t not in only:
            continue
        stg = ctl_get(db, t, "_stage")
        if not stg or stg["state"] != "staged":
            continue
        if t in missing:
            ctl_set(db, t, "_stage", state="failed", error="missing column: " + ",".join(missing[t]))
            log(f"transform {t}: stopped, no source for canonical column(s) {','.join(missing[t])}: edit mapping/columns.json")
            status[t] = "missing:" + ",".join(missing[t])
            continue
        for row in db.run("SELECT chunk_key, state, rows_in FROM apsis_ctl.chunk WHERE vendor_table = :'t' AND chunk_key <> '_stage' ORDER BY 1", {"t": t}).split():
            ch, state, rows_in = row.split("|")
            if state == "verified":
                continue
            raw_view = mapping["tables"][t]["canonical"] if sqlf.startswith("25") else None
            # the staged rows must still be the rows recorded at staging (a loss is detected here too, never verified as an empty chunk)
            have = int(db.scalar(f"SELECT count(DISTINCT _row_uuid) FROM {STAGE}.{safety.quote(t)} WHERE _chunk = :'c'", {"c": ch}) or 0)
            if have != int(rows_in):
                ctl_set(db, t, ch, state="failed", error=f"staging holds {have} rows, {rows_in} recorded", run_id=run_id)
                ctl_set(db, t, "_stage", state="pending", stmt_ordinal=0, rows_parsed=0)         # the next run re-stages this table from the dump
                raise DbError(f"{t} chunk {ch}: staged rows ({have}) differ from the recorded count ({rows_in}); re-run to re-stage")
            try:
                run_sql_file(db, sqlf, dict(vars_, chunk=ch, vt=t), raw_view)
                ctl_set(db, t, ch, state="transformed", run_id=run_id)
                a = {k: int(v) for k, v in metrics_stage(db, t, ch, mapping, qty_unit).items()}
                b = {k: int(v) for k, v in metrics_hist(db, hist, ch, vt=t if sqlf.startswith("25") else None).items()}
                a = {k: a[k] for k in b if k in a}
                if a != b:
                    raise DbError(f"{t} chunk {ch}: checksum mismatch (counts or sums differ between staging and archive)")
                ctl_set(db, t, ch, state="verified", rows_out=b["rows"], rows_skipped=int(rows_in) - b["rows"], checksum=b, run_id=run_id)
                log(f"verified {t} {ch}: {b['rows']} rows, {int(rows_in) - b['rows']} skipped")
            except DbError as e:
                ctl_set(db, t, ch, state="failed", error=str(e), run_id=run_id)
                raise
        status[t] = "verified"
        if t not in KEEP_STAGING and all(r.split("|")[1] == "verified" for r in db.run("SELECT chunk_key, state FROM apsis_ctl.chunk WHERE vendor_table = :'t' AND chunk_key <> '_stage'", {"t": t}).split()):
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
    chunks, meta = {}, {}
    for r in db.run("SELECT vendor_table, sum(rows_parsed) FILTER (WHERE chunk_key = '_stage'), sum(rows_in) FILTER (WHERE chunk_key = '_stage'), "
                    "sum(rows_in) FILTER (WHERE chunk_key <> '_stage'), sum(rows_out) FILTER (WHERE chunk_key <> '_stage'), sum(rows_skipped) FILTER (WHERE chunk_key <> '_stage') "
                    "FROM apsis_ctl.chunk WHERE vendor_table NOT LIKE '\\_%' GROUP BY 1 ORDER BY 1").split("\n"):
        if not r:
            continue
        t, parsed, rin, crin, rout, rskip = r.split("|")
        chunks[t] = {"rows_parsed": int(parsed or 0), "rows_in": int(rin or 0), "chunk_rows_in": int(crin or 0), "rows_out": int(rout or 0), "rows_skipped": int(rskip or 0)}
        cs = db.scalar("SELECT COALESCE(checksum::text, '{}') FROM apsis_ctl.chunk WHERE vendor_table = :'t' AND chunk_key = '_stage'", {"t": t})
        meta[t] = json.loads(cs or "{}")
    # master tables that are matched, not transformed: loaded = in (they are accounted for by the masters report)
    out = gates.evaluate(rep, chunks, meta, expected, mapping)
    payload = json.dumps(out, indent=1, sort_keys=True)
    if out_path:
        Path(out_path).write_text(payload + "\n", encoding="utf-8")
    db.run("INSERT INTO apsis_ctl.report (run_id, name, value) VALUES (:'r'::bigint, 'reconciliation', :'v'::jsonb) ON CONFLICT (run_id, name) DO UPDATE SET value = EXCLUDED.value",
           {"r": str(run_id), "v": payload})
    return out


def finalize(db):
    """After the load: read indexes, autovacuum back on, ANALYZE (a SECURITY DEFINER function owned by the migrate role: the job role owns no archive table)."""
    db.run("SELECT apsis_hist.finish_load()")


def cleanup(db):
    """After sign-off: drops the staging copy. Refuses unless the latest reconciliation report is ok. No hand edits of apsis_ctl are ever needed."""
    ok = db.scalar("SELECT COALESCE((value ->> 'ok')::boolean, false) FROM apsis_ctl.report WHERE name = 'reconciliation' ORDER BY run_id DESC LIMIT 1")
    if ok != "t":
        raise DbError("cleanup refused: no passing reconciliation report")
    for kind, q in (("VIEW", "SELECT quote_ident(table_name) FROM information_schema.views WHERE table_schema = 'apsis_stage'"),
                    ("TABLE", "SELECT quote_ident(table_name) FROM information_schema.tables WHERE table_schema = 'apsis_stage' AND table_type = 'BASE TABLE'")):
        for name in db.run(q).split():
            db.run(f"DROP {kind} IF EXISTS {STAGE}.{name} CASCADE")


def reset_transform(db):
    """Settings changed or a re-transform is wanted: fact chunks go back to 'staged' where staging still exists, else the table is re-staged."""
    for t in sorted(FACT_TABLES):
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
        s.add_argument("--db-role", help="SET ROLE for the session, e.g. apsis_migrator")
        s.add_argument("--dump-dir")
        s.add_argument("--mapping", default=str(HERE / "mapping" / "columns.json"))
        s.add_argument("--review", help="reviewed column list (mapping/column_review.json)")
        s.add_argument("--expected-counts", default=str(HERE / "mapping" / "expected_counts.json"))
        s.add_argument("--source-tz", default=None)
        s.add_argument("--qty-unit", choices=["base", "pack"], default="base")
        s.add_argument("--fill-locations", action="store_true")
        s.add_argument("--confirm-locations", action="store_true")
        s.add_argument("--only", nargs="*")
        s.add_argument("--dry-run", action="store_true")
        s.add_argument("--out")
        s.add_argument("--run-id", type=int)
        s.add_argument("--suggest-review", help="with profile: write a suggested column review here")
    a = ap.parse_args(argv)
    mapping = staging.load_mapping(a.mapping)
    expected = json.loads(Path(a.expected_counts).read_text())["rows"] if Path(a.expected_counts).exists() else {}
    tz = a.source_tz or mapping.get("source_tz", "UTC")
    if a.cmd == "profile" or (a.cmd == "run" and a.dry_run):
        rep = profile(a.dump_dir, mapping, expected=expected)
        text = json.dumps(rep, indent=1, sort_keys=True)
        Path(a.out).write_text(text + "\n") if a.out else print(text)
        if a.suggest_review:
            Path(a.suggest_review).write_text(json.dumps(suggest_review(rep), indent=1) + "\n")
        return 0
    if a.db_role != "apsis_migrator":
        sys.exit("refused: pass --db-role apsis_migrator (the tool never defaults to an administrator login)")
    db = Db(a.dsn, role=a.db_role, sync_commit_off=(a.cmd == "run"))
    if db.scalar("SELECT current_user = 'apsis_migrator' AND NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN (current_user, session_user) AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb))") != "t":
        sys.exit("refused: the session is not running as the restricted role apsis_migrator, or the login holds superuser/createrole/bypassrls rights")
    if a.cmd == "finalize":
        finalize(db); return 0
    if a.cmd == "cleanup":
        cleanup(db); log("staging dropped"); return 0
    if a.cmd == "reset-transform":
        reset_transform(db); return 0
    if a.cmd == "reconcile":
        rep = reconcile(db, a.run_id or int(db.scalar("SELECT max(id) FROM apsis_ctl.run")), mapping, expected, a.out)
        print("reconciliation ok" if rep["ok"] else "reconciliation FAILED: " + "; ".join(rep["problems"]))
        return 0 if rep["ok"] else 2
    review = staging.load_review(a.review)
    run_id = start_run(db, tz, a.confirm_locations, a.qty_unit)
    only = set(a.only) if a.only else None
    # masters first (small), then one fact table at a time (stage, transform, verify, drop its staging): peak staging is the masters plus one table
    files, _ = find_files(a.dump_dir, set(mapping["allowed_tables"]))
    ordered = sorted(files, key=order_key)
    masters = [t for t in ordered if t not in FACT_TABLES]
    st = {}
    for group in [masters] + [[t] for t in ordered if t in FACT_TABLES]:
        group = [t for t in group if not only or t in only]
        if not group:
            continue
        stage_all(db, a.dump_dir, mapping, review, run_id, set(group))
        st.update(transform_all(db, mapping, run_id, tz, a.qty_unit, a.fill_locations, a.confirm_locations, set(group)))
    db.run("UPDATE apsis_ctl.run SET finished_at = now() WHERE id = :'r'::bigint", {"r": str(run_id)})
    log("run %d done: %s" % (run_id, ", ".join(f"{k}={v}" for k, v in st.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
