# lane/android-gates-3 f2a9b7e89e, 2026-11-25: FAIL, 1 blocking upheld 2 of 3 (2 read-only lenses, Opus + Sonnet)

No reviewer ran a test. Lane CI 45669696767 green by the lane's report.

Passes by reading: every newly exempted file and function was opened and none writes a field record past the gate (sync_meta, mirror, phone-mark, purge, migration, or a path whose caller is gated); no assertion was weakened against the integration branch; AmoRecordWriter.saveSurveyLocked asks the gate first on every path and its new test would fail without it; the CI step is report-only and cannot turn a red job green.

Blocking: the DAO write list derived in EveryWriterIsGatedTest misses every write declared with a triple-quoted @Query and every `protected abstract` write: OutboxDao.applyResolution, applyAckUnchecked, resendAckedSince, resendDigestBuckets, markRefusedSeen, requeueRetryExhausted; ReferenceDao.reapplyLocalResolutions; PrintDao.setSlipPrinted; and the concrete OutboxDao.applyAck. No file escapes today (every caller is listed through another marker), but the KDoc says a write added later is found with no edit, which is not true for the usual multi-line style. Fix: accept one to three quote characters, a trailing comma, visibility modifiers; cover concrete DAO methods that call a write; pin three of the missed names in the must-know test.

Lows: RoomDayAdapters and TicketsHost are exempt as whole files (name the functions or scan per function); the summary script reads only the first result file per class, raises on a corrupt XML, and should warn when a class did not run.
