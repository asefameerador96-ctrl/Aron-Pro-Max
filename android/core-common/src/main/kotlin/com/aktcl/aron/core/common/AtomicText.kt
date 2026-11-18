package com.aktcl.aron.core.common

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Small text files that must never be lost half-written (the Developer options gate's event log, the lab exemption, the
 * device-owner release flag). A write goes to `<name>.tmp` and is moved over the real file in ONE step (atomic replace);
 * the old file is never deleted first, so a kill at any point leaves either the old content or the new, never neither.
 * [read] recovers a tmp file that was fully written but not yet moved (the kill came between the two steps).
 */
object AtomicText {
    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = tmpOf(file)
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) // still a replace, never delete-then-rename
        }
    }

    /** The content of [file], or of a leftover tmp file that is newer; null when there is neither. */
    fun read(file: File): String? {
        val tmp = tmpOf(file)
        if (tmp.isFile) {
            // A complete tmp file is the newer truth: promote it. An empty or unreadable one is a torn write: drop it.
            val text = runCatching { tmp.readText() }.getOrNull()
            if (!text.isNullOrEmpty()) { runCatching { write(file, text) } } else runCatching { tmp.delete() }
        }
        return runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
    }

    fun delete(file: File) { runCatching { file.delete() }; runCatching { tmpOf(file).delete() } }

    private fun tmpOf(file: File) = File(file.parentFile, file.name + ".tmp")
}
