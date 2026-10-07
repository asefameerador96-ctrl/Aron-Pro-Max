package com.aktcl.aron.core.printing.text

import com.aktcl.aron.core.printing.text.Bengali.POS_AFTER_POST
import com.aktcl.aron.core.printing.text.Bengali.POS_BASE_C
import com.aktcl.aron.core.printing.text.Bengali.POS_BELOW_C
import com.aktcl.aron.core.printing.text.Bengali.POS_FINAL_C
import com.aktcl.aron.core.printing.text.Bengali.POS_POST_C
import com.aktcl.aron.core.printing.text.Bengali.POS_PRE_C
import com.aktcl.aron.core.printing.text.Bengali.POS_PRE_M
import com.aktcl.aron.core.printing.text.Bengali.POS_RA_TO_BECOME_REPH
import com.aktcl.aron.core.printing.text.Bengali.POS_SMVD
import com.aktcl.aron.core.printing.text.Bengali.POS_START

/** A shaped run: glyph ids with pen positions in font units (x right, y up), advance the total width. */
class ShapedRun(val glyphs: IntArray, val x: IntArray, val y: IntArray, val advance: Int)

/**
 * Text shaper for the print renderer (N-018). Bengali runs go through the Indic model (syllables, reph,
 * pre-base matras, conjunct forms through the font's own GSUB and GPOS); other runs get the default
 * features (ligatures, kerning, marks). The result matches HarfBuzz glyph for glyph on the bundled font,
 * which `ShaperOracleTest` checks against the JVM's HarfBuzz.
 */
class Shaper(private val font: OpenTypeFont) {
    private val engine = LayoutEngine(font)
    private val bengaliScripts = listOf("bng2", "beng", "DFLT")
    private val latinScripts = listOf("latn", "DFLT")

    private val spaceGlyph = font.glyphOf(0x20)
    private val viramaGlyph = if (font.hasGlyph(0x09CD)) font.glyphOf(0x09CD) else 0

    private companion object {
        const val GLOBAL = 1
        const val RPHF = 1 shl 1
        const val HALF = 1 shl 2
        const val BLWF = 1 shl 3
        const val ABVF = 1 shl 4
        const val PSTF = 1 shl 5
        const val INIT = 1 shl 6
        const val PREF = 1 shl 7

        val AUTO = FeatureMode(autoZwj = true, autoZwnj = true, perSyllable = false)
        val AUTO_SYL = FeatureMode(autoZwj = true, autoZwnj = true, perSyllable = true)
        val MANUAL_SYL = FeatureMode(autoZwj = false, autoZwnj = false, perSyllable = true)
    }

    private class F(val tag: String, val mask: Int, val mode: FeatureMode)

    /** Merges the lookups of [features] into one stage, by lookup index (HarfBuzz map builder). */
    private fun stage(table: LayoutTable, scripts: List<String>, features: List<F>): List<LookupPlan> {
        val byIndex = sortedMapOf<Int, LookupPlan>()
        for (f in features) for (li in table.lookupsFor(scripts, f.tag)) {
            val prev = byIndex[li]
            byIndex[li] = if (prev == null) LookupPlan(li, f.mask, f.mode) else LookupPlan(
                li, prev.mask or f.mask,
                FeatureMode(prev.mode.autoZwj && f.mode.autoZwj, prev.mode.autoZwnj && f.mode.autoZwnj, prev.mode.perSyllable),
            )
        }
        return byIndex.values.toList()
    }

    private val bengaliStages: List<List<LookupPlan>> by lazy {
        val s = { fs: List<F> -> stage(font.gsub, bengaliScripts, fs) }
        listOf(
            s(listOf(F("locl", GLOBAL, AUTO_SYL), F("ccmp", GLOBAL, AUTO_SYL))),
            // pause: initial reordering
            s(listOf(F("nukt", GLOBAL, MANUAL_SYL))),
            s(listOf(F("akhn", GLOBAL, MANUAL_SYL))),
            s(listOf(F("rphf", RPHF, MANUAL_SYL))),
            s(listOf(F("rkrf", GLOBAL, MANUAL_SYL))),
            s(listOf(F("pref", PREF, MANUAL_SYL))),
            s(listOf(F("blwf", BLWF, MANUAL_SYL))),
            s(listOf(F("abvf", ABVF, MANUAL_SYL))),
            s(listOf(F("half", HALF, MANUAL_SYL))),
            s(listOf(F("pstf", PSTF, MANUAL_SYL))),
            s(listOf(F("vatu", GLOBAL, MANUAL_SYL))),
            s(listOf(F("cjct", GLOBAL, MANUAL_SYL))),
            // pause: final reordering
            s(listOf(
                F("init", INIT, MANUAL_SYL), F("pres", GLOBAL, MANUAL_SYL), F("abvs", GLOBAL, MANUAL_SYL),
                F("blws", GLOBAL, MANUAL_SYL), F("psts", GLOBAL, MANUAL_SYL), F("haln", GLOBAL, MANUAL_SYL),
                F("rlig", GLOBAL, AUTO), F("calt", GLOBAL, AUTO), F("clig", GLOBAL, AUTO),
                F("liga", GLOBAL, AUTO), F("rclt", GLOBAL, AUTO),
            )),
        )
    }

    private val bengaliGpos by lazy { gposStage(bengaliScripts) }
    private val latinGsub by lazy {
        stage(font.gsub, latinScripts, listOf("ccmp", "locl", "rlig", "calt", "clig", "liga", "rclt").map { F(it, GLOBAL, AUTO) })
    }
    private val latinGpos by lazy { gposStage(latinScripts) }

    private fun gposStage(scripts: List<String>) =
        stage(font.gpos, scripts, listOf("abvm", "blwm", "mark", "mkmk", "curs", "dist", "kern").map { F(it, GLOBAL, AUTO) })

    private fun lookups(feature: String) = font.gsub.lookupsFor(bengaliScripts, feature)
    private val blwfLookups by lazy { lookups("blwf") }
    private val pstfLookups by lazy { lookups("pstf") }
    private val prefLookups by lazy { lookups("pref") }
    private val vatuLookups by lazy { lookups("vatu") }
    private val rphfLookups by lazy { lookups("rphf") }

    /** Shapes [text] (one line, no line breaks) into a single run of positioned glyphs. */
    fun shape(text: String): ShapedRun {
        val glyphs = ArrayList<Int>()
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        var pen = 0
        for ((runText, bengali) in itemize(text)) {
            val (buf, pos) = if (bengali) shapeBengali(runText) else shapeDefault(runText)
            for (i in buf.indices) {
                // Joiners and other default ignorables become the space glyph with no advance (HarfBuzz
                // hide_default_ignorables); without a space glyph they are dropped.
                if (buf[i].isDefaultIgnorable) {
                    if (spaceGlyph == 0) continue
                    glyphs.add(spaceGlyph); xs.add(pen + pos[i].xOffset); ys.add(pos[i].yOffset)
                    continue
                }
                glyphs.add(buf[i].glyph)
                xs.add(pen + pos[i].xOffset)
                ys.add(pos[i].yOffset)
                pen += pos[i].xAdvance
            }
        }
        return ShapedRun(glyphs.toIntArray(), xs.toIntArray(), ys.toIntArray(), pen)
    }

    /** Script runs: Bengali-block characters versus the rest; neutral characters join the run before them. */
    internal fun itemize(text: String): List<Pair<String, Boolean>> {
        val cps = text.codePoints().toArray()
        if (cps.isEmpty()) return emptyList()
        fun script(cp: Int): Int = when {
            Bengali.isBengaliBlock(cp) -> 1
            Character.isLetter(cp) -> 2
            else -> 0
        }
        val first = cps.map { script(it) }.firstOrNull { it != 0 } ?: 2
        val out = ArrayList<Pair<String, Boolean>>()
        val sb = StringBuilder()
        var current = first
        for (cp in cps) {
            val s = script(cp)
            if (s != 0 && s != current) {
                if (sb.isNotEmpty()) out.add(sb.toString() to (current == 1))
                sb.setLength(0)
                current = s
            }
            sb.appendCodePoint(cp)
        }
        if (sb.isNotEmpty()) out.add(sb.toString() to (current == 1))
        return out
    }

    // ---- normalisation -----------------------------------------------------------------------------------

    private fun normalize(cps: IntArray): IntArray {
        val out = ArrayList<Int>(cps.size + 4)
        for (cp in cps) {
            val dec = when (cp) {
                0x09CB -> intArrayOf(0x09C7, 0x09BE)
                0x09CC -> intArrayOf(0x09C7, 0x09D7)
                // RRA and RHA stay composed, as in HarfBuzz (harfbuzz#779).
                0x09DF -> intArrayOf(0x09AF, 0x09BC)
                else -> null
            }
            if (dec != null && dec.all { font.hasGlyph(it) }) dec.forEach { out.add(it) } else out.add(cp)
        }
        // Canonical ordering of nukta (ccc 7) and virama (ccc 9) runs.
        fun ccc(cp: Int) = when (cp) { 0x09BC -> 7; 0x09CD -> 9; else -> 0 }
        var i = 0
        while (i < out.size) {
            if (ccc(out[i]) == 0) { i++; continue }
            var j = i
            while (j < out.size && ccc(out[j]) != 0) j++
            val sorted = out.subList(i, j).sortedBy { ccc(it) }
            for (k in sorted.indices) out[i + k] = sorted[k]
            i = j
        }
        // Recompose YYA (HarfBuzz's one composition-exclusion exception for Indic).
        val res = ArrayList<Int>(out.size)
        i = 0
        while (i < out.size) {
            if (out[i] == 0x09AF && i + 1 < out.size && out[i + 1] == 0x09BC && font.hasGlyph(0x09DF)) {
                res.add(0x09DF); i += 2
            } else {
                res.add(out[i]); i++
            }
        }
        return res.toIntArray()
    }

    private fun newInfo(cp: Int, cluster: Int): GlyphInfo =
        GlyphInfo(font.glyphOf(cp), cp, cluster).also { it.mask = GLOBAL; engine.classify(it) }

    private fun positions(buf: List<GlyphInfo>): MutableList<GlyphPos> =
        buf.mapTo(ArrayList()) { GlyphPos(font.advance(it.glyph)) }

    /** Composed form (NFC) where the font has the composed letter, as HarfBuzz's normaliser does: e + U+0301 -> é. */
    private fun composeDefault(text: String): IntArray {
        val out = ArrayList<Int>()
        for (cp in java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).codePoints().toArray()) {
            if (font.hasGlyph(cp) || cp < 0x80) { out.add(cp); continue }
            val d = java.text.Normalizer.normalize(String(Character.toChars(cp)), java.text.Normalizer.Form.NFD).codePoints().toArray()
            if (d.size > 1 && d.all { font.hasGlyph(it) }) d.forEach { out.add(it) } else out.add(cp)
        }
        return out.toIntArray()
    }

    private fun shapeDefault(text: String): Pair<List<GlyphInfo>, List<GlyphPos>> {
        val cps = composeDefault(text)
        val buf = cps.mapIndexedTo(ArrayList()) { i, cp -> newInfo(cp, i) }
        engine.applyGsub(buf, latinGsub)
        val pos = positions(buf)
        engine.applyGpos(buf, pos, latinGpos)
        engine.resolveAttachments(pos)
        // Zero the advance of marks after positioning (HarfBuzz zero-width marks by GDEF, late).
        for (i in buf.indices) if (buf[i].glyphClass == 3) { pos[i].xOffset -= pos[i].xAdvance; pos[i].xAdvance = 0 }
        return buf to pos
    }

    // ---- Bengali -----------------------------------------------------------------------------------------

    /**
     * HarfBuzz's vowel constraints for Bengali: an independent vowel followed by the sign that would spell another
     * vowel (A + AA, VOCALIC R + R sign, VOCALIC L + L sign) gets a dotted circle between them, so a misspelt
     * "অা" prints visibly wrong instead of looking like "আ".
     */
    private fun vowelConstraints(cps: IntArray): IntArray {
        val out = ArrayList<Int>(cps.size + 2)
        for (i in cps.indices) {
            out.add(cps[i])
            val next = cps.getOrNull(i + 1) ?: continue
            if ((cps[i] == 0x0985 && next == 0x09BE) || (cps[i] == 0x098B && next == 0x09C3) || (cps[i] == 0x098C && next == 0x09E2)) out.add(0x25CC)
        }
        return out.toIntArray()
    }

    private fun shapeBengali(text: String): Pair<List<GlyphInfo>, List<GlyphPos>> {
        val cps = normalize(vowelConstraints(text.codePoints().toArray()))
        var buf: MutableList<GlyphInfo> = cps.mapIndexedTo(ArrayList()) { i, cp ->
            newInfo(cp, i).also {
                it.category = Bengali.category(cp)
                if ((cp == 0x09B0 || cp == 0x09F0)) it.category = Bengali.RA
                it.position = Bengali.initialPosition(cp, it.category)
            }
        }
        setupSyllables(buf)
        val stages = bengaliStages
        engine.applyGsub(buf, stages[0])
        buf = initialReordering(buf)
        for (s in 1 until stages.size - 1) engine.applyGsub(buf, stages[s])
        finalReordering(buf)
        engine.applyGsub(buf, stages.last())
        val pos = positions(buf)
        engine.applyGpos(buf, pos, bengaliGpos)
        engine.resolveAttachments(pos)
        return buf to pos
    }

    private fun setupSyllables(buf: List<GlyphInfo>) {
        var serial = 1
        for ((s, e, type) in Bengali.syllables(IntArray(buf.size) { buf[it].category })) {
            for (i in s until e) { buf[i].syllable = serial; buf[i].syllableType = type }
            serial++
        }
    }

    private fun syllableRanges(buf: List<GlyphInfo>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < buf.size) {
            var j = i + 1
            while (j < buf.size && buf[j].syllable == buf[i].syllable) j++
            out.add(i until j)
            i = j
        }
        return out
    }

    private fun ligated(i: GlyphInfo) = i.ligated
    private fun isConsonant(i: GlyphInfo) = !ligated(i) && Bengali.isConsonantCategory(i.category)
    private fun isHalant(i: GlyphInfo) = !ligated(i) && i.category == Bengali.H
    private fun isJoiner(i: GlyphInfo) = !ligated(i) && (i.category == Bengali.ZWJ || i.category == Bengali.ZWNJ)

    private fun consonantPositionFromFace(consonant: Int): Int {
        val g = intArrayOf(viramaGlyph, consonant, viramaGlyph)
        val first = g.copyOfRange(0, 2)
        val second = g.copyOfRange(1, 3)
        fun would(l: IntArray) = engine.wouldSubstitute(l, first) || engine.wouldSubstitute(l, second)
        if (would(blwfLookups) || would(vatuLookups)) return POS_BELOW_C
        if (would(pstfLookups)) return POS_POST_C
        if (would(prefLookups)) return POS_POST_C
        return POS_BASE_C
    }

    private fun initialReordering(input: MutableList<GlyphInfo>): MutableList<GlyphInfo> {
        if (viramaGlyph != 0) {
            for (info in input) if (info.position == POS_BASE_C) info.position = consonantPositionFromFace(info.glyph)
        }
        // Broken clusters get a dotted circle at their start, as HarfBuzz does.
        val buf: MutableList<GlyphInfo> = ArrayList(input.size + 2)
        var last = -1
        for (info in input) {
            if (info.syllableType == Bengali.BROKEN_CLUSTER && info.syllable != last && font.hasGlyph(0x25CC)) {
                buf.add(newInfo(0x25CC, info.cluster).also {
                    it.category = Bengali.DOTTEDCIRCLE
                    it.position = POS_BASE_C
                    it.syllable = info.syllable
                    it.syllableType = info.syllableType
                    it.mask = info.mask
                })
            }
            last = info.syllable
            buf.add(info)
        }
        for (r in syllableRanges(buf)) {
            when (buf[r.first].syllableType) {
                Bengali.CONSONANT_SYLLABLE, Bengali.VOWEL_SYLLABLE, Bengali.STANDALONE_CLUSTER, Bengali.BROKEN_CLUSTER ->
                    reorderConsonantSyllable(buf, r.first, r.last + 1)
            }
        }
        return buf
    }

    private fun reorderConsonantSyllable(info: MutableList<GlyphInfo>, start: Int, end: Int) {
        var base = end
        var hasReph = false
        var limit = start
        if (rphfLookups.isNotEmpty() && start + 3 <= end && !isJoiner(info[start + 2])) {
            if (engine.wouldSubstitute(rphfLookups, intArrayOf(info[start].glyph, info[start + 1].glyph))) {
                limit += 2
                while (limit < end && isJoiner(info[limit])) limit++
                base = start
                hasReph = true
            }
        }
        run {
            var i = end
            var seenBelow = false
            do {
                i--
                if (isConsonant(info[i])) {
                    if (info[i].position != POS_BELOW_C && (info[i].position != POS_POST_C || seenBelow)) {
                        base = i
                        break
                    }
                    if (info[i].position == POS_BELOW_C) seenBelow = true
                    base = i
                } else if (start < i && info[i].category == Bengali.ZWJ && info[i - 1].category == Bengali.H) {
                    break
                }
            } while (i > limit)
        }
        if (hasReph && base == start && limit - base <= 2) hasReph = false

        for (i in start until base) info[i].position = minOf(POS_PRE_C, info[i].position)
        if (base < end) info[base].position = POS_BASE_C

        // Final consonants: a consonant after a matra.
        for (i in base + 1 until end) {
            if (info[i].category == Bengali.M) {
                for (j in i + 1 until end) if (isConsonant(info[j])) { info[j].position = POS_FINAL_C; break }
                break
            }
        }
        if (hasReph) info[start].position = POS_RA_TO_BECOME_REPH

        // Attach misc marks to the previous character so they move with it.
        run {
            var lastPos = POS_START
            for (i in start until end) {
                val cat = info[i].category
                if (cat == Bengali.ZWJ || cat == Bengali.ZWNJ || cat == Bengali.N || cat == Bengali.RS || cat == Bengali.CM || cat == Bengali.H) {
                    info[i].position = lastPos
                    if (cat == Bengali.H && info[i].position == POS_PRE_M) {
                        for (j in i downTo start + 1) {
                            if (info[j - 1].position != POS_PRE_M) { info[i].position = info[j - 1].position; break }
                        }
                    }
                } else if (info[i].position != POS_SMVD) {
                    lastPos = info[i].position
                }
            }
        }
        // Post-base consonants own everything before them since the last consonant or matra.
        run {
            var last = base
            for (i in base + 1 until end) {
                if (isConsonant(info[i])) {
                    for (j in last + 1 until i) if (info[j].position < POS_SMVD) info[j].position = info[i].position
                    last = i
                } else if (info[i].category == Bengali.M) {
                    last = i
                }
            }
        }
        // Stable sort by position.
        val sorted = info.subList(start, end).sortedBy { it.position }
        for (k in sorted.indices) info[start + k] = sorted[k]

        // Find base again; flip a sequence of left matras.
        var firstLeft = end
        var lastLeft = end
        base = end
        for (i in start until end) {
            if (info[i].position == POS_BASE_C) { base = i; break }
            if (info[i].position == POS_PRE_M) {
                if (firstLeft == end) firstLeft = i
                lastLeft = i
            }
        }
        if (firstLeft < lastLeft) {
            info.subList(firstLeft, lastLeft + 1).reverse()
            var i = firstLeft
            for (j in i..lastLeft) {
                if (info[j].category == Bengali.M) {
                    info.subList(i, j + 1).reverse()
                    i = j + 1
                }
            }
        }

        // Masks.
        for (i in start until end) { if (info[i].position == POS_RA_TO_BECOME_REPH) info[i].mask = info[i].mask or RPHF else break }
        for (i in start until base) info[i].mask = info[i].mask or HALF or BLWF
        for (i in base + 1 until end) info[i].mask = info[i].mask or BLWF or ABVF or PSTF

        // ZWNJ disables half forms before it.
        for (i in start + 1 until end) {
            if (isJoiner(info[i]) && info[i].category == Bengali.ZWNJ) {
                var j = i
                do {
                    j--
                    info[j].mask = info[j].mask and HALF.inv()
                } while (j > start && !isConsonant(info[j]))
            }
        }
    }

    private fun finalReordering(buf: MutableList<GlyphInfo>) {
        if (viramaGlyph != 0) {
            for (g in buf) if (g.glyph == viramaGlyph && g.ligated && g.multiplied) {
                g.category = Bengali.H; g.ligated = false; g.multiplied = false
            }
        }
        for (r in syllableRanges(buf)) {
            when (buf[r.first].syllableType) {
                Bengali.CONSONANT_SYLLABLE, Bengali.VOWEL_SYLLABLE, Bengali.STANDALONE_CLUSTER, Bengali.BROKEN_CLUSTER ->
                    finalReorderSyllable(buf, r.first, r.last + 1)
            }
        }
    }

    private fun isOneOfNB(i: GlyphInfo, vararg cats: Int) = !ligated(i) && i.category in cats

    private fun finalReorderSyllable(info: MutableList<GlyphInfo>, start: Int, end: Int) {
        var base = start
        while (base < end) {
            if (info[base].position >= POS_BASE_C) {
                if (start < base && info[base].position > POS_BASE_C) base--
                break
            }
            base++
        }
        if (base == end && start < base && isOneOfNB(info[base - 1], Bengali.ZWJ)) base--
        if (base < end) {
            while (start < base && isOneOfNB(info[base], Bengali.N, Bengali.H)) base--
        }

        // Pre-base matras move to just before the main consonant, after the last standalone halant.
        if (start + 1 < end && start < base) {
            var newPos = if (base == end) base - 2 else base - 1
            search@ while (true) {
                while (newPos > start && !isOneOfNB(info[newPos], Bengali.M, Bengali.H)) newPos--
                if (isHalant(info[newPos]) && info[newPos].position != POS_PRE_M) {
                    if (newPos + 1 < end && info[newPos + 1].category == Bengali.ZWJ) {
                        if (newPos > start) { newPos--; continue@search }
                    }
                } else {
                    newPos = start
                }
                break
            }
            if (start < newPos && info[newPos].position != POS_PRE_M) {
                var i = newPos
                while (i > start) {
                    if (info[i - 1].position == POS_PRE_M) {
                        val oldPos = i - 1
                        if (oldPos < base && base <= newPos) base--
                        val tmp = info.removeAt(oldPos)
                        info.add(newPos, tmp)
                        newPos--
                    }
                    i--
                }
            }
        }

        // Reph moves after sub-joined forms (Bengali: reph position after sub).
        if (start + 1 < end && info[start].position == POS_RA_TO_BECOME_REPH &&
            (info[start].category == Bengali.REPHA) xor (info[start].ligated && !info[start].multiplied)
        ) {
            var newRephPos: Int
            var moved = false
            // Step 2: after the first explicit halant between the reph and the main consonant.
            newRephPos = start + 1
            while (newRephPos < base && !isHalant(info[newRephPos])) newRephPos++
            if (newRephPos < base && isHalant(info[newRephPos])) {
                if (newRephPos + 1 < base && isJoiner(info[newRephPos + 1])) newRephPos++
                moved = true
            }
            // Step 4: before the first post-base form.
            if (!moved) {
                newRephPos = base
                while (newRephPos + 1 < end && info[newRephPos + 1].position !in intArrayOf(POS_POST_C, POS_AFTER_POST, POS_SMVD)) newRephPos++
                if (newRephPos < end) moved = true
            }
            if (!moved) {
                // Step 5 repeats step 2; step 6: end of the syllable.
                newRephPos = end - 1
                while (newRephPos > start && info[newRephPos].position == POS_SMVD) newRephPos--
                if (isHalant(info[newRephPos])) {
                    for (i in base + 1 until newRephPos) if (info[i].category == Bengali.M) newRephPos--
                }
            }
            val reph = info.removeAt(start)
            info.add(newRephPos, reph)
            if (start < base && base <= newRephPos) base--
        }

        // 'init' on a left matra at the start of a word.
        if (info[start].position == POS_PRE_M) {
            val prevIsWordChar = start > 0 && when (Character.getType(info[start - 1].codePoint).toByte()) {
                Character.FORMAT, Character.UNASSIGNED, Character.PRIVATE_USE, Character.SURROGATE,
                Character.LOWERCASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER, Character.TITLECASE_LETTER,
                Character.UPPERCASE_LETTER, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK, Character.NON_SPACING_MARK -> true
                else -> false
            }
            if (!prevIsWordChar) info[start].mask = info[start].mask or INIT
        }
    }
}
