package com.aktcl.aron.core.printing.text

/**
 * Bengali character properties and the Indic syllable model (HarfBuzz's indic shaper with the Bengali
 * configuration: base position last, reph after sub-joined forms, implicit reph, below forms pre and post base).
 */
internal object Bengali {
    // Categories (HarfBuzz I_Cat values are not needed, only distinct ids).
    const val X = 0
    const val C = 1
    const val V = 2
    const val N = 3
    const val H = 4
    const val ZWNJ = 5
    const val ZWJ = 6
    const val M = 7
    const val SM = 8
    const val A = 10
    const val PLACEHOLDER = 11
    const val DOTTEDCIRCLE = 12
    const val RS = 13
    const val REPHA = 15
    const val RA = 16
    const val CM = 17
    const val SYMBOL = 18
    const val CS = 19

    // Positions, in HarfBuzz order (the stable sort of initial reordering uses this order).
    const val POS_START = 0
    const val POS_RA_TO_BECOME_REPH = 1
    const val POS_PRE_M = 2
    const val POS_PRE_C = 3
    const val POS_BASE_C = 4
    const val POS_AFTER_MAIN = 5
    const val POS_ABOVE_C = 6
    const val POS_BEFORE_SUB = 7
    const val POS_BELOW_C = 8
    const val POS_AFTER_SUB = 9
    const val POS_BEFORE_POST = 10
    const val POS_POST_C = 11
    const val POS_AFTER_POST = 12
    const val POS_FINAL_C = 13
    const val POS_SMVD = 14
    const val POS_END = 15

    // Syllable types.
    const val CONSONANT_SYLLABLE = 0
    const val VOWEL_SYLLABLE = 1
    const val STANDALONE_CLUSTER = 2
    const val SYMBOL_CLUSTER = 3
    const val BROKEN_CLUSTER = 4
    const val NON_INDIC_CLUSTER = 5

    fun isBengaliBlock(cp: Int): Boolean = cp in 0x0980..0x09FF

    fun category(cp: Int): Int = when (cp) {
        0x0981, 0x0982, 0x0983 -> SM
        in 0x0985..0x098C, 0x098F, 0x0990, in 0x0993..0x0994, 0x09E0, 0x09E1 -> V
        0x09B0, 0x09F0 -> RA
        in 0x0995..0x09A8, in 0x09AA..0x09AF, 0x09B2, in 0x09B6..0x09B9, 0x09CE, 0x09DC, 0x09DD, 0x09DF, 0x09F1 -> C
        0x09BC -> N
        0x09BD -> SYMBOL
        in 0x09BE..0x09C4, 0x09C7, 0x09C8, 0x09CB, 0x09CC, 0x09D7, 0x09E2, 0x09E3 -> M
        0x09CD -> H
        in 0x09E6..0x09EF -> PLACEHOLDER
        0x09FE -> SM
        0x200C -> ZWNJ
        0x200D -> ZWJ
        0x25CC -> DOTTEDCIRCLE
        0x00A0, 0x2010, 0x2011, in 0x2012..0x2014 -> PLACEHOLDER
        else -> X
    }

    private fun matraPosition(cp: Int): Int = when (cp) {
        0x09BF, 0x09C7, 0x09C8 -> POS_PRE_M
        0x09BE, 0x09C0, 0x09D7 -> POS_AFTER_POST
        in 0x09C1..0x09C4, 0x09E2, 0x09E3 -> POS_AFTER_SUB
        else -> POS_AFTER_POST
    }

    fun isConsonantCategory(cat: Int): Boolean =
        cat == C || cat == CS || cat == RA || cat == CM || cat == V || cat == PLACEHOLDER || cat == DOTTEDCIRCLE

    fun initialPosition(cp: Int, cat: Int): Int = when {
        isConsonantCategory(cat) -> POS_BASE_C
        cat == M -> matraPosition(cp)
        cat == SM || cat == A || cat == SYMBOL -> POS_SMVD
        else -> POS_END
    }

    // ---- syllables ----------------------------------------------------------------------------------------

    private fun letter(cat: Int): Char = when (cat) {
        C -> 'C'; RA -> 'R'; V -> 'V'; N -> 'N'; H -> 'H'; ZWNJ -> 'n'; ZWJ -> 'j'; M -> 'M'; SM -> 'S'
        A -> 'A'; PLACEHOLDER -> 'p'; DOTTEDCIRCLE -> 'd'; RS -> 'r'; REPHA -> 'F'; CM -> 'm'; SYMBOL -> 'y'; CS -> 'c'
        else -> 'x'
    }

    private val patterns: List<Pair<Int, Regex>> by lazy {
        val c = "[CR]"
        val n = "(?:(?:n?r)?(?:NN?)?)"
        val reph = "(?:RH|F)"
        val cn = "(?:${c}j?$n)"
        val symbol = "(?:yN?)"
        val matraGroup = "(?:[jn]*M N?H?)".replace(" ", "")
        val syllableTail = "(?:(?:[jn]?SS?n?)?[AD]*)"
        val halantGroup = "(?:[jn]?H(?:jN?)?)"
        val finalHalantGroup = "(?:$halantGroup|Hn)"
        val medialGroup = "m?"
        val halantOrMatraGroup = "(?:$finalHalantGroup|$matraGroup*)"
        val complexTail = "(?:(?:$halantGroup$cn)*$medialGroup$halantOrMatraGroup$syllableTail)"
        listOf(
            CONSONANT_SYLLABLE to Regex("[Fc]?$cn$complexTail"),
            VOWEL_SYLLABLE to Regex("$reph?V$n?(?:j|$complexTail)"),
            STANDALONE_CLUSTER to Regex("(?:[Fc]?p|$reph?d)$n?$complexTail"),
            SYMBOL_CLUSTER to Regex("$symbol$syllableTail"),
            BROKEN_CLUSTER to Regex("$reph?$n?$complexTail"),
        )
    }

    /** Splits [cats] into syllables: list of (start, end exclusive, type), leftmost-longest like Ragel. */
    fun syllables(cats: IntArray): List<Triple<Int, Int, Int>> {
        val s = String(CharArray(cats.size) { letter(cats[it]) })
        val out = ArrayList<Triple<Int, Int, Int>>()
        var start = 0
        while (start < s.length) {
            var bestLen = 0
            var bestType = NON_INDIC_CLUSTER
            for ((type, re) in patterns) {
                val m = re.toPattern().matcher(s)
                for (end in s.length downTo start + 1) {
                    if (end - start <= bestLen) break
                    m.region(start, end)
                    if (m.matches()) { bestLen = end - start; bestType = type; break }
                }
            }
            if (bestLen == 0) { bestLen = 1; bestType = NON_INDIC_CLUSTER }
            out.add(Triple(start, start + bestLen, bestType))
            start += bestLen
        }
        return out
    }
}
