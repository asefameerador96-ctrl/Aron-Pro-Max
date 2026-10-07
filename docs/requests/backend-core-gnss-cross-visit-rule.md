# backend-core to backend-admin: a cross-visit GNSS rule in the risk worker (N-028 follow-up)

**Filed 2026-10-07 by backend-core (session 8).**

N-028 accepts when "a fix claiming an open-sky position with zero satellites, and a fix whose signal strength is
identical across 20 visits, each raise a risk signal; a normal fix raises none". backend-core raises
`GEO_GNSS_INCONSISTENT` per fix at ingest exactly as docs/24 s11.4 defines it (lane/backend-core b5c4b209,
`GnssRule.kt`; BC-72): fewer than `cfg.geo.gnss_min_satellites_used` satellites used, at least 6 used with a C/N0
standard deviation below `cfg.geo.gnss_cn0_stddev_min_dbhz`, or a C/N0 mean above `cfg.geo.gnss_cn0_mean_max_dbhz`.

That per-fix rule misses a simulator that replays the same, plausibly varied sky every time. Catching it needs the
fixes of a user-day, which is the risk worker's (`masterdata/RiskSignals.kt`, yours).

**Ask:** a rule in `RiskRules` over the user-day's `gps` fixes: when at least 20 of them carry a GNSS summary whose
(`satellites_used`, `cn0_used_mean_dbhz` rounded to 0.1, `cn0_used_stddev_dbhz` rounded to 0.1) are identical, raise
`GEO_GNSS_INCONSISTENT` on subject `user` with evidence `{"reason":"identical_sky","fixes":n,...}` (severity 3,
weight 30, as s11.4). The summary is in the record payload (`fix.gnss`); `app.geo_fix` does not hold it today, so
either read it from the source record or ask db for columns. No new config key is needed for the pilot (20 is the
row's number); log it if you add one. Tell backend-core when it lands so N-028 can be marked done.
