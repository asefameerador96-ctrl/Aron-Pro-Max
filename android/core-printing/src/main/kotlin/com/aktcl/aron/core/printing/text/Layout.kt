package com.aktcl.aron.core.printing.text

/*
 * OpenType layout structures (GSUB, GPOS, GDEF) parsed into plain objects (N-018).
 * Only what the bundled fonts use is modelled: GSUB 1, 2, 4, 5, 6 (and 7 extension), GPOS 1, 2, 4, 6, 8 (and 9).
 * Anything else is parsed as [Unsupported] and never applies, so an unexpected font degrades to unshaped glyphs,
 * never to a crash.
 */

internal class Coverage(private val glyphs: IntArray, private val indices: IntArray) {
    /** Coverage index of [glyph], or -1. */
    fun index(glyph: Int): Int {
        var lo = 0
        var hi = glyphs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val g = glyphs[mid]
            when {
                g < glyph -> lo = mid + 1
                g > glyph -> hi = mid - 1
                else -> return indices[mid]
            }
        }
        return -1
    }

    companion object {
        fun parse(d: FontData, at: Int): Coverage {
            when (d.u16(at)) {
                1 -> {
                    val n = d.u16(at + 2)
                    val g = IntArray(n) { d.u16(at + 4 + 2 * it) }
                    return sorted(g, IntArray(n) { it })
                }
                2 -> {
                    val n = d.u16(at + 2)
                    val gl = ArrayList<Int>()
                    val ix = ArrayList<Int>()
                    for (r in 0 until n) {
                        val p = at + 4 + 6 * r
                        val start = d.u16(p)
                        val end = d.u16(p + 2)
                        val startIndex = d.u16(p + 4)
                        for (g in start..end) {
                            gl.add(g)
                            ix.add(startIndex + g - start)
                        }
                    }
                    return sorted(gl.toIntArray(), ix.toIntArray())
                }
                else -> return Coverage(IntArray(0), IntArray(0))
            }
        }

        private fun sorted(g: IntArray, ix: IntArray): Coverage {
            val order = g.indices.sortedBy { g[it] }
            return Coverage(IntArray(g.size) { g[order[it]] }, IntArray(g.size) { ix[order[it]] })
        }
    }
}

internal class ClassDef(private val map: Map<Int, Int>) {
    fun classOf(glyph: Int): Int = map[glyph] ?: 0

    companion object {
        val EMPTY = ClassDef(emptyMap())

        fun parse(d: FontData, at: Int): ClassDef {
            val m = HashMap<Int, Int>()
            when (d.u16(at)) {
                1 -> {
                    val start = d.u16(at + 2)
                    val n = d.u16(at + 4)
                    for (i in 0 until n) m[start + i] = d.u16(at + 6 + 2 * i)
                }
                2 -> {
                    val n = d.u16(at + 2)
                    for (r in 0 until n) {
                        val p = at + 4 + 6 * r
                        val cls = d.u16(p + 4)
                        for (g in d.u16(p)..d.u16(p + 2)) m[g] = cls
                    }
                }
            }
            return ClassDef(m)
        }
    }
}

internal class ValueRecord(val xPlacement: Int, val yPlacement: Int, val xAdvance: Int, val yAdvance: Int) {
    companion object {
        val ZERO = ValueRecord(0, 0, 0, 0)

        fun size(format: Int): Int = 2 * Integer.bitCount(format and 0xFF)

        fun parse(d: FontData, at: Int, format: Int): ValueRecord {
            var p = at
            fun next(bit: Int): Int = if (format and bit != 0) d.s16(p).also { p += 2 } else 0
            val xp = next(0x01)
            val yp = next(0x02)
            val xa = next(0x04)
            val ya = next(0x08)
            return ValueRecord(xp, yp, xa, ya)
        }
    }
}

internal class Anchor(val x: Int, val y: Int) {
    companion object {
        fun parse(d: FontData, at: Int): Anchor = Anchor(d.s16(at + 2), d.s16(at + 4))
    }
}

internal class LookupRecord(val sequenceIndex: Int, val lookupIndex: Int)

/** One way to match a context rule: what to compare at each input, backtrack and lookahead position. */
internal class ContextRule(
    /** Input sequence including the first glyph; each element is tested with [inputTest]. */
    val input: IntArray,
    val backtrack: IntArray,
    val lookahead: IntArray,
    val records: List<LookupRecord>,
)

internal sealed class Subtable {
    // GSUB
    class SingleSubst(val coverage: Coverage, val delta: Int, val substitutes: IntArray?) : Subtable()
    class MultipleSubst(val coverage: Coverage, val sequences: Array<IntArray>) : Subtable()
    class Ligature(val glyph: Int, val components: IntArray)
    class LigatureSubst(val coverage: Coverage, val sets: Array<List<Ligature>>) : Subtable()

    // Shared contextual forms (GSUB 5/6, GPOS 7/8). How each element of a rule is compared depends on [kind].
    enum class MatchKind { GLYPH, CLASS, COVERAGE }

    class Context(
        val kind: MatchKind,
        /** Coverage of the first input glyph; for format 3 the first input coverage. */
        val coverage: Coverage,
        val inputClasses: ClassDef,
        val backtrackClasses: ClassDef,
        val lookaheadClasses: ClassDef,
        /** For GLYPH: rule sets by coverage index; for CLASS: by class of the first glyph; for COVERAGE: one set. */
        val ruleSets: Array<List<ContextRule>>,
        /** COVERAGE only: the coverage tables indexed by the rule's element values. */
        val coverages: List<Coverage>,
    ) : Subtable()

    // GPOS
    class SinglePos(val coverage: Coverage, val values: Array<ValueRecord>) : Subtable()
    class PairValue(val second: Int, val v1: ValueRecord, val v2: ValueRecord)
    class PairPos1(val coverage: Coverage, val sets: Array<List<PairValue>>, val hasV2: Boolean) : Subtable()
    class PairPos2(
        val coverage: Coverage,
        val class1: ClassDef,
        val class2: ClassDef,
        val class2Count: Int,
        val v1: Array<ValueRecord>,
        val v2: Array<ValueRecord>,
        val hasV2: Boolean,
    ) : Subtable()
    class MarkAttach(
        val markCoverage: Coverage,
        val baseCoverage: Coverage,
        val markClass: IntArray,
        val markAnchor: Array<Anchor>,
        /** [baseIndex][class], null when the base has no anchor for that class. */
        val baseAnchors: Array<Array<Anchor?>>,
        val markToMark: Boolean,
    ) : Subtable()

    object Unsupported : Subtable()
}

internal class Lookup(val type: Int, val flag: Int, val markFilteringSet: Int, val subtables: List<Subtable>) {
    val ignoreBase get() = flag and 0x2 != 0
    val ignoreLigatures get() = flag and 0x4 != 0
    val ignoreMarks get() = flag and 0x8 != 0
    val useMarkFilteringSet get() = flag and 0x10 != 0
    val markAttachmentType get() = (flag ushr 8) and 0xFF
}

internal class LayoutTable(
    /** script tag -> (lang tag or "" for default) -> feature indices. */
    private val scripts: Map<String, List<Int>>,
    val featureTags: List<String>,
    val featureLookups: List<IntArray>,
    val lookups: List<Lookup>,
) {
    /** Lookup indices of [feature] under the first of [scriptTags] the font has, sorted, without duplicates. */
    fun lookupsFor(scriptTags: List<String>, feature: String): IntArray {
        val features = scriptTags.firstNotNullOfOrNull { scripts[it] } ?: return IntArray(0)
        val out = sortedSetOf<Int>()
        for (fi in features) if (featureTags[fi] == feature) featureLookups[fi].forEach { out.add(it) }
        return out.toIntArray()
    }

    companion object {
        val EMPTY = LayoutTable(emptyMap(), emptyList(), emptyList(), emptyList())

        fun parse(d: FontData, at: Int, gpos: Boolean): LayoutTable {
            val scriptList = at + d.u16(at + 4)
            val featureList = at + d.u16(at + 6)
            val lookupList = at + d.u16(at + 8)

            val scripts = HashMap<String, List<Int>>()
            for (i in 0 until d.u16(scriptList)) {
                val rec = scriptList + 2 + 6 * i
                val script = scriptList + d.u16(rec + 4)
                val defaultLangSys = d.u16(script)
                if (defaultLangSys != 0) {
                    val ls = script + defaultLangSys
                    val req = d.u16(ls + 2)
                    val n = d.u16(ls + 4)
                    val list = ArrayList<Int>()
                    if (req != 0xFFFF) list.add(req)
                    for (k in 0 until n) list.add(d.u16(ls + 6 + 2 * k))
                    scripts[d.tag(rec)] = list
                }
            }

            val tags = ArrayList<String>()
            val featureLookups = ArrayList<IntArray>()
            for (i in 0 until d.u16(featureList)) {
                val rec = featureList + 2 + 6 * i
                tags.add(d.tag(rec))
                val f = featureList + d.u16(rec + 4)
                val n = d.u16(f + 2)
                featureLookups.add(IntArray(n) { d.u16(f + 4 + 2 * it) })
            }

            val lookups = ArrayList<Lookup>()
            for (i in 0 until d.u16(lookupList)) {
                val l = lookupList + d.u16(lookupList + 2 + 2 * i)
                val type = d.u16(l)
                val flag = d.u16(l + 2)
                val n = d.u16(l + 4)
                val filter = if (flag and 0x10 != 0) d.u16(l + 6 + 2 * n) else 0
                var effectiveType = type
                val subs = ArrayList<Subtable>()
                for (k in 0 until n) {
                    var st = l + d.u16(l + 6 + 2 * k)
                    var t = type
                    if ((!gpos && type == 7) || (gpos && type == 9)) {
                        t = d.u16(st + 2)
                        st += d.u32(st + 4).toInt()
                        effectiveType = t
                    }
                    subs.add(if (gpos) parseGpos(d, st, t) else parseGsub(d, st, t))
                }
                lookups.add(Lookup(effectiveType, flag, filter, subs))
            }
            return LayoutTable(scripts, tags, featureLookups, lookups)
        }

        private fun parseGsub(d: FontData, at: Int, type: Int): Subtable = when (type) {
            1 -> {
                val cov = Coverage.parse(d, at + d.u16(at + 2))
                if (d.u16(at) == 1) {
                    Subtable.SingleSubst(cov, d.s16(at + 4), null)
                } else {
                    val n = d.u16(at + 4)
                    Subtable.SingleSubst(cov, 0, IntArray(n) { d.u16(at + 6 + 2 * it) })
                }
            }
            2 -> {
                val cov = Coverage.parse(d, at + d.u16(at + 2))
                val n = d.u16(at + 4)
                Subtable.MultipleSubst(cov, Array(n) {
                    val s = at + d.u16(at + 6 + 2 * it)
                    IntArray(d.u16(s)) { k -> d.u16(s + 2 + 2 * k) }
                })
            }
            4 -> {
                val cov = Coverage.parse(d, at + d.u16(at + 2))
                val n = d.u16(at + 4)
                Subtable.LigatureSubst(cov, Array(n) {
                    val set = at + d.u16(at + 6 + 2 * it)
                    List(d.u16(set)) { k ->
                        val lig = set + d.u16(set + 2 + 2 * k)
                        val count = d.u16(lig + 2)
                        Subtable.Ligature(d.u16(lig), IntArray(count - 1) { c -> d.u16(lig + 4 + 2 * c) })
                    }
                })
            }
            5 -> parseContext(d, at, chained = false)
            6 -> parseContext(d, at, chained = true)
            else -> Subtable.Unsupported
        }

        private fun parseGpos(d: FontData, at: Int, type: Int): Subtable = when (type) {
            1 -> {
                val cov = Coverage.parse(d, at + d.u16(at + 2))
                val vf = d.u16(at + 4)
                if (d.u16(at) == 1) {
                    val v = ValueRecord.parse(d, at + 6, vf)
                    Subtable.SinglePos(cov, arrayOf(v))
                } else {
                    val n = d.u16(at + 6)
                    val sz = ValueRecord.size(vf)
                    Subtable.SinglePos(cov, Array(n) { ValueRecord.parse(d, at + 8 + sz * it, vf) })
                }
            }
            2 -> {
                val fmt = d.u16(at)
                val cov = Coverage.parse(d, at + d.u16(at + 2))
                val vf1 = d.u16(at + 4)
                val vf2 = d.u16(at + 6)
                val s1 = ValueRecord.size(vf1)
                val s2 = ValueRecord.size(vf2)
                if (fmt == 1) {
                    val n = d.u16(at + 8)
                    Subtable.PairPos1(cov, Array(n) {
                        val set = at + d.u16(at + 10 + 2 * it)
                        List(d.u16(set)) { k ->
                            val p = set + 2 + k * (2 + s1 + s2)
                            Subtable.PairValue(d.u16(p), ValueRecord.parse(d, p + 2, vf1), ValueRecord.parse(d, p + 2 + s1, vf2))
                        }
                    }, vf2 != 0)
                } else {
                    val cd1 = ClassDef.parse(d, at + d.u16(at + 8))
                    val cd2 = ClassDef.parse(d, at + d.u16(at + 10))
                    val c1 = d.u16(at + 12)
                    val c2 = d.u16(at + 14)
                    val v1 = ArrayList<ValueRecord>()
                    val v2 = ArrayList<ValueRecord>()
                    for (a in 0 until c1) for (b in 0 until c2) {
                        val p = at + 16 + (a * c2 + b) * (s1 + s2)
                        v1.add(ValueRecord.parse(d, p, vf1))
                        v2.add(ValueRecord.parse(d, p + s1, vf2))
                    }
                    Subtable.PairPos2(cov, cd1, cd2, c2, v1.toTypedArray(), v2.toTypedArray(), vf2 != 0)
                }
            }
            4, 6 -> {
                val markCov = Coverage.parse(d, at + d.u16(at + 2))
                val baseCov = Coverage.parse(d, at + d.u16(at + 4))
                val classCount = d.u16(at + 6)
                val markArray = at + d.u16(at + 8)
                val baseArray = at + d.u16(at + 10)
                val nm = d.u16(markArray)
                val cls = IntArray(nm) { d.u16(markArray + 2 + 4 * it) }
                val anchors = Array(nm) { Anchor.parse(d, markArray + d.u16(markArray + 4 + 4 * it)) }
                val nb = d.u16(baseArray)
                val bases = Array(nb) { b ->
                    Array(classCount) { c ->
                        val off = d.u16(baseArray + 2 + 2 * (b * classCount + c))
                        if (off == 0) null else Anchor.parse(d, baseArray + off)
                    }
                }
                Subtable.MarkAttach(markCov, baseCov, cls, anchors, bases, markToMark = type == 6)
            }
            7 -> parseContext(d, at, chained = false)
            8 -> parseContext(d, at, chained = true)
            else -> Subtable.Unsupported
        }

        private fun records(d: FontData, at: Int, n: Int): List<LookupRecord> =
            List(n) { LookupRecord(d.u16(at + 4 * it), d.u16(at + 4 * it + 2)) }

        private fun parseContext(d: FontData, at: Int, chained: Boolean): Subtable {
            val fmt = d.u16(at)
            return when (fmt) {
                1, 2 -> {
                    val cov = Coverage.parse(d, at + d.u16(at + 2))
                    var p = at + 4
                    var bt = ClassDef.EMPTY
                    var inp = ClassDef.EMPTY
                    var la = ClassDef.EMPTY
                    if (fmt == 2) {
                        if (chained) {
                            bt = ClassDef.parse(d, at + d.u16(p)); inp = ClassDef.parse(d, at + d.u16(p + 2)); la = ClassDef.parse(d, at + d.u16(p + 4))
                            p += 6
                        } else {
                            inp = ClassDef.parse(d, at + d.u16(p)); p += 2
                        }
                    }
                    val n = d.u16(p)
                    val sets = Array(n) { s ->
                        val off = d.u16(p + 2 + 2 * s)
                        if (off == 0) emptyList() else {
                            val set = at + off
                            List(d.u16(set)) { r -> parseRule(d, set + d.u16(set + 2 + 2 * r), chained) }
                        }
                    }
                    Subtable.Context(
                        if (fmt == 1) Subtable.MatchKind.GLYPH else Subtable.MatchKind.CLASS,
                        cov, inp, bt, la, sets, emptyList(),
                    )
                }
                3 -> {
                    val covs = ArrayList<Coverage>()
                    fun covArray(p: Int, n: Int): IntArray = IntArray(n) {
                        covs.add(Coverage.parse(d, at + d.u16(p + 2 * it))); covs.size - 1
                    }
                    val rule: ContextRule
                    if (chained) {
                        var p = at + 2
                        val nb = d.u16(p); val back = covArray(p + 2, nb); p += 2 + 2 * nb
                        val ni = d.u16(p); val input = covArray(p + 2, ni); p += 2 + 2 * ni
                        val nl = d.u16(p); val ahead = covArray(p + 2, nl); p += 2 + 2 * nl
                        val nr = d.u16(p)
                        rule = ContextRule(input, back, ahead, records(d, p + 2, nr))
                    } else {
                        val ni = d.u16(at + 2)
                        val nr = d.u16(at + 4)
                        val input = covArray(at + 6, ni)
                        rule = ContextRule(input, IntArray(0), IntArray(0), records(d, at + 6 + 2 * ni, nr))
                    }
                    val first = if (rule.input.isEmpty()) Coverage(IntArray(0), IntArray(0)) else covs[rule.input[0]]
                    Subtable.Context(Subtable.MatchKind.COVERAGE, first, ClassDef.EMPTY, ClassDef.EMPTY, ClassDef.EMPTY, arrayOf(listOf(rule)), covs)
                }
                else -> Subtable.Unsupported
            }
        }

        /** Formats 1 and 2 rules: the first input element is implied by the set; stored here as -1. */
        private fun parseRule(d: FontData, at: Int, chained: Boolean): ContextRule {
            if (!chained) {
                val ni = d.u16(at)
                val nr = d.u16(at + 2)
                val input = IntArray(ni) { if (it == 0) -1 else d.u16(at + 4 + 2 * (it - 1)) }
                return ContextRule(input, IntArray(0), IntArray(0), records(d, at + 4 + 2 * (ni - 1), nr))
            }
            var p = at
            val nb = d.u16(p); val back = IntArray(nb) { d.u16(p + 2 + 2 * it) }; p += 2 + 2 * nb
            val ni = d.u16(p); val input = IntArray(ni) { if (it == 0) -1 else d.u16(p + 2 + 2 * (it - 1)) }; p += 2 + 2 * (ni - 1)
            val nl = d.u16(p); val ahead = IntArray(nl) { d.u16(p + 2 + 2 * it) }; p += 2 + 2 * nl
            val nr = d.u16(p)
            return ContextRule(input, back, ahead, records(d, p + 2, nr))
        }
    }
}

internal class Gdef(
    private val glyphClass: ClassDef,
    private val markAttachClass: ClassDef,
    private val markSets: List<Coverage>,
) {
    /** 1 base, 2 ligature, 3 mark, 4 component, 0 unclassified. */
    fun glyphClass(g: Int): Int = glyphClass.classOf(g)
    fun markAttachClass(g: Int): Int = markAttachClass.classOf(g)
    fun inMarkSet(set: Int, g: Int): Boolean = set < markSets.size && markSets[set].index(g) >= 0

    companion object {
        val EMPTY = Gdef(ClassDef.EMPTY, ClassDef.EMPTY, emptyList())

        fun parse(d: FontData, at: Int): Gdef {
            val minor = d.u16(at + 2)
            val gc = d.u16(at + 4).let { if (it == 0) ClassDef.EMPTY else ClassDef.parse(d, at + it) }
            val mac = d.u16(at + 10).let { if (it == 0) ClassDef.EMPTY else ClassDef.parse(d, at + it) }
            val sets = ArrayList<Coverage>()
            if (minor >= 2) {
                val off = d.u16(at + 12)
                if (off != 0) {
                    val ms = at + off
                    for (i in 0 until d.u16(ms + 2)) sets.add(Coverage.parse(d, ms + d.u32(ms + 4 + 4 * i).toInt()))
                }
            }
            return Gdef(gc, mac, sets)
        }
    }
}
