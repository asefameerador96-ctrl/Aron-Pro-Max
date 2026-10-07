package com.aktcl.aron.benchmark

import androidx.test.platform.app.InstrumentationRegistry

/** The app under test: `com.aktcl.aron.<sr|amo|tso>`, from `-Paron.benchmarkApp` (see build.gradle.kts). */
internal val targetPackage: String
    get() = InstrumentationRegistry.getArguments().getString("aron.targetPackage") ?: "com.aktcl.aron.sr"
