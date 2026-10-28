package com.aktcl.aron.rules

import java.io.File

/** Finds contract/fixtures/kpi-format-corpus.json from the test's working directory (the module) upwards to the repo root. */
internal actual fun kpiFormatCorpusText(): String =
    generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "contract/fixtures/kpi-format-corpus.json") }
        .first { it.isFile }
        .readText(Charsets.UTF_8)
