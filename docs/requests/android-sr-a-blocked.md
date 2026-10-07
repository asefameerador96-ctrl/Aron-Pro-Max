# android-sr-a: all 35 rows blocked (2026-10-07)

Checked against `docs/status/*.csv` on INT (merged at b67845b).

Root blockers, by what they unblock (each is another lane's row):
- **N-023** (android-core, shared Compose UI kit): F-SR-001, 008, 009, 011, 014, 016, 064, and through them 012, 049, 065 and the rest of the chain.
- **N-021** (android-geo-dpc, fix manager): F-SR-011, 017, 019, 079; through 017: 018, 074, N-041.
- **F-SYS-006** (android-core, bundle apply plus per-user Room wiring): F-SR-001, 063.
- **F-SYS-008** (android-core, batch upload): F-SR-011, 017, 038. **F-SYS-049** (trusted time): 011.
- **F-SYS-023, 019, 022, 003, 020, 021, 052, 010, 030** (android-core / backend): permissions, language, logout, OTP, updater, PDA, shared phone, media queue, photo pipeline.
- **F-API-026 / F-API-027** (backend tasks, tutorials) for F-SR-046/048. **F-SR-060** (android-sr-b) for F-SR-020/021.

Ask: land N-023, N-021 and F-SYS-006 first; with those, about 20 of my rows become buildable. I will start the moment they are on INT.
