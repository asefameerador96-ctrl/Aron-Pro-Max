package com.aktcl.aron.backend.masterdata.loader

import java.io.BufferedReader
import java.io.Reader

/**
 * Streaming reader of the owner's master-data export, saved as CSV (the workbook itself is never committed, never copied into a test, a seed or a log:
 * `docs/reference/master-data-profile-2026-10.md`). One row per outlet, the 32 columns of the profile; the header names are matched by letters and digits
 * only, so "Sales_Rep_Id", "Sales Rep Id" and "SALES REP ID" are the same column and the column ORDER does not matter. A column the file does not carry
 * reads as empty everywhere. Quoted fields, embedded commas, doubled quotes and CRLF are handled; nothing is held in memory but the current row.
 */
class MasterCsv(reader: Reader) : AutoCloseable {
    private val r = BufferedReader(reader, 1 shl 16)

    /** The file's header names in file order (as written), for the report. */
    val header: List<String>

    private val index: Map<String, Int>

    init {
        val first = readRecord() ?: error("the master CSV is empty")
        header = first
        index = first.mapIndexed { i, h -> key(h) to i }.toMap()
    }

    /** One input row: [no] is its 1-based position in the data rows (the header is not a data row). */
    class Row(val no: Long, private val cells: List<String>, private val index: Map<String, Int>) {
        /** The named column, trimmed; "" when the column is absent or empty. The field writes "--" for "none": the callers (ImportRules) know that. */
        operator fun get(column: Column): String = index[column.key]?.let { cells.getOrNull(it) }?.trim().orEmpty()
        /** True when every cell is empty (a trailing line of the export): such a row is not counted as an input row. */
        fun isBlank(): Boolean = cells.all { it.isBlank() }
    }

    /** The columns the loader reads, with the profile's header names. */
    enum class Column(header: String) {
        WING_CODE("Wing Code"), WING("Wing"), DIVISION_CODE("Division Code"), DIVISION("Division"),
        TERRITORY_CODE("Territory Code"), TERRITORY("Territory"), HOUSE_CODE("House Code"), HOUSE("House"),
        ZONE_CODE("Zone Code"), ZONE("Zone"), ROUTE_CODE("Route Code"), ROUTE("Route"),
        VISIT_FREQUENCY("Visit Frequency"), VISIT_DAY("Visit Day"),
        REP_ID("Sales_Rep_Id"), REP_NAME("Sales_Rep_Name"), REP_PHONE("Phone_No"),
        OUTLET_CODE("Outlet Code"), OUTLET_NAME("Outlet Name"), OWNER_NAME("Owner Name"), CONTACT_NUMBER("Contact Number"),
        ADDRESS("Address"), CLUSTER_NAME("Cluster Name"), CLUSTER_TYPE("Cluster Type"), STATUS("Status"),
        LATITUDE("Latitude"), LONGITUDE("Longitude"), GEO_CLASS("Geo Classification"), CHANNEL("Channel"), SUB_CHANNEL("Sub-Channel"),
        CREATED_AT("Created At"), UPDATED_AT("Updated At");

        val key: String = key(header)
    }

    /** Header names of [Column] that the file does not carry (reported once; every value of such a column reads as empty). */
    fun missingColumns(): List<Column> = Column.entries.filter { it.key !in index }

    private var rowNo = 0L

    /** The next data row, or null at the end of the file. Blank lines are skipped and never counted. */
    fun next(): Row? {
        while (true) {
            val cells = readRecord() ?: return null
            val row = Row(rowNo + 1, cells, index)
            if (row.isBlank()) continue
            rowNo++
            return row
        }
    }

    /** Reads one CSV record (which may span lines inside a quoted field); null at the end of the file. */
    private fun readRecord(): List<String>? {
        val out = ArrayList<String>(32)
        val cell = StringBuilder()
        var quoted = false
        var any = false
        while (true) {
            val c = r.read()
            if (c < 0) return if (any || out.isNotEmpty() || cell.isNotEmpty()) out.also { it.add(cell.toString()) } else null
            any = true
            val ch = c.toChar()
            when {
                quoted && ch == '"' -> {
                    r.mark(1)
                    val n = r.read()
                    if (n.toChar() == '"' && n >= 0) cell.append('"') else { quoted = false; if (n >= 0) r.reset() }
                }
                quoted -> cell.append(ch)
                ch == '"' && cell.isEmpty() -> quoted = true
                ch == ',' -> { out.add(cell.toString()); cell.setLength(0) }
                ch == '\r' -> Unit
                ch == '\n' -> { out.add(cell.toString()); return out }
                else -> cell.append(ch)
            }
        }
    }

    override fun close() = r.close()

    companion object {
        /** Letters and digits only, lower case: "Sales_Rep_Id", "Sales Rep Id" and "SALESREPID" are one column. */
        fun key(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
