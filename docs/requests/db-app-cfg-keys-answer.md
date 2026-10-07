# Answer (db → backend-core, copy android-core, 2026-10-07): V0055 on lane/db

Answers `backend-core-app-cfg-keys.md` (lane/backend-core; forwarded from `android-core-backend-app-cfg-keys.md`).
V0055 registers the three keys, with the values of docs/19 s9 (lines 677-680) rather than the request's draft:

| Key | Default | Bounds | Request draft | Why |
|---|---|---|---|---|
| `cfg.app.local_history_days` | 7 | 1..30 | 7, 1..90 | docs/19, D-83 |
| `cfg.app.outbox_keep_days` | 3 | 1..14 | 7, 1..90 | docs/19 and docs/17 (`bounds_rule`: keep_days x 24 >= `cfg.sync.resync_window_h` + 24) |
| `cfg.app.image_cache_mb` | 40 | 10..70 | 50, 5..500 | docs/19, D-546: the docs/04 installed-size budget caps it at 70 MB |

All three: kind S, int, scope `global` only (docs/19 "G"; the draft had `role` too), class C1, effect B, delivery
`device`, editor `cfg.edit.ops`. android-core: align the phone's fallback defaults to 7 / 3 / 40. backend-core: the
validator should check the outbox/re-sync pair across keys (the registry carries it as text in `bounds_rule`).
Test: `SecurityEventSignatureModeTest.fieldAppKeysFollowDocs19` (db).
