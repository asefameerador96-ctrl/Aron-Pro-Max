# Phones and ADB

## What ADB is

**ADB (Android Debug Bridge)** is a small program on your laptop that talks to your phone through the USB cable. With it you can:

- install an app file (an APK) on the phone with one command;
- read the phone's log when an app crashes (this is how I find bugs on a real device);
- run the one command that makes the Aron app the phone's **device owner** (see below).

It comes with Android Studio (the folder `platform-tools`). You do not need to learn it: I give you the exact commands.

## Turn it on, once per phone

1. Settings > About phone > Software information > tap **Build number** seven times (enter your PIN if asked). A message says developer mode is on.
2. Settings > Developer options > turn on **USB debugging**.
3. Plug the phone into the laptop. On the phone, tick "Always allow from this computer" and tap **Allow**.
4. On the laptop, in a new PowerShell window: `adb devices`. You should see your phone's code and the word `device`. If it says `unauthorized`, look at the phone and tap Allow.

## Installing an app build

I build the apps in GitHub. You download the APK and run `adb install -r file.apk`. (You can also copy the APK to the phone and tap it.)

## Device owner (what makes app blocking and lock-down possible)

A phone can have one **device owner** app. It can suspend other apps, switch off developer options, forbid installing from unknown sources and pin permissions. It must be set when the phone is factory-reset, before any Google account is added.

- **Your three test phones (Galaxy A06, Galaxy A07, Honor X5c Plus):** I give you an `adb` command to run on a freshly reset phone.
- **The 8,500 field phones:** a QR code shown at first boot (tap the welcome screen six times, scan the QR). The admin portal will generate that QR.
- While we develop, enrolled **test** phones keep USB debugging allowed through a development setting, so you are never locked out. The production setting turns it off.

## Notes on your phones and printer

- **Samsung and Honor battery savers** can stop background work. On a device-owner phone I exempt Aron from battery optimisation and from "force stop"; on a normal phone you will see a prompt to allow it.
- **Printer MP-58N (58 mm thermal):** these printers do not contain Bangla fonts. The app draws the memo as a picture and sends it as graphics, so Bangla prints correctly. I test this with your printer on Day 2.
