# backend-reports request to the db lane: entered_by_user_id in dw, and fact_supervisor_day

Filed 2026-10-15 by backend-reports. Two small additive changes; the Aggregator code is ready to follow each one.

## 1. `entered_by_user_id` on `dw.fact_memo` and `dw.fact_visit`

V0077 put `entry_source` into dw and the Aggregator copies it (done). `app.memo` and `app.visit` also carry
`entered_by_user_id` (the web user who keyed a paper memo or a dead-phone day, F-API-038), but V0077 did not copy it to dw,
so "who keyed the manual entries" cannot be answered from dw (audit and the manual-entry report).

```sql
ALTER TABLE dw.fact_memo  ADD COLUMN entered_by_user_id bigint;   -- NULL for entry_source = 'device'
ALTER TABLE dw.fact_visit ADD COLUMN entered_by_user_id bigint;
-- Same invariant as app.*: (entry_source = 'manual') = (entered_by_user_id IS NOT NULL), added NOT VALID then validated in a later file.
COMMENT ON COLUMN dw.fact_memo.entered_by_user_id  IS 'Web user who keyed the memo (entry_source manual), copied from app.memo.';
COMMENT ON COLUMN dw.fact_visit.entered_by_user_id IS 'Web user who keyed the visit (entry_source manual), copied from app.visit.';
```

No foreign key (dw facts carry no FKs). Expose it in a stable view only if the dictionary lane wants it (PII-adjacent: a user id, not a name).
After it lands I add it to the projector copy (`Aggregator`) with a test; the rebuild path already re-reads the whole row.

## 2. `dw.fact_supervisor_day` (F-AMO-039, docs/16 s2 line 1853 and s1928)

docs/16 plans `fact_supervisor_day` (user x date) but no migration creates it, and no rebuild reads `app.supervisor_day`.
Needed for Q38 ("supervisor days without a check-in") and for the AMO/TSO day-control views, kept apart from route-day
Login % and Submit % (D-27).

```sql
CREATE TABLE dw.fact_supervisor_day (
  user_id             bigint NOT NULL,
  business_date       date   NOT NULL,
  role                text   NOT NULL,                 -- amo | tso, from app_user at projection time
  zone_id             bigint,                          -- home zone of the user that day (NULL for a TSO with several)
  checked_in_at       timestamptz,                 -- as app.supervisor_day
  checked_out_at      timestamptz,
  sales_submitted_at  timestamptz,
  submit_cycle        int NOT NULL DEFAULT 1,
  submit_voided       boolean NOT NULL DEFAULT false,
  last_event_id       bigint,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, business_date)
);
CREATE INDEX ON dw.fact_supervisor_day (business_date, zone_id);
```

Needs the usual: a data-dictionary comment per column, grants for the analytics worker role, and `app.supervisor_day`
in the dirty-key trigger list (I found no trigger on it in V0001 to V0077 other than the touch trigger; docs/16 s1714 lists it). The columns follow `app.supervisor_day` (it has no first-sync or dues column; the docs/16 list predates V0007);
db may rename. Reply with the migration number and I wire the projector, the rebuild and one report.
