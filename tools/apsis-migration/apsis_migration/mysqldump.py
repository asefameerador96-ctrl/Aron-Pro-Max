"""Strict streaming reader for mysqldump SQL files (optionally .gz) and TSV with a header. EVERY line of a file is accounted for: it is consumed by a known
construct or the file is REFUSED (UnsupportedShape): nothing is skipped silently. Values stay Python str / None (numbers stay as text: no float touches money).

Known constructs: blank lines; `--` comments and single-line `/* ... */` comments; the mysqldump directives `/*!NNNNN SET ... */;` (also with a space before the
semicolon, as written around trigger sections) and `/*!NNNNN ALTER TABLE `t` DISABLE|ENABLE KEYS */;`; plain `SET ...;`; `DROP TABLE IF EXISTS`; `LOCK TABLES`/`UNLOCK
TABLES`; `CREATE TABLE` (one per file when the file's table is known; any number in a whole-database file: each is its own table, name validated by the caller) incl.
the `/*!50100 PARTITION BY ... */` clause (recorded as partitioned + partition count, never the bounds); one `INSERT INTO t [(columns)] VALUES (...),(...);` per line
(a column list is mapped by name and must name existing columns); and `DELIMITER ;;` ... `DELIMITER ;` blocks (trigger/routine bodies), which are skipped WITHOUT reading
their statements (never counted as rows). Binary values (`_binary '..'`, `0x..`, `b'..'`, raw bytes in a quoted string) are accepted ONLY for columns declared binary/
varbinary/blob/bit/geometry and are opaque (a byte length, never decoded). Anything else (UPDATE, DELETE, ALTER, a prefixed or conditional-comment INSERT, REPLACE INTO, a
row-spanning INSERT, ...) refuses the file."""
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
_PART = re.compile(r"/\*!\d{5} PARTITION BY")
_PARTITION_WORD = re.compile(r"(?<![A-Za-z])PARTITION\s+(?!BY\b)[`A-Za-z0-9_]+", re.I)
_PARTITIONS_N = re.compile(r"PARTITIONS\s+([0-9]+)", re.I)
_BIT = re.compile(r"[bB]'[01]*'|0b[01]+")
_HEX = re.compile(r"0x[0-9A-Fa-f]+")
BINARY_TYPES = {"binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob", "bit", "geometry", "point", "linestring", "polygon", "multipoint",
                "multilinestring", "multipolygon", "geometrycollection"}
_SURR = re.compile(r"[\udc80-\udcff]")


class Binary:
    """An opaque binary value: only its length in bytes is known. Never decoded, never printed."""
    __slots__ = ("nbytes",)

    def __init__(self, nbytes):
        self.nbytes = nbytes


ABSENT = object()           # a column that a column-list INSERT does not name (generated columns are normally absent)
_DIRECTIVE_SET = re.compile(r"^/\*!\d{5} SET [^;*]*\*/ ?;$")
_DIRECTIVE_KEYS = re.compile(r"^/\*!\d{5} ALTER TABLE `[^`]*` (DISABLE|ENABLE) KEYS \*/;$")
_COMMENT = re.compile(r"^/\*(?!!)(?:(?!\*/).)*\*/$")             # a plain comment: no `*/` inside (so `/* c */ INSERT ... /* d */` is not one)
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
    partitioned: bool = False
    partitions: int = 0

    def column_names(self):
        return [c.name for c in self.columns]


def _unescape(body: str) -> str:
    """One pass, left to right: a backslash escape or a doubled quote is decoded once. NUL is removed."""
    if "\\" not in body and "''" not in body:
        return body
    return _UNESC.sub(lambda m: "'" if m.group(1) is None else _ESC.get(m.group(1), m.group(1)), body)


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
            elif text.startswith("_binary", i) and i + 7 < n and text[i + 7] in " '":
                k = _WS.match(text, i + 7).end()
                if k >= n or text[k] != "'":
                    raise UnsupportedShape(_BAD)
                j = _scan_string(text, k)
                cur.append(Binary(len(_unescape(text[k + 1:j - 1]).encode("utf-8", "surrogateescape"))))
                i = j
            elif _HEX.match(text, i):
                m = _HEX.match(text, i)
                cur.append(Binary((len(m.group(0)) - 2 + 1) // 2))
                i = m.end()
            elif _BIT.match(text, i):
                m = _BIT.match(text, i)
                bits = len(m.group(0)) - (3 if m.group(0)[0] in "bB" else 2)
                cur.append(Binary(max(1, (bits + 7) // 8)))
                i = m.end()
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
    errors = "surrogateescape" if strict else "replace"      # strict: non-UTF-8 bytes survive as lone surrogates and are judged per column (binary: opaque; text: charset refusal)
    if str(path).endswith(".gz"):
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding=charset, errors=errors, newline="\n")
    return open(path, "r", encoding=charset, errors=errors, newline="\n")


# --- named constructs of whole-database dumps, consumed UNREAD (never profiled, never printed)
_CREATE_DB = re.compile(r"^CREATE DATABASE (?:/\*!\d{5} IF NOT EXISTS\*/ )?`[^`]*`(?: /\*!\d{5} (?:(?!\*/)[^;])*\*/)*;$", re.I)
_ALTER_DB = re.compile(r"^ALTER DATABASE `[^`]*` CHARACTER SET [A-Za-z0-9_]+ COLLATE [A-Za-z0-9_]+ ?;$", re.I)
_USE = re.compile(r"^USE `[^`]*`;$", re.I)
_DROPX = re.compile(r"^/\*!\d{5} DROP (?:TABLE|VIEW|PROCEDURE|FUNCTION|EVENT|TRIGGER) IF EXISTS `[^`]*` ?\*/ ?;$", re.I)
_STANDIN_START = re.compile(r"^/\*!\d{5} CREATE TABLE `[^`]*` \($")             # a view's stand-in table: never a table in the profile
_STANDIN_VIEW_START = re.compile(r"^/\*!\d{5} CREATE VIEW `[^`]*` AS SELECT(?: 1 AS `[^`]*`,?)*( ?\*/;)?$")
_STANDIN_VIEW_COL = re.compile(r"^ ?1 AS `[^`]*`(,|\s?\*/;)$")
_STANDIN_COL = re.compile(r"^\s*`[^`]*` [A-Za-z]")
_STANDIN_END = re.compile(r"^\) ENGINE=[A-Za-z0-9_]+ \*/;$")
_VIEW_ALGO = re.compile(r"^/\*!\d{5} CREATE ALGORITHM=[A-Z]+ \*/$")
_VIEW_DEFINER = re.compile(r"^/\*!\d{5} DEFINER=(?:(?!\*/).)* \*/$")
_VIEW_BODY = re.compile(r"^/\*!\d{5} VIEW `[^`]*` AS (?:(?!\*/).)* \*/;$")
_ROCKSDB = re.compile(r"^/\*!50717 (?:SELECT COUNT\(\*\) INTO @rocksdb_\w+ FROM \w+\.\w+ WHERE (?:(?!\*/).)*|SET @rocksdb_\w+ ?= ?(?:(?!\*/).)*|PREPARE s FROM @rocksdb_\w+|EXECUTE s|DEALLOCATE PREPARE s) ?\*/;$")
_GTID_START = re.compile(r"^SET @@GLOBAL\.GTID_PURGED\s*=\s*(?:/\*!\d{5} '[+-]?'\s*\*/\s*)?'[0-9a-fA-F:,\-]*$")
_GTID_CONT = re.compile(r"^[0-9a-fA-F:,\-]*(?:';)?$")
_COLLIST = re.compile(r"^\(`[^`]+`(?:, ?`[^`]+`)*\)$")
# partition clause grammar (the bounds are values: they are matched by character class, never kept)
_PEXPR = "A-Za-z0-9_`,. ()+\\-"
_PCHAR = re.compile("[" + _PEXPR + "]")
_PHEAD = re.compile(r"\s*(?:(?:SUB)?PARTITION BY (?:LINEAR )?(?:RANGE|LIST|HASH|KEY)(?:\s+COLUMNS)?)\s*(?=\()", re.I)
_PTOK = re.compile(r"\s*(?:(?:SUB)?PARTITION\s+`?[A-Za-z0-9_]+`?|VALUES\s+LESS\s+THAN\s+(?:MAXVALUE|\([" + _PEXPR + r"'\-:]*?\)(?=\s*(?:[,)]|ENGINE|STORAGE|COMMENT|$)))"
                   r"|VALUES\s+IN\s+\([" + _PEXPR + r"'\-:]*?\)(?=\s*(?:[,)]|ENGINE|STORAGE|COMMENT|$))|(?:STORAGE\s+)?ENGINE\s*=\s*[A-Za-z0-9_]+|COMMENT\s*=\s*'[A-Za-z0-9_ ]*'"
                   r"|(?:SUB)?PARTITIONS\s+[0-9]+|[(),])", re.I)


def _partition_line(s, first):
    """One line of a /*!50100 PARTITION BY ... */ clause. Returns (definitions, PARTITIONS n or None, ends_here). Anything outside the grammar refuses."""
    end = False
    m = re.search(r"\s*\*/;$", s)
    if m:
        end, s = True, s[:m.start()]
    if first:
        s = re.sub(r"^/\*!\d{5}\s+", "", s, count=1)
    pos, heads = 0, 0
    while True:
        mh = _PHEAD.match(s, pos)
        if not mh:
            break
        depth, j = 0, mh.end()
        while j < len(s):
            ch = s[j]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    break
            elif not _PCHAR.match(ch):
                raise UnsupportedShape("a partition expression outside the recognised grammar")
            j += 1
        if depth != 0 or j >= len(s):
            raise UnsupportedShape("an unclosed partition expression")
        pos, heads = j + 1, heads + 1
    if first and not heads:
        raise UnsupportedShape("a partition clause outside the recognised grammar")
    while s[pos:].strip():
        mt = _PTOK.match(s, pos)
        if not mt:
            raise UnsupportedShape("a line of a PARTITION clause outside the recognised grammar (PARTITION name VALUES ... ENGINE = x): a partition clause is never skipped")
        pos = mt.end()
    defs = len(re.findall(r"(?<![A-Za-z])PARTITION\s+(?!BY\b)`?[A-Za-z0-9_]+", s, re.I))
    mn = re.search(r"(?<![A-Za-z])PARTITIONS\s+([0-9]+)", s, re.I)
    return defs, (int(mn.group(1)) if mn else None), end


class _BlockScan:
    """Scans a DELIMITER ;; block with a linear quote/comment state machine. The block ends only at a `DELIMITER ;` line that follows a statement terminated by `;;`
    OUTSIDE quotes and comments: a forged `DELIMITER ;` inside a string or an unfinished statement never exposes the lines after it."""

    def __init__(self):
        self.q, self.pending, self.terms = None, False, 0

    def feed(self, line):
        if line.strip() == "DELIMITER ;":
            if self.q is not None:
                pass                                          # inside a string or comment: just text
            elif self.pending or self.terms == 0:
                raise UnsupportedShape("a DELIMITER block ends in the middle of a statement (no ;; terminator before DELIMITER ;)")
            else:
                return True
        i, n = 0, len(line)
        while i < n:
            c = line[i]
            if self.q in ("'", '"', "`"):
                if c == "\\" and self.q != "`":
                    i += 2
                    continue
                if c == self.q:
                    self.q = None
            elif self.q == "/*":
                if c == "*" and line[i + 1:i + 2] == "/":
                    self.q = None
                    i += 1
            else:
                if c in "'\"`":
                    self.q, self.pending = c, True
                elif c == "/" and line[i + 1:i + 2] == "*":
                    self.q = "/*"
                    i += 1
                elif c == "#" or (c == "-" and line[i + 1:i + 2] == "-" and (i + 2 >= n or line[i + 2] in " \t")):
                    break
                elif c == ";" and line[i + 1:i + 2] == ";":
                    self.pending, self.terms = False, self.terms + 1
                    i += 1
                elif not c.isspace():
                    self.pending = True
            i += 1
        return False



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


def _check_row(row, schema, order, lineno, ordinal):
    """Width by position (or by the INSERT's column list), binary values only in binary-typed columns, non-UTF-8 bytes only in binary-typed columns.
    Returns the row in table order (ABSENT for a column the INSERT does not name)."""
    cols = schema.columns
    width = len(order) if order is not None else len(cols)
    if len(row) != width:
        raise UnsupportedShape(f"a row has {len(row)} values but the {'column list names' if order is not None else 'table has'} {width} columns (statement {ordinal}): a shifted or garbled row")
    out = row if order is None else [ABSENT] * len(cols)
    for k, v in enumerate(row):
        i = k if order is None else order[k]
        binary = cols[i].base in BINARY_TYPES
        if not binary and isinstance(v, str) and "\0" in v:
            v = v.replace("\0", "")                              # a NUL in a text value cannot be stored in text columns; binary columns keep exact byte lengths
        if isinstance(v, Binary):
            if not binary:
                raise UnsupportedShape(f"a binary literal in a column that is not declared binary (statement {ordinal})")
        elif v is not None and binary and isinstance(v, str) and not v.isascii():
            row_v = Binary(len(v.encode("utf-8", "surrogateescape")))
            v = row_v
        elif isinstance(v, str) and not v.isascii() and _SURR.search(v):
            raise CharsetError(f"the file is not valid text in the stated character set near line {lineno}: do not guess; the owner decides the character set (Q2), then pass --charset")
        elif binary and isinstance(v, str):
            v = Binary(len(v.encode("utf-8", "surrogateescape")))
        if order is None:
            if out is row:
                out = list(row)
            out[i] = v
        else:
            out[i] = v
    return tuple(out)


def events(path, batch_rows=50000, skip_statements=0, charset="utf-8", strict=True, expect_table=None, multi=False):
    """Yield ('schema', TableSchema) and ('rows', table, rows, statement_ordinal, statement_done). expect_table: the file's table (stem) when it is known; a CREATE of
    another name is refused and a second CREATE is refused. multi=True: a whole-database file: every CREATE TABLE section is its own table (the caller validates the
    name against its allow-list), an INSERT must target the table whose CREATE is current. skip_statements: INSERT statements with ordinal <= this are skipped (resume).
    A refusal carries `table_hint`: the table whose section was being read (a raw name: the caller validates it before printing)."""
    state = {"schema": None, "body": True}
    try:
        yield from _events(path, batch_rows, skip_statements, charset, strict, expect_table, multi, state)
    except (UnsupportedShape, CharsetError) as e:
        e.table_hint = state["schema"].name if state["schema"] is not None else None
        e.in_body = state["body"]            # False: raised after at least one complete INSERT of that table (its data was complete), in text between sections
        raise


def _events(path, batch_rows, skip_statements, charset, strict, expect_table, multi, state):
    schema, in_create, in_block, in_part, in_tail, in_gtid, in_standin = None, False, None, False, False, False, False
    creates, ordinal, part_count = 0, 0, 0
    seen = set()

    def finish_part():
        schema.partitions = part_count

    with _open(path, charset, strict) as fh:
        for lineno, line in _lines(fh):
            if in_block is not None:                             # a DELIMITER block (trigger/routine/event body): skipped WITHOUT reading its statements
                if in_block.feed(line):
                    in_block = None
                continue
            if in_gtid:                                          # the continuation of a multi-line SET @@GLOBAL.GTID_PURGED='...,\n...';
                if not _GTID_CONT.match(line):
                    raise UnsupportedShape(f"a line inside a GTID_PURGED header the reader does not recognise (file line {lineno})")
                if line.endswith("';"):
                    in_gtid = False
                continue
            if in_standin:                                       # a view's stand-in table: consumed unread, never a table of the profile
                if _STANDIN_END.match(line):
                    in_standin = False
                elif in_standin == "view":                       # the 8.0 form: CREATE VIEW `v` AS SELECT / 1 AS `c`, / 1 AS `d`*/;
                    if not _STANDIN_VIEW_COL.match(line):
                        raise UnsupportedShape(f"a line inside a view stand-in the reader does not recognise (file line {lineno})")
                    if line.endswith("*/;"):
                        in_standin = False
                elif not _STANDIN_COL.match(line):
                    raise UnsupportedShape(f"a line inside a view stand-in table the reader does not recognise (file line {lineno})")
                continue
            if in_tail:                                          # `) ENGINE=...` without `;`: only a PARTITION clause may follow
                in_tail = False
                s = line.strip()
                if not _PART.search(s):
                    raise UnsupportedShape(f"the closing line of a CREATE TABLE does not end the statement (file line {lineno})")
                schema.partitioned = True
                d, n, end = _partition_line(s, True)
                part_count = n if n is not None else d
                if end:
                    finish_part()
                    yield ("schema", schema)
                else:
                    in_part = True
                continue
            if in_part:                                          # the rest of a multi-line /*!50100 PARTITION BY ... */ clause: matched against the grammar, never read
                d, n, end = _partition_line(line.strip(), False)
                part_count = n if n is not None else part_count + d
                if end:
                    in_part = False
                    finish_part()
                    yield ("schema", schema)
                continue
            if in_create:
                s = line.strip()
                if s.startswith(")"):
                    in_create = False
                    me, mc2 = _TAIL.search(s), _TAILCS.search(s)
                    schema.engine, schema.charset = (me.group(1) if me else ""), (mc2.group(1) if mc2 else "")
                    mpart = _PART.search(s)
                    if mpart:
                        schema.partitioned = True
                        d, n, end = _partition_line(s[mpart.start():], True)
                        part_count = n if n is not None else d
                        if end:
                            finish_part()
                            yield ("schema", schema)
                        else:
                            in_part = True
                        continue
                    if not s.endswith(";"):
                        in_tail = True
                        continue
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
                in_block = _BlockScan()
                continue
            if _GTID_START.match(s):
                in_gtid = True
                continue
            if (_DIRECTIVE_SET.match(s) or _DIRECTIVE_KEYS.match(s) or _SET.match(s) and s.count(";") == 1 or _DROP.match(s) or _LOCK.match(s)
                    or _CREATE_DB.match(s) or _USE.match(s) or _ALTER_DB.match(s) or _DROPX.match(s) or _VIEW_ALGO.match(s) or _VIEW_DEFINER.match(s) or _VIEW_BODY.match(s) or _ROCKSDB.match(s)):
                continue
            if _STANDIN_START.match(s):
                in_standin = True
                continue
            mv = _STANDIN_VIEW_START.match(s)
            if mv:
                in_standin = False if mv.group(1) else "view"      # a single-line stand-in ends on its own line
                continue
            head = line.lstrip()[:12].upper()
            if head.startswith("REPLACE"):
                raise UnsupportedShape(f"REPLACE INTO at line {lineno} (mysqldump --replace) is not read: re-dump without --replace")
            if head.startswith("INSERT") and not _INSERT.match(line):
                raise UnsupportedShape(f"an INSERT shape the reader does not know (indented, prefixed, or not a plain INSERT INTO t VALUES) at line {lineno}")
            mc = _CREATE.match(s)
            if mc:
                creates += 1
                if not multi and creates > 1:
                    raise UnsupportedShape(f"a second CREATE TABLE in the file (line {lineno}): one table per file")
                if not multi and expect_table is not None and mc.group(1) != expect_table:
                    raise UnsupportedShape("the table named inside the dump file is not the file's name")
                if multi:
                    if mc.group(1) in seen:
                        raise UnsupportedShape(f"the same table is created twice in one file (line {lineno})")
                    seen.add(mc.group(1))
                    ordinal = 0
                if multi and schema is not None:
                    yield ("end", schema.name)                    # the previous section is over: the caller can close its profile before this one is read
                schema = TableSchema(mc.group(1))
                state["schema"], state["body"] = schema, True
                in_create = True
                continue
            mi = _INSERT.match(line)
            if mi:
                if schema is None:
                    raise UnsupportedShape(f"INSERT before any CREATE TABLE at line {lineno} (a data-only file: mydumper layout is not supported)")
                if mi.group(1) != schema.name:
                    raise UnsupportedShape("an INSERT targets another table than the CREATE TABLE that precedes it")
                order = None
                if mi.group(2):
                    if not _COLLIST.match(mi.group(2)):
                        raise UnsupportedShape(f"an INSERT column list that is not exactly comma-separated backticked names (line {lineno})")
                    names = re.findall(r"`([^`]*)`", mi.group(3))
                    cn = schema.column_names()
                    if not names or len(names) != len(set(names)) or any(n not in cn for n in names):
                        raise UnsupportedShape(f"INSERT with a column list that names a column the table does not have (line {lineno})")
                    order = [cn.index(n) for n in names]
                if not line.rstrip().endswith(";"):
                    raise UnsupportedShape(f"an INSERT statement does not end on its line (line {lineno}): row-spanning statements are not read; re-dump with one statement per line")
                ordinal += 1
                if ordinal <= skip_statements:
                    continue
                state["body"] = True
                it = iter_values(line[mi.end():])
                try:
                    cur = list(islice(it, batch_rows))
                    while cur:
                        cur = [_check_row(r, schema, order, lineno, ordinal) for r in cur]
                        nxt = list(islice(it, batch_rows))
                        yield ("rows", mi.group(1), cur, ordinal, not nxt)
                        cur = nxt
                    next(it, None)                                # drains the generator: the grammar must also hold for the end of the statement
                    state["body"] = False
                except UnsupportedShape as e:
                    raise UnsupportedShape(f"statement {ordinal} (file line {lineno}): {e}") from None
                continue
            raise UnsupportedShape(f"a line the reader does not recognise (file line {lineno}): UPDATE, DELETE, ALTER, a conditional-comment statement or other text is refused, never skipped")
        if in_block is not None:
            raise UnsupportedShape("a DELIMITER block is not closed before the end of the file")
        if in_gtid or in_standin or in_tail or in_part or in_create:
            raise UnsupportedShape("a statement is not closed before the end of the file")


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
