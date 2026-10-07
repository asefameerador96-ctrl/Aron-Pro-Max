package com.aktcl.aron.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes the baseline profile of the app's start-up path (AUD-PERF-04). Needs Android 13+ (the A06), or a rooted device
 * or emulator on Android 9 to 12 (macrobenchmark's own rule). Copy the generated `*-baseline-prof.txt` from the test output to
 * android/app-<app>/src/main/baseline-prof.txt; the profile installer compiles it at install.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun startUp() = rule.collect(packageName = targetPackage, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
    }
}
