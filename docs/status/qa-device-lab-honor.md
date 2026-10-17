# Device-lab check sheet: Honor X5c Plus (qa, 2026-10-17)

The only phone in reach. State on 2026-10-17:
- device owner by adb path A;
- **dev lockdown** (adb stays on);
- enrolled as device 2, sr1001 bound;
- SR build 0.1.724-dev, release-signed (certificate `468d9b4e…a4fb`).

The detailed steps live in `docs/status/device-checks.md` under each check's ID. This sheet sets the order, says who is
needed, and keeps the result.

- **Who:** L = the laptop session alone over adb; O = the owner's hands on the phone (the laptop session drives);
  OUT = outdoors.
- **Result:** PASS / FAIL / PENDING, with the date, Dhaka time and a photo or log path. Every FAIL becomes a defect
  line with the phone, time and photo.

## Run order (one sitting is about 90 minutes plus the evening check-out)

| # | Check | Row | Who | Command or steps | Time | Result |
|---|---|---|---|---|---|---|
| 1 | Phone listed, SR installed, API login, /health from Azure | N-015 (D-QA-1) | L, then O for the phone login | `powershell -ExecutionPolicy Bypass -File .\tools\checks\day1-check.ps1` | 10 min | PENDING |
| 2 | Release signer, upgrade 0.1.724 to the newer CI build keeps data and device owner | N-042 (D-QA-5) | L | `.\tools\checks\release-install-check.ps1 -OldApk <724> -NewApk <newer> -ExpectedCertSha256 <release cert>`; the log line goes to `release-install-check.log` | 10 min | PENDING (the in-app path waits on `qa-release-publish-route.md`) |
| 3 | Sideloading blocked; spoofing apps refused; check-in suspends the blocked apps | N-043 (D-QA-4) | L + O | `.\tools\checks\day3-check.ps1 -SkipCheckout -SpoofApks <apks>` in the morning | 15 min | suspension PASS on 2026-10-17 (D-04); spoofing PENDING |
| 4 | Airplane-mode sale, one memo on the server after reconnect, no double | N-022 (D-QA-2) | L + O | `.\tools\checks\day2-check.ps1` (it turns airplane mode off again in its `finally`) | 15 min | PENDING |
| 5 | Mock-location apps: five fake-GPS apps and one cloner, each judged | N-039 | O (Google account or APKs), L | the D-05 notes, then `day3-check.ps1 -SpoofApks`, results into `docs/status/qa-n039-antispoofing.md` | 30 min | mechanism PASS on 2026-10-17 (D-05, fused mock refused); real apps PENDING |
| 6 | Radius 100 m to 50 m obeyed at the next sync | N-050 (D-QA-3) | L + O + OUT | `.\tools\checks\day4-check.ps1` (it restores the radius at the end) | 20 min | PENDING; the stamped-radius line FAILs until `qa-visit-radius-read.md` lands |
| 7 | Outdoor legibility, Sunlight mode | D-UI-01 | O + OUT | device-checks.md D-UI-01 (written for the A06; run it on the Honor too and say so) | 10 min | PENDING |
| 8 | Press-and-hold 1.2 s | D-UI-02 | O | device-checks.md D-UI-02 | 3 min | PENDING |
| 9 | SR Settings language and logout; Force Sale photo; PDA to Support; outlet map; my requests; first bundle | D-SRA-1 to D-SRA-6 | O | device-checks.md, each section | 20 min | PENDING (D-SRA-4 needs a build with the Maps key) |
| 10 | Check-out releases the blocked apps (after 17:00) | N-043 (D-QA-4) | L + O | `.\tools\checks\day3-check.ps1 -CheckoutOnly` | 5 min | PASS on 2026-10-17 17:03 (D-04) |
| 11 | 8-hour battery and data day | N-060 | O carries the phone, L before and after | below | 8 h | PENDING |

## Not on this phone

- **The AMO and TSO apps (D-AMO-\*):** the Honor is the SR's device owner, and one phone holds one owner app. Running
  them needs a reset and a new enrolment, which is the owner's decision, or a second phone.
- **Printing (N-020, N-066, D-P1 on paper):** the MP-58N printer is not in hand.
- **Speed gates (D-PERF-04, D-PERF-05):** these are A06 budgets. A run on the Honor is information only and must be
  labelled as such.

## N-060 on the Honor: the 8-hour battery and data run (laptop before and after, owner carries)

Before the day starts, on USB:
```
adb shell dumpsys batterystats --reset
adb shell dumpsys netstats detail > n060-netstats-before.txt   # netstats has no reset: the run is after minus before
adb shell settings get global low_power         # note it: battery saver must be off for the run
adb shell dumpsys deviceidle whitelist | findstr aron   # the exemption the policy sets
```
Then:
- Unplug and note the battery percent.
- Run a normal SR day for 8 hours: route, visits, about 10 sales, one sync per visit cluster. Keep the screen-on time
  short and note it.
- Plug back in only after the run.

After the run, on USB:
```
adb shell dumpsys batterystats --charged com.aktcl.aron.sr > n060-batterystats.txt
adb shell dumpsys netstats detail > n060-netstats-after.txt
adb bugreport n060-bugreport.zip
```

Budgets (row acceptance):
- non-screen drain at most 6 % over 8 hours;
- at most 80 GPS fixes;
- mobile data at most 1 MB without photos, 3 MB with them.

The Day 6 script (N-063) will read these files and print PASS or FAIL per budget. Until it exists, the laptop session
reads the `com.aktcl.aron.sr` uid in the batterystats file and in both netstats files (mobile bytes after minus
before).
