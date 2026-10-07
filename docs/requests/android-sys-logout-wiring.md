# android-sys: wire F-SYS-022 logout (for android-tso / app-tso owner, android-sr-a, android-core)

`LogoutFlow` (core-system, package `com.aktcl.aron.core.system.logout`) holds the rule; the shells still call
`session.logout()` directly, so a TSO with unsent rows can log out today. Please wire it (about 15 lines per shell).

## app-tso (MainActivity `onLogout`) and app-sr / app-amo Settings logout
```kotlin
val ports = DatabaseLogoutPorts(
    context = this, userId = user.userId,
    db = { userDatabases.of(user.userId) },
    endSessionAction = { components.session.logout() },
    requestSyncAction = { syncScheduler.requestSync(user.userId, SyncTrigger.MANUAL); mediaScheduler.requestUpload() },
    unsentPhotosCount = { mediaStoreOf(user.userId).all().count { it.state == MediaState.ATTACHED || it.state == MediaState.UPLOADED } },
    closeDatabaseAction = { userDatabases.close(user.userId) },          // android-core, below
    forgetCredentialsAction = { components.session.forgetUser(user.userId) }, // android-core, below
)
val flow = LogoutFlow(AppRole.TSO /* SR in app-sr, AMO in app-amo */, ports)
// On the Settings "Logout" tap:
when (val c = flow.check()) {
    is LogoutCheck.Refused -> showUnsent = c.unsent            // UnsentItemsDialog(c.unsent, onSyncNow = flow::syncNow, onCancel = { showUnsent = null })
    is LogoutCheck.Proceed -> scope.launch { flow.logout() }   // TSO: LoggedOutDataKept / WipeIncomplete -> show logout_wipe_incomplete
}
```
SR and AMO keep the confirmation dialog they have and call `flow.logout()` (it never wipes; it schedules an upload).

## android-core (core-database, core-session)
- `UserDatabases.close(userId)`: close and forget the open Room instance so its files can be deleted.
- `SessionRepository.forgetUser(userId)`: after a TSO wipe, drop that user's tokens (upload grant included) and offline
  verifier, and remove the user from `knownUserIds()` so the sync engine stops opening a deleted database.
