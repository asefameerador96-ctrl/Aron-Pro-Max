# Device walk, AMO build 1860 (APK of train 81): findings relayed from the owner's laptop session

Source: the owner's local Claude Code session "Device walk AMO-1860 status" (2026-11-07 05:19–05:46 UTC; phone Honor NLA-LX2P, Android 15; read-only adb except one `adb logcat -c`; report and screenshots on the laptop at `Aron/device/amo-1860/`, not in git). Summarised by the lead on 2026-11-10 from the session's transcript. Build 1860 predates trains 82 and 83, so each item is to be re-verified on the newest APK before any fix.

| Row | Result on 1860 | Owner of the follow-up |
|---|---|---|
| 1, 2 | PASS (icon, label "ARON AMO", warm start 183 ms) | — |
| 3 login | differs: underline fields (screenshot: grey rounded boxes); "লগইন করুন" at the bottom, not under the fields; footer "সংস্করণ ০.২.৮৬০ (১৮৬০) / © AKTCL" vs the agreed "ARON AMO v<version>" (D-AMO-SHELL-05 at c0a73f06 changed the footer after 1860: re-verify) | A1 #2 |
| 3b location prompt | not captured (location already granted) | owner's phone |
| 4 camera | blocked: CAMERA asked only in outlet capture | by design (D-AMO-OUTLET) |
| 8 Home | 14 tiles not 17 (টিম লোকেশন, টিম পারফর্মেন্স, এস আর স্টক come with A3, not landed); header "AMO, ২০২৬-১১-০৭" without the zone name (zone needs the bundle: owner commit 5e78569a); no red "০" Outlet badge at zero; icons differ on জয়েন্ট কল, কন্ট্রোল কল, লাইভ ড্যাশবোর্ড, রিপোর্ট; all 13 tiles open, Back works, Home < 1.5 s | A1 #2 (badge at zero, icons, header); A3 (3 tiles) |
| 9 target cards | behaviour PASS; look differs: chevron folds, cards dark blue (~#0B5C8C) vs sky-blue (~#1AB4E8); sync line "সংস্করণ 2026-11-07:1" mixes Latin digits | A1 #2 |
| 75 Settings | vertical list vs the three tiles with flag + "বাংলা ▾"; language switch and Sunlight mode work | A1 #2 |
| 77 logout confirmation | present; differs: dark text, no blue "i", round buttons with না on the left; logout works, no crash | A1 #2 |
| Sale tile (17–20) | opens "কন্ট্রোল কল অ্যাক্টিভিটি" (the Control Call pick) — confirm against notes 17–20 (the Sales path goes through the retailer pick by design; the title must match the Sale screenshot) | A2 #2 |
| Sales Submit (65–66) | raw key "device_status" shown as a row label | A2 #2 |
| Live Dashboard | titled "লাইভ ডেটা"; shows "তালিকা আনা যায়নি। এই ফোনে যা আছে তা দেখাচ্ছে।" while online (F2 endpoint not landed; A3 #2 finding "refusal banner reads as offline") | A3 #2 after F2 |
| Stock / routes | "আপনার জোনের জন্য কোনো SKU নেই", "আপনার জোনে এখনও কোনো রুট নেই" — root cause fixed by the owner's commit 5e78569a (the AMO app never applied the bundle), not test data | landed with train 83 |
| Look | primary buttons solid dark green, not the screenshot's pill | A1 #2 (AmoLook tokens) |
| Logs | app: one "Invalid resource ID 0x00000000"; "enableOnBackInvokedCallback is not set"; Honor "BluetoothSocket hnRegisterCallback: Invalid params" on printer connect (OEM noise unless printing fails); no FATAL / AndroidRuntime crash | A1 #2 (resource id, back-invoked flag) |

Not walked on 1860: rows 5–7, 10–16, 17–66 (not in that build), 71–74, 76; rows 67–70 deferred (Astha); SR scenarios A and B with the MP-58N not run. The session's last asks to the owner: log back in as amo1001 (optionally revoke Location first for row 3b); when to walk SR A/B with the printer; whether the phone may switch to the SR app.
