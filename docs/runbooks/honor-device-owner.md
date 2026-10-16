# Honor X5c Plus: make ARON SR the device owner (owner runbook)

Written by the laptop session on 2026-10-16. Phone: Honor X5c Plus (`NLA-LX2P`, Android 15, MagicOS 9.0.0).
Lines starting with `>` are commands the **laptop session** runs; you only plug the cable in and tap on the phone.
About 15 minutes including the reset. Nothing here needs a developer.

## 0. Which path works today

- **The QR at first boot does not work on dev yet** (two fixes belong to other lanes, see appendix B and
  `docs/status/device-checks.md`). **Use path A**, sections 1 to 3: reset, wizard with no account, USB debugging on,
  one `adb` command from the laptop.
- Token for this run: **token 3** (SR, dev lockdown, 2 uses, valid until 2026-10-19 17:30 UTC), held by the laptop in
  `%USERPROFILE%\AronSecrets\sr-do-qr.png` (QR) and `sr-do-token.txt` (paste text). Never read it out or paste it in chat.
- **Dev lockdown keeps USB debugging on** (`adb_enabled` 1, debugging, uninstall, factory reset and adb installs not
  blocked), so the laptop is never locked out.

## 1. Before the reset (2 minutes)

1. Nothing to back up: the phone is dedicated to the project. **Everything on it will be erased.**
2. The phone has **no Google and no HONOR ID account today**. If you added one since, remove it (Settings > Users &
   accounts > the account > Remove): a reset with a Google account on it turns on Factory Reset Protection, which blocks
   the device-owner setup.
3. Nothing to reinstall for D-04: Facebook and TikTok are Honor preloads and YouTube is a system app; all three return
   after the reset. The D-05 mock apps need Play, so a Google account is added **only after section 3 step 6 succeeds**.
4. Charger on or battery above 50 percent; know the Wi-Fi password.

## 2. Factory reset on Honor (5 minutes)

1. Settings > System & updates > Reset > **Reset phone** > Reset phone (type the screen lock if asked).
2. Fallback: power off, hold **Volume Up + Power** until the HONOR logo, choose **Wipe data/factory reset**, confirm.
3. The welcome screen appears after 2 to 4 minutes.

## 3. Path A: wizard, USB debugging, one adb command (5 minutes)

1. Welcome screen: pick English (or Bangla), accept the terms, connect to Wi-Fi.
2. **Skip every account screen** (no Google, no HONOR ID, no "copy from old phone"), skip the screen lock, no second
   user or Private Space. Finish the wizard to the home screen.
3. Settings > About phone > tap **Build number** seven times.
4. Settings > System & updates > Developer options: turn on **USB debugging** and **Allow ADB debugging in charge only
   mode** (Honor needs this one or the laptop will not see the phone).
5. Plug the cable in; on the phone tick **Always allow from this computer** and tap **Allow**.
6. Laptop (you watch the phone for prompts):
   ```
   > adb devices                                            ("device"; if "unauthorized", tap Allow on the phone)
   > adb shell dumpsys account | findstr "Accounts:"        (must say "Accounts: 0")
   > adb install -r %USERPROFILE%\AronApk\do-check\aron-sr-0.1.722-dev.apk      ("Success")
   > adb shell dpm set-device-owner com.aktcl.aron.sr/com.aktcl.aron.dpc.AronDeviceAdminReceiver
   ```
   Expected: `Success: Device owner set to package com.aktcl.aron.sr` and `Active admin set to component
   {com.aktcl.aron.sr/com.aktcl.aron.dpc.AronDeviceAdminReceiver}`.
7. If it refuses:
   - "...already some accounts on the device": a Google or HONOR ID account slipped in. Settings > Users & accounts >
     remove it, run the command again (or reset again and skip every account).
   - "...already several users on the device": a second user or Private Space exists. Remove it there, run it again.
   - "Unknown admin" / "Component ... not found": the app is not installed; run the `adb install` line first.
   - "device owner is already set" / "already provisioned": done already; go to section 4.

## 4. Enrol, log in, bind (5 minutes)

1. Open **ARON SR**: it shows **Enrol this phone**. Tap **Scan QR** and scan `sr-do-qr.png` on the laptop screen, or
   tap paste and let the laptop type the token over USB (never shown).
2. "Enrolling..." ends on the login screen. The dev policy is applied here: location, camera, Bluetooth and
   notification permissions are granted. No battery prompt is expected on build 722 (that wiring is pending); if one
   appears, tap **Allow**. If background work is needed before it lands: Settings > Battery > ARON SR > Unrestricted.
3. Log in as `sr1001`: the laptop types the password over USB (`step5_phone_login.py`) or reads it to you in person.
4. The app asks for a **4-digit bind code**: the laptop reads it from the Device OTP page as `tso1001`
   (`step5_bind_code.py`). **Type it within 10 minutes of logging in** (the phone's bind ticket expires then; the code
   itself lasts 2 hours); if the app refuses it, log in again and retype it. Home opens with today's route.
5. Laptop proof that the phone is a device-owner phone:
   ```
   > adb shell dpm list-owners              (User 0: admin=com.aktcl.aron.sr/...AronDeviceAdminReceiver,DeviceOwner)
   > adb shell dumpsys device_policy | head
   > adb shell settings get global adb_enabled       (1: dev lockdown kept USB debugging)
   > adb shell dumpsys user | findstr "no_"           (dev: no no_debugging_features)
   ```
   and in `GET /v1/admin/devices` the new row shows `device_owner: true`, `lockdown_level: dev`.

## 5. D-04 app blocking (10 minutes)

**Run D-04 on a working day and before 20:00 Dhaka time (14:00 UTC).** The schedule blocks nothing on a holiday or
after its hard end (default `hard_end_time` 20:00, `working_days_only` on); a late check-in is a false failure, not a
bug. The laptop confirms the two values from the device policy before you start.

Facebook (`com.facebook.katana`), TikTok (`com.ss.android.ugc.trill`) and YouTube (`com.google.android.youtube`) are on
the default block list; no login is needed in them.

1. In ARON SR tap **Check In**. Within a few seconds the three apps are suspended: icons grey out, tapping one says the
   app is paused by your admin. Laptop: `> adb shell dumpsys package com.facebook.katana | findstr suspended` shows
   `suspended=true`.
2. Airplane mode on, kill the app from Recents, reopen: still suspended (the rule lives on the phone).
3. Reboot: after boot they are still suspended.
4. Tap **Check Out**: the three apps open again within seconds (`suspended=false`). WhatsApp, Phone, Messages, Camera,
   Maps and Settings were never blocked.
5. If nothing is suspended at check-in: first check the day and time against the rule above; then the laptop reads
   `adb shell dumpsys device_policy | findstr -i suspend` and the portal's device row (`blocking_active`,
   `suspended_packages`) and tells the lead the time.

## 6. D-05 mock-location apps (10 minutes)

Only now, **after section 3 succeeded**, add the project's Google account (Settings > Users & accounts; allowed under
dev) so Play works, or let the laptop `adb install` APKs it holds. Dev keeps Developer options, so a mock app can be
selected (prod hides them).

1. Install five mock-location apps (for example Fake GPS Location by Lexa, Fake GPS by ByteRev, GPS Emulator, Mock
   Locations, GPS JoyStick) and one cloning tool (Parallel Space or Dual Space).
2. Developer options > **Select mock location app** > pick one; start it and set a point in Mirpur 10.
3. In ARON SR open an outlet (or Check In): the fix is marked mock and the visit or sale is refused or flagged (banner:
   location not trusted). Repeat for each app. Laptop proof: `> adb shell appops query-op android:mock_location allow`
   lists the selected app; the portal's device row shows `mock_location_apps`.
4. Clone ARON SR with the cloning tool and open the clone: it cannot enrol or log in (not the device owner, not the
   signed app). Write down what each app showed; "refused or flagged" is the pass.

## 7. Getting out again

- No in-app unenrol yet, and `adb shell dpm remove-active-admin` is refused for a device owner of a release build. The
  way out is a **factory reset** (allowed under dev: Settings reset or recovery as in section 2). Do not put the
  **prod** lockdown on this phone unless you can reset it from recovery: prod turns USB debugging off and blocks
  uninstall and the Settings reset.
- Token 3 has 2 uses: one for this run, one for a repeat. After 2026-10-19 the laptop mints a new one (`--remint`).

## Appendix B: the QR at first boot (only after two fixes land)

Blocked today: the published release's download link is the GitHub Actions run page, not the APK (infra /
backend-admin), and build 722 does not declare the two Android 12+ provisioning screens (android-core, then
android-geo-dpc), so Android 15 stops with "Can't set up device". When both land: reset, on the **first welcome screen
tap the same empty spot six times**, connect to Wi-Fi, scan `sr-do-qr.png` from the laptop screen; the phone downloads
ARON SR (12.5 MB), checks its signature, becomes device owner and finishes setup in 2 to 3 minutes with no account
screens; open ARON SR, wait for "Enrolling..." (Try again if offline), continue at section 4 step 3. If it fails, finish
the wizard with no account and do section 3.
