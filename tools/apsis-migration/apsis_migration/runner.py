"""Orchestration: profile (dry run), stage, transform, verify, reconcile. Counts and checksums only; never a data row."""
import json
import re
import sys
import time
from pathlib import Path

from . import staging
from .db import Db, DbError
from .mysqldump import events

HERE = Path(__file__).resolve().parent.parent
SQL = HERE / "sql"
SAFE = re.compile(r"^[A-Za-z0-9_]+$")

# placeholder -> canonical name in mapping/columns.json
PLACEHOLDERS = {"@C_OUTLETS@": "outlets", "@C_ROUTES@": "routes", "@C_SKU@": "sku", "@C_GEO@": "geo_config",
                "@C_SALES@": "sales", "@C_GEO_VISIT@": "visit_geo", "@C_ATT@": "attendance", "@C_SUMMARY@": "section_summary",
                "@C_OUTLET_ROUTE@": "outlet_route"}
FACTS = [  # (vendor table, sql file, hist table, verify spec)
    ("trans_sales_by_outlet", "20_fact_sales.sql", "sales_line"),
    ("live_geo_data_by_outlets", "21_fact_geo.sql", "visit_geo"),
    ("master_ff_attendance_records", "22_fact_attendance.sql", "attendance"),
    ("trans_section_summary", "23_section_summary.sql", "route_day_vendor"),
]
RAW = ["trans_by_outlet_promotion_data", "master_sr_target_scope", "master_sr_targe_configuration", "master_promotion"]
MASTERS = ["master_outlets", "master_routes", "master_sku", "master_geo_config", "master_outlet_section_mapping"]
# load order of the design (docs/status/apsis-migration.md section 4): small masters first, the big facts last
KEEP_STAGING = {"trans_sales_by_outlet", "master_outlet_section_mapping"}   # read again by reconcile.sql
ORDER = ["master_locations", "master_dep", "master_routes", "master_sku", "master_outlets", "master_outlet_section_mapping",
         "master_geo_config", "master_ff_attendance_records", "trans_section_summary", "master_sr_target_scope",
         "master_sr_targe_configuration", "master_promotion", "trans_by_outlet_promotion_data", "live_geo_data_by_outlets",
         "trans_sales_by_outlet"]


def log(msg):
    print(msg, flush=True)


def find_files(dump_dir):
    d = Path(dump_dir)
    files = [p for p in d.iterdir() if p.is_file() and re.search(r"\.(sql|sql\.gz|tsv|tsv\.gz)$", p.name)]
    out = {}
    for p in files:
        stem = re.sub(r"\.(sql|tsv)(\.gz)?$", "", p.name)
        out[stem] = p
    return out


# ---------------------------------------------------------------- dry run / profile (no database at all)
def profile(dump_dir, mapping, max_key_check_rows=5_000_000):
    """Counts only: rows per table, per chunk, null counts, zero dates, credential-looking columns, key duplicates (small tables)."""
    rep = {"tables": {}}
    for stem, path in sorted(find_files(dump_dir).items()):
        t0 = time.time()
        ts, rows, per_chunk, nulls, zero, keys_seen, dup = None, 0, {}, {}, 0, set(), 0
        cfg_cd, cd_idx, key_idx = None, None, None
        cols = []
        for ev in events(path):
            if ev[0] == "schema":
                ts = ev[1]
                cols = ts.column_names()
                nulls = {c: 0 for c in cols}
                cfg = mapping["tables"].get(ts.name, {})
                cd = staging.pick(cfg.get("chunk_date", []), cols)
                cd_idx = cols.index(cd) if cd else None
                key_idx = [cols.index(p) for p in ts.pk] if ts.pk else None
                continue
            for r in ev[2]:
                rows += 1
                for i, v in enumerate(r):
                    if v is None:
                        nulls[cols[i]] += 1
                    elif isinstance(v, str) and v.startswith("0000-00-00"):
                        zero += 1
                ch = staging.chunk_of(r[cd_idx]) if cd_idx is not None else "all"
                per_chunk[ch] = per_chunk.get(ch, 0) + 1
                if key_idx is not None and rows <= max_key_check_rows:
                    k = hash(tuple(r[i] for i in key_idx))
                    if k in keys_seen:
                        dup += 1
                    keys_seen.add(k)
        if ts is None:
            rep["tables"][stem] = {"error": "no CREATE TABLE in file: schema-only profile needed"}
            continue
        rep["tables"][ts.name] = {
            "rows": rows, "columns": len(cols), "chunks": dict(sorted(per_chunk.items())),
            "null_counts": {c: n for c, n in nulls.items() if n},
            "zero_dates": zero,
            "dropped_columns": [c for c in cols if staging.is_dropped(c)],
            "has_primary_key": bool(ts.pk), "duplicate_keys": dup if key_idx is not None and rows <= max_key_check_rows else None,
            "float_money_columns": [c.name for c in ts.columns if c.base in ("float", "double", "real") and re.search(r"amount|price|value|total|mrp|tp", c.name, re.I)],
            "seconds": round(time.time() - t0, 1)}
    return rep


# ---------------------------------------------------------------- canonical views
def build_views(db, mapping, stage="apsis_stage"):
    """Create apsis_stage.c_<canonical> over each mapped table; returns {table: [missing required canonical columns]}."""
    missing = {}
    have_tables = set(db.run(f"SELECT table_name FROM information_schema.tables WHERE table_schema='{stage}'").split())
    for table, cfg in mapping["tables"].items():
        canon = cfg["canonical"]
        if not SAFE.match(canon):
            raise ValueError("bad canonical name")
        wanted = cfg.get("columns", {})
        if table in have_tables:
            avail = db.run(f"SELECT column_name FROM information_schema.columns WHERE table_schema='{stage}' AND table_name='{table}'").split("\n")
            avail = [a for a in avail if a]
        else:
            avail = []
        sel, miss = [], []
        for c, cands in wanted.items():
            v = staging.pick(cands, avail)
            if v:
                sel.append(f"{staging.qident(v)} AS {c}")
            else:
                sel.append(f"NULL::text AS {c}")
                if c in cfg.get("required", []):
                    miss.append(c)
        if cfg.get("columns") == {} and canon.endswith("_raw") or canon.endswith("_config_raw") or canon.endswith("_scope_raw"):
            cd = staging.pick(cfg.get("chunk_date", []), avail)
            dexp = f"{staging.qident(cd)}::text" if cd else "NULL::text"
            if table in have_tables:
                src = (f"SELECT {dexp} AS d, to_jsonb(s) - '_row_uuid' - '_chunk' - '_stmt' AS j, _row_uuid, _chunk FROM {stage}.{table} s")
            else:
                src = "SELECT NULL::text AS d, NULL::jsonb AS j, NULL::uuid AS _row_uuid, NULL::text AS _chunk WHERE false"
            db.run(f"CREATE OR REPLACE VIEW {stage}.c_{canon} AS {src}")
        else:
            if table in have_tables:
                src = f"SELECT {', '.join(sel)}, _row_uuid, _chunk FROM {stage}.{table}"
            else:
                src = f"SELECT {', '.join(sel)}, NULL::uuid AS _row_uuid, NULL::text AS _chunk WHERE false"
            db.run(f"CREATE OR REPLACE VIEW {stage}.c_{canon} AS {src}")
        if miss and table in have_tables:
            missing[table] = miss
    return missing


def render(sql_text, stage="apsis_stage", raw_view=None):
    for ph, canon in PLACEHOLDERS.items():
        sql_text = sql_text.replace(ph, f"{stage}.c_{canon}")
    if raw_view:
        sql_text = sql_text.replace("@C_RAW@", f"{stage}.c_{raw_view}")
    return sql_text


def run_sql_file(db, name, variables, raw_view=None, tmp_dir=None):
    text = render((SQL / name).read_text(encoding="utf-8"), raw_view=raw_view)
    import tempfile
    tmp = Path(tmp_dir or Path(tempfile.gettempdir()) / "apsis-migration-sql")
    tmp.mkdir(exist_ok=True)
    f = tmp / ("r_" + name)
    f.write_text(text, encoding="utf-8")
    return db.file(f, variables)


# ---------------------------------------------------------------- control table
def ctl_set(db, table, chunk, **kw):
    cols = {"state": "state", "rows_in": "rows_in", "rows_out": "rows_out", "stmt_ordinal": "stmt_ordinal", "error": "error"}
    sets, ins_c, ins_v = [], ["vendor_table", "chunk_key"], [f"'{table}'", f"'{chunk}'"]
    for k, v in kw.items():
        if k == "checksum":
            lit = "'" + json.dumps(v).replace("'", "''") + "'::jsonb"
            sets.append(f"checksum = {lit}"); ins_c.append("checksum"); ins_v.append(lit)
        elif k == "run_id":
            sets.append(f"run_id = {int(v)}"); ins_c.append("run_id"); ins_v.append(str(int(v)))
        elif k in cols:
            lit = "NULL" if v is None else (f"'{str(v)[:300].replace(chr(39), chr(39)*2)}'" if isinstance(v, str) else str(int(v)))
            sets.append(f"{k} = {lit}"); ins_c.append(k); ins_v.append(lit)
    if kw.get("state") in ("transformed", "verified", "failed"):
        sets.append("finished_at = now()")
    else:
        sets.append("started_at = COALESCE(apsis_ctl.chunk.started_at, now())")
    db.run(f"INSERT INTO apsis_ctl.chunk ({', '.join(ins_c)}) VALUES ({', '.join(ins_v)}) "
           f"ON CONFLICT (vendor_table, chunk_key) DO UPDATE SET {', '.join(sets)}")


def ctl_get(db, table, chunk):
    out = db.run(f"SELECT state, stmt_ordinal, rows_in FROM apsis_ctl.chunk WHERE vendor_table='{table}' AND chunk_key='{chunk}'").strip()
    if not out:
        return None
    s, o, r = out.split("|")
    return {"state": s, "stmt_ordinal": int(o), "rows_in": int(r)}


STATE_RANK = {"pending": 0, "staged": 1, "transformed": 2, "verified": 3, "failed": -1}


# ---------------------------------------------------------------- run
def stage_all(db, dump_dir, mapping, run_id, only=None, log=log):
    stager = staging.TableStager(db, mapping)
    files = find_files(dump_dir)
    result = {}
    for stem in sorted(files, key=lambda s: ORDER.index(s) if s in ORDER else 99):
        if only and stem not in only:
            continue
        st = ctl_get(db, stem, "_stage")
        if st and STATE_RANK[st["state"]] >= 1:
            log(f"stage {stem}: already staged, skipped")
            continue
        skip = max(0, st["stmt_ordinal"] - 1) if st else 0     # the statement in flight is redone; duplicates dedupe on _row_uuid
        if skip == 0:
            db.run(f'DROP TABLE IF EXISTS apsis_stage."{stem}"') if SAFE.match(stem) else None
        cp = lambda t, ordinal, n: ctl_set(db, stem, "_stage", state="pending", stmt_ordinal=ordinal, rows_in=n, run_id=run_id)
        tsv = str(files[stem]).endswith((".tsv", ".tsv.gz"))
        info = stager.stage_file(files[stem], table_hint=stem, checkpoint=cp, skip_statements=skip, tsv=tsv)
        t = info["table"]
        total = int(db.scalar(f'SELECT count(DISTINCT _row_uuid) FROM apsis_stage."{t}"') or 0)
        ctl_set(db, t, "_stage", state="staged", rows_in=total, run_id=run_id)
        for ch, n in (r.split("|") for r in db.run(f'SELECT _chunk, count(DISTINCT _row_uuid) FROM apsis_stage."{t}" GROUP BY 1').split()):
            prev = ctl_get(db, t, ch)
            if prev and prev["state"] == "verified" and prev["rows_in"] == int(n):
                continue                      # a verified chunk with the same row count is kept
            ctl_set(db, t, ch, state="staged", rows_in=int(n), run_id=run_id)
        result[t] = {"rows_distinct": total, "dropped_credential_columns": info["dropped_columns"]}
        log(f"stage {t}: {total} distinct rows, {len(info['dropped_columns'])} credential column(s) dropped")
    return result


def metrics_stage(db, table, chunk, mapping, qty_unit):
    cfg = mapping["tables"][table]
    canon = cfg["canonical"]
    h = "sum(('x'||substr(md5(_row_uuid::text),1,15))::bit(60)::bigint)::numeric"
    if canon == "sales":
        mult = "CASE WHEN '%s'='pack' THEN COALESCE((SELECT base_per_pack FROM apsis_map.sku_map k WHERE k.sku_code=btrim(c.sku_code::text)),1) ELSE 1 END" % qty_unit
        q = (f"SELECT jsonb_build_object('rows', count(*), 'hash', COALESCE({h},0), "
             f"'qty_base', COALESCE(sum(round(COALESCE(NULLIF(btrim(qty::text),''),'0')::numeric * {mult})),0), "
             f"'gross_mtk', COALESCE(sum(round(COALESCE(NULLIF(btrim(amount::text),''),'0')::numeric * 1000)),0)) "
             f"FROM (SELECT DISTINCT ON (_row_uuid) * FROM apsis_stage.c_{canon} WHERE _chunk='{chunk}' AND outlet_code IS NOT NULL "
             f"AND sale_date IS NOT NULL AND btrim(sale_date::text) <> '' AND left(btrim(sale_date::text),4) <> '0000' ORDER BY _row_uuid) c")
    else:
        col = {"visit_geo": ("captured_at", "outlet_code"), "attendance": ("check_in_at", "user_code"),
               "section_summary": ("summary_date", "route_code")}.get(canon)
        if col:
            q = (f"SELECT jsonb_build_object('rows', count(*), 'hash', COALESCE({h},0)) FROM (SELECT DISTINCT ON (_row_uuid) * "
                 f"FROM apsis_stage.c_{canon} WHERE _chunk='{chunk}' AND {col[1]} IS NOT NULL AND {col[0]} IS NOT NULL "
                 f"AND btrim({col[0]}::text) <> '' AND left(btrim({col[0]}::text),4) <> '0000' ORDER BY _row_uuid) c")
        else:
            q = (f"SELECT jsonb_build_object('rows', count(*), 'hash', COALESCE({h},0)) FROM (SELECT DISTINCT ON (_row_uuid) * "
                 f"FROM apsis_stage.c_{canon} WHERE _chunk='{chunk}' ORDER BY _row_uuid) c")
    return json.loads(db.scalar(q))


def metrics_hist(db, hist, chunk, vt=None):
    h = "sum(('x'||substr(md5(row_uuid::text),1,15))::bit(60)::bigint)::numeric"
    extra = ", 'qty_base', COALESCE(sum(qty_base),0), 'gross_mtk', COALESCE(sum(gross_mtk),0)" if hist == "sales_line" else ""
    where = f"chunk_key='{chunk}'" + (f" AND vendor_table='{vt}'" if vt else "")
    return json.loads(db.scalar(f"SELECT jsonb_build_object('rows', count(*), 'hash', COALESCE({h},0){extra}) FROM apsis_hist.{hist} WHERE {where}"))


def norm(m):
    return {k: int(v) for k, v in m.items()}


def transform_all(db, mapping, run_id, source_tz, qty_unit, fill_locations=False, confirm_locations=False, only=None, log=log):
    missing = build_views(db, mapping)
    base_vars = {"run_id": str(run_id), "src_tz": source_tz, "qty_unit": qty_unit, "confirm": "true" if confirm_locations else "false"}
    # masters (match, never create) once per run
    m = ctl_get(db, "_masters", "all")
    if not (m and m["state"] == "verified"):
        run_sql_file(db, "10_masters_map.sql", base_vars)
        if fill_locations:
            if "master_geo_config" in missing:
                log("fill-locations skipped: master_geo_config is missing " + ",".join(missing["master_geo_config"]))
            else:
                run_sql_file(db, "15_fill_locations.sql", base_vars)
        ctl_set(db, "_masters", "all", state="verified", run_id=run_id)
        # master tables are matched, not transformed in chunks: once matched they are accounted for (rows_out = rows_in)
        facts_and_raw = "', '".join(["master_ff_attendance_records"] + RAW)
        db.run("UPDATE apsis_ctl.chunk SET state = 'verified', rows_out = rows_in, finished_at = now() "
               "WHERE vendor_table LIKE 'master\\_%' AND vendor_table NOT IN ('" + facts_and_raw + "') "
               "AND chunk_key <> '_stage' AND state = 'staged'")
    if (c := ctl_get(db, "master_outlet_section_mapping", "_stage")) and c["state"] != "pending" and "master_outlet_section_mapping" not in missing:
        run_sql_file(db, "24_outlet_route.sql", base_vars)
    status = {}
    plan = [(t, f, h) for t, f, h in FACTS] + [(t, "25_raw_archive.sql", "raw_archive") for t in RAW]
    plan.sort(key=lambda x: ORDER.index(x[0]))
    for t, sqlf, hist in plan:
        if only and t not in only:
            continue
        stg = ctl_get(db, t, "_stage")
        if not stg or STATE_RANK[stg["state"]] < 1:
            continue
        if t in missing:
            ctl_set(db, t, "_stage", state="failed", error="missing column: " + ",".join(missing[t]))
            log(f"transform {t}: stopped, missing canonical column(s) {','.join(missing[t])}: edit mapping/columns.json")
            status[t] = "missing:" + ",".join(missing[t])
            continue
        chunks = [r.split("|") for r in db.run(f"SELECT chunk_key, state FROM apsis_ctl.chunk WHERE vendor_table='{t}' AND chunk_key <> '_stage' ORDER BY 1").split()]
        for ch, state in chunks:
            if state == "verified":
                continue
            v = dict(base_vars, chunk=ch, vt=t)
            raw_view = mapping["tables"][t]["canonical"] if sqlf.startswith("25") else None
            try:
                run_sql_file(db, sqlf, v, raw_view=raw_view)
                ctl_set(db, t, ch, state="transformed", run_id=run_id)
                a = norm(metrics_stage(db, t, ch, mapping, qty_unit)) if not sqlf.startswith("25") else None
                if sqlf.startswith("25"):
                    a = norm(metrics_stage(db, t, ch, mapping, qty_unit))
                    b = norm(metrics_hist(db, "raw_archive", ch, vt=t))
                else:
                    b = norm(metrics_hist(db, hist, ch))
                a = {k: a[k] for k in b if k in a}
                if a != b:
                    ctl_set(db, t, ch, state="failed", error=f"checksum mismatch stage={a} hist={b}", run_id=run_id)
                    raise DbError(f"{t} chunk {ch}: checksum mismatch (counts/sums differ)")
                ctl_set(db, t, ch, state="verified", rows_out=b["rows"], checksum=b, run_id=run_id)
                log(f"verified {t} {ch}: {b['rows']} rows")
            except DbError as e:
                ctl_set(db, t, ch, state="failed", error=str(e), run_id=run_id)
                raise
        status[t] = "verified"
        if t not in KEEP_STAGING and not (only and t not in only):
            # every chunk verified: free the disk now (design section 4); sales and the masters stay for the reconciliation
            if all(r.split("|")[1] == "verified" for r in db.run(f"SELECT chunk_key, state FROM apsis_ctl.chunk WHERE vendor_table='{t}' AND chunk_key <> '_stage'").split()):
                db.run(f'DROP TABLE IF EXISTS apsis_stage."{t}" CASCADE')
                log(f"staging of {t} dropped after verification")
    return status


def start_run(db, source_tz, confirm, dry=False, note=""):
    return int(db.scalar(f"INSERT INTO apsis_ctl.run (source_tz, confirm_locations, dry_run, note) VALUES ('{source_tz}', {str(confirm).lower()}, {str(dry).lower()}, '{note}') RETURNING id"))


def reconcile(db, run_id, out_path=None):
    """Run sql/reconcile.sql sections; write numbers-only JSON (and store it in apsis_ctl.report)."""
    text = (SQL / "reconcile.sql").read_text(encoding="utf-8")
    parts = re.split(r"^-- name: (\w+)\s*$", text, flags=re.M)
    rep = {}
    for name, body in zip(parts[1::2], parts[2::2]):
        rep[name] = json.loads(db.scalar(body.strip().rstrip(";")))
    ok = all(v.get("ok", True) for v in rep.values() if isinstance(v, dict))
    rep["ok"] = ok
    payload = json.dumps(rep, indent=1, sort_keys=True)
    if out_path:
        Path(out_path).write_text(payload + "\n", encoding="utf-8")
    lit = payload.replace("'", "''")
    db.run(f"INSERT INTO apsis_ctl.report (run_id, name, value) VALUES ({int(run_id)}, 'reconciliation', '{lit}'::jsonb) "
           f"ON CONFLICT (run_id, name) DO UPDATE SET value = EXCLUDED.value")
    return rep


def finalize(db):
    """After the load: indexes on the archive, autovacuum back on, ANALYZE. Idempotent."""
    stmts = [
        "CREATE INDEX IF NOT EXISTS sales_line_route_day ON apsis_hist.sales_line (route_id, business_date)",
        "CREATE INDEX IF NOT EXISTS sales_line_outlet ON apsis_hist.sales_line (outlet_id, business_date)",
        "CREATE INDEX IF NOT EXISTS visit_geo_outlet ON apsis_hist.visit_geo (outlet_id, business_date)",
        "CREATE INDEX IF NOT EXISTS visit_geo_route_day ON apsis_hist.visit_geo (route_id, business_date)",
        "CREATE INDEX IF NOT EXISTS attendance_user ON apsis_hist.attendance (user_code, business_date)",
    ]
    for s in stmts:
        db.run(s)
    for t in db.run("SELECT inhrelid::regclass::text FROM pg_inherits i JOIN pg_class c ON c.oid=i.inhparent JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='apsis_hist'").split():
        db.run(f"ALTER TABLE {t} SET (autovacuum_enabled = true)")
    db.run("ANALYZE apsis_hist.sales_line; ANALYZE apsis_hist.visit_geo; ANALYZE apsis_hist.attendance")


def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(prog="apsis-migration")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("profile", "run", "reconcile", "finalize"):
        s = sub.add_parser(name)
        s.add_argument("--dsn")
        s.add_argument("--dump-dir")
        s.add_argument("--mapping", default=str(HERE / "mapping" / "columns.json"))
        s.add_argument("--source-tz", default=None)
        s.add_argument("--qty-unit", choices=["base", "pack"], default="base")
        s.add_argument("--fill-locations", action="store_true")
        s.add_argument("--confirm-locations", action="store_true")
        s.add_argument("--only", nargs="*")
        s.add_argument("--dry-run", action="store_true")
        s.add_argument("--out")
        s.add_argument("--run-id", type=int)
    a = ap.parse_args(argv)
    mapping = staging.load_mapping(a.mapping)
    tz = a.source_tz or mapping.get("source_tz", "UTC")
    if a.cmd == "profile" or (a.cmd == "run" and a.dry_run):
        rep = profile(a.dump_dir, mapping)
        text = json.dumps(rep, indent=1, sort_keys=True)
        if a.out:
            Path(a.out).write_text(text + "\n")
        else:
            print(text)
        return 0
    db = Db(a.dsn)
    if a.cmd == "finalize":
        finalize(db)
        return 0
    if a.cmd == "reconcile":
        rep = reconcile(db, a.run_id or int(db.scalar("SELECT max(id) FROM apsis_ctl.run")), a.out)
        print("reconciliation ok" if rep["ok"] else "reconciliation FAILED")
        return 0 if rep["ok"] else 2
    run_id = start_run(db, tz, a.confirm_locations, note="qty_unit=" + a.qty_unit)
    stage_all(db, a.dump_dir, mapping, run_id, set(a.only) if a.only else None)
    st = transform_all(db, mapping, run_id, tz, a.qty_unit, a.fill_locations, a.confirm_locations, set(a.only) if a.only else None)
    db.run(f"UPDATE apsis_ctl.run SET finished_at = now() WHERE id = {run_id}")
    log("run %d done: %s" % (run_id, ", ".join(f"{k}={v}" for k, v in st.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
