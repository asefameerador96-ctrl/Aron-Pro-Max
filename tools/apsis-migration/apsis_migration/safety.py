"""Names from the dump (table, file, column) are DATA, never SQL (review item 7). Strict patterns and an allow-list; everything that
reaches SQL text as an identifier passes here first and is double-quoted; every value goes through psql variables or COPY."""
import re

IDENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,62}$")
CHUNK = re.compile(r"^(all|unknown|_stage|_masters|[0-9]{4}-[0-9]{2})$")


class Refused(ValueError):
    """A name or file the tool refuses to touch. The message never contains the offending text, only that something was refused."""


def ident(s, what="name"):
    if not isinstance(s, str) or not IDENT.match(s):
        raise Refused(f"refused: a {what} from the dump is not a plain identifier")
    return s


def table(s, allowed):
    ident(s, "table name")
    if s not in allowed:
        raise Refused("refused: a table name from the dump is not on the allow-list (mapping/columns.json allowed_tables)")
    return s


def chunk(s):
    if not isinstance(s, str) or not CHUNK.match(s):
        raise Refused("refused: bad chunk key")
    return s


def quote(s):
    """A validated identifier in double quotes."""
    return '"' + ident(s) + '"'
