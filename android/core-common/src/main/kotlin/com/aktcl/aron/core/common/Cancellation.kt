package com.aktcl.aron.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching { block() }.getOrNull()` that does NOT swallow cancellation (D-AMO-CALLS-35). `runCatching` catches the [CancellationException] a
 * cancelled coroutine throws at its next suspension point and turns it into `null`; the cancelled coroutine then carries on and writes that `null` as if it
 * were an answer. In a Compose `LaunchedEffect` keyed on a changing value this erases the newer run's result: the old run (cancelled by the key change) resumes
 * AFTER the new one has set its state and overwrites it. Any other failure still yields `null`, as before.
 */
inline fun <T : Any> orNullKeepingCancellation(block: () -> T?): T? =
    try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) { null }

/** The `runCatching { block() }.getOrDefault(default)` counterpart of [orNullKeepingCancellation] (D-AMO-CALLS-37): cancellation propagates, any other failure gives [default]. */
inline fun <T> orDefaultKeepingCancellation(default: T, block: () -> T): T =
    try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) { default }
