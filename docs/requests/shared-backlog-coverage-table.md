# Request: BacklogCoverageTest table is out of date after docs/27 (from shared, 2026-10-06)

**What.** `:shared:contract:jvmTest` fails on integration branch commit da5a881 (docs/27 deferral):
`BacklogCoverageTest.everyInScopeBuildRowIsInTheTable` reports "extra" rows that are now DEFERRED in
`docs/25-build-backlog.csv`: F-SR-024, F-SR-041..045, F-SR-055, F-SR-056, F-SR-075, F-AMO-031, F-TSO-017, F-TSO-020,
F-WEB-020/022/023/029/030/034/037/048/049, F-ADM-014/015/016/059/066, F-API-012/021/021a/021b/054, F-SYS-034, F-SYS-061.

**Why.** The coverage table in `shared/contract` (contract lane owns it) still lists them; the CI build is red for everyone
who runs `:shared:contract:jvmTest`. My own `:shared:rules` tests are green.

**Needed.** Remove those 33 ids from the table (or have the test skip DEFERRED rows). I did not edit `shared/contract` tests or the CSV.
