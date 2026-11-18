"""Staging loader: dump -> PostgreSQL schema apsis_stage, types mapped, no transformation of values.

Adds three metadata columns: _row_uuid (deterministic key), _chunk ('YYYY-MM' of the chunk date column, else 'all'),
_stmt (INSERT statement ordinal, for resume). Credential-looking columns are dropped here (D-APSIS-MIG-05).
"""
import json
import re
from pathlib import Path

from . import keys
from .mysqldump import events, tsv_events

CRED = re.compile(r"(pass|pwd|hash|salt|token|otp|secret|key|imei|fcm|session|device)", re.I)
# whole-name allow list for columns that match CRED by accident (foreign keys such as sku_key are NOT allowed: be strict)
CRED_ALLOW = {"primary_key_id"}

_INT = {"tinyint", "smallint", "mediumint", "int", "integer", "bigint", "year", "bit", "bool", "boolean"}
_DEC = {"decimal", "numeric"}
_FLT = {"float", "double", "real"}
_BIN = {"blob", "tinyblob", "mediumblob", "longblob", "binary", "varbinary"}


def pg_type(col):
    b = col.base
    if b in _INT:
        return "numeric" if (b == "bigint" and col.unsigned) else "bigint"
    if b in _DEC:
        return "numeric"
    if b in _FLT:
        return "double precision"
    if b in _BIN:
        return "text"       # hex literal kept as text; binary payloads are not part of this migration
    return "text"           # char/varchar/text/enum/set/json and temporal types (raw text, zero dates preserved)


# Personal data (D-APSIS-MIG-06): this tool matches masters by code and loads history by code; it never needs a name, a phone,
# an address or an identity number, so those columns are dropped at staging too and the staging copy holds none.
PERSONAL = re.compile(r"(owner|phone|mobile|contact|(^|_)nid($|_)|(^|_)tin($|_)|trade_lic|e_?mail|address|(^|_)name($|_)|_name$)", re.I)


def is_personal(name):
    return bool(PERSONAL.search(name))


def is_dropped(name):
    return is_credential(name) or is_personal(name)


def is_credential(name):
    return bool(CRED.search(name)) and name.lower() not in CRED_ALLOW


def qident(s):
    return '"' + s.replace('"', '""') + '"'


def pg_text(v):
    """COPY text-format encoding of one value."""
    if v is None:
        return "\\N"
    return v.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")


def load_mapping(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def chunk_of(value):
    """'YYYY-MM' from a vendor date text, 'unknown' for a null or zero date."""
    if not value or value.startswith("0000"):
        return "unknown"
    m = re.match(r"^(\d{4})-(\d{2})", value)
    return f"{m.group(1)}-{m.group(2)}" if m else "unknown"


def pick(candidates, available):
    low = {a.lower(): a for a in available}
    for c in candidates:
        if c.lower() in low:
            return low[c.lower()]
    return None


class TableStager:
    """Stages one vendor table. Resumable: skip_statements and the checkpoint callback."""

    def __init__(self, db, mapping, schema="apsis_stage"):
        self.db, self.mapping, self.schema = db, mapping, schema

    def table_cfg(self, table):
        return self.mapping["tables"].get(table, {})

    def ddl(self, ts, dropped):
        cols = []
        for c in ts.columns:
            if c.name in dropped:
                continue
            cols.append(f"{qident(c.name)} {pg_type(c)}")
        cols += ["_row_uuid uuid NOT NULL", "_chunk text NOT NULL", "_stmt int NOT NULL"]
        return f"CREATE UNLOGGED TABLE IF NOT EXISTS {self.schema}.{qident(ts.name)} ({', '.join(cols)})"

    def stage_file(self, path, table_hint=None, checkpoint=None, skip_statements=0, batch_rows=50000, tsv=False):
        """Returns dict: table, rows_read, dropped_columns, columns."""
        it = tsv_events(path, table_hint, batch_rows) if tsv else events(path, batch_rows, skip_statements)
        ts, keep_idx, cols, dropped, key_idx, chunk_idx = None, None, None, [], None, None
        rows_read, table = 0, table_hint
        for ev in it:
            if ev[0] == "schema":
                ts = ev[1]
                table = ts.name
                names = ts.column_names()
                dropped = [n for n in names if is_dropped(n)]
                keep_idx = [i for i, n in enumerate(names) if n not in dropped]
                cols = [names[i] for i in keep_idx]
                self.db.run(f"CREATE SCHEMA IF NOT EXISTS {self.schema}")
                self.db.run(self.ddl(ts, set(dropped)))
                cfg = self.table_cfg(table)
                pk = ts.pk or []
                key_idx = [names.index(p) for p in pk] if pk else None   # a dropped credential column can't be a key: pk wins
                cd = pick(cfg.get("chunk_date", []), names)
                chunk_idx = names.index(cd) if cd else None
                continue
            _, tname, rows, ordinal = ev
            if ts is None:
                raise ValueError(f"{tname}: INSERT before CREATE TABLE: schema-only profile required")
            out = []
            for r in rows:
                if len(r) != len(ts.columns):
                    raise ValueError(f"{tname}: statement {ordinal}: row has {len(r)} values, table has {len(ts.columns)} columns")
                k = keys.row_uuid(table, [r[i] for i in key_idx]) if key_idx else keys.row_uuid(table, [keys.full_row_key(r)])
                ch = chunk_of(r[chunk_idx]) if chunk_idx is not None else "all"
                out.append("\t".join([pg_text(r[i]) for i in keep_idx] + [k, ch, str(ordinal)]))
            data = ("\n".join(out) + "\n").encode("utf-8")
            self.db.copy_in(f"{self.schema}.{qident(table)}", cols + ["_row_uuid", "_chunk", "_stmt"], data)
            rows_read += len(rows)
            if checkpoint:
                checkpoint(table, ordinal, rows_read)
        return {"table": table, "rows_read": rows_read, "dropped_columns": dropped, "columns": cols or []}
