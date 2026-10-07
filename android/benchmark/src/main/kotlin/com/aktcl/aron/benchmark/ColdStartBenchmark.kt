package com.aktcl.aron.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start of the field app (docs/04 gate: 2.5 s to the first screen on the Galaxy A06). Runs on a device or emulator
 * only. [coldStart] records the numbers in the macrobenchmark report (with and without the baseline profile);
 * [coldStartMeetsTheGate] fails when the median `am start -W` TotalTime of five process-cold launches (speed-profile
 * compiled, one warm-up not counted) is 2.5 s or more. Lab phones only (see build.gradle.kts).
 */
@RunWith(AndroidJUnit4::class)
class ColdStartBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStartWithBaselineProfile() = coldStart(CompilationMode.Partial())

    @Test fun coldStartWithoutCompilation() = coldStart(CompilationMode.None())

    private fun coldStart(mode: CompilationMode) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        iterations = 5,
        startupMode = StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test fun coldStartMeetsTheGate() {
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun run(cmd: String): String =
            shell.executeShellCommand(cmd).let { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() } }
        // The compile state is fixed, not left to install history or earlier tests: reset, have ProfileInstaller write the
        // shipped profile (what CompilationMode.Partial does), compile with it, then one launch that is not counted.
        run("cmd package compile --reset $targetPackage")
        run("am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE $targetPackage/androidx.profileinstaller.ProfileInstallReceiver")
        run("cmd package compile -f -m speed-profile $targetPackage")
        run("am force-stop $targetPackage")
        run("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $targetPackage")
        val times = (1..5).map {
            run("am force-stop $targetPackage")
            val out = run("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $targetPackage")
            Regex("TotalTime: (\\d+)").find(out)?.groupValues?.get(1)?.toLong() ?: error("no TotalTime in: $out")
        }.sorted()
        val median = times[times.size / 2]
        // Process-cold (force-stop): the page cache is not dropped, which needs root.
        assertTrue("process-cold start median $median ms of $times (gate $GATE_MS ms)", median < GATE_MS)
    }

    private companion object {
        const val GATE_MS = 2_500L
    }
}
