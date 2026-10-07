package com.aktcl.aron.core.printing.text

/**
 * Big-endian reader over the raw bytes of a TrueType/OpenType file (N-018).
 *
 * The print renderer reads the bundled Noto Sans Bengali itself instead of asking the platform to draw text:
 * every step from shaping to the 1-bit bitmap is plain Kotlin integer arithmetic, so the memo bitmap is the
 * same bit for bit on the JVM (golden tests) and on the phone. Out-of-range reads throw
 * [MalformedFontException] instead of an index error so a corrupt font fails loudly once, at load.
 */
internal class FontData(private val bytes: ByteArray) {
    val size: Int get() = bytes.size

    fun u8(at: Int): Int {
        check(at, 1)
        return bytes[at].toInt() and 0xFF
    }

    fun u16(at: Int): Int {
        check(at, 2)
        return ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
    }

    fun s16(at: Int): Int = u16(at).toShort().toInt()

    fun u32(at: Int): Long {
        check(at, 4)
        return ((u16(at).toLong()) shl 16) or u16(at + 2).toLong()
    }

    fun tag(at: Int): String {
        check(at, 4)
        return String(CharArray(4) { (bytes[at + it].toInt() and 0xFF).toChar() })
    }

    private fun check(at: Int, len: Int) {
        if (at < 0 || at + len > bytes.size) throw MalformedFontException("read of $len bytes at $at outside ${bytes.size}")
    }
}

class MalformedFontException(message: String) : RuntimeException(message)
