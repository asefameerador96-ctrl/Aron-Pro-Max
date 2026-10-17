package com.aktcl.aron.feature.tso

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

private class RetainedHolder<T : Any>(val value: T) : ViewModel()

/**
 * Keeps [factory]'s object across configuration changes and `recreate()` (rotation, language switch, font scale) for the
 * life of the activity: a half-typed form and its client uuid survive, so a retry can never queue a second record. Change
 * [key] to start fresh (a new form after the last one was sent). Process death loses a draft; the outbox row is the durable part.
 */
@Composable
fun <T : Any> rememberRetained(key: String, factory: () -> T): T =
    viewModel<RetainedHolder<T>>(key = key, factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = RetainedHolder(factory()) as V
    }).value

/** A retained counter: bumped when a screen's form or list must start fresh; survives recreation (unlike rememberSaveable inside AnimatedContent). */
class RetainedCounter {
    private val state = androidx.compose.runtime.mutableIntStateOf(0)
    var n: Int
        get() = state.intValue
        set(v) { state.intValue = v }
}
