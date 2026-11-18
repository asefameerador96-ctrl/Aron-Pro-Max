"""Staging loader: dump -> PostgreSQL schema apsis_stage (LOGGED tables), types mapped, no transformation of values.

Columns are kept or dropped by an EXPLICIT reviewed list per table (mapping/column_review.json: {"tables": {t: {"drop": [...], "keep": [...]}}}):
every column of the dump must be in exactly one list, otherwise the table is refused (fail closed). The name patterns below only SUGGEST a
review (the dry run prints the suggestion, names only); they never decide. Adds metadata columns _row_uuid (deterministic key), _chunk
('YYYY-MM' of the chunk date column) and _stmt (INSERT statement ordinal).
"""
import json
import re
from pathlib import Path

from . import keys, safety
from .mysqldump import events, tsv_events

CRED = re.compile(r"(pass|pwd|hash|salt|token|otp|secret|key|imei|fcm|session|device)", re.I)
PERSONAL = re.compile(r"(owner|phone|mobile|contact|(^|_)nid($|_)|(^|_)tin($|_)|trade_lic|e_?mail|address|(^|_)name($|_)|_name$|msisdn|cell|bkash|father|dob|birth|remarks|note|comment)", re.I)
CRED_ALLOW = {"primary_key_id"}

_INT = {"tinyint", "smallint", "mediumint", "int", "integer", "bigint", "year", "bit", "bool", "boolean"}
_DEC = {"decimal", "numeric"}
_FLT = {"float", "double", "real"}


class ReviewError(ValueError):
    pass


def is_credential(name):
    return bool(CRED.search(name)) and name.lower() not in CRED_ALLOW


def is_personal(name):
    return bool(PERSONAL.search(name))


def suggest(name):
    """'credential', 'personal' or 'keep': a SUGGESTION for the reviewer, never applied automatically."""
    return "credential" if is_credential(name) else "personal" if is_personal(name) else "keep"


def pg_type(col):
    b = col.base
    if b in _INT:
        return "numeric" if (b == "bigint" and col.unsigned) else "bigint"
    if b in _DEC:
        return "numeric"
    if b in _FLT:
        return "double precision"
    return "text"           # char/varchar/text/enum/set/json/blob(hex literal) and temporal types (raw text, zero dates preserved)


def pg_text(v):
    if v is None:
        return "\\N"
    return v.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")


def load_mapping(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def load_review(path):
    return json.loads(Path(path).read_text(encoding="utf-8")) if path else {"tables": {}}


def chunk_of(value):
    """'YYYY-MM' from a vendor date text, 'unknown' for a null, blank or zero date."""
    if not value or value.startswith("0000"):
        return "unknown"
    m = re.match(r"^(\d{4})-(\d{2})", value)
    return f"{m.group(1)}-{m.group(2)}" if m and m.group(2) != "00" else "unknown"


def pick(candidates, available):
    low = {a.lower(): a for a in available}
    for c in candidates:
        if c.lower() in low:
            return low[c.lower()]
    return None


def review_for(table, names, review):
    """Returns (keep, drop) for a table, or raises ReviewError (never names a value; column NAMES are structure, not data)."""
    r = review.get("tables", {}).get(table)
    if r is None:
        raise ReviewError(f"{table}: no entry in the column review (mapping/column_review.json); run the dry run and review its suggestion")
    keep, drop = set(r.get("keep", [])), set(r.get("drop", []))
    ack = set(r.get("ack_keep_credential_like", []))
    both = keep & drop
    if both:
        raise ReviewError(f"{table}: column(s) in both keep and drop: {sorted(both)}")
    unknown = (keep | drop) - set(names)
    if unknown:
        raise ReviewError(f"{table}: the review names column(s) the dump does not have: {sorted(unknown)}")
    missing = [n for n in names if n not in keep and n not in drop]
    if missing:
        raise ReviewError(f"{table}: unclassified column(s) {missing}: every column must be in keep or drop")
    risky = [n for n in keep if is_credential(n) and n not in ack]
    if risky:
        raise ReviewError(f"{table}: credential-looking column(s) {risky} are in keep without ack_keep_credential_like")
    return [n for n in names if n in keep], [n for n in names if n in drop]


class TableStager:
    def __init__(self, db, mapping, review, schema="apsis_stage"):
        self.db, self.mapping, self.review, self.schema = db, mapping, review, schema
        self.allowed = set(mapping["allowed_tables"])

    def ddl(self, ts, keep):
        cols = [f"{safety.quote(c.name)} {pg_type(c)}" for c in ts.columns if c.name in keep]
        cols += ["_row_uuid uuid NOT NULL", "_chunk text NOT NULL", "_stmt int NOT NULL"]
        return f"CREATE TABLE IF NOT EXISTS {self.schema}.{safety.quote(ts.name)} ({', '.join(cols)})"      # LOGGED: survives a restart or failover

    def stage_file(self, path, table, checkpoint=None, skip_statements=0, parsed_before=0, batch_rows=50000, tsv=False):
        """Stages one file. `table` is the file's stem and must equal the CREATE TABLE name and be on the allow-list.
        checkpoint(ordinal, rows_parsed_total) is called after each COMPLETE INSERT statement (so a resume redoes at most one statement)."""
        safety.table(table, self.allowed)
        it = tsv_events(path, table, batch_rows) if tsv else events(path, batch_rows, skip_statements)
        ts, keep_idx, cols, dropped, key_idx, chunk_idx = None, None, None, [], None, None
        parsed, bad_chars = parsed_before, 0
        for ev in it:
            if ev[0] == "schema":
                ts = ev[1]
                if ts.name != table:
                    raise safety.Refused("refused: the table named inside a dump file is not the file's name")
                names = ts.column_names()
                for n in names:
                    safety.ident(n, "column name")
                keep, dropped = review_for(table, names, self.review)
                keep_idx = [names.index(n) for n in keep]
                cols = keep
                cfg = self.mapping["tables"].get(table, {})
                cands = cfg.get("chunk_date", [])
                cd = pick(cands, names) if cands else None
                if cands and cd is None:
                    raise ReviewError(f"{table}: none of the date columns {cands} exists: edit mapping/columns.json (no fallback to another column)")
                if cd is not None and cd not in keep:
                    raise ReviewError(f"{table}: the chunk date column is dropped in the review")
                chunk_idx = names.index(cd) if cd else None
                key_idx = [names.index(p) for p in ts.pk] if ts.pk else None
                self.has_pk = bool(ts.pk)
                self.key_cols = list(ts.pk or [])
                if not self._schema_exists():
                    self.db.run("CREATE SCHEMA " + safety.quote(self.schema))
                self.db.run(self.ddl(ts, set(keep)))
                continue
            _, tname, rows, ordinal, stmt_done = ev
            if ts is None:
                raise ValueError(f"{table}: INSERT before CREATE TABLE: schema-only profile required")
            safety.table(tname, self.allowed)
            if tname != table:
                raise safety.Refused("refused: an INSERT in a dump file targets another table than the file's")
            out = []
            for r in rows:
                if len(r) != len(ts.columns):
                    raise ValueError(f"{table}: statement {ordinal}: a row has {len(r)} values but the table has {len(ts.columns)} columns")
                if any(v is not None and "\ufffd" in v for v in r):
                    bad_chars += 1
                k = keys.row_uuid(table, [r[i] for i in key_idx]) if key_idx else keys.row_uuid(table, [keys.full_row_key(r)])
                ch = chunk_of(r[chunk_idx]) if chunk_idx is not None else "all"
                out.append("\t".join([pg_text(r[i]) for i in keep_idx] + [k, ch, str(ordinal)]))
            self.db.copy_in(f"{self.schema}.{safety.quote(table)}", ", ".join(safety.quote(c) for c in cols) + ", _row_uuid, _chunk, _stmt",
                            ("\n".join(out) + "\n").encode("utf-8"))
            parsed += len(rows)
            if stmt_done and checkpoint:
                checkpoint(ordinal, parsed)
        return {"table": table, "rows_parsed": parsed, "dropped_columns": dropped, "kept_columns": cols or [],
                "has_pk": getattr(self, "has_pk", False), "key_cols": getattr(self, "key_cols", []), "undecodable_rows": bad_chars}

    def _schema_exists(self):
        return self.db.scalar("SELECT count(*) FROM pg_namespace WHERE nspname = :'s'", {"s": self.schema}) == "1"
