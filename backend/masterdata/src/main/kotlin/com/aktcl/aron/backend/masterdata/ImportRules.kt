package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.rules.TextRules
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The rules that turn the field's master data into our rows, in ONE place so that every path (the outlet import, the bulk loader, the portal editors,
 * the outlet requests, the user phone) gives the same answer (docs/reference/master-data-mapping.md, D-OWNER-PARITY-02, D-MD-nn). Pure functions: no
 * database, no clock except where a parameter says so. The field's data is the truth; a value we cannot use is repaired, or stored empty with a warning,
 * and the row still loads.
 */
internal object ImportRules {
    /**
     * Prefix of an outlet code the SERVER generates when a row has none (M12). The field's codes are digits, digits with capitals, and two capitals, a hyphen
     * and digits; a lower-case letter and a dot never occur in them, so a generated code can never merge into a master outlet. (Before D-MD-12 the prefix was `AD-`,
     * the same shape as 6,022 field codes; rows already created keep their code.)
     */
    const val GENERATED_OUTLET_CODE_PREFIX = "gen."

    // ---- phones (M08) -------------------------------------------------------------------------------------------------------------------------

    /** [value] is the canonical national form `01XXXXXXXXX` or null; [repaired] when the stored form differs from the input; [unusable] when text was given but cannot be repaired. */
    class Phone(val value: String?, val repaired: Boolean, val unusable: Boolean)

    /**
     * The shared phone rule: the same as the Android `TextRules.normalisePhone` (digits only, Bengali digits converted, `+880`/`880`/`00880` prefix and a
     * missing leading 0 repaired, spaces, dashes and brackets ignored). Blank input is "no phone" (not a warning). A number that cannot be repaired
     * (three digits, letters, wrong length) is stored empty and flagged; the stored CHECK stays strict.
     */
    fun phone(raw: String?): Phone {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return Phone(null, false, false)
        val n = TextRules.normalisePhone(t) ?: return Phone(null, false, true)
        return Phone(n.value, n.value != t, false)
    }

    // ---- channels (M05) -----------------------------------------------------------------------------------------------------------------------

    val CHANNELS: List<String> = listOf("GT", "DCC", "Astha", "RCC", "MT", "HoReCa")
    private val CHANNEL_BY_KEY = CHANNELS.associateBy { key(it) }

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    sealed interface ChannelParse {
        /** The file has no channel for this row. */
        data object Empty : ChannelParse
        /** Text that names none of the six channels. */
        data class Unknown(val raw: String) : ChannelParse
        data class Known(val code: String) : ChannelParse
    }

    /** Accepts our six codes and the field's labels ("GT Channel", "Astha Channel", "DCC Channel", "MT Channel", "RCC", "HoReCa"), any case, with or without the word Channel. */
    fun channel(raw: String?): ChannelParse {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t == "--") return ChannelParse.Empty
        var k = key(t)
        if (k.endsWith("channel") && k.length > "channel".length) k = k.removeSuffix("channel")
        return CHANNEL_BY_KEY[k]?.let { ChannelParse.Known(it) } ?: ChannelParse.Unknown(t)
    }

    /** `--`, blank and "none" mean "no sub-channel". */
    fun isNoSubChannel(raw: String?): Boolean {
        val t = raw?.trim().orEmpty()
        return t.isEmpty() || t == "--" || t.equals("none", ignoreCase = true) || t == "-"
    }

    // ---- geo class (M16) ----------------------------------------------------------------------------------------------------------------------

    /** Maps the field's text ("Semi Urban") to a stored geo_class value by comparing letters only, case-insensitively, against [known] (app.geo_class_def); null when blank or unknown. */
    fun geoClass(raw: String?, known: Collection<String>): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t == "--") return null
        val k = key(t)
        return known.firstOrNull { key(it) == k }
    }

    // ---- coordinates (M01) --------------------------------------------------------------------------------------------------------------------

    /** The box the sync layer uses for a plausible Bangladesh pin (IngestService); outside it a pin is stored but never confirmed. */
    fun inBangladesh(lat: Double, lng: Double): Boolean = lat in 20.5..26.7 && lng in 88.0..92.7

    /** Rounds to 8 decimals (about 1 mm); the field's sheets carry more digits and that is never a reason to reject a row. */
    fun round8(v: Double): Double = BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP).toDouble()

    // ---- timestamps (M20) ---------------------------------------------------------------------------------------------------------------------

    private val DHAKA = ZoneId.of("Asia/Dhaka")
    private val LOCAL_FORMATS: List<DateTimeFormatter> = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm",
        "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy-MM-dd HH:mm:ss.SSS", "M/d/yyyy H:mm:ss", "M/d/yyyy H:mm",
    ).map { DateTimeFormatter.ofPattern(it) }
    private val DATE_FORMATS: List<DateTimeFormatter> = listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "yyyy/MM/dd", "M/d/yyyy").map { DateTimeFormatter.ofPattern(it) }

    /**
     * Parses the field's Created At / Updated At text. A value without an offset is Asia/Dhaka local time; a date alone is the start of that Dhaka day.
     * Returns null when the text cannot be read or is later than [now] plus one day (a typo must not make an outlet look "changed in the future"); the
     * caller keeps the import time and warns. Day-first is tried before month-first for slashes (the field is Bangladeshi).
     */
    fun timestamp(raw: String?, now: Instant): Instant? {
        val t = TextRules.toWesternDigits(raw?.trim().orEmpty()).value
        if (t.isEmpty()) return null
        val parsed: Instant? = runCatching { OffsetDateTime.parse(t).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(t) }.getOrNull()
            ?: LOCAL_FORMATS.firstNotNullOfOrNull { f -> try { LocalDateTime.parse(t, f).atZone(DHAKA).toInstant() } catch (_: DateTimeParseException) { null } }
            ?: DATE_FORMATS.firstNotNullOfOrNull { f -> try { LocalDate.parse(t, f).atStartOfDay(DHAKA).toInstant() } catch (_: DateTimeParseException) { null } }
        return parsed?.takeIf { it <= now.plusSeconds(86_400) }
    }

    // ---- visit plan (M02, M03, M19) -----------------------------------------------------------------------------------------------------------

    /** The weekday names of the field's Visit Day column, bit0 Saturday ... bit6 Friday (the contract's mask). */
    private val DAY_BIT = mapOf("sat" to 0, "sun" to 1, "mon" to 2, "tue" to 3, "wed" to 4, "thu" to 5, "fri" to 6)

    /**
     * [kind] is the stored visit_kind (`daily`, `3f`, `2f`, `none`, or null for a count the three kinds do not name); [mask] the stored visit_days_mask.
     * [warnings]: `frequency_mismatch` (the frequency disagrees with the days; the DAYS win), `days_missing` (a frequency without days, loaded as no plan),
     * `unreadable_days` (day text we cannot read, loaded as no plan), `weekend_day_in_pattern` (the pattern includes a weekend day, loaded as in the file, M19).
     */
    class VisitPlan(val kind: String?, val mask: Int, val warnings: List<String>)

    /**
     * ONE rule for the visit plan of a route (D-MD-02, D-MD-03). Frequency 6 or "Daily" is kind `daily` with mask 127: "daily" means EVERY SELLING DAY of the
     * zone, the weekend stays off because [com.aktcl.aron.backend.platform.DayPlan.isPlanned] needs a selling day as well as the mask bit, and a
     * make-up Friday in the working calendar plans the daily routes on that Friday; nothing else does. A list of day names ("(Sat, Mon, Wed)") sets exactly
     * those bits; a frequency of 3 or 2 names the kind. No frequency and no days is the explicit "no visit plan" (kind `none`, mask 0), stored as it is.
     * [weekendIsoDays] are the ISO weekday numbers (1 Monday .. 7 Sunday) that are not selling days (default Friday = 5).
     */
    fun visitPlan(frequency: String?, days: String?, weekendIsoDays: Set<Int> = setOf(5)): VisitPlan {
        val f = TextRules.toWesternDigits(frequency?.trim().orEmpty()).value
        val d = days?.trim().orEmpty()
        val warnings = ArrayList<String>()
        val freq = f.toDoubleOrNull()?.toInt()
        if (f.isEmpty() && d.isEmpty()) return VisitPlan("none", 0, warnings)
        var mask: Int
        if (d.equals("daily", ignoreCase = true) || (d.isEmpty() && freq == 6)) {
            mask = 127
        } else if (d.isEmpty()) {
            warnings += "days_missing"
            return VisitPlan("none", 0, warnings)
        } else {
            mask = 0
            var unreadable = false
            for (tok in d.split(',', ';', '/', ' ', '(', ')', '+').map { it.trim().lowercase() }.filter { it.isNotEmpty() }) {
                val bit = DAY_BIT[tok.take(3)]
                if (bit == null) unreadable = true else mask = mask or (1 shl bit)
            }
            if (unreadable || mask == 0) { warnings += "unreadable_days"; return VisitPlan("none", 0, warnings) }
        }
        val n = Integer.bitCount(mask)
        val kind = when (n) { 7 -> "daily"; 3 -> "3f"; 2 -> "2f"; else -> null }
        // Frequency 6 is "Daily" in the field (every selling day, Friday off): it equals seven mask bits. Any other frequency is compared with the number of named days.
        if (freq != null && freq != n && !(freq == 6 && n == 7)) warnings += "frequency_mismatch"
        val weekendBits = weekendIsoDays.fold(0) { m, iso -> m or (1 shl com.aktcl.aron.backend.platform.DayPlan.bit(java.time.DayOfWeek.of(iso))) }
        // A "daily" route carries the weekend bit by definition (mask 127); only a named-day pattern with a weekend day is flagged.
        if (kind != "daily" && mask and weekendBits != 0) warnings += "weekend_day_in_pattern"
        return VisitPlan(kind, mask, warnings)
    }
}
