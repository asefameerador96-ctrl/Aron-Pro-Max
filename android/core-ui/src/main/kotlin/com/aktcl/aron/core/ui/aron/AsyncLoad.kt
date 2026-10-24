package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aktcl.aron.core.ui.AronErrorState
import com.aktcl.aron.core.ui.LocalAronLook
import kotlinx.coroutines.CancellationException

/** The state of one local read a host does before its first frame: not answered yet, answered, or threw. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data object Failed : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>
}

/** A read that can be run again: [retry] puts it back to [LoadState.Loading] and reads once more. */
@Stable
class AsyncLoad<T> internal constructor(state: LoadState<T>, private val onRetry: () -> Unit) {
    val state: LoadState<T> = state
    fun retry() = onRetry()
}

/**
 * Runs [load] once per [keys] (and once more on every retry). A read that throws becomes [LoadState.Failed], never a crash and
 * never a screen stuck on its loading card; cancellation is not a failure.
 */
@Composable
fun <T> rememberAsyncLoad(vararg keys: Any?, load: suspend () -> T): AsyncLoad<T> {
    var state by remember(*keys) { mutableStateOf<LoadState<T>>(LoadState.Loading) }
    var attempt by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(*keys, attempt) {
        state = try { LoadState.Ready(load()) } catch (e: CancellationException) { throw e } catch (e: Exception) { LoadState.Failed }
    }
    return AsyncLoad(state) { state = LoadState.Loading; attempt++ }
}

/**
 * The three states of a [AsyncLoad]: the kit loading screen, the kit error state with its retry action, or the content once the
 * read has answered. The words are the caller's (localised, from its own strings).
 */
@Composable
fun <T> AronLoaded(load: AsyncLoad<T>, errorMessage: String, retryLabel: String, content: @Composable (T) -> Unit) {
    when (val s = load.state) {
        LoadState.Loading -> AronLoadingScreen()
        LoadState.Failed -> Box(Modifier.fillMaxSize().background(LocalAronLook.current.page), contentAlignment = Alignment.Center) {
            AronErrorState(errorMessage, retryLabel, { load.retry() })
        }
        is LoadState.Ready -> content(s.value)
    }
}
