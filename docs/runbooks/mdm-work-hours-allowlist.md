# Work-hours app allow-list: lock every app except a chosen few (owner runbook)

Lane mdm, session 3, 2026-10-24. Ruling D-NIGHT-MDM-ALLOWLIST-01. Phone in the device lab: Honor X5c Plus (`NLA-LX2P`,
Android 15, MagicOS 9). Lines starting with `>` are commands the **laptop session** runs; you plug the cable in and tap.

## 1. The answer

**Yes**, on a phone where the ARON app is the **device owner**. During working hours the phone suspends every app that
has an icon on the home screen except the ones you allow (for example WhatsApp and Calculator); after hours they open
again. You set it in the portal: **Admin > MDM > Policies > (policy) > Work-hours allow-list**. Nothing needs the
network on the phone once the policy has arrived: the phone keeps the rule and the schedule itself.

What a rep sees: blocked icons turn grey; tapping one shows Android's "App paused by your IT admin" sheet. The ARON
app, the phone dialler (and emergency calls), Settings, the keyboard and the home screen always work.

A phone that is **not** device owner cannot be locked like this (Android allows app suspension only to the device
owner or a profile owner). The policy is then stored but does nothing, and the portal's device row says
`device_owner: false`.

## 2. What Android allows, and which mechanism we use

| Mechanism | What it does | Used for |
|---|---|---|
| `DevicePolicyManager.setPackagesSuspended` (device owner, Android 7+) | The app stays installed with its data; its icon greys out, it cannot start activities, its notifications are hidden. Lifted by the same call. A system setting: survives app kill and reboot. | **The work-hours allow-list** (this runbook) and the existing check-in blocking (N-032). |
| Lock task (`setLockTaskPackages` + `startLockTask`) | Kiosk: only the listed apps can be on screen; home, recents and the status bar are restricted. | The stricter **Kiosk** card already in the policy editor (D-MDM-01). Not used by the allow-list. |
| Hiding (`setApplicationHidden`) | Removes the app from the launcher entirely. | Not used: harder to undo, and a hidden app looks "deleted" to a rep. |

How the phone applies the allow-list (the DPC, `android/dpc/.../mdm`):

1. When the policy arrives (`apply_policy` command), at boot, at every app start and at each window edge it computes
   "is now inside working hours" on the **trusted clock** (Dhaka time; prod phones also block changing the date/time).
2. Inside the window (after `grace_min` minutes from its start) it suspends every launcher-visible app that is not in
   the allow-list and not protected (section 5). Outside the window it releases only what **it** suspended; apps held by
   the check-in blocking or by a block window stay as those rules say.
3. It arms an alarm at the next window edge (exact when Android lets the app schedule exact alarms, otherwise within a
   few minutes; the next app start also re-checks).
4. It records what it suspended **before** suspending (write-ahead), so a crash can never strand an app suspended.

## 3. Make the Honor a device owner

The full, tested procedure is `docs/runbooks/honor-device-owner.md` (sections 1 to 4). In short:

**Path A (phone with no accounts):**
```
> adb devices                                             ("device")
> adb shell dumpsys account | findstr "Accounts:"         (must say "Accounts: 0")
> adb shell pm list users                                 (only "UserInfo{0:...}"; no second user, no Private Space)
> adb install -r %USERPROFILE%\AronApk\<build>\aron-sr-<version>-dev.apk
> adb shell dpm set-device-owner com.aktcl.aron.sr/com.aktcl.aron.dpc.AronDeviceAdminReceiver
```
Expected: `Success: Device owner set to package com.aktcl.aron.sr`.

**If the phone has a Google or HONOR ID account** (`Accounts: 1` or more): remove it in Settings > Users & accounts and
run the command again. If you cannot, or an error says it is "already provisioned": **factory reset**, skip every
account screen in the wizard, then path A (or the QR at first boot, appendix B of the Honor runbook, once its two fixes
are on dev). A factory reset erases the phone; the device-lab phone holds nothing to keep.

Checks after enrolment:
```
> adb shell dpm list-owners                               (com.aktcl.aron.sr/...AronDeviceAdminReceiver,DeviceOwner)
> adb shell appops get com.aktcl.aron.sr SCHEDULE_EXACT_ALARM
```
If the second line says `default` or `deny` (Android 14+ does not pre-grant it), grant it so window edges are exact:
```
> adb shell appops set com.aktcl.aron.sr SCHEDULE_EXACT_ALARM allow
```
Without it the rule still works; an edge can be a few minutes late (more in deep sleep), and `grace_min` absorbs it.

The AMO and TSO phones use `com.aktcl.aron.amo` and `com.aktcl.aron.tso` in the same commands.

## 4. What the owner does in the portal

1. **Admin > MDM > Policies**, open the policy the phone is on (or **New policy**; then assign it on the device page
   with a reason).
2. Card **Work-hours allow-list** (কাজের সময়ে অনুমোদিত অ্যাপ):
   - Toggle **On**.
   - **Allowed apps**: tap a preset (WhatsApp `com.whatsapp`, WhatsApp Business `com.whatsapp.w4b`, Calculator
     `com.android.calculator2`, Honor Calculator `com.hihonor.calculator`, Huawei Calculator `com.huawei.calculator`,
     Google Calculator `com.google.android.calculator`) or type a package id. Up to 50.
   - **Schedule**: *Working days from the calendar* (holidays in the ARON calendar are free days) with a start and end
     time, or *Fixed window* with weekdays and a start and end time. Dhaka time.
   - **Grace** (minutes after the start before apps lock; 0 to 30, default 5).
   - The preview says, for example: "Blocked today 09:00–18:00 except 3 apps".
3. **Save** with a reason. Every phone on the policy gets an `apply_policy` command (by push when online, else at its
   next sync); the activity log shows who changed what and why.
4. To switch it off: toggle **Off** and save. The phones release what the allow-list suspended at the next command.

To find a package id: on the laptop `> adb shell pm list packages | findstr -i whatsapp`, or Play Store web address
(`...details?id=com.whatsapp`). The packages catalogue in the picker (the phone's own app list) comes with contract 1.9.

## 5. Limits (by design)

- **Never suspended, whatever the policy says:** the ARON apps (`com.aktcl.aron.sr`, `.amo`, `.tso`), the default
  dialler and every app that answers "call" or "dial", the default SMS app, the home screen (launcher), the active and
  enabled keyboards, Settings, System UI, the package installer and permission controller, emergency apps, and the
  policy's own `allowed_packages`. The phone computes this list itself on every evaluation; no policy can remove an
  entry from it, and the server also refuses a policy that would explicitly block an ARON app or a dialler.
- **Emergency calls** always work: the dialler is protected, and Android allows emergency calls from the lock screen in
  any case.
- **Settings stays open** so a rep can turn on Wi-Fi, Bluetooth (printer) and location. What a rep may change inside
  Settings is governed by the lockdown level (prod blocks date/time, factory reset, developer options, installs).
- **Apps without a home-screen icon** (background services, Google Play services) are not touched; they are not apps a
  rep can open.
- **Android may refuse** a few packages (the active launcher, the default dialler, the device owner itself). Those stay
  open; the phone reports them in its status as `failed`.
- **Notifications** of a suspended app are hidden; WhatsApp messages arrive only if WhatsApp is on the allow-list.
- **Not a parental-control lock against a determined owner of the phone:** someone with a factory reset (blocked in
  prod lockdown) or USB debugging (dev lockdown keeps it on) can undo it. Prod phones have both closed.
- **Kiosk** (lock task) is stricter and stays available on the same policy page; use it when only the ARON app may run.
- On a non-device-owner phone the allow-list does nothing (section 1).

## 6. Device-lab row D-MDM-ALLOWLIST-01 (DEVICE-PENDING)

Run on the enrolled Honor (section 3) with the dev build named in the morning report. Steps:

1. Portal: on the phone's policy turn the allow-list on, allowed = WhatsApp + Calculator, *Fixed window* today's weekday,
   start = now + 3 min, end = start + 10 min, grace 0. Save with a reason.
2. Phone online: within a minute the command is `acked` on the device page. At the start time Facebook, TikTok, YouTube,
   Chrome, Camera and Gallery grey out; WhatsApp, Calculator, Phone, Messages, Settings and ARON SR open.
   `> adb shell dumpsys package com.facebook.katana | findstr suspended` shows `suspended=true`.
3. Dial **999** from the Phone app and hang up before it connects: the dialler opens and dials (do not complete the call).
4. Airplane mode on, kill ARON SR, reboot: after unlock the same apps are still suspended.
5. At the end time (+ a few minutes if the exact-alarm grant is missing): every app opens again (`suspended=false`).
6. Check-in blocking still works: check in during a window, check out after it ends; Facebook stays blocked until the
   check-out (the two rules do not release each other's apps).
7. Toggle the allow-list off during a window and save: the apps open again after the command is acked.

Pass = 2 to 7 as written. Report the times and any app that Android refused (`failed` in the device row).
