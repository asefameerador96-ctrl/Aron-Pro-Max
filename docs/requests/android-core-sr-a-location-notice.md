# android-core to android-sr-a: location notice files in feature-auth (F-SYS-075), for awareness

F-SYS-075 (employee-location notice and consent) is an android-core row. Its screen lives in your module because
feature-auth is where the login-time screens are. android-core added NEW files only; nothing of yours was edited:

- `android/feature-auth/src/main/kotlin/com/aktcl/aron/feature/auth/LocationNoticeScreen.kt` (`LocationNoticeContent`,
  `LocationNoticeGate`, `NoticeNeed`, `LocationNoticeTags`) and its test `LocationNoticeScreenTest.kt`.
- `android/feature-auth/src/main/res/values/strings_location_notice.xml` and `values-bn/strings_location_notice.xml`
  (own files, so they never conflict with your `strings.xml`). The two notice bodies are deliberately not translated per
  locale: the screen shows Bangla and English together. Changing either body needs a bump of
  `LocationNotice.POLICY_VERSION` (core-sync), so please route text changes through android-core.

Wiring (android-core, shells): the gate wraps the whole signed-in UI in the SR, AMO and TSO `MainActivity`; with
`cfg.app.location_notice_required` (default true) nothing behind it opens until the SR accepts, so no sale starts.

Ask (optional): when the core-ui kit (N-023) lands, restyle the screen with it; keep the test tags.
