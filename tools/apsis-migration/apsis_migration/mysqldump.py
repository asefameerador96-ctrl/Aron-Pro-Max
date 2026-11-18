"""Streaming reader for mysqldump / mydumper SQL files (optionally .gz) and TSV with a header.

yield_table_events() yields ('schema', TableSchema) and ('rows', table, [tuple, ...], stmt_ordinal).
Values are Python str / None (numbers stay as their text so no float ever touches money).
"""
import gzip
import io
import re
from dataclasses import dataclass, field

_CREATE = re.compile(r"^CREATE TABLE (?:IF NOT EXISTS )?`?([^`\s(]+)`?\s*\($", re.I)
_COL = re.compile(r"^\s*`([^`]+)`\s+([a-zA-Z]+)(\([^)]*\))?(.*?),?\s*$")
_PK = re.compile(r"^\s*PRIMARY KEY\s*\(([^)]*)\)", re.I)
_INSERT = re.compile(r"^INSERT (?:IGNORE )?INTO `?([^`\s(]+)`?\s*(\(([^)]*)\))?\s*VALUES\s*", re.I)
_TOKEN = re.compile(
    r"""'(?:[^'\\]|\\.|'')*'      # quoted string
      |_binary\s+'(?:[^'\\]|\\.|'')*'
      |0x[0-9A-Fa-f]+
      |NULL
      |-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?
      |[(),;]
    """,
    re.X | re.S,
)
_ESC = {"0": "\0", "'": "'", '"': '"', "b": "\b", "n": "\n", "r": "\r", "t": "\t", "Z": "\x1a", "\\": "\\"}


@dataclass
class Column:
    name: str
    base: str          # lower-case base type: int, decimal, varchar, datetime ...
    args: str = ""     # "(12,3)" or ""
    unsigned: bool = False


@dataclass
class TableSchema:
    name: str
    columns: list = field(default_factory=list)
    pk: list = field(default_factory=list)

    def column_names(self):
        return [c.name for c in self.columns]


_UNESC = re.compile(r"\\(.)|''", re.S)


def _unescape(body: str) -> str:
    """One pass, left to right: a backslash escape or a doubled quote is decoded once (a second pass would collapse \\'' wrongly). NUL is removed."""
    if "\\" not in body and "''" not in body:
        return body
    return _UNESC.sub(lambda m: "'" if m.group(1) is None else _ESC.get(m.group(1), m.group(1)), body).replace("\0", "")


_TSV = re.compile(r"\\(.)", re.S)
_TSVESC = {"t": "\t", "n": "\n", "r": "\r", "\\": "\\", "0": ""}


def _unescape_tsv(body: str) -> str:
    return _TSV.sub(lambda m: _TSVESC.get(m.group(1), m.group(1)), body) if "\\" in body else body


def _value(tok: str):
    if tok == "NULL":
        return None
    if tok[0] == "'":
        return _unescape(tok[1:-1])
    if tok.startswith("_binary"):
        return _unescape(tok[tok.index("'") + 1:-1])
    if tok.startswith("0x"):
        return tok          # a hex blob literal, kept as text
    return tok


def parse_values(text: str):
    """Parse '(a,b),(c,d);' into a list of tuples. Raises ValueError on a malformed statement."""
    rows, cur, depth = [], None, 0
    for m in _TOKEN.finditer(text):
        t = m.group(0)
        if t == "(":
            depth += 1
            cur = []
        elif t == ")":
            if depth != 1 or cur is None:
                raise ValueError("unbalanced parenthesis")
            rows.append(tuple(cur))
            cur, depth = None, 0
        elif t == ",":
            pass
        elif t == ";":
            break
        else:
            if cur is None:
                raise ValueError("value outside a row")
            cur.append(_value(t))
    if depth != 0:
        raise ValueError("statement ended inside a row")
    return rows


def _open(path):
    if str(path).endswith(".gz"):
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8", errors="replace", newline="\n")
    return open(path, "r", encoding="utf-8", errors="replace", newline="\n")


def _split_type(rest: str):
    m = re.match(r"^\s*`[^`]+`\s+([a-zA-Z]+)(\((?:[^()']|'[^']*')*\))?(\s+unsigned)?", rest, re.I)
    return m


def events(path, batch_rows=50000, skip_statements=0):
    """Yield ('schema', TableSchema) and ('rows', table, rows, statement_ordinal) from one dump file.

    skip_statements: INSERT statements with ordinal <= this are skipped (resume); the CREATE TABLE still yields.
    """
    schema = None
    in_create = False
    ordinal = 0
    with _open(path) as fh:
        for lineno, line in enumerate(fh, 1):
            if in_create:
                s = line.strip()
                if s.startswith(")"):
                    in_create = False
                    yield ("schema", schema)
                    continue
                mp = _PK.match(s)
                if mp:
                    schema.pk = [c.strip().strip("`") for c in mp.group(1).split(",")]
                    continue
                if s.startswith("`"):
                    mt = _split_type(s)
                    if mt:
                        schema.columns.append(Column(
                            re.match(r"^`([^`]+)`", s).group(1), mt.group(1).lower(), mt.group(2) or "",
                            bool(mt.group(3))))
                continue
            mc = _CREATE.match(line.strip())
            if mc:
                schema = TableSchema(mc.group(1))
                in_create = True
                continue
            mi = _INSERT.match(line)
            if mi:
                ordinal += 1
                if ordinal <= skip_statements:
                    continue
                try:
                    rows = parse_values(line[mi.end():])
                except ValueError as e:
                    raise ValueError(f"{mi.group(1)}: statement {ordinal} (file line {lineno}): {e}") from None
                for i in range(0, len(rows), batch_rows):
                    yield ("rows", mi.group(1), rows[i:i + batch_rows], ordinal, i + batch_rows >= len(rows))


def tsv_events(path, table, batch_rows=50000):
    """TSV with a header line (MySQL SELECT INTO OUTFILE style, \\N = NULL). Schema: every column text."""
    with _open(path) as fh:
        header = fh.readline().rstrip("\n").split("\t")
        yield ("schema", TableSchema(table, [Column(h, "text") for h in header]))
        batch = []
        for line in fh:
            parts = [None if p == "\\N" else _unescape_tsv(p) for p in line.rstrip("\n").split("\t")]
            batch.append(tuple(parts))
            if len(batch) >= batch_rows:
                yield ("rows", table, batch, 0, False)
                batch = []
        if batch:
            yield ("rows", table, batch, 0, True)
