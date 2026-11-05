package com.aktcl.aron.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * tso-manual A1 (SH-17, D-A1-17): the ViewModels of each phase of a session in a store of their own, never in the activity's store.
 *
 * A phase is "logged out" (the login form) or "signed in as <key>" (the day: every `rememberRetained` holder, half-typed forms and
 * their client uuids). Every change of phase is a new phase with a fresh store, so the next sign-in, even of the same user, never gets
 * the last one's state back. When a logged-out phase is on screen, every other store is cleared (the signed-in day that just ended
 * and the previous login form); entering a signed-in phase clears only other signed-in stores, so the login form whose `login` call
 * just let the user in is never cancelled under it. The activity's own store is never cleared: clearing it while the login screen
 * already holds its ViewModel left that ViewModel's scope cancelled for good (a login spinner that never ends after a logout).
 *
 * The holder is itself a ViewModel of the activity, so a phase keeps its store across rotation and `recreate()` (language switch).
 */
class SessionViewModelStores : ViewModel() {
    private val stores = LinkedHashMap<String, ViewModelStore>()
    private val owners = HashMap<String, ViewModelStoreOwner>()
    private var kind: String? = null
    private var count = 0

    /**
     * The phase for [signedInKey] (null while logged out): `<n>:out` or `<n>:in:<key>`, where n counts the changes of phase seen.
     * Asking again with the same key gives the same phase (it is called from composition).
     */
    fun phase(signedInKey: String?): String {
        val k = if (signedInKey == null) OUT else IN + signedInKey
        if (k != kind) {
            kind = k
            count++
        }
        return "$count:$k"
    }

    /** The store owner of [phase] (created on first use). */
    fun owner(phase: String): ViewModelStoreOwner = owners.getOrPut(phase) {
        val store = ViewModelStore().also { stores[phase] = it }
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    /** [phase] is on screen and its predecessor has left the composition: clear what that phase no longer needs (see the class). */
    fun entered(phase: String) {
        val out = isLoggedOut(phase)
        stores.keys.filter { it != phase && (out || !isLoggedOut(it)) }.forEach { key ->
            owners.remove(key)
            stores.remove(key)?.clear()
        }
    }

    /** The phases that still hold a store (tests). */
    val livePhases: Set<String> get() = stores.keys.toSet()

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
        owners.clear()
    }

    companion object {
        private const val OUT = "out"
        private const val IN = "in:"

        fun isLoggedOut(phase: String): Boolean = phase.substringAfter(':') == OUT
    }
}

/**
 * Runs [content] with the ViewModel store of its session phase ([SessionViewModelStores]): pass null while logged out and the
 * signed-in user's key (the user id) once signed in. Every `viewModel { }` inside, `rememberRetained` included, lives in that phase's
 * store and is cleared when a later logged-out phase is shown; the activity's store keeps only the holder.
 */
@Composable
fun SessionPhaseScope(signedInKey: String?, content: @Composable () -> Unit) {
    val stores = viewModel { SessionViewModelStores() }
    val phase = stores.phase(signedInKey)
    LaunchedEffect(stores, phase) { stores.entered(phase) }
    CompositionLocalProvider(LocalViewModelStoreOwner provides stores.owner(phase)) { content() }
}
