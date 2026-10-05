# Request (db → lead): seed the docs/19 config keys that are not in docs/24 s9.5?

docs/24 s9.5 ends with "Keys of `docs/19` not listed here keep their `docs/19` definition and are seeded too".
V0006 seeds the 172 keys of s9.5 only. docs/19 names about 690 keys; roughly 550 are not in s9.5 (some are renames
the s9.5 table already replaced, for example the semver release keys).

**Asked:** confirm that the remaining docs/19 keys should be seeded (Phase 1 code may not depend on them, s9.5),
or name the subset. On a yes the db lane writes one follow-up migration (`INSERT ... ON CONFLICT DO NOTHING` into
`app.cfg_key`), excluding keys that s9.5 renamed or replaced.

**Meanwhile:** nothing reads them; the registry holds exactly s9.5.
