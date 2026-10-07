package com.aktcl.aron.core.printing

import com.aktcl.aron.core.printing.text.OpenTypeFont
import java.io.File

/** The bundled print fonts, read from core-ui where the app ships them (one copy in the APK). */
object TestFonts {
    private fun file(name: String): File {
        val candidates = listOf(File("../core-ui/src/main/res/font/$name"), File("android/core-ui/src/main/res/font/$name"))
        return candidates.firstOrNull { it.isFile } ?: error("font $name not found from ${File(".").absolutePath}")
    }

    val regularBytes: ByteArray by lazy { file("noto_sans_bengali_regular.ttf").readBytes() }
    val boldBytes: ByteArray by lazy { file("noto_sans_bengali_bold.ttf").readBytes() }
    val regular: OpenTypeFont by lazy { OpenTypeFont(regularBytes) }
    val bold: OpenTypeFont by lazy { OpenTypeFont(boldBytes) }
}
