package com.aktcl.aron.core.printing.text

/**
 * One glyph in the shaping buffer. Field meanings follow HarfBuzz so the shaper can be checked against it
 * (the JVM's text stack uses HarfBuzz; `ShaperOracleTest` compares glyph for glyph).
 */
internal class GlyphInfo(
    var glyph: Int,
    /** The character this glyph came from (the first one for a ligature). */
    val codePoint: Int,
    var cluster: Int,
) {
    var mask: Int = 0
    var category: Int = 0
    var position: Int = 0
    var syllable: Int = 0
    var syllableType: Int = 0
    var glyphClass: Int = 0
    var ligated = false
    var multiplied = false
    var ligId = 0
    var ligComp = 0

    val isZwnj get() = codePoint == 0x200C
    val isZwj get() = codePoint == 0x200D
    val isDefaultIgnorable get() = isDefaultIgnorable(codePoint)

    fun copy(): GlyphInfo = GlyphInfo(glyph, codePoint, cluster).also {
        it.mask = mask; it.category = category; it.position = position; it.syllable = syllable
        it.syllableType = syllableType; it.glyphClass = glyphClass; it.ligated = ligated
        it.multiplied = multiplied; it.ligId = ligId; it.ligComp = ligComp
    }

    companion object {
        fun isDefaultIgnorable(cp: Int): Boolean =
            cp == 0x00AD || cp == 0x034F || cp in 0x200B..0x200F || cp in 0x202A..0x202E ||
                cp in 0x2060..0x206F || cp == 0xFEFF
    }
}

/** Positioning of one glyph, font units, y up. */
internal class GlyphPos(var xAdvance: Int) {
    var xOffset = 0
    var yOffset = 0
    var yAdvance = 0
    /** Index of the glyph this mark is attached to, or -1. */
    var attachTo = -1
}

/** How the lookups of one feature are applied (HarfBuzz feature flags). */
internal class FeatureMode(val autoZwj: Boolean, val autoZwnj: Boolean, val perSyllable: Boolean)

/** A lookup to apply with the mask and joiner mode of the feature that brought it in. */
internal class LookupPlan(val index: Int, val mask: Int, val mode: FeatureMode)

/**
 * Applies GSUB and GPOS lookups to a glyph buffer with HarfBuzz's matching rules (lookup flags, mark filtering,
 * joiner skipping, per-syllable limits, context recursion).
 */
internal class LayoutEngine(private val font: OpenTypeFont) {
    private var nextLigId = 1

    // ---- glyph properties -------------------------------------------------------------------------------

    fun classify(info: GlyphInfo) {
        info.glyphClass = font.gdef.glyphClass(info.glyph)
    }

    private fun ignoredByFlags(info: GlyphInfo, lookup: Lookup): Boolean {
        when (info.glyphClass) {
            1 -> if (lookup.ignoreBase) return true
            2 -> if (lookup.ignoreLigatures) return true
            3 -> {
                if (lookup.ignoreMarks) return true
                if (lookup.useMarkFilteringSet) return !font.gdef.inMarkSet(lookup.markFilteringSet, info.glyph)
                if (lookup.markAttachmentType != 0) return font.gdef.markAttachClass(info.glyph) != lookup.markAttachmentType
            }
        }
        return false
    }

    // ---- skipping iterator ------------------------------------------------------------------------------

    private enum class M { MATCH, NOT_MATCH, SKIP }

    private inner class Skippy(
        val buf: List<GlyphInfo>,
        val lookup: Lookup,
        val mask: Int,
        val ignoreZwnj: Boolean,
        val ignoreZwj: Boolean,
        val syllable: Int,
        val perSyllable: Boolean,
    ) {
        fun test(info: GlyphInfo, matches: (GlyphInfo) -> Boolean?): M {
            val skip = when {
                ignoredByFlags(info, lookup) -> 2
                info.isDefaultIgnorable && (ignoreZwnj || !info.isZwnj) && (ignoreZwj || !info.isZwj) -> 1
                else -> 0
            }
            if (skip == 2) return M.SKIP
            // may_match: mask, syllable, then the element test (null = no test: MAYBE).
            val mayMatch: Int = when {
                info.mask and mask == 0 -> 0
                perSyllable && syllable != 0 && syllable != info.syllable -> 0
                else -> when (matches(info)) { null -> 2; true -> 1; false -> 0 }
            }
            if (mayMatch == 1 || (mayMatch == 2 && skip == 0)) return M.MATCH
            if (skip == 0) return M.NOT_MATCH
            return M.SKIP
        }

        /** Next matching position after [from] using [matches], or -1. */
        fun next(from: Int, matches: (GlyphInfo) -> Boolean?): Int {
            var i = from + 1
            while (i < buf.size) {
                when (test(buf[i], matches)) {
                    M.MATCH -> return i
                    M.NOT_MATCH -> return -1
                    M.SKIP -> i++
                }
            }
            return -1
        }

        fun prev(from: Int, matches: (GlyphInfo) -> Boolean?): Int {
            var i = from - 1
            while (i >= 0) {
                when (test(buf[i], matches)) {
                    M.MATCH -> return i
                    M.NOT_MATCH -> return -1
                    M.SKIP -> i--
                }
            }
            return -1
        }
    }

    private class Ctx(
        val buf: MutableList<GlyphInfo>,
        val pos: MutableList<GlyphPos>?,
        val table: LayoutTable,
        val gpos: Boolean,
        val mask: Int,
        val mode: FeatureMode,
    ) {
        var lookup: Lookup = Lookup(0, 0, 0, emptyList())
    }

    private fun inputIter(c: Ctx, at: Int) = Skippy(c.buf, c.lookup, c.mask,
        ignoreZwnj = c.gpos, ignoreZwj = c.mode.autoZwj, syllable = c.buf[at].syllable, perSyllable = !c.gpos && c.mode.perSyllable)

    private fun contextIter(c: Ctx, at: Int) = Skippy(c.buf, c.lookup, -1,
        ignoreZwnj = c.gpos || c.mode.autoZwnj, ignoreZwj = true, syllable = c.buf[at].syllable, perSyllable = !c.gpos && c.mode.perSyllable)

    // ---- public entry points ----------------------------------------------------------------------------

    fun applyGsub(buf: MutableList<GlyphInfo>, plans: List<LookupPlan>) {
        for (p in plans) {
            val lookup = font.gsub.lookups.getOrNull(p.index) ?: continue
            val c = Ctx(buf, null, font.gsub, gpos = false, mask = p.mask, mode = p.mode)
            c.lookup = lookup
            var i = 0
            while (i < buf.size) {
                val info = buf[i]
                if (info.mask and p.mask != 0 && !ignoredByFlags(info, lookup)) {
                    val next = applyLookupAt(c, lookup, i)
                    if (next >= 0) { i = next; continue }
                }
                i++
            }
        }
    }

    fun applyGpos(buf: MutableList<GlyphInfo>, pos: MutableList<GlyphPos>, plans: List<LookupPlan>) {
        for (p in plans) {
            val lookup = font.gpos.lookups.getOrNull(p.index) ?: continue
            val c = Ctx(buf, pos, font.gpos, gpos = true, mask = p.mask, mode = p.mode)
            c.lookup = lookup
            var i = 0
            while (i < buf.size) {
                val info = buf[i]
                if (info.mask and p.mask != 0 && !ignoredByFlags(info, lookup)) {
                    val next = applyLookupAt(c, lookup, i)
                    if (next >= 0) { i = next; continue }
                }
                i++
            }
        }
    }

    /**
     * Whether [feature]'s lookups would substitute exactly the sequence [glyphs] (HarfBuzz would_substitute with
     * zero context): single and multiple substitution on one glyph, a ligature with exactly these components,
     * or a context rule without backtrack or lookahead whose input is exactly this sequence.
     */
    fun wouldSubstitute(lookups: IntArray, glyphs: IntArray): Boolean {
        for (li in lookups) {
            val l = font.gsub.lookups.getOrNull(li) ?: continue
            for (st in l.subtables) if (wouldApply(st, glyphs)) return true
        }
        return false
    }

    private fun wouldApply(st: Subtable, g: IntArray): Boolean = when (st) {
        is Subtable.SingleSubst -> g.size == 1 && st.coverage.index(g[0]) >= 0
        is Subtable.MultipleSubst -> g.size == 1 && st.coverage.index(g[0]) >= 0
        is Subtable.LigatureSubst -> {
            val ci = st.coverage.index(g[0])
            ci >= 0 && st.sets[ci].any { lig -> lig.components.size == g.size - 1 && lig.components.indices.all { lig.components[it] == g[it + 1] } }
        }
        is Subtable.Context -> {
            val ci = st.coverage.index(g[0])
            if (ci < 0) false else {
                val set = when (st.kind) {
                    Subtable.MatchKind.GLYPH -> st.ruleSets.getOrNull(ci)
                    Subtable.MatchKind.CLASS -> st.ruleSets.getOrNull(st.inputClasses.classOf(g[0]))
                    Subtable.MatchKind.COVERAGE -> st.ruleSets[0]
                } ?: emptyList()
                set.any { r ->
                    r.backtrack.isEmpty() && r.lookahead.isEmpty() && r.input.size == g.size &&
                        (1 until g.size).all { elementMatches(st, r.input[it], g[it], Role.INPUT) } &&
                        (st.kind != Subtable.MatchKind.COVERAGE || st.coverages[r.input[0]].index(g[0]) >= 0)
                }
            }
        }
        else -> false
    }

    // ---- lookup dispatch --------------------------------------------------------------------------------

    /** Applies the first subtable of [lookup] that matches at [i]; returns the next index, or -1. */
    private fun applyLookupAt(c: Ctx, lookup: Lookup, i: Int): Int {
        for (st in lookup.subtables) {
            val r = if (c.gpos) applyPos(c, st, i) else applySubst(c, st, i)
            if (r >= 0) return r
        }
        return -1
    }

    private fun setGlyph(info: GlyphInfo, glyph: Int) {
        info.glyph = glyph
        classify(info)
    }

    private fun applySubst(c: Ctx, st: Subtable, i: Int): Int {
        val buf = c.buf
        val info = buf[i]
        when (st) {
            is Subtable.SingleSubst -> {
                val ci = st.coverage.index(info.glyph)
                if (ci < 0) return -1
                val g = if (st.substitutes != null) st.substitutes.getOrNull(ci) ?: return -1 else (info.glyph + st.delta) and 0xFFFF
                setGlyph(info, g)
                return i + 1
            }
            is Subtable.MultipleSubst -> {
                val ci = st.coverage.index(info.glyph)
                if (ci < 0) return -1
                val seq = st.sequences.getOrNull(ci) ?: return -1
                when (seq.size) {
                    0 -> { buf.removeAt(i); return i }
                    1 -> { setGlyph(info, seq[0]); return i + 1 }
                }
                val outs = seq.mapIndexed { k, g ->
                    info.copy().also { o ->
                        setGlyph(o, g); o.multiplied = true; o.ligId = 0; o.ligComp = k
                    }
                }
                buf.removeAt(i)
                buf.addAll(i, outs)
                return i + outs.size
            }
            is Subtable.LigatureSubst -> {
                val ci = st.coverage.index(info.glyph)
                if (ci < 0) return -1
                for (lig in st.sets[ci]) {
                    val positions = matchInput(c, i, lig.components.size + 1) { k, g -> g.glyph == lig.components[k - 1] } ?: continue
                    return ligate(c, positions, lig.glyph)
                }
                return -1
            }
            is Subtable.Context -> return applyContext(c, st, i)
            else -> return -1
        }
    }

    /** Positions of the full input sequence starting at [i] (inclusive), or null. */
    private inline fun matchInput(c: Ctx, i: Int, count: Int, crossinline test: (Int, GlyphInfo) -> Boolean): IntArray? {
        val out = IntArray(count)
        out[0] = i
        val it = inputIter(c, i)
        var at = i
        for (k in 1 until count) {
            at = it.next(at) { g -> test(k, g) }
            if (at < 0) return null
            out[k] = at
        }
        return out
    }

    private fun ligate(c: Ctx, positions: IntArray, ligGlyph: Int): Int {
        val buf = c.buf
        val first = buf[positions[0]]
        val isMarkLigature = positions.all { buf[it].glyphClass == 3 }
        val ligId = if (isMarkLigature) 0 else nextLigId++.also { if (nextLigId > 7) nextLigId = 1 }
        // Marks skipped between components now belong to the ligature (HarfBuzz lig props for marks).
        var comp = 1
        for (k in 1 until positions.size) {
            for (j in positions[k - 1] + 1 until positions[k]) {
                if (!isMarkLigature && buf[j].glyphClass == 3) { buf[j].ligId = ligId; buf[j].ligComp = comp }
            }
            comp++
        }
        setGlyph(first, ligGlyph)
        first.ligated = true
        first.multiplied = false
        first.ligId = ligId
        first.ligComp = 0
        for (k in positions.size - 1 downTo 1) buf.removeAt(positions[k])
        // Next glyph to process: the one after the last component, which moved left by (count - 1).
        return positions.last() - (positions.size - 1) + 1
    }

    private enum class Role { INPUT, BACKTRACK, LOOKAHEAD }

    private fun elementMatches(st: Subtable.Context, value: Int, glyph: Int, role: Role): Boolean = when (st.kind) {
        Subtable.MatchKind.GLYPH -> glyph == value
        Subtable.MatchKind.CLASS -> when (role) {
            Role.INPUT -> st.inputClasses.classOf(glyph) == value
            Role.BACKTRACK -> st.backtrackClasses.classOf(glyph) == value
            Role.LOOKAHEAD -> st.lookaheadClasses.classOf(glyph) == value
        }
        Subtable.MatchKind.COVERAGE -> st.coverages[value].index(glyph) >= 0
    }

    private fun applyContext(c: Ctx, st: Subtable.Context, i: Int): Int {
        val first = c.buf[i].glyph
        val ci = st.coverage.index(first)
        if (ci < 0) return -1
        val rules = when (st.kind) {
            Subtable.MatchKind.GLYPH -> st.ruleSets.getOrNull(ci)
            Subtable.MatchKind.CLASS -> st.ruleSets.getOrNull(st.inputClasses.classOf(first))
            Subtable.MatchKind.COVERAGE -> st.ruleSets[0]
        } ?: return -1
        for (r in rules) {
            val positions = matchInput(c, i, r.input.size) { k, g -> elementMatches(st, r.input[k], g.glyph, Role.INPUT) } ?: continue
            if (r.backtrack.isNotEmpty()) {
                val it = contextIter(c, i)
                var at = i
                var ok = true
                for (k in r.backtrack.indices) {
                    at = it.prev(at) { g -> elementMatches(st, r.backtrack[k], g.glyph, Role.BACKTRACK) }
                    if (at < 0) { ok = false; break }
                }
                if (!ok) continue
            }
            if (r.lookahead.isNotEmpty()) {
                val it = contextIter(c, i)
                var at = positions.last()
                var ok = true
                for (k in r.lookahead.indices) {
                    at = it.next(at) { g -> elementMatches(st, r.lookahead[k], g.glyph, Role.LOOKAHEAD) }
                    if (at < 0) { ok = false; break }
                }
                if (!ok) continue
            }
            return applyRecords(c, positions, r.records)
        }
        return -1
    }

    /** HarfBuzz apply_lookup: runs the nested lookups and keeps the match positions right as the buffer changes. */
    private fun applyRecords(c: Ctx, matched: IntArray, records: List<LookupRecord>): Int {
        val mp = matched.toMutableList()
        var count = mp.size
        var end = mp[count - 1] + 1
        val outer = c.lookup
        for (rec in records) {
            val idx = rec.sequenceIndex
            if (idx >= count) continue
            val nested = c.table.lookups.getOrNull(rec.lookupIndex) ?: continue
            val origLen = c.buf.size
            val at = mp[idx]
            if (at >= c.buf.size) continue
            c.lookup = nested
            val applied = applyLookupAt(c, nested, at) >= 0
            c.lookup = outer
            if (!applied) continue
            var delta = c.buf.size - origLen
            if (delta == 0) continue
            end += delta
            if (end < mp[idx]) { delta += mp[idx] - end; end = mp[idx] }
            var next = idx + 1
            if (delta < 0) {
                delta = maxOf(delta, next - count)
                next -= delta
            }
            // Shift mp[next until count] by delta slots.
            val tail = ArrayList(mp.subList(next, count))
            while (mp.size < count + delta) mp.add(0)
            for (k in tail.indices) mp[next + delta + k] = tail[k]
            next += delta
            count += delta
            for (j in idx + 1 until next) mp[j] = mp[j - 1] + 1
            while (next < count) { mp[next] = mp[next] + delta; next++ }
        }
        return end
    }

    // ---- GPOS ------------------------------------------------------------------------------------------

    private fun applyValue(p: GlyphPos, v: ValueRecord) {
        p.xOffset += v.xPlacement
        p.yOffset += v.yPlacement
        p.xAdvance += v.xAdvance
        p.yAdvance += v.yAdvance
    }

    private fun applyPos(c: Ctx, st: Subtable, i: Int): Int {
        val buf = c.buf
        val pos = c.pos!!
        val info = buf[i]
        when (st) {
            is Subtable.SinglePos -> {
                val ci = st.coverage.index(info.glyph)
                if (ci < 0) return -1
                applyValue(pos[i], if (st.values.size == 1) st.values[0] else st.values.getOrNull(ci) ?: return -1)
                return i + 1
            }
            is Subtable.PairPos1, is Subtable.PairPos2 -> {
                val cov = if (st is Subtable.PairPos1) st.coverage else (st as Subtable.PairPos2).coverage
                val ci = cov.index(info.glyph)
                if (ci < 0) return -1
                val j = inputIter(c, i).next(i) { null }
                if (j < 0) return -1
                val second = buf[j].glyph
                val v1: ValueRecord
                val v2: ValueRecord
                val hasV2: Boolean
                if (st is Subtable.PairPos1) {
                    val pv = st.sets[ci].firstOrNull { it.second == second } ?: return -1
                    v1 = pv.v1; v2 = pv.v2; hasV2 = st.hasV2
                } else {
                    st as Subtable.PairPos2
                    val k1 = st.class1.classOf(info.glyph)
                    val k2 = st.class2.classOf(second)
                    val k = k1 * st.class2Count + k2
                    if (k >= st.v1.size) return -1
                    v1 = st.v1[k]; v2 = st.v2[k]; hasV2 = st.hasV2
                }
                applyValue(pos[i], v1)
                applyValue(pos[j], v2)
                return if (hasV2) j + 1 else j
            }
            is Subtable.MarkAttach -> {
                val mi = st.markCoverage.index(info.glyph)
                if (mi < 0) return -1
                val j: Int
                if (!st.markToMark) {
                    // Nearest earlier glyph that is not a mark (HarfBuzz MarkBasePos).
                    val baseLookup = Lookup(c.lookup.type, 0x8, 0, emptyList())
                    val it = Skippy(buf, baseLookup, c.mask, ignoreZwnj = true, ignoreZwj = c.mode.autoZwj, syllable = 0, perSyllable = false)
                    var k = i - 1
                    var found = -1
                    while (k >= 0) {
                        if (it.test(buf[k]) { null } == M.MATCH) { found = k; break }
                        k--
                    }
                    if (found < 0) return -1
                    j = found
                } else {
                    val markLookup = Lookup(c.lookup.type, c.lookup.flag and 0xE.inv(), c.lookup.markFilteringSet, emptyList())
                    val it = Skippy(buf, markLookup, c.mask, ignoreZwnj = true, ignoreZwj = c.mode.autoZwj, syllable = 0, perSyllable = false)
                    j = it.prev(i) { null }
                    if (j < 0 || buf[j].glyphClass != 3) return -1
                    val id1 = info.ligId; val id2 = buf[j].ligId
                    val comp1 = info.ligComp; val comp2 = buf[j].ligComp
                    val good = if (id1 == id2) (id1 == 0 || comp1 == comp2) else ((id1 > 0 && comp1 == 0) || (id2 > 0 && comp2 == 0))
                    if (!good) return -1
                }
                val bi = st.baseCoverage.index(buf[j].glyph)
                if (bi < 0) return -1
                val cls = st.markClass[mi]
                val baseAnchor = st.baseAnchors.getOrNull(bi)?.getOrNull(cls) ?: return -1
                val markAnchor = st.markAnchor[mi]
                val p = pos[i]
                p.xOffset = baseAnchor.x - markAnchor.x
                p.yOffset = baseAnchor.y - markAnchor.y
                p.attachTo = j
                return i + 1
            }
            is Subtable.Context -> return applyContext(c, st, i)
            else -> return -1
        }
    }

    /** HarfBuzz propagate_attachment_offsets for left-to-right text. */
    fun resolveAttachments(pos: List<GlyphPos>) {
        val done = BooleanArray(pos.size)
        fun resolve(i: Int) {
            if (done[i]) return
            done[i] = true
            val p = pos[i]
            val j = p.attachTo
            if (j < 0 || j >= pos.size) return
            resolve(j)
            p.xOffset += pos[j].xOffset
            p.yOffset += pos[j].yOffset
            for (k in j until i) p.xOffset -= pos[k].xAdvance
        }
        for (i in pos.indices) resolve(i)
    }
}
