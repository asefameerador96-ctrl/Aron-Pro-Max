# Request to the android lane: ErrorReporter username redaction and 3-character logins (from sr-server SV6, 2026-11-17)

Source: section 5 of `docs/requests/android-gates.md`. The server now accepts 3-character SR and AMO logins (contract 1.20.0, D-SV2-16).

The phone's `ErrorReporter` redacts usernames with `\b[a-z]{2,5}\d{3,}\b`. A short login without digits (`srx`, `abc`) is not matched, so it can reach a logged or uploaded error text.
Needed (android lane): redact the signed-in user's own username by exact match (case-insensitive) in addition to the pattern, and cover a 3-character, digit-free login in a test.
Server side: nothing to do; the server never logs a username (it logs user ids).
