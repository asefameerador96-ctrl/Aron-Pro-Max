"""Phase 1: PRESERVE + PROFILE. Reads the dump files and writes only structure, counts, lengths, shapes and (full file only) numeric/date ranges.
No data row is ever printed or written. Free text, names, phones and e-mails never leave: a value list appears only for a column with fewer than
30 distinct values that are all short codes (letters, digits, underscore) whose column name is not personal-looking."""
import json
import re
import sys
import time
from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path

from . import safety, staging
from .mysqldump import UnsupportedShape, CharsetError, events

REPO_ROOT = Path(__file__).resolve().parents[3]
CAP_DISTINCT = 100_000
ENUM_MAX = 30
ENUM_HINT = re.compile(r"(status|type|kind|class|channel|category|flag|unit|state|mode|level|tier|code|day|variant|segment|reason|source|role|group|gender|is_|has_)", re.I)
SHAPES = ["empty", "datetime-like", "date-like", "time-like", "uuid-like", "hash-like", "email-like", "url-like", "phone-like", "digits-only", "decimal",
          "bengali", "bengali-latin-mixed", "latin-text", "alnum-code", "other"]

_R_DT = re.compile(r"^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d+)?$")
_R_D = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_R_T = re.compile(r"^\d{2}:\d{2}(:\d{2})?$")
_R_UUID = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
_R_HASH = re.compile(r"^(?:\$2[aby]\$.{50,}|[0-9a-fA-F]{32}|[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$")
_R_EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
_R_URL = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*://")
_R_PHONE = re.compile(r"^\+?(?:880)?0?1[3-9][0-9]{8}$|^\+?[0-9]{10,14}$")
_R_DIGITS = re.compile(r"^-?[0-9]+$")
_R_DEC = re.compile(r"^-?[0-9]+\.[0-9]+$")
_R_CODE = re.compile(r"^[A-Za-z0-9._\- /]+$")
_R_LATIN = re.compile(r"^[A-Za-z .,'()&\-]+$")
_BENGALI = re.compile(r"[ঀ-৿]")
_ENUM_VALUE = re.compile(r"^[A-Za-z0-9_]{1,24}$")
_CAPITALISED = re.compile(r"^[A-Z][a-z]+$")


def shape(v):
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
    if _R_EMAIL.match(v):
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
    n = re.sub(r"\s+", "", v).upper()
    return re.sub(r"^0+(?=[0-9])", "", n) if n.isdigit() else n


class Col:
    def __init__(self, name, ctype, base, cap):
        self.name, self.type, self.base, self.cap = name, ctype, base, cap
        self.n = self.nulls = 0
        self.distinct, self.capped = {}, False
        self.minlen = self.maxlen = None
        self.shapes = {s: 0 for s in SHAPES}
        self.nmin = self.nmax = self.nmin_s = self.nmax_s = None
        self.longdigits = False
        self.dmin = self.dmax = None
        self.zero_dates = 0
        self.hours = [0] * 24
        self.numeric = base in ("tinyint", "smallint", "mediumint", "int", "integer", "bigint", "decimal", "numeric", "float", "double", "real", "bit", "year")
        self.temporal = base in ("date", "datetime", "timestamp")

    def add(self, v):
        self.n += 1
        if v is None:
            self.nulls += 1
            return
        L = len(v)
        self.minlen = L if self.minlen is None else min(self.minlen, L)
        self.maxlen = L if self.maxlen is None else max(self.maxlen, L)
        self.shapes[shape(v)] += 1
        if not self.capped:
            if v in self.distinct:
                self.distinct[v] += 1
            elif len(self.distinct) >= self.cap:
                self.capped = True
                self.distinct = {}            # memory bound: past the cap only the fact that it is capped is kept
            else:
                self.distinct[v] = 1
        if self.numeric and _R_DIGITS.match(v) or self.numeric and _R_DEC.match(v):
            try:
                d = Decimal(v)
            except InvalidOperation:
                d = None
            if d is not None:
                if self.nmin is None or d < self.nmin:
                    self.nmin, self.nmin_s = d, v
                if self.nmax is None or d > self.nmax:
                    self.nmax, self.nmax_s = d, v
                if "." not in v and len(v.lstrip("-")) >= 10:
                    self.longdigits = True       # phone-, NID- or counter-like integers: their values are withheld
        elif self.temporal:
            if v.startswith("0000"):
                self.zero_dates += 1
            elif _R_D.match(v[:10]):
                dv = v[:19] if len(v) >= 19 else v[:10]
                self.dmin = dv if self.dmin is None or dv < self.dmin else self.dmin
                self.dmax = dv if self.dmax is None or dv > self.dmax else self.dmax
                if self.base in ("datetime", "timestamp") and len(v) >= 19 and v[11:13].isdigit():
                    self.hours[int(v[11:13]) % 24] += 1

    def enum_values(self):
        if self.capped or not self.distinct or len(self.distinct) >= ENUM_MAX or staging.is_personal(self.name) or staging.is_credential(self.name):
            return None
        for v in self.distinct:
            if not _ENUM_VALUE.match(v) or shape(v) in ("phone-like", "email-like", "hash-like", "uuid-like"):
                return None
            if _CAPITALISED.match(v) and not ENUM_HINT.search(self.name):
                return None                  # a Capitalised word could be a name: only with an enum-looking column name
        return dict(sorted(self.distinct.items(), key=lambda kv: -kv[1]))

    def report(self):
        nn = self.n - self.nulls
        out = {"name": self.name, "type": self.type, "rows": self.n, "null_share": round(self.nulls / self.n, 6) if self.n else None,
               "distinct": None if self.capped else len(self.distinct), "distinct_capped": self.capped,
               "min_length": self.minlen, "max_length": self.maxlen,
               "shapes": {k: v for k, v in self.shapes.items() if v}}
        if self.numeric:
            out["value_range"] = "withheld: long digit strings (phone/NID/counter-like)" if self.longdigits else \
                ({"min": self.nmin_s, "max": self.nmax_s} if self.nmin is not None else None)
        if self.temporal:
            out["value_range"] = {"min": self.dmin, "max": self.dmax}
            out["zero_dates"] = self.zero_dates
            if sum(self.hours):
                out["hour_of_day_stored"] = self.hours
        ev = self.enum_values()
        if ev is not None:
            out["values"] = ev
        return out


class RefCheck:
    """Share of a fact column's values found in a master key column (exact, and after the normalisation rule)."""

    def __init__(self, spec):
        self.spec = spec
        self.master_exact, self.master_norm = set(), set()
        self.rows = self.found_exact = self.found_norm = 0
        self.fact_distinct, self.fact_capped = set(), False
        self.fact_missing_distinct = 0
        self.fact_col = self.master_col = None


def load_refs(path):
    return json.loads(Path(path).read_text(encoding="utf-8")).get("refs", []) if path and Path(path).exists() else []


def _tables_in_order(files, order):
    return sorted(files, key=lambda t: order.index(t) if t in order else 50)


def profile_dump(dump_dir, mapping, refs=(), charset="utf-8", cap=CAP_DISTINCT, order=(), log=lambda m: None):
    allowed = set(mapping["allowed_tables"])
    files, refused_names = {}, 0
    problems = []
    unsupported_ext = re.compile(r"\.(zst|bz2|xz|zip|lz4|7z|tar|tgz|part\d*|\d{5})(\.|$)|-schema(-create)?\.sql|\.\d{5}\.sql", re.I)
    for p in sorted(Path(dump_dir).iterdir()):
        if not p.is_file():
            continue
        m = re.match(r"^(.*)\.(sql|tsv)(\.gz)?$", p.name)
        if unsupported_ext.search(p.name):
            stem = re.split(r"[.\-]", p.name)[0]
            problems.append({"table": stem if safety.IDENT.match(stem) else "<name not a plain identifier>", "refused": "unsupported file shape (compressed part, mydumper layout or split file)"})
            continue
        if not m:
            continue
        try:
            files[safety.table(m.group(1), allowed)] = p
        except safety.Refused:
            refused_names += 1
    out = {"tables": {}, "files_not_on_allow_list": refused_names, "tables_missing": sorted(allowed - set(files)), "refused": problems, "charset": charset}
    # reference checks: masters are processed first so their key sets exist when the facts stream by
    ref_by_master, ref_by_fact = {}, {}
    for r in refs:
        rc = RefCheck(r)
        ref_by_master.setdefault(r["master_table"], []).append(rc)
        ref_by_fact.setdefault(r["fact_table"], []).append(rc)
    for table in _tables_in_order(files, list(order)):
        t0 = time.time()
        cols, schema, rows = None, None, 0
        try:
            for ev in events(files[table], batch_rows=20000, charset=charset, strict=True):
                if ev[0] == "schema":
                    schema = ev[1]
                    cols = [Col(c.name, c.base + c.args + (" unsigned" if c.unsigned else ""), c.base, cap) for c in schema.columns]
                    names = schema.column_names()
                    for rc in ref_by_master.get(table, []):
                        rc.master_col = staging.pick(rc.spec["master_columns"], names)
                        rc.m_idx = names.index(rc.master_col) if rc.master_col else None
                    for rc in ref_by_fact.get(table, []):
                        rc.fact_col = staging.pick(rc.spec["fact_columns"], names)
                        rc.f_idx = names.index(rc.fact_col) if rc.fact_col else None
                    continue
                _, tname, batch, ordinal, done = ev
                if tname != table:
                    raise UnsupportedShape("an INSERT targets another table than the file's")
                for r in batch:
                    rows += 1
                    for i, v in enumerate(r):
                        cols[i].add(v)
                    for rc in ref_by_master.get(table, []):
                        if rc.m_idx is not None and r[rc.m_idx] is not None:
                            rc.master_exact.add(r[rc.m_idx]); rc.master_norm.add(norm_code(r[rc.m_idx]))
                    for rc in ref_by_fact.get(table, []):
                        if rc.f_idx is not None and r[rc.f_idx] is not None:
                            v = r[rc.f_idx]
                            rc.rows += 1
                            ex = v in rc.master_exact
                            nm = ex or norm_code(v) in rc.master_norm
                            rc.found_exact += ex
                            rc.found_norm += nm
                            if not rc.fact_capped:
                                if v not in rc.fact_distinct:
                                    if len(rc.fact_distinct) >= cap:
                                        rc.fact_capped = True
                                    else:
                                        rc.fact_distinct.add(v)
                                        rc.fact_missing_distinct += (not nm)
            if schema is None:
                raise UnsupportedShape("the file holds no CREATE TABLE statement (mydumper data file or empty file)")
        except UnsupportedShape as e:
            problems.append({"table": table, "refused": str(e)[:200]})
            log(f"{table}: REFUSED ({e})")
            continue
        except CharsetError as e:
            problems.append({"table": table, "refused": str(e)[:240], "kind": "charset"})
            log(f"{table}: REFUSED (character set)")
            continue
        out["tables"][table] = {
            "engine": schema.engine, "declared_charset": schema.charset, "rows": rows,
            "primary_key": schema.pk, "indexes": schema.indexes,
            "columns": [c.report() for c in cols], "seconds": round(time.time() - t0, 1)}
        log(f"{table}: {rows} rows, {len(cols)} columns")
    out["reference_checks"] = []
    for r in refs:
        for rc in ref_by_fact.get(r["fact_table"], []):
            if rc.spec is not r:
                continue
            res = {"name": r.get("name", ""), "fact": f"{r['fact_table']}.{rc.fact_col}", "master": f"{r['master_table']}.{rc.master_col}",
                   "resolved": bool(rc.fact_col and rc.master_col), "fact_rows_with_key": rc.rows, "master_keys": len(rc.master_exact)}
            if rc.rows:
                res.update({"share_rows_found_exact": round(rc.found_exact / rc.rows, 6), "share_rows_found_after_normalisation": round(rc.found_norm / rc.rows, 6),
                            "distinct_fact_keys_sampled": len(rc.fact_distinct), "distinct_sample_capped": rc.fact_capped,
                            "distinct_sampled_not_found_after_normalisation": rc.fact_missing_distinct})
            out["reference_checks"].append(res)
    out["ok"] = not problems
    return out


def sanitise(full):
    """Names, types, counts, shapes; value lists only for enum-like columns; no numeric/date value ranges, no lengths of free text beyond min/max length."""
    s = json.loads(json.dumps(full))
    for t in s["tables"].values():
        for c in t["columns"]:
            c.pop("value_range", None)
    s["_note"] = "sanitised rendering: no value ranges; value lists only for enum-like code columns"
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
        L += ["| column | type | null share | distinct | length | shapes | range / values |", "|---|---|---|---|---|---|---|"]
        for c in t["columns"]:
            rng = ""
            if isinstance(c.get("value_range"), dict):
                rng = f"{c['value_range']['min']} .. {c['value_range']['max']}"
            elif c.get("value_range"):
                rng = str(c["value_range"])
            if "values" in c:
                rng = (rng + " " if rng else "") + "values: " + ", ".join(f"{k}={v}" for k, v in c["values"].items())
            if "hour_of_day_stored" in c:
                rng = (rng + " " if rng else "") + "hours: " + ",".join(map(str, c["hour_of_day_stored"]))
            L.append(f"| {c['name']} | {c['type']} | {c['null_share']} | {c['distinct'] if not c['distinct_capped'] else 'capped'} | {c['min_length']}-{c['max_length']} | "
                     + ", ".join(f"{k} {v}" for k, v in c["shapes"].items()) + f" | {rng} |")
        L.append("")
    if d["reference_checks"]:
        L += ["## Reference checks (shares only)", ""] + [f"- {r['name']}: {r['fact']} in {r['master']}: " + (f"exact {r.get('share_rows_found_exact')}, after normalisation {r.get('share_rows_found_after_normalisation')}" if r.get("fact_rows_with_key") else "not resolved or no rows") for r in d["reference_checks"]] + [""]
    return "\n".join(L)


def check_out_dir(out_dir):
    p = Path(out_dir).resolve()
    if REPO_ROOT == p or REPO_ROOT in p.parents:
        raise safety.Refused("refused: the profile must be written OUTSIDE the repository (the repository is public)")
    return p


def write_outputs(out_dir, full):
    p = check_out_dir(out_dir)
    p.mkdir(parents=True, exist_ok=True)
    san = sanitise(full)
    (p / "apsis-profile.json").write_text(json.dumps(full, indent=1, sort_keys=True), encoding="utf-8")
    (p / "apsis-profile.md").write_text(render_md(full, "Apsis dump profile (full: value ranges and hour histograms; never rows)"), encoding="utf-8")
    (p / "apsis-profile.sanitised.json").write_text(json.dumps(san, indent=1, sort_keys=True), encoding="utf-8")
    (p / "apsis-profile.sanitised.md").write_text(render_md(san, "Apsis dump profile (sanitised: names, types, counts, shapes; value lists only for enum-like columns)"), encoding="utf-8")
    return p
