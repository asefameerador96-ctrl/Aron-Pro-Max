"""Structural parse check for PowerShell scripts when pwsh is not installed (D-FINAL-INFRA-11).

PowerShell parses a whole script before running it, so ONE stray or missing brace stops the script at the first command
(tools/github-governance.ps1 had a leftover `}` for weeks, found only by reading it). This is a lexer, not a parser: it
tracks comments (# and <# #>), single- and double-quoted strings (with backtick escapes and $( ) sub-expressions), here-strings
and the bracket stack of code, and reports the first unbalanced or mismatched ), ], } or an unterminated string/comment.
It is what the CI runner can run without pwsh; on a machine that has pwsh the same files can also be parsed for real:
  pwsh -NoProfile -Command "[System.Management.Automation.Language.Parser]::ParseFile('<file>',[ref]$null,[ref]$e); $e"
"""
import sys

PAIRS = {"{": "}", "(": ")", "[": "]"}


def check(text):
    """Returns None when balanced, else 'line N: message'."""
    i, n, line = 0, len(text), 1
    stack = []  # entries: (closer, line, kind) kind: 'code' or 'sub' ($( ... ) inside a double-quoted string)
    mode = "code"
    # dq stack frames hold the stack depth to return to
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
        if mode == "code":
            if c == "<" and nxt == "#":
                end = text.find("#>", i + 2)
                if end < 0:
                    return f"line {line}: unterminated <# comment"
                line += text.count("\n", i, end)
                i = end + 2
                continue
            if c == "#":
                j = text.find("\n", i)
                i = n if j < 0 else j
                continue
            if c == "@" and nxt in ("'", '"') and text[i + 2:i + 4].lstrip(" \t\r")[:0] == "" and text[i + 2:i + 3] in ("\r", "\n"):
                q = nxt
                end = text.find("\n" + q + "@", i + 2)
                if end < 0:
                    return f"line {line}: unterminated here-string"
                line += text.count("\n", i, end + 1)
                i = end + 3
                continue
            if c == "`":
                if nxt == "\n":
                    line += 1
                i += 2
                continue
            if c == "'":
                j = i + 1
                while j < n:
                    if text[j] == "\n":
                        line += 1
                    if text[j] == "'":
                        if j + 1 < n and text[j + 1] == "'":
                            j += 2
                            continue
                        break
                    j += 1
                else:
                    return f"line {line}: unterminated single-quoted string"
                i = j + 1
                continue
            if c == '"':
                stack.append(('"', line, "dq"))
                mode = "dq"
                i += 1
                continue
            if c in PAIRS:
                stack.append((PAIRS[c], line, "code"))
            elif c in ")]}":
                if not stack or stack[-1][2] == "dq":
                    return f"line {line}: unexpected {c!r}"
                closer, opened, kind = stack.pop()
                if closer != c:
                    return f"line {line}: {c!r} closes the {closer!r} opened at line {opened}"
                if kind == "sub":
                    mode = "dq"
            i += 1
        else:  # inside "..."
            if c == "`":
                if nxt == "\n":
                    line += 1
                i += 2
                continue
            if c == '"':
                if i + 1 < n and text[i + 1] == '"':  # "" is a literal quote
                    i += 2
                    continue
                stack.pop()
                mode = "code"
                i += 1
                continue
            if c == "$" and nxt == "(":
                stack.append((")", line, "sub"))
                mode = "code"
                i += 2
                continue
            i += 1
    if mode != "code":
        return f"line {line}: unterminated double-quoted string"
    if stack:
        closer, opened, _ = stack[-1]
        return f"line {opened}: missing {closer!r}"
    return None


if __name__ == "__main__":
    bad = 0
    for f in sys.argv[1:]:
        r = check(open(f, encoding="utf-8").read())
        print(f"{f}: {'ok' if r is None else r}")
        bad += r is not None
    sys.exit(1 if bad else 0)
