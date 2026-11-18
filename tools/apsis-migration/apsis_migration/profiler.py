"""Phase 1: PRESERVE + PROFILE. Reads the dump files and writes only structure and numbers: table and column names that pass the identifier rule, counts,
lengths, shape-class histograms, booleans, and (FULL file only, tables of at least 1000 rows, columns that are not near-unique) COARSE descriptors: digit-count
range, sign and integer/decimal kind of numbers, YEAR-MONTH range of dates, hour-of-day histograms. NO EXACT MIN/MAX VALUE and NO VALUE LIST of any kind is written
by `profile`; a column that looks like a code (status/type) only gets an `enum_candidate` flag. A separate, deliberate, owner-run command (`profile-values`)
re-derives that test from the dump itself and lists the values of ONE column into an owner-only file. Memory is bounded in bytes: distinct values are tracked as
fixed-size hashes up to a cap; shape classification looks at a 256-character prefix with linear-time rules."""
import json
import os
import re
import sys
import time
from decimal import Decimal, InvalidOperation
from pathlib import Path

from . import safety, staging
from .mysqldump import UnsupportedShape, CharsetError, Binary, ABSENT, events

REPO_ROOT = Path(__file__).resolve().parents[3]
CAP_DISTINCT = 50_000              # entries per column (a hash and a count: about 5 MB per column at the cap)
REF_CAP = 1_500_000                # entries per reference key set (about 100 MB per set at the cap)
PREFIX = 256                       # shape classification sees at most this many characters
ENUM_MAX = 30
ENUM_MIN_ROWS = 1000
ENUM_MIN_PER_VALUE = 20
DESCRIBE_MIN_NONNULL = 100        # fewer non-null values in a column: no descriptor, no histogram
DESCRIBE_MIN_ROWS = 1000          # below this a table gets only type, null share, lengths and shape classes
SHAPES = ["empty", "datetime-like", "date-like", "time-like", "uuid-like", "hash-like", "email-like", "url-like", "phone-like", "digits-only", "decimal",
          "bengali", "bengali-latin-mixed", "latin-text", "alnum-code", "binary", "other"]
SAFE_WORD = re.compile(r"^[A-Za-z0-9_]{1,32}$")

_R_DT = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?$")
_R_D = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
_R_T = re.compile(r"^[0-9]{2}:[0-9]{2}(:[0-9]{2})?$")
_R_UUID = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
_R_HASH = re.compile(r"^(?:\$2[aby]\$[^\s]{50,}|[0-9a-fA-F]{32}|[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$")
_R_URL = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]{0,20}://")
_R_PHONE = re.compile(r"^\+?(?:880)?0?1[3-9][0-9]{8}$|^\+?[0-9]{10,14}$")
_R_DIGITS = re.compile(r"^-?[0-9]+$")
_R_DEC = re.compile(r"^-?[0-9]+\.[0-9]+$")
_R_CODE = re.compile(r"^[A-Za-z0-9._\- /]+$")
_R_LATIN = re.compile(r"^[A-Za-z .,'()&\-]+$")
_BENGALI = re.compile(r"[ঀ-৿]")
_ENUM_VALUE = re.compile(r"^[A-Za-z0-9_]{1,24}$")


def _is_email(v):
    if v.count("@") != 1 or " " in v:
        return False
    a, b = v.split("@")
    return bool(a) and "." in b and not b.startswith(".") and not b.endswith(".") and len(b) > 2


def shape(v):
    """Linear-time class of a value: looks at a 256-character prefix only; no backtracking patterns."""
    v = v[:PREFIX]
    if v == "":
        return "empty"
    if _R_DT.match(v):
        return "datetime-like"
    if _R_D.match(v):
        return "date-like"
    if _R_T.match(v):
        return "time-like"
    if _R_UUID.match(v):
        return "uuid-like"
    if _R_HASH.match(v):
        return "hash-like"
    if _is_email(v):
        return "email-like"
    if _R_URL.match(v):
        return "url-like"
    if _R_PHONE.match(v.replace(" ", "").replace("-", "")):
        return "phone-like"
    if _R_DIGITS.match(v):
        return "digits-only"
    if _R_DEC.match(v):
        return "decimal"
    if _BENGALI.search(v):
        return "bengali" if all(_BENGALI.match(c) or not c.isalpha() for c in v) else "bengali-latin-mixed"
    if _R_LATIN.match(v):
        return "latin-text"
    if _R_CODE.match(v):
        return "alnum-code"
    return "other"


def norm_code(v):
    n = re.sub(r"\s+", "", v[:PREFIX]).upper()
    return re.sub(r"^0+(?=[0-9])", "", n) if n.isdigit() else n


class Names:
    """Column, index and key NAMES are data from the dump: only a plain identifier is ever printed; anything else becomes [name-N] (N counts per table)."""

    def __init__(self):
        self.n = 0
        self.map = {}

    def show(self, name):
        if isinstance(name, str) and safety.IDENT.match(name) and not re.search(r"[0-9]{8,}", name):
            return name
        if name not in self.map:
            self.n += 1
            self.map[name] = f"[name-{self.n}]"
        return self.map[name]


def safe_word(v):
    return v if isinstance(v, str) and SAFE_WORD.match(v) else ("" if not v else "[value-withheld]")


class Col:
    def __init__(self, shown, ctype, base, cap):
        self.name, self.base, self.cap = shown, base, cap
        self.type = ctype if re.match(r"^[A-Za-z]+(\([0-9, ]{1,12}\))?( unsigned)?$", ctype) else "[type-withheld]"
        self.n = self.nulls = 0
        self.distinct, self.capped = {}, False       # {hash: count}: a fixed-size hash per distinct value, never the value
        self.all_short = True
        self.minlen = self.maxlen = None
        self.shapes = {s: 0 for s in SHAPES}
        # coarse descriptors only (never an exact value): digit counts, sign, integer/decimal kind, decimal places, year-month
        self.dig_min = self.dig_max = None
        self.neg = self.nonneg = self.saw_int = self.saw_dec = False
        self.dp_min = self.dp_max = None
        self.ym_min = self.ym_max = None
        self.zero_dates = 0
        self.hours = [0] * 24
        self.binary = False
        self.numeric = base in ("tinyint", "smallint", "mediumint", "int", "integer", "bigint", "decimal", "numeric", "float", "double", "real", "bit", "year")
        self.temporal = base in ("date", "datetime", "timestamp")

    def add(self, v):
        if v is ABSENT:                              # a column the INSERT's column list does not name (generated column): nothing was written, nothing is counted
            return
        self.n += 1
        if v is None:
            self.nulls += 1
            return
        if isinstance(v, Binary):                    # opaque: class 'binary' and a length in bytes; never decoded, hashed, printed
            L = v.nbytes
            self.minlen = L if self.minlen is None else min(self.minlen, L)
            self.maxlen = L if self.maxlen is None else max(self.maxlen, L)
            self.shapes["binary"] += 1
            self.all_short = False
            self.binary = True
            return
        L = len(v)
        self.minlen = L if self.minlen is None else min(self.minlen, L)
        self.maxlen = L if self.maxlen is None else max(self.maxlen, L)
        self.shapes[shape(v)] += 1
        if self.all_short and (L > 24 or not _ENUM_VALUE.match(v)):
            self.all_short = False
        if not self.capped:
            h = hash(v)
            if h in self.distinct:
                self.distinct[h] += 1
            elif len(self.distinct) >= self.cap:
                self.capped, self.distinct = True, {}
            else:
                self.distinct[h] = 1
        if self.numeric and L <= 40 and (_R_DIGITS.match(v) or _R_DEC.match(v)):
            neg = v.startswith("-")
            body = v[1:] if neg else v
            ip, _, fp = body.partition(".")
            digits = len(ip.lstrip("0")) or 1
            self.dig_min = digits if self.dig_min is None else min(self.dig_min, digits)
            self.dig_max = digits if self.dig_max is None else max(self.dig_max, digits)
            if neg and body.strip("0."):
                self.neg = True
            else:
                self.nonneg = True
            if fp:
                self.saw_dec = True
                self.dp_min = len(fp) if self.dp_min is None else min(self.dp_min, len(fp))
                self.dp_max = len(fp) if self.dp_max is None else max(self.dp_max, len(fp))
            else:
                self.saw_int = True
        elif self.temporal and L <= 40:
            if v.startswith("0000"):
                self.zero_dates += 1
            elif _R_D.match(v[:10]):
                ym = v[:7]
                self.ym_min = ym if self.ym_min is None or ym < self.ym_min else self.ym_min
                self.ym_max = ym if self.ym_max is None or ym > self.ym_max else self.ym_max
                if self.base in ("datetime", "timestamp") and len(v) >= 19 and v[11:13].isdigit():
                    self.hours[int(v[11:13]) % 24] += 1

    def enum_candidate(self, table_rows):
        """A boolean only: fewer than 30 distinct values, in a table of at least 1000 rows, every value in at least 20 rows, all short codes."""
        return bool(not self.capped and self.distinct and len(self.distinct) < ENUM_MAX and table_rows >= ENUM_MIN_ROWS and self.all_short
                    and min(self.distinct.values()) >= ENUM_MIN_PER_VALUE)

    def describable(self, table_rows):
        """Coarse descriptors only for a table of at least 1000 rows and a column that is not (almost) unique per row."""
        if table_rows < DESCRIBE_MIN_ROWS:
            return False
        nn = self.n - self.nulls
        if nn < DESCRIBE_MIN_NONNULL:                # a few non-null values (a single one is a value) get no descriptor and no histogram
            return False
        return not (not self.capped and len(self.distinct) >= 0.9 * nn)

    def report(self, table_rows):
        big = table_rows >= DESCRIBE_MIN_ROWS
        out = {"name": self.name, "type": self.type, "rows": self.n, "null_share": round(self.nulls / self.n, 6) if self.n else None,
               "distinct": (None if self.capped else len(self.distinct)) if big else None, "distinct_capped": self.capped if big else False,
               "min_length": self.minlen, "max_length": self.maxlen, "shapes": {k: v for k, v in self.shapes.items() if v},
               "enum_candidate": self.enum_candidate(table_rows)}
        if self.describable(table_rows):
            if self.numeric and self.dig_min is not None:
                out["number_descriptor"] = {"integer_digits": [self.dig_min, self.dig_max],
                                            "sign": "mixed" if (self.neg and self.nonneg) else ("negative-only" if self.neg else "non-negative"),
                                            "kind": "mixed" if (self.saw_int and self.saw_dec) else ("decimal" if self.saw_dec else "integer"),
                                            "decimal_places": [self.dp_min, self.dp_max] if self.saw_dec else None}
            if self.temporal:
                out["year_month_range"] = [self.ym_min, self.ym_max] if self.ym_min else None
                if sum(self.hours):
                    out["hour_of_day_stored"] = self.hours
        if self.temporal:
            out["zero_dates"] = self.zero_dates
        return out


class RefCheck:
    """Share of a fact column's values found in a master key column (exact, and after the normalisation rule). Hash sets with an entry cap."""

    def __init__(self, spec):
        self.spec = spec
        self.master_exact, self.master_norm = set(), set()
        self.master_capped = False
        self.rows = self.found_exact = self.found_norm = 0
        self.fact_distinct, self.fact_capped = set(), False
        self.fact_missing_distinct = 0
        self.fact_col = self.master_col = None
        self.m_idx = self.f_idx = None


def load_refs(path):
    return json.loads(Path(path).read_text(encoding="utf-8")).get("refs", []) if path and Path(path).exists() else []


def profile_dump(dump_dir, mapping, refs=(), charset="utf-8", cap=CAP_DISTINCT, order=(), ref_cap=REF_CAP, log=lambda m: None, whole_database=False):
    allowed = set(mapping["allowed_tables"])
    files, refused_names, refused_other, other_files = {}, 0, 0, []
    problems = []
    unsupported_ext = re.compile(r"\.(zst|bz2|xz|zip|lz4|7z|tar|tgz|part[0-9]*|[0-9]{5})(\.|$)|-schema(-create)?\.sql|\.[0-9]{5}\.sql", re.I)

    def reason(name):
        if re.search(r"\.(zst|bz2|xz|zip|lz4|7z|tar|tgz)(\.|$)", name, re.I):
            return "compressed file: only plain .sql and .sql.gz are read"
        if re.search(r"-schema(-create)?\.sql", name, re.I):
            return "mydumper schema file: the mydumper layout (separate schema and data files) is not supported"
        if re.search(r"\.[0-9]{5}\.sql", name):
            return "mydumper data chunk file: the split layout is not supported"
        return "split part file: parts are not read"
    for p in sorted(Path(dump_dir).iterdir()):
        if not p.is_file():
            continue
        m = re.match(r"^(.*)\.(sql|tsv)(\.gz)?$", p.name)
        if unsupported_ext.search(p.name):
            stem = re.split(r"[.\-]", p.name)[0]
            if stem in allowed:                                            # a file name is printed verbatim ONLY if it is on the allow-list; the others are counted
                problems.append({"table": stem, "refused": "unsupported file shape: " + reason(p.name)})
            else:
                problems.append({"table": "<file not on the allow-list>", "refused": "unsupported file shape: " + reason(p.name)})
                refused_other += 1
            continue
        if not m:
            continue
        try:
            files[safety.table(m.group(1), allowed)] = p
        except safety.Refused:
            refused_names += 1
            if whole_database:
                other_files.append(p)
    out = {"tables": {}, "files_not_on_allow_list": refused_names, "tables_missing": sorted(allowed - set(files)), "refused": problems, "refused_files_not_on_allow_list": refused_other,
           "tables_not_on_allow_list_in_whole_file": 0, "whole_database_mode": bool(whole_database), "charset": charset,
           "limits": {"distinct_cap_per_column": cap, "reference_key_cap": ref_cap, "shape_prefix_chars": PREFIX}}
    ref_by_master, ref_by_fact = {}, {}
    for r in refs:
        rc = RefCheck(r)
        ref_by_master.setdefault(r["master_table"], []).append(rc)
        ref_by_fact.setdefault(r["fact_table"], []).append(rc)
    ordered = sorted(files, key=lambda t: list(order).index(t) if t in order else 50)
    done = set()

    def finish(table, schema, cols, rows, names, t0):
        idx = []
        for i in schema.indexes:
            ent = {"kind": i["kind"] if i["kind"] in ("key", "unique", "fulltext", "spatial", "foreign") else "other", "name": names.show(i["name"]), "columns": [names.show(c) for c in i["columns"]]}
            if i["kind"] == "foreign":
                ent["references"] = i.get("references") if i.get("references") in allowed else "[table-not-on-allow-list]"
                ent["ref_columns"] = [names.show(c) for c in i.get("ref_columns", [])]
            idx.append(ent)
        out["tables"][table] = {
            "engine": safe_word(schema.engine), "declared_charset": safe_word(schema.charset), "rows": rows,
            "partitioned": bool(schema.partitioned), "partitions": schema.partitions if schema.partitioned else 0,   # the count only, never the bounds
            "primary_key": [names.show(c) for c in schema.pk], "indexes": idx,
            "columns": [c.report(rows) for c in cols], "seconds": round(time.time() - t0, 1)}
        done.add(table)
        log(f"{table}: {rows} rows, {len(cols)} columns")

    def read_file(path, table, multi_file):
        """One file: one table (named by the file) or, for a whole-database file, one table per CREATE TABLE section (name validated, allow-list applied)."""
        cur, cols, schema, rows, names, t0, skip = None, None, None, 0, Names(), time.time(), False
        try:
            for ev in events(path, batch_rows=20000, charset=charset, strict=True, expect_table=table, multi=multi_file):
                if ev[0] == "schema":
                    if cur is not None:
                        finish(cur, schema, cols, rows, names, t0)
                        cur = None
                    schema = ev[1]
                    if multi_file:
                        try:
                            cur = safety.table(schema.name, allowed)
                        except safety.Refused:
                            out["tables_not_on_allow_list_in_whole_file"] += 1
                            cur, skip = None, True                      # read (every line is still checked) but never profiled
                        else:
                            skip = False
                        if cur in out["tables"] or cur in files:
                            raise UnsupportedShape("a table in the whole-database file also has its own file")
                    else:
                        cur, skip = table, False
                    rows, t0, names = 0, time.time(), Names()
                    if skip:
                        cols = None
                        continue
                    cols = [Col(names.show(c.name), c.base + c.args + (" unsigned" if c.unsigned else ""), c.base, cap) for c in schema.columns]
                    raw_names = schema.column_names()
                    for rc in ref_by_master.get(cur, []):
                        rc.master_col = staging.pick(rc.spec["master_columns"], [n for n in raw_names if safety.IDENT.match(n)])
                        rc.m_idx = raw_names.index(rc.master_col) if rc.master_col else None
                    for rc in ref_by_fact.get(cur, []):
                        rc.fact_col = staging.pick(rc.spec["fact_columns"], [n for n in raw_names if safety.IDENT.match(n)])
                        rc.f_idx = raw_names.index(rc.fact_col) if rc.fact_col else None
                        rc.master_not_yet_read = rc.spec["master_table"] not in done
                    continue
                _, tname, batch, ordinal, done_stmt = ev
                if skip:
                    continue
                if tname != cur:
                    raise UnsupportedShape("an INSERT targets another table than the CREATE TABLE that precedes it")
                for r in batch:
                    rows += 1
                    for i, v in enumerate(r):
                        cols[i].add(v)
                    for rc in ref_by_master.get(cur, []):
                        v = r[rc.m_idx] if rc.m_idx is not None else None
                        if v is not None and v is not ABSENT and not isinstance(v, Binary):
                            if len(rc.master_exact) < ref_cap:
                                rc.master_exact.add(hash(v)); rc.master_norm.add(hash(norm_code(v)))
                            else:
                                rc.master_capped = True
                    for rc in ref_by_fact.get(cur, []):
                        v = r[rc.f_idx] if rc.f_idx is not None else None
                        if v is not None and v is not ABSENT and not isinstance(v, Binary):
                            rc.rows += 1
                            ex = hash(v) in rc.master_exact
                            nm = ex or hash(norm_code(v)) in rc.master_norm
                            rc.found_exact += ex
                            rc.found_norm += nm
                            if not rc.fact_capped:
                                hv = hash(v)
                                if hv not in rc.fact_distinct:
                                    if len(rc.fact_distinct) >= ref_cap // 10:
                                        rc.fact_capped = True
                                    else:
                                        rc.fact_distinct.add(hv)
                                        rc.fact_missing_distinct += (not nm)
            if schema is None:
                raise UnsupportedShape("the file holds no CREATE TABLE statement (mydumper data file or empty file)")
            if cur is not None:
                finish(cur, schema, cols, rows, names, t0)
        except UnsupportedShape as e:
            problems.append({"table": cur or table or "<whole-database file>", "refused": str(e)[:240]})
            log(f"{cur or table or '<whole-database file>'}: REFUSED ({e})")
        except CharsetError as e:
            problems.append({"table": cur or table or "<whole-database file>", "refused": str(e)[:240], "kind": "charset"})
            log(f"{cur or table or '<whole-database file>'}: REFUSED (character set)")
        except Exception as e:      # a garbled file must refuse its table, never abort the whole run or print what it holds
            problems.append({"table": cur or table or "<whole-database file>", "refused": f"unreadable file (internal error class {type(e).__name__})"})
            log(f"{cur or table or '<whole-database file>'}: REFUSED (unreadable)")

    for table in ordered:
        read_file(files[table], table, False)
    for p in other_files:                                                  # whole-database files (only with --whole-database), one table per CREATE TABLE section
        read_file(p, None, True)
    out["tables_missing"] = sorted(allowed - set(out["tables"]) - {pr["table"] for pr in problems})
    out["reference_checks"] = []
    for r in refs:
        for rc in ref_by_fact.get(r["fact_table"], []):
            if rc.spec is not r:
                continue
            res = {"name": r.get("name", ""), "fact": f"{r['fact_table']}.{rc.fact_col if rc.fact_col else '(no column)'}",
                   "master": f"{r['master_table']}.{rc.master_col if rc.master_col else '(no column)'}",
                   "resolved": bool(rc.fact_col and rc.master_col), "fact_rows_with_key": rc.rows, "master_keys": len(rc.master_exact), "master_keys_capped": rc.master_capped}
            if rc.rows and not rc.master_capped and not getattr(rc, "master_not_yet_read", False):
                res.update({"share_rows_found_exact": round(rc.found_exact / rc.rows, 6), "share_rows_found_after_normalisation": round(rc.found_norm / rc.rows, 6),
                            "distinct_fact_keys_sampled": len(rc.fact_distinct), "distinct_sample_capped": rc.fact_capped,
                            "distinct_sampled_not_found_after_normalisation": rc.fact_missing_distinct})
            out["reference_checks"].append(res)
    out["ok"] = not problems and not out["tables_missing"]          # a missing allow-listed table is a failure too (listed in the output)
    return out


def sanitise(full):
    """Names, types, counts, shapes, booleans; no value ranges (and, as in the full file, no value of any kind)."""
    s = json.loads(json.dumps(full))
    for t in s["tables"].values():
        for c in t["columns"]:
            for k in ("number_descriptor", "year_month_range", "hour_of_day_stored"):
                c.pop(k, None)
    s["_note"] = "sanitised rendering: names, types, counts, shapes and flags only; no descriptors, no ranges, no values"
    return s


def render_md(d, title):
    L = [f"# {title}", "", f"Tables profiled: {len(d['tables'])}; missing: {len(d['tables_missing'])}; refused: {len(d['refused'])}; files not on the allow-list: {d['files_not_on_allow_list']}; character set: {d['charset']}", ""]
    if d["refused"]:
        L += ["## Refused (the run exits non-zero)", ""] + [f"- `{r['table']}`: {r['refused']}" for r in d["refused"]] + [""]
    for name, t in d["tables"].items():
        L += [f"## {name}", "", f"engine {t['engine'] or '?'}, charset {t['declared_charset'] or '?'}, **{t['rows']} rows**, primary key: {', '.join(t['primary_key']) or 'none'}", ""]
        if t["indexes"]:
            def _ix(i):
                base = f"{i['kind']} {i['name']}({', '.join(i['columns'])})"
                return base + (f" -> {i.get('references')}({', '.join(i.get('ref_columns', []))})" if i["kind"] == "foreign" else "")
            L += ["indexes: " + "; ".join(_ix(i) for i in t["indexes"]), ""]
        L += ["| column | type | null share | distinct | length | shapes | enum candidate | descriptor / hours |", "|---|---|---|---|---|---|---|---|"]
        for c in t["columns"]:
            rng = ""
            nd = c.get("number_descriptor")
            if nd:
                rng = f"{nd['kind']}, {nd['sign']}, {nd['integer_digits'][0]}-{nd['integer_digits'][1]} integer digits" + (f", {nd['decimal_places'][0]}-{nd['decimal_places'][1]} decimals" if nd.get("decimal_places") else "")
            if c.get("year_month_range"):
                rng = f"{c['year_month_range'][0]} .. {c['year_month_range'][1]} (year-month)"
            if "hour_of_day_stored" in c:
                rng = (rng + " " if rng else "") + "hours: " + ",".join(map(str, c["hour_of_day_stored"]))
            L.append(f"| {c['name']} | {c['type']} | {c['null_share']} | {c['distinct'] if not c['distinct_capped'] else 'capped'} | {c['min_length']}-{c['max_length']} | "
                     + ", ".join(f"{k} {v}" for k, v in c["shapes"].items()) + f" | {'yes' if c['enum_candidate'] else 'no'} | {rng} |")
        L.append("")
    if d["reference_checks"]:
        L += ["## Reference checks (shares only)", ""] + [f"- {r['name']}: {r['fact']} in {r['master']}: " + (f"exact {r.get('share_rows_found_exact')}, after normalisation {r.get('share_rows_found_after_normalisation')}" if r.get("fact_rows_with_key") and "share_rows_found_exact" in r else "not resolved, no rows, or key set capped") for r in d["reference_checks"]] + [""]
    return "\n".join(L)


def check_out_dir(out_dir):
    """The profile may be written anywhere EXCEPT inside a git work tree (any repository, not only this copy): walk up for a .git."""
    p = Path(out_dir).resolve()
    for q in [p] + list(p.parents):
        if (q / ".git").exists():
            raise safety.Refused("refused: the profile must be written OUTSIDE any git work tree (the repository is public)")
    return p


def write_outputs(out_dir, full):
    p = check_out_dir(out_dir)
    p.mkdir(parents=True, exist_ok=True)
    san = sanitise(full)
    (p / "apsis-profile.json").write_text(json.dumps(full, indent=1, sort_keys=True), encoding="utf-8")
    (p / "apsis-profile.md").write_text(render_md(full, "Apsis dump profile (full: numeric/date ranges of non-identifying columns and hour histograms; never rows, never values)"), encoding="utf-8")
    (p / "apsis-profile.sanitised.json").write_text(json.dumps(san, indent=1, sort_keys=True), encoding="utf-8")
    (p / "apsis-profile.sanitised.md").write_text(render_md(san, "Apsis dump profile (sanitised: names, types, counts, shapes, flags)"), encoding="utf-8")
    return p


def profile_values(dump_dir, table, column, confirm_column, out_dir, mapping, charset="utf-8", warn=lambda m: None):
    """DELIBERATE, owner-run, ONE named column. Does NOT trust any profile file: it re-derives the enum-candidate test from the dump itself (fewer than 30 distinct
    values, table of at least 1000 rows, every value in at least 20 rows, all short codes) and refuses otherwise. The owner must type the column name again
    (--confirm-column). Writes an owner-only file in the output directory (never the sanitised files, never a terminal); a loud warning names the column."""
    p = check_out_dir(out_dir)
    safety.table(table, set(mapping["allowed_tables"]))
    safety.ident(column, "column name")
    if confirm_column != column:
        raise safety.Refused("refused: type the column name again with --confirm-column to confirm that a person decides this column holds codes, not people")
    if any((p / n).exists() for n in ("apsis-profile.sanitised.json", "apsis-profile.sanitised.md", "apsis-profile.json", "apsis-profile.md")):
        raise safety.Refused("refused: write the values file into a directory DIFFERENT from the profile files (the sanitised pair is the only thing that may leave the machine)")
    warn(f"WARNING: listing the values of column {column} of table {table}. The test can only check the SHAPE of the values: names, places, PINs and gender codes can pass it. "
         "A PERSON must look at the column and judge that it holds codes, not people, before this file is used or shown to anyone. "
         "The output is an OWNER-ONLY file (mode 0600): never commit it, never share it.")
    files = {m.group(1): f for f in Path(dump_dir).iterdir() if (m := re.match(r"^(.*)\.(sql|tsv)(\.gz)?$", f.name))}
    if table not in files:
        raise safety.Refused("refused: the table's file is not in the dump folder")
    counts, idx, rows = {}, None, 0
    for ev in events(files[table], batch_rows=20000, charset=charset, strict=True, expect_table=table):
        if ev[0] == "schema":
            names = ev[1].column_names()
            if column not in names:
                raise safety.Refused("refused: the column is not in the table")
            idx = names.index(column)
            continue
        for r in ev[2]:
            rows += 1
            v = r[idx]
            if v is None:
                continue                                          # NULL is excluded from the test exactly as `profile` excludes it from the distinct count
            if not isinstance(v, str):
                raise safety.Refused("refused: the column holds binary or absent values, not codes")
            counts[v] = counts.get(v, 0) + 1
            if len(counts) >= ENUM_MAX:
                raise safety.Refused("refused: the column fails the enum-candidate test (30 or more distinct values)")
    ok = (rows >= ENUM_MIN_ROWS and counts and all(len(v) <= 24 and _ENUM_VALUE.match(v) for v in counts)
          and min(counts.values()) >= ENUM_MIN_PER_VALUE)
    if not ok:
        raise safety.Refused("refused: the column fails the enum-candidate test re-derived from the dump (table of 1000+ rows, fewer than 30 distinct values, each in 20+ rows, all short codes)")
    p.mkdir(parents=True, exist_ok=True)
    f = p / f"apsis-values-{table}-{column}.OWNER-ONLY.json"
    fd = os.open(f, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(json.dumps({"table": table, "column": column, "value_counts": dict(sorted(counts.items(), key=lambda kv: -kv[1]))}, indent=1, ensure_ascii=False))
    os.chmod(f, 0o600)
    return f, len(counts)
