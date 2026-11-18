"""Pass/fail decisions of the reconciliation. Numbers in, numbers out: a gap fails the report, it is never printed as ok."""

DEFAULTS = {
    "max_skipped_share": 0.01,              # staged rows with no usable date/number, per table
    "max_unmatched_outlet_rows_share": 0.10,
    "max_unmatched_sku_rows_share": 0.10,
    "max_no_scope_rows_share": 0.02,        # matched outlet but no zone/wing chain
    "min_master_match_share": 0.50,         # distinct normalised vendor codes matched to ours, per master
    "min_time_zone_agreement": 0.80,        # route-days where the vendor's visited count equals our recomputation
    "accept_full_row_key": [],              # tables without a primary key the owner accepted (identical real rows collapse)
}


def thresholds(mapping):
    t = dict(DEFAULTS)
    t.update(mapping.get("thresholds", {}))
    return t


def share(a, b):
    return 0.0 if not b else a / b


def evaluate(rep, chunks, stage_meta, expected, mapping):
    """rep: sections from reconcile.sql + masters_gap; chunks: per table ctl numbers; stage_meta: per table {has_pk,...}; expected: {table: rows}."""
    t = thresholds(mapping)
    problems = []
    out = {"thresholds_used": t}
    # tables: nothing lost between the dump, staging and the archive; vendor totals
    tabs = {}
    for table, c in sorted(chunks.items()):
        e = expected.get(table)
        row = dict(c)
        row["expected_rows"] = e
        row["collapsed_duplicate_rows"] = c["rows_parsed"] - c["rows_in"]
        ok = True
        if c["rows_in"] != c["chunk_rows_in"]:
            ok = False; problems.append(f"{table}: staged rows per chunk ({c['chunk_rows_in']}) differ from the staged total ({c['rows_in']})")
        if c["rows_out"] + c["rows_skipped"] != c["chunk_rows_in"]:
            ok = False; problems.append(f"{table}: loaded + skipped ({c['rows_out'] + c['rows_skipped']}) differ from staged rows ({c['chunk_rows_in']})")
        if share(c["rows_skipped"], c["rows_in"]) > t["max_skipped_share"]:
            ok = False; problems.append(f"{table}: skipped share {share(c['rows_skipped'], c['rows_in']):.4f} above {t['max_skipped_share']}")
        if e is None:
            ok = False; problems.append(f"{table}: no expected row count (mapping/expected_counts.json)")
        elif c["rows_parsed"] != e:
            ok = False; problems.append(f"{table}: rows read from the dump ({c['rows_parsed']}) differ from the vendor's count ({e})")
        meta = stage_meta.get(table, {})
        if meta.get("has_pk") is False and table not in t["accept_full_row_key"] and table in chunks and c["rows_in"] > 0 and meta.get("is_fact"):
            ok = False; problems.append(f"{table}: no primary key, rows keyed by the whole row; owner decision needed (thresholds.accept_full_row_key)")
        row["ok"] = ok
        tabs[table] = row
    out["tables"] = {"ok": all(r["ok"] for r in tabs.values()) and bool(tabs), "by_table": tabs}
    if not tabs:
        problems.append("no table was loaded")
    # money and quantity against the dump
    dm = rep["daily_money"]
    ok = dm["days_differing"] == 0 and dm["max_abs_diff_mtk"] == 0 and dm["total_vendor_mtk"] == dm["total_loaded_mtk"]
    if not ok:
        problems.append(f"daily money: {dm['days_differing']} day(s) differ, max difference {dm['max_abs_diff_mtk']} milli-taka")
    out["daily_money"] = dict(dm, ok=ok)
    sq = rep["sku_qty"]
    ok = sq["skus_differing"] == 0
    if not ok:
        problems.append(f"per-SKU quantity: {sq['skus_differing']} SKU(s) differ")
    out["sku_qty"] = dict(sq, ok=ok)
    # memo numbers
    mn = rep["memo_numbers"]
    ok = mn["numbers_on_more_than_one_outlet"] == 0
    if not ok:
        problems.append(f"memo numbers: {mn['numbers_on_more_than_one_outlet']} number(s) on more than one outlet")
    out["memo_numbers"] = dict(mn, ok=ok)
    # time zone: the vendor's own route-day figures against our recomputation under the chosen zone
    tz = rep["time_zone"]
    agree = share(tz["route_days_equal"], tz["route_days_compared"])
    ok = tz["route_days_compared"] > 0 and agree >= t["min_time_zone_agreement"]
    if not ok:
        problems.append(f"time zone check: {tz['route_days_equal']} of {tz['route_days_compared']} route-days agree with the vendor's summary "
                        f"(needs {t['min_time_zone_agreement']:.2f}); the source time zone or the date column may be wrong")
    out["time_zone"] = dict(tz, agreement=round(agree, 4), ok=ok)
    # masters and scope
    mg, ctl = rep["masters_gap"], rep["control"]
    mok = True
    for k, v, m in (("outlets", "vendor_outlets", "outlets_matched"), ("routes", "vendor_routes", "routes_matched"), ("skus", "vendor_skus", "skus_matched")):
        sh = share(mg[m], mg[v])
        if mg[v] == 0 or sh < t["min_master_match_share"]:
            mok = False; problems.append(f"masters: only {mg[m]} of {mg[v]} vendor {k} matched ours (needs share {t['min_master_match_share']:.2f})")
    uo = share(ctl["sales_rows_without_outlet_id"], ctl["sales_rows"])
    if uo > t["max_unmatched_outlet_rows_share"]:
        mok = False; problems.append(f"sales rows without outlet match: share {uo:.4f} above {t['max_unmatched_outlet_rows_share']}")
    us = share(ctl["sales_rows_without_sku_id"], ctl["sales_rows"])
    if us > t["max_unmatched_sku_rows_share"]:
        mok = False; problems.append(f"sales rows without SKU match: share {us:.4f} above {t['max_unmatched_sku_rows_share']}")
    ns = share(ctl["sales_rows_outlet_without_scope"], ctl["sales_rows"])
    if ns > t["max_no_scope_rows_share"]:
        mok = False; problems.append(f"sales rows of a matched outlet with no zone/wing: share {ns:.4f} above {t['max_no_scope_rows_share']}")
    out["masters"] = dict(mg, unmatched_outlet_rows_share=round(uo, 4), unmatched_sku_rows_share=round(us, 4), no_scope_rows_share=round(ns, 4), ok=mok)
    cok = ctl["default_partition_rows"] == 0 and ctl["chunks_not_verified"] == 0
    if not cok:
        problems.append(f"control: {ctl['default_partition_rows']} row(s) in a default partition, {ctl['chunks_not_verified']} chunk(s) not verified")
    out["control"] = dict(ctl, ok=cok)
    out["outlets_per_route"] = dict(rep["outlets_per_route"], informational=True)
    out["problems"] = problems
    out["ok"] = not problems
    return out
