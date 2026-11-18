"""Pass/fail decisions of the reconciliation. Numbers in, numbers out: a gap fails the report, it is never printed as ok."""

DEFAULTS = {
    "max_skipped_share": 0.01,              # staged rows with no usable date/number, per table
    "max_reject_share": 0.001,              # rows with a usable date outside the plausible window (apsis_ctl.setting window_from/to), per table
    "max_unmatched_outlet_rows_share": 0.10,
    "max_unmatched_sku_rows_share": 0.10,
    "max_no_scope_rows_share": 0.02,        # matched outlet but no zone/wing chain
    "min_master_match_share": 0.50,         # distinct normalised vendor codes matched to ours, per master
    "max_zero_money_share": 0.02,           # non-zero-sale lines with zero money, with zero quantity, or with a NULL/blank amount
    "accept_full_row_key": [],              # tables without a primary key the owner accepted (identical real rows collapse)
}


def thresholds(mapping):
    t = dict(DEFAULTS)
    t.update(mapping.get("thresholds", {}))
    return t


def share(a, b):
    return 0.0 if not b else a / b


def evaluate(rep, chunks, stage_meta, expected, mapping, refused_files=0, coverage=None):
    """rep: sections from reconcile.sql + masters_gap + rejects_by_table; chunks: per table ctl numbers; stage_meta: per table {has_pk,...}; expected: {table: rows}.
    The loop runs over the EXPECTED tables (the vendor's list), not over what happened to be staged: a table that was never loaded fails."""
    t = thresholds(mapping)
    problems = []
    out = {"thresholds_used": t}
    if refused_files:
        problems.append(f"{refused_files} file(s) in the dump folder were not on the table allow-list and were ignored: the dump is not the expected 26 tables")
    tabs = {}
    for table in sorted(set(expected) | set(chunks)):
        c = chunks.get(table)
        e = expected.get(table)
        if c is None:
            tabs[table] = {"expected_rows": e, "loaded": False, "ok": False}
            problems.append(f"{table}: expected but never loaded (not staged, refused, or the run did not cover it)")
            continue
        rej = rep["rejects_by_table"].get(table, 0)
        row = dict(c, expected_rows=e, rejected_out_of_window=rej, loaded=True)
        row["collapsed_duplicate_rows"] = c["rows_parsed"] - c["rows_in"]
        ok = True
        if not c["all_verified"]:
            ok = False; problems.append(f"{table}: not every month is verified")
        if c["rows_in"] != c["chunk_rows_in"]:
            ok = False; problems.append(f"{table}: staged rows per chunk ({c['chunk_rows_in']}) differ from the staged total ({c['rows_in']})")
        if c["rows_out"] + c["rows_skipped"] + rej != c["chunk_rows_in"]:
            ok = False; problems.append(f"{table}: loaded + skipped + rejected ({c['rows_out'] + c['rows_skipped'] + rej}) differ from staged rows ({c['chunk_rows_in']})")
        if share(c["rows_skipped"], c["rows_in"]) > t["max_skipped_share"]:
            ok = False; problems.append(f"{table}: skipped share {share(c['rows_skipped'], c['rows_in']):.4f} above {t['max_skipped_share']}")
        if share(rej, c["rows_in"]) > t["max_reject_share"]:
            ok = False; problems.append(f"{table}: {rej} row(s) with a date outside the plausible window (share {share(rej, c['rows_in']):.4f} above {t['max_reject_share']})")
        if e is None:
            ok = False; problems.append(f"{table}: no expected row count (mapping/expected_counts.json)")
        elif c["rows_parsed"] != e:
            ok = False; problems.append(f"{table}: rows read from the dump ({c['rows_parsed']}) differ from the vendor's count ({e})")
        meta = stage_meta.get(table, {})
        if meta.get("has_pk") is False and table not in t["accept_full_row_key"] and meta.get("is_fact") and c["rows_in"] > 0:
            ok = False; problems.append(f"{table}: no primary key, rows keyed by the whole row; owner decision needed (thresholds.accept_full_row_key)")
        row["ok"] = ok
        tabs[table] = row
    out["tables"] = {"ok": all(r["ok"] for r in tabs.values()) and bool(tabs), "by_table": tabs}
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
    # money and quantity must not silently be zero: a wrong column or unit shows here
    ctl, mq = rep["control"], rep["money_quality"]
    nz = ctl["sales_lines_nonzero"]
    zm, zq, nm = share(ctl["sales_lines_zero_money"], nz), share(ctl["sales_lines_zero_qty"], nz), share(mq["amount_null_or_blank"], mq["stage_lines"])
    mqok = zm <= t["max_zero_money_share"] and zq <= t["max_zero_money_share"] and nm <= t["max_zero_money_share"]
    if not mqok:
        problems.append(f"money/quantity quality: zero-money lines {zm:.4f}, zero-quantity lines {zq:.4f}, NULL/blank amounts {nm:.4f} (limit {t['max_zero_money_share']}): a mapped column may be wrong")
    out["money_quality"] = dict(mq, zero_money_share=round(zm, 4), zero_qty_share=round(zq, 4), null_amount_share=round(nm, 4),
                                negative_money_lines=ctl["sales_lines_negative_money"], ok=mqok)
    mn = rep["memo_numbers"]
    ok = mn["numbers_on_more_than_one_outlet"] == 0
    if not ok:
        problems.append(f"memo numbers: {mn['numbers_on_more_than_one_outlet']} number(s) on more than one outlet")
    out["memo_numbers"] = dict(mn, ok=ok)
    # time zone: informational evidence ONLY. The zone is decided from the dry run's evidence (hour histogram, date-column agreement) and signed by the owner.
    tz = rep["time_zone"]
    out["time_zone"] = dict(tz, route_day_agreement=round(share(tz["route_days_equal"], tz["vendor_route_days"]), 4) if tz["geo_has_route_column"] else None,
                            informational=True, owner_signs_the_zone=True)
    mg = rep["masters_gap"]
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
    # mapping coverage: every target column and its source column (names only); an unmapped one that is not intentionally absent is an error
    cov_errors = {tbl: [r["target"] for r in rows if r["status"] == "UNMAPPED"] for tbl, rows in (coverage or {}).items()}
    cov_errors = {k: v for k, v in cov_errors.items() if v}
    if cov_errors:
        problems.append("mapping: unmapped target column(s) in " + ", ".join(sorted(cov_errors)))
    out["mapping_coverage"] = {"ok": not cov_errors, "by_table": coverage or {}}
    out["outlets_per_route"] = dict(rep["outlets_per_route"], informational=True)
    out["problems"] = problems
    out["ok"] = not problems
    return out
