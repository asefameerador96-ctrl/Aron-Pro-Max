"""Strict streaming reader for mysqldump SQL files (optionally .gz) and TSV with a header. EVERY line of a file is accounted for: it is consumed by a known
construct or the file is REFUSED (UnsupportedShape): nothing is skipped silently. Values stay Python str / None (numbers stay as text: no float touches money).

Known constructs: blank lines; `--` comments and single-line `/* ... */` comments; the mysqldump directives `/*!NNNNN SET ... */;` and
`/*!NNNNN ALTER TABLE `t` DISABLE|ENABLE KEYS */;`; plain `SET ...;`; `DROP TABLE IF EXISTS`; `LOCK TABLES`/`UNLOCK TABLES`; exactly one `CREATE TABLE` whose name is
the file's table; one `INSERT INTO t VALUES (...),(...);` per line; and `DELIMITER ;;` ... `DELIMITER ;` blocks (trigger/routine bodies), which are skipped WITHOUT
reading their statements (never counted as rows). Anything else (UPDATE, DELETE, ALTER, a prefixed or conditional-comment INSERT, a second CREATE TABLE, a CREATE of
another name, REPLACE INTO, a column list, a row-spanning INSERT, ...) refuses the file."""
import gzip
import io
import re
from dataclasses import dataclass, field
from itertools import islice

from . import safety

MAX_LINE = 256 * 1024 * 1024


class UnsupportedShape(safety.Refused):
    """A shape the reader cannot read faithfully. The message never holds a row value or a name from the file."""


class CharsetError(ValueError):
    """The file is not valid text in the stated character set. Never guessed: the owner decides (Q2)."""


_CREATE = re.compile(r"^CREATE TABLE (?:IF NOT EXISTS )?`([^`]*)`\s*\($", re.I)
_PK = re.compile(r"^\s*PRIMARY KEY\s*\(([^)]*)\)", re.I)
_IDX = re.compile(r"^\s*(UNIQUE KEY|FULLTEXT KEY|SPATIAL KEY|KEY)\s+`([^`]*)`\s*\(([^)]*)\)", re.I)
_FK = re.compile(r"^\s*CONSTRAINT\s+`([^`]*)`\s+FOREIGN KEY\s*\(([^)]*)\)\s*REFERENCES\s+`([^`]*)`\s*\(([^)]*)\)", re.I)
_CONSTRAINT_OTHER = re.compile(r"^\s*(CONSTRAINT\s+`[^`]*`\s+CHECK\s*\(|CHECK\s*\()", re.I)
_TAIL = re.compile(r"ENGINE\s*=\s*([A-Za-z0-9_]+)", re.I)
_TAILCS = re.compile(r"CHARSET\s*=\s*([A-Za-z0-9_]+)", re.I)
_INSERT = re.compile(r"^INSERT (?:IGNORE )?INTO `([^`]*)`\s*(\(([^)]*)\))?\s*VALUES\s*", re.I)
_DIRECTIVE_SET = re.compile(r"^/\*!\d{5} SET [^;*]*\*/;$")
_DIRECTIVE_KEYS = re.compile(r"^/\*!\d{5} ALTER TABLE `[^`]*` (DISABLE|ENABLE) KEYS \*/;$")
_COMMENT = re.compile(r"^/\*[^!].*\*/$|^/\*\*/$")
_SET = re.compile(r"^SET [A-Za-z@_][^;]*;$", re.I)
_DROP = re.compile(r"^DROP TABLE IF EXISTS `[^`]*`;$", re.I)
_LOCK = re.compile(r"^(LOCK TABLES `[^`]*` WRITE;|UNLOCK TABLES;)$", re.I)
_NUM = re.compile(r"-?[0-9]+(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?")
_WS = re.compile(r"\s*")
_ESC = {"0": "\0", "'": "'", '"': '"', "b": "\b", "n": "\n", "r": "\r", "t": "\t", "Z": "\x1a", "\\": "\\"}
_UNESC = re.compile(r"\\(.)|''", re.S)
_SPECIAL = re.compile(r"[\\']")


@dataclass
class Column:
    name: str
    base: str          # lower-case base type: int, decimal, varchar, datetime ...
    args: str = ""
    unsigned: bool = False


@dataclass
class TableSchema:
    name: str
    columns: list = field(default_factory=list)
    pk: list = field(default_factory=list)
    indexes: list = field(default_factory=list)      # [{"kind": "key|unique|fulltext|spatial|foreign", "name": .., "columns": [..]}]
    engine: str = ""
    charset: str = ""

    def column_names(self):
        return [c.name for c in self.columns]


def _unescape(body: str) -> str:
    """One pass, left to right: a backslash escape or a doubled quote is decoded once. NUL is removed."""
    if "\\" not in body and "''" not in body:
        return body
    return _UNESC.sub(lambda m: "'" if m.group(1) is None else _ESC.get(m.group(1), m.group(1)), body).replace("\0", "")


_TSV = re.compile(r"\\(.)", re.S)
_TSVESC = {"t": "\t", "n": "\n", "r": "\r", "\\": "\\", "0": ""}


def _unescape_tsv(body: str) -> str:
    return _TSV.sub(lambda m: _TSVESC.get(m.group(1), m.group(1)), body) if "\\" in body else body


def _scan_string(text, i):
    """Index just past the closing quote of the single-quoted string that starts at text[i]; a single linear pass (no backtracking). Unterminated -> refused."""
    n = len(text)
    i += 1
    while True:
        m = _SPECIAL.search(text, i)
        if m is None:
            raise UnsupportedShape("an unterminated quoted string inside an INSERT")
        j = m.start()
        if text[j] == "\\":
            i = j + 2
            if i > n:
                raise UnsupportedShape("an unterminated quoted string inside an INSERT")
        elif j + 1 < n and text[j + 1] == "'":
            i = j + 2
        else:
            return j + 1


_BAD = "an unrecognised or unmodelled literal inside an INSERT (double-quoted string, TRUE/FALSE, X'..', 0x.., _binary, b'..' or stray text)"


def iter_values(text: str):
    """Lazily parse '(a,b),(c,d);' into tuples with a single linear scanner and a small grammar: '(' value (',' value)* ')' (',' '(' ... )* ';'.
    Every character must belong to a modelled token or be whitespace; commas between values and tuples are REQUIRED; nothing may follow the ';'."""
    n, i = len(text), 0
    state = "tuple"            # tuple: expect '(' ; value: expect a value ; after_value: expect ',' or ')' ; after_row: expect ',' or ';' ; done
    cur = None
    while True:
        i = _WS.match(text, i).end()
        if i >= n:
            break
        c = text[i]
        if state == "done":
            raise UnsupportedShape("text after the end of an INSERT statement")
        if c == "(":
            if state != "tuple":
                raise UnsupportedShape("a missing comma or a misplaced parenthesis inside an INSERT")
            cur, state, i = [], "value", i + 1
        elif c == ")":
            if state not in ("after_value", "value") or cur is None or (state == "value" and cur):
                raise UnsupportedShape("a misplaced parenthesis inside an INSERT")
            yield tuple(cur)
            cur, state, i = None, "after_row", i + 1
        elif c == ",":
            if state == "after_value":
                state = "value"
            elif state == "after_row":
                state = "tuple"
            else:
                raise UnsupportedShape("a misplaced comma inside an INSERT")
            i += 1
        elif c == ";":
            if state != "after_row":
                raise UnsupportedShape("a misplaced semicolon inside an INSERT")
            state, i = "done", i + 1
        elif state == "value":
            if c == "'":
                j = _scan_string(text, i)
                cur.append(_unescape(text[i + 1:j - 1]))
                i = j
            elif text.startswith("NULL", i) and not (i + 4 < n and (text[i + 4].isalnum() or text[i + 4] == "_")):
                cur.append(None)
                i += 4
            else:
                m = _NUM.match(text, i)
                if not m:
                    raise UnsupportedShape(_BAD)
                if m.end() < n and (text[m.end()].isalnum() or text[m.end()] in "_."):
                    raise UnsupportedShape(_BAD)
                cur.append(m.group(0))
                i = m.end()
            state = "after_value"
        else:
            raise UnsupportedShape(_BAD)
    if state not in ("done",):
        raise UnsupportedShape("an INSERT statement that does not end with ';' after its last row")


def parse_values(text: str):
    return list(iter_values(text))


def _open(path, charset="utf-8", strict=True):
    errors = "strict" if strict else "replace"
    if str(path).endswith(".gz"):
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding=charset, errors=errors, newline="\n")
    return open(path, "r", encoding=charset, errors=errors, newline="\n")


def _lines(fh):
    """Lines with a size guard; a decode failure is reported with the line number only (never the bytes)."""
    n = 0
    while True:
        try:
            line = fh.readline(MAX_LINE + 1)
        except UnicodeDecodeError:
            raise CharsetError(f"the file is not valid text in the stated character set near line {n + 1}: "
                               "do not guess; the owner decides the character set (Q2), then pass --charset") from None
        if not line:
            return
        n += 1
        if len(line) > MAX_LINE:
            raise UnsupportedShape(f"line {n} is longer than {MAX_LINE} bytes: re-dump with a smaller --net-buffer-length (or one statement per line)")
        yield n, line.rstrip("\r\n")


def _split_type(rest: str):
    return re.match(r"^\s*`[^`]*`\s+([a-zA-Z]+)(\((?:[^()']|'[^']*')*\))?(\s+unsigned)?", rest, re.I)


def events(path, batch_rows=50000, skip_statements=0, charset="utf-8", strict=True, expect_table=None):
    """Yield ('schema', TableSchema) and ('rows', table, rows, statement_ordinal, statement_done). expect_table: the file's table (stem); a CREATE of another
    name is refused. skip_statements: INSERT statements with ordinal <= this are skipped (resume); the CREATE TABLE still yields."""
    schema, in_create, in_block = None, False, False
    creates, ordinal = 0, 0
    with _open(path, charset, strict) as fh:
        for lineno, line in _lines(fh):
            if in_block:                                         # a DELIMITER block (trigger/routine body): skipped WITHOUT reading its statements
                if line.strip() == "DELIMITER ;":
                    in_block = False
                continue
            if in_create:
                s = line.strip()
                if s.startswith(")"):
                    in_create = False
                    me, mc2 = _TAIL.search(s), _TAILCS.search(s)
                    schema.engine, schema.charset = (me.group(1) if me else ""), (mc2.group(1) if mc2 else "")
                    yield ("schema", schema)
                    continue
                mp = _PK.match(s)
                if mp:
                    schema.pk = [c.strip().strip("`") for c in mp.group(1).split(",")]
                    continue
                mx = _IDX.match(s)
                if mx:
                    schema.indexes.append({"kind": mx.group(1).split()[0].lower(), "name": mx.group(2),
                                           "columns": [re.sub(r"\(\d+\)$", "", c.strip().strip("`")) for c in mx.group(3).split(",")]})
                    continue
                mf = _FK.match(s)
                if mf:
                    schema.indexes.append({"kind": "foreign", "name": mf.group(1), "columns": [c.strip().strip("`") for c in mf.group(2).split(",")],
                                           "references": mf.group(3), "ref_columns": [c.strip().strip("`") for c in mf.group(4).split(",")]})
                    continue
                if _CONSTRAINT_OTHER.match(s):
                    continue                                      # a CHECK constraint: structure only, never read
                if s.startswith("`"):
                    mt = _split_type(s)
                    if not mt:
                        raise UnsupportedShape(f"a column definition the reader cannot parse in a CREATE TABLE (file line {lineno})")
                    schema.columns.append(Column(re.match(r"^`([^`]*)`", s).group(1), mt.group(1).lower(), mt.group(2) or "", bool(mt.group(3))))
                    continue
                raise UnsupportedShape(f"a line inside CREATE TABLE that the reader does not recognise (file line {lineno})")
            s = line.strip()
            if not s or s.startswith("--") or _COMMENT.match(s):
                continue
            if s == "DELIMITER ;;":
                in_block = True
                continue
            if _DIRECTIVE_SET.match(s) or _DIRECTIVE_KEYS.match(s) or _SET.match(s) and s.count(";") == 1 or _DROP.match(s) or _LOCK.match(s):
                continue
            head = line.lstrip()[:12].upper()
            if head.startswith("REPLACE"):
                raise UnsupportedShape(f"REPLACE INTO at line {lineno} (mysqldump --replace) is not read: re-dump without --replace")
            if head.startswith("INSERT") and not _INSERT.match(line):
                raise UnsupportedShape(f"an INSERT shape the reader does not know (indented, prefixed, or not a plain INSERT INTO t VALUES) at line {lineno}")
            mc = _CREATE.match(s)
            if mc:
                creates += 1
                if creates > 1:
                    raise UnsupportedShape(f"a second CREATE TABLE in the file (line {lineno}): one table per file")
                if expect_table is not None and mc.group(1) != expect_table:
                    raise UnsupportedShape("the table named inside the dump file is not the file's name")
                schema = TableSchema(mc.group(1))
                in_create = True
                continue
            mi = _INSERT.match(line)
            if mi:
                if mi.group(2):
                    raise UnsupportedShape(f"INSERT with a column list at line {lineno}: re-dump without --complete-insert (columns are read by position)")
                if schema is None:
                    raise UnsupportedShape(f"INSERT before any CREATE TABLE at line {lineno} (a data-only file: mydumper layout is not supported)")
                if expect_table is not None and mi.group(1) != expect_table:
                    raise UnsupportedShape("an INSERT targets another table than the file's")
                if not line.rstrip().endswith(";"):
                    raise UnsupportedShape(f"an INSERT statement does not end on its line (line {lineno}): row-spanning statements are not read; re-dump with one statement per line")
                ordinal += 1
                if ordinal <= skip_statements:
                    continue
                width = len(schema.columns)
                it = iter_values(line[mi.end():])
                try:
                    cur = list(islice(it, batch_rows))
                    while cur:
                        for r in cur:
                            if len(r) != width:
                                raise UnsupportedShape(f"a row has {len(r)} values but the table has {width} columns (statement {ordinal}): a shifted or garbled row")
                        nxt = list(islice(it, batch_rows))
                        yield ("rows", mi.group(1), cur, ordinal, not nxt)
                        cur = nxt
                    next(it, None)                                # drains the generator: the grammar must also hold for the end of the statement
                except UnsupportedShape as e:
                    raise UnsupportedShape(f"statement {ordinal} (file line {lineno}): {e}") from None
                continue
            raise UnsupportedShape(f"a line the reader does not recognise (file line {lineno}): UPDATE, DELETE, ALTER, a conditional-comment statement or other text is refused, never skipped")
        if in_block:
            raise UnsupportedShape("a DELIMITER block is not closed before the end of the file")
        if in_create:
            raise UnsupportedShape("a CREATE TABLE statement is not closed before the end of the file")


def tsv_events(path, table, batch_rows=50000, charset="utf-8", strict=True):
    """TSV with a header line (MySQL SELECT INTO OUTFILE style, \\N = NULL). Schema: every column text."""
    with _open(path, charset, strict) as fh:
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
