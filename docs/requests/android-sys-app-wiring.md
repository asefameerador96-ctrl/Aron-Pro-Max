# android-sys: app-shell wiring for core-system and core-media (for android-core, android-sr-a, the AMO and TSO shells)

Everything below is built and tested in `:android:core-system` / `:android:core-media`; it does nothing on a phone until
the shells (owned by other lanes) wire it. Each item is a few lines. Use the trusted clock (`SessionComponents.clock`)
everywhere a `nowMs` is asked for.

1. **Camera (F-SYS-030)** in app-sr: `MediaComponents(context, userId, clock, businessDate, scheduler = mediaScheduler)`,
   `CameraCaptureOverlay(media.camera)` once at the top of the composition, `PhotoPipeline` adapter replacing
   `NoCameraPipeline`, `media.attach(...)` before committing the owning record, `media.resume()` at start
   (details: docs/status/android-sys.md, sent to android-sr-a).
2. **Media upload (F-SYS-010, F-SYS-037)** in each Application: `MediaRuntime.wiring = MediaRuntime.Wiring(...)`
   (docs/status/android-sys.md); `mediaScheduler.requestUpload()` after a sync run that acked rows (core-sync hook); the
   `media_meta` sink (docs/requests/android-sys-media-meta.md). Settings: `WifiOnlyPhotosRow(wifiOnly, onChange = {
   setting.set(it); mediaScheduler.requestUpload() }, cfg.media.evidence_mobile_fallback_h)`.
3. **Permission gate (F-SYS-023)**: `PermissionGate(GatedFeature.ATTENDANCE | SALE | OUTLET_REQUEST)` around those
   screens; print buttons enabled by `GatedFeature.PRINT`.
4. **Updater (F-SYS-020)**: `UpdateManager.check(atLogin = true)` after login and `check(false)` on resume;
   `dayGate(dayOpen, session.updateRequired)` before a new day (DayGateBanner); `UpdateContent` from Settings and from a
   prompt; `ApkDownloader(okHttp, File(filesDir, "updates"))`; `AndroidUpdater(ctx).install(apk, release, syncIdle)`.
5. **PDA to Support (F-SYS-021)**: a Settings tile "PDA টু সাপোর্ট" (SR and AMO; TSO per F-TSO-024) opening
   `SupportContent(controller.status(...), versionName, lastSync, onSend = { scope.launch { controller.send() } })`;
   `SupportRuntime.wiring = SupportRuntime.Wiring(uploader = { SupportUploader(SupportQueue.forUser(filesDir, activeUserId),
   SupportHttpApi(apiClient, okHttp), clock::nowMs) }, wifiOnly = { cfg.support.pda_upload_wifi_only })`; the
   `SupportSource` reads the outbox (unsent + rejected + quarantined payloads, recent acked ones) and the local log ring
   buffer; the public key: docs/requests/android-sys-support-key.md.
6. **Logout (F-SYS-022)**: docs/requests/android-sys-logout-wiring.md.
7. **Language (F-SYS-019)**: already wired through `AppLocale` in the shells; use `LanguageSwitch.localized(context)` for
   any text built in a worker or notification.
8. **String gate**: docs/requests/android-sys-string-scan.md.
