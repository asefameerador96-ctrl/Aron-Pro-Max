# android-core: three Settings slots in feature-home (for android-sr-a)

Filed 2026-10-07 by android-core (fifth session). Owner of the change: android-sr-a (`feature-home` `SettingsContent`).

The shells now hold the updater (F-SYS-020 wiring, `core-sync` `shell/UpdateShell`), the photo Wi-Fi-only switch
(`app-sr` `MediaShell.wifiOnly`) and, next, PDA to Support (F-SYS-021). Each needs one row in Settings, which is
`feature-home` and not android-core's module. Please add these optional parameters (all default to "row not shown", so
AMO/TSO and previews keep compiling):

```kotlin
fun SettingsContent(
    versionText: String,
    onLanguageSelect: (AppLanguage) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    /** "App update" row (SR p7): opens the update page. Shown when non-null. */
    onUpdate: (() -> Unit)? = null,
    /** A newer release is waiting: show a dot / "update available" on the row. */
    updateAvailable: Boolean = false,
    /** "Photos only on Wi-Fi" switch (F-SYS-037). Shown when non-null. */
    photosWifiOnly: Boolean? = null,
    onPhotosWifiOnly: (Boolean) -> Unit = {},
    /** "PDA টু সাপোর্ট" tile (F-SYS-021; SR and AMO, TSO per F-TSO-024). Shown when non-null. */
    onSupport: (() -> Unit)? = null,
)
```

Strings in `values` and `values-bn`; 48 dp rows; Robolectric test that each row appears only when its parameter is set.

android-core then binds them in `app-sr` `SrApp` (SETTINGS branch):
`onUpdate = updateShell.coordinator::openPage`, `updateAvailable = state.update is UpdateState.Available`,
`photosWifiOnly = mediaShell.wifiOnly.get()`, and the support page once F-SYS-021 is wired. No other change needed.

**Update (lead, 2026-10-08):** the SettingsContent slots are implemented on lane/android-sr-a (head 08718d91, Robolectric-tested). Only the android-core side remains: bind the update row and the photos-on-Wi-Fi switch in SrApp.
