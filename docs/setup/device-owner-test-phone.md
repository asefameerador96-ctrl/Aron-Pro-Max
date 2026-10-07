# Device owner on a test phone (Galaxy A06, A07, Honor X5c Plus)

For the android-geo-dpc rows (N-029 lock-down, N-032 app blocking, N-034 managed update). The laptop operator session can run every laptop step; the owner's hands are needed only on the phone (reset, taps, USB "Allow").

## Before you start
- The phone must be **factory-reset with no Google or Samsung account added**: Android allows a device owner only on a fresh phone.
- On the phone: skip every account screen in the setup wizard; then turn on Developer options and USB debugging (see `phones-adb.md`).
- The SR debug APK comes from CI (artifact of the Android job on the integration branch).

## Make Aron SR the device owner (dev lockdown, USB debugging stays on)
```powershell
adb devices                                   # the phone shows as "device"
adb install -r app-sr-debug.apk
adb shell dpm set-device-owner com.aktcl.aron.sr/com.aktcl.aron.dpc.AronDeviceAdminReceiver
# Expected: "Success: Device owner set to package com.aktcl.aron.sr"
adb shell dumpsys device_policy | findstr /C:"Device Owner" /C:"com.aktcl.aron.sr"
```
If it says "Not allowed to set the device owner because there are already some accounts", remove the accounts in Settings (or reset again).

## Remove device owner again (dev lockdown only)
While the phone runs the **dev** policy, uninstall is not blocked, so:
```powershell
adb uninstall com.aktcl.aron.sr          # removes the app and its device-owner role
```
**Warning for prod-policy tests:** the prod policy turns USB debugging off, blocks uninstall and blocks "Factory data reset" in Settings. The only way out is a recovery-mode reset (power off, hold Power + Volume up, choose "Wipe data/factory reset"). Run the prod checks last on a phone you can reset this way.

## Read what the policy did
```powershell
adb shell dumpsys device_policy > dpc.txt     # restrictions, permission grants, suspended packages
adb shell pm list packages --user 0 -s         # system packages, to extend always_allowed for Honor
```
