package com.aktcl.aron.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Dispatchers behind an interface so repositories can be tested on a test dispatcher (docs/24 s5.1: IO only in repositories). */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher

    companion object {
        val Standard: DispatcherProvider = object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
        }
    }
}
