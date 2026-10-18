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

---

# Owner walkthrough, 10 minutes: SR and TSO as built by the android-tso lane (2026-10-18)

Written for the owner, with the phone in hand. Only steps the android-tso lane built. Result for each step: tick it, or write
FAIL with what you saw and a photo. **Language:** the app starts in Bangla; the English text in brackets is for the
tester (Settings has the language switch). Printer steps need the MP-58N; if it is not in hand, skip them and say so.
**SR build:** the release-signed CI build of the day (a release build, so it also covers D-SIZE-01). **TSO:** needs its
own phone or a reset (one phone holds one owner app); on the Honor run only the SR part.

## A. SR (about 7 minutes)
| # | Step | You should see | OK |
|---|---|---|---|
| 1 | Install the release APK, open it | Login screen "লগইন" with ইউজারনেম / পাসওয়ার্ড; version line "সংস্করণ ..." | |
| 2 | Log in as the test SR (password from the owner's vault, never typed into chat) | Home with tiles অ্যাটেনডেন্স, স্টক, বিক্রয়, মেমো, সারসংক্ষেপ, বিক্রয় জমা, আউটলেট ... | |
| 3 | Attendance: tap অ্যাটেনডেন্স, hold the **green** "চেক ইন" button | Green check-in button; the hold sheet shows the time as "hh:mm AM/PM"; after the hold "চেক ইন সম্পন্ন হয়েছে hh:mm AM/PM" | |
| 4 | Load the route (wait for "আপনার দিন প্রস্তুত হচ্ছে" to finish) and open বিক্রয় | The outlet picker lists today's outlets; tap one | |
| 5 | One sale: add 2 SKUs with + , tap এগিয়ে যান [Proceed] | Review "নিরীক্ষণ" shows the outlet name and code at the top, lines, "মোট", "সর্বমোট" | |
| 6 | Tap প্রিন্ট [Print], answer Yes to the save question; with the printer on, check the paper | "ছাপা ঠিক আছে?" [Is the print readable?], green হ্যাঁ / red না; then the dialog titled "সফল" [Success] with green "ওকে" | |
| 7 | Kill and relaunch: swipe the app away mid-sale (before step 6) and reopen | The same draft is back with the same quantities; no second memo after you finish | |
| 8 | Offline: switch on airplane mode, make one more sale, then switch it off and open বিক্রয় জমা [Submit sales] | The sale works offline; after reconnect the table shows device and server counts matching ("সব তথ্য মিলেছে") | |
| 9 | Tap ডাটা সিঙ্ক করুন [Sync data], then বিক্রয় জমা | If any outlet still owes: a dialog "আপনি কি নিশ্চিত?" with the dues sentence, green হ্যাঁ / red না; Yes opens the hold step; after the hold: "বিক্রয় সফলভাবে জমা হয়েছে।" | |
| 10 | Sunlight mode: go outdoors, tap the sun switch ("Sunlight mode") on Home, look at Review | Numbers and the main button turn solid, high contrast, readable in direct sun (D-UI-01) | |
| 11 | QC on a zero sale: open বিক্রয়, with NO quantity entered tap "বিক্রয় ছাড়া প্রোডাক্ট QC" [Product QC without a sale] | A list of every SKU as closed cards; tap one to open it; six fault rows in two groups (production / transport), each with a stepper and a typed-number box | |
| 12 | Type a number in one box (for example 3), then tap "QC জমা দিন" and answer হ্যাঁ | Summary table: one row per SKU with "MFC ত্রুটি", "MKT ত্রুটি", "মোট" columns; then Review shows "মোট QC − ..." and a **negative** "সর্বমোট" with the note "এটি একটি ক্রেডিট হিসেবে সংরক্ষিত হবে"; the credit switch is not offered | |
| 13 | Try a sale WITH a line and QC bigger than the sale | The Review refuses it ("QC এর কারণে মোট শূন্যের নিচে যাচ্ছে। QC কমান।") and Print stays disabled | |
| 14 | Settings (সেটিংস): switch language to English and back, then লগআউট [Log out] | Texts switch at once; log out asks for confirmation (with unsent data it may refuse: note what it says) | |

## B. TSO (about 3 minutes; needs a TSO phone)
| # | Step | You should see | OK |
|---|---|---|---|
| 1 | Log in as `tso1001` (no OTP step for TSO) | Dark navy login "Log in"; then the TSO home with the drawer | |
| 2 | Open the drawer | ড্যাশবোর্ড, ছুটি, ফাইনাল সাবমিট, আমার পরিধি, আমার মতামত (Dashboard, Leave, Final Submit, My Periphery, My Feedback) | |
| 3 | Leave: ছুটি, apply for 1 day, airplane mode on, submit; then airplane off | The request shows "not sent yet", then the server's status after reconnect; killing the app in between loses nothing and never sends twice | |
| 4 | Dashboard: big numbers | English "Cr" for crore (grouped "1,234.50 Cr"), Bangla "কোটি"; lakh "Lakh" / "লাখ" | |
| 5 | Final Submit | Online only: with no network it says so; with network a confirm, then the dialog "সফল" [Success] | |

## C. D-SIZE-01 release smoke (the size build, 3 minutes; run on the same release APK)
The release build now ships its native libraries compressed and extracts them at install, drops Kotlin null checks and
flattens class names. These can only fail at run time, so:
1. Install the **release** APK of the day (not debug). Check Settings > Apps > Aron > Storage: the installed size should be about 1.3 MB larger than the previous build; nothing else.
2. Cold start from the launcher: the login screen appears, no crash, within a few seconds on the Honor.
3. Log in: the encrypted database opens (SQLCipher) and the password check runs (argon2). A crash or "database unavailable" here is a FAIL of D-SIZE-01: photograph it and run `adb logcat -d | findstr "UnsatisfiedLink SQLCipher AndroidRuntime"` (the laptop session does this).
4. Do SR steps 4 to 9 above (route, sale, print, sync) once: this exercises the sale, sync, print and photo paths on the repackaged build.
5. Kill and relaunch once more and open Memo: the memo of step 5 is listed.
PASS when steps 1 to 5 show no crash, no missing text and no "unsatisfied link" line.
