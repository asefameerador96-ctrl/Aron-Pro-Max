# Forecast (lead, updated each lane check)

Day 1 = 2026-10-05, Day 7 = 2026-10-11, hard cap Day 10 = 2026-10-14. Written 2026-10-07 03:50 UTC (about Day 3 morning).

## Method
Rows done: 26 of 446. Calibration from the Day-1 time logs: S 18 min, M 34 min, L 48 min per row including the checker (thin sample: 18 rows, all foundation work). Real work will be slower (UI rows, device checks, integration, fix cycles), so the table also shows a x2.5 pessimistic factor. Lane-days assume about 14 productive hours a day per lane.

| Sub-lane | Rows left | Optimistic lane-hours | Pessimistic (x2.5) | Pessimistic lane-days |
|---|---|---|---|---|
| backend-core | 48 | 21.5 | 53.8 | 3.8 |
| web-config | 40 | 18.8 | 46.9 | 3.4 |
| backend-admin | 41 | 17.7 | 44.3 | 3.2 |
| web-dashboard | 48 | 16.8 | 41.9 | 3.0 |
| android-amo | 40 | 16.3 | 40.7 | 2.9 |
| android-core | 34 | 15.0 | 37.4 | 2.7 |
| android-sr-a | 35 | 14.5 | 36.2 | 2.6 |
| backend-reports | 27 | 14.1 | 35.2 | 2.5 |
| android-sr-b | 22 | 11.6 | 28.9 | 2.1 |
| web-admin | 23 | 10.1 | 25.2 | 1.8 |
| qa | 18 | 9.2 | 23.1 | 1.6 |
| android-tso | 22 | 8.7 | 21.8 | 1.6 |
| android-geo-dpc | 9 | 5.8 | 14.4 | 1.0 |
| android-print | 8 | 3.9 | 9.8 | 0.7 |
| infra | 4 | 2.0 | 5.0 | 0.4 |
| shared | 1 | 0.8 | 2.0 | 0.1 |
| **total** | 420 | 186.7 | 466.8 | |

## Reading
- With the 8 lanes started on 2026-10-07 and the 6 existing lanes working continuously, the longest pessimistic chain is backend-core (about 3 lane-days) and the SR app lanes; the rest run in parallel. That fits inside Day 7 (2026-10-11) **if lanes never idle again**. Overnight on 2026-10-06 to 07 two critical lanes (backend-core, android-core) sat idle for about 15 hours waiting for a go-ahead: that cost roughly one lane-day each and is the main reason Day 2 is not finished.
- Not parallelisable by adding lanes: the owner's device checks (printing on the MP-58N, GPS and spoofing apps on the phones, device-owner enrolment on a factory-reset phone, the 8-hour battery run) and integration of the SR slice end to end. These need the owner's hands; they are listed in docs/status/device-checks.md. Schedule risk is concentrated there.
- Wave 2 lanes (android-amo, android-tso, qa) start when the SR slice (login, bundle, visit, sale, memo, sync) runs end to end on dev, expected within Day 3 to Day 4.
- The full 8,500-user proof depends on the final Azure account (docs/28); until then the claim is designed and tested small.

## Status of the plan
Day 7 holds if: (1) no lane idles more than 2 hours (the lane check runs every 2 hours), (2) the owner runs the device checks the evening they are posted, (3) usage limits do not stop work for more than a few hours (docs/29 s6). Day 8 to 10 stay the buffer, not the plan.
