package com.aktcl.aron.core.sync.shell

import android.content.Context
import androidx.work.WorkManager
import com.aktcl.aron.contract.AppFlavour
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.session.SessionState
import com.aktcl.aron.core.sync.push.PushDispatcher
import com.aktcl.aron.core.sync.push.PushHandler
import com.aktcl.aron.core.sync.push.PushNotices
import com.aktcl.aron.core.sync.push.PushPullKind
import com.aktcl.aron.core.sync.push.PushPullScheduler
import com.aktcl.aron.core.sync.push.PushRuntime
import com.aktcl.aron.core.sync.push.PushTokenApi
import com.aktcl.aron.core.sync.push.PushTokenRegistrar
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The app shells' push wiring (N-038). One per process; [install] in Application.onCreate.
 * - Token: registered for the signed-in user at start, after every online login, on resume and when FCM rotates it
 *   ([PushTokenRegistrar] sends only when the user or the token changed).
 * - Message: the notice in the app's language and a jittered pull ([PushDispatcher]); never an upload.
 * - A build without `google-services.json` (CI, a dev phone) has no Firebase app: push is simply off.
 */
class PushShell(
    context: Context,
    private val components: SessionComponents,
    flavour: AppFlavour,
    /** The context in the app's chosen language (AppLocale.wrap). */
    private val localized: (Context) -> Context,
    /** Whether the app runs in Bangla (announcements carry both texts). */
    private val bangla: (Context) -> Boolean,
) : PushHandler {
    private val app = context.applicationContext
    val pulls = PushPullScheduler { WorkManager.getInstance(app) }
    private val registrar = PushTokenRegistrar(
        PushTokenApi(components.apiClient, components.proofSigner, { components.deviceIdentity.deviceUuid }, flavour.wire, components.clock::nowMs),
        PrefsTokenStore(app), components.clock::nowMs,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatcher = PushDispatcher(
        show = { notice -> PushNotices.show(localized(app), notice, bangla(app)) },
        schedule = { kind, delayMs -> pulls.schedule(kind, delayMs) },
    )

    fun install() {
        PushRuntime.handler = this
        try { PushNotices.ensureChannels(localized(app)) } catch (_: RuntimeException) { }
        scope.launch {
            components.session.state.map { (it as? SessionState.Active)?.user?.userId }.distinctUntilChanged().collect { id -> id?.let { register(it, null) } }
        }
        scope.launch { components.session.onlineLogins.collect { id -> registrar.retryRefused(); register(id, null) } }
    }

    /** App in front: a token that could not be registered earlier (offline, refused yesterday) is tried again. */
    fun onResume() {
        activeUser()?.let { id -> scope.launch { register(id, null) } }
    }

    /** The rep opened a task notification: the task list wants the task now, not after the spread. */
    fun openedTasks() = pulls.schedule(PushPullKind.BUNDLE, 0, now = true)

    override fun onMessage(data: Map<String, String>) {
        dispatcher.onMessage(data)
    }

    override fun onNewToken(token: String) {
        activeUser()?.let { id -> scope.launch { register(id, token) } }
    }

    fun activeUser(): Long? = (components.session.state.value as? SessionState.Active)?.user?.userId

    private suspend fun register(userId: Long, known: String?) {
        try {
            // The token is sent only for the user signed in now (the call uses the active full grant).
            if (activeUser() != userId) return
            registrar.ensure(userId, known ?: fcmToken())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun fcmToken(): String? {
        if (FirebaseApp.getApps(app).isEmpty()) return null
        return withTimeoutOrNull(20_000L) {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
        }
    }

    private class PrefsTokenStore(context: Context) : PushTokenRegistrar.Store {
        private val prefs = context.getSharedPreferences("aron-push-token", Context.MODE_PRIVATE)
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
    }
}
