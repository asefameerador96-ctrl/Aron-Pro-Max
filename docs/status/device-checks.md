# Device checks (owner's phones and printer)

The lead posts each check here as soon as the build for it exists, with exact steps (about 10 minutes each). The owner runs them in the evening and replies with what happened. Lanes add rows marked DEVICE-PENDING.

| # | Check | Needs | Status |
|---|---|---|---|
| D-01 | Install the SR debug APK from the CI artifact on the Galaxy A06, log in (dev API through Front Door), see Home | A06 + USB debugging, `adb install -r` | waits for android-sr-a first run |
| D-02 | Airplane mode: check in, open a visit, sell, review, save; network on; the sale appears once on the server | A06 | waits for SR slice |
| D-03 | Print a memo and a stock slip on the MP-58N from the SR app; compare with the photos of the current printout | A06 + MP-58N, photos of a real printout (owner to supply) | waits for android-print |
| D-04 | Factory-reset test phone: enrol as device owner (adb command from docs/setup), install SR, check in suspends the chosen apps, check out releases them | a reset phone (A07 or Honor X5c Plus) | waits for android-geo-dpc |
| D-05 | Five mock-location apps and one cloning tool refused or flagged | same phone | waits for android-geo-dpc |
| D-06 | 8-hour scripted battery and data run | A06 | Day 6 |
