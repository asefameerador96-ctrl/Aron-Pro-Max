package com.aktcl.aron.feature.tso.deviceotp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What a read of the list gave (the host maps its transport answer to this). */
sealed interface DeviceOtpFetch {
    data class Fresh(val json: String) : DeviceOtpFetch
    /** Offline or the server is busy: issuing a code needs the server, so there is no kept copy (a code is a secret). */
    data object Unavailable : DeviceOtpFetch
    /** 403/404: this account may not use the panel. */
    data object Refused : DeviceOtpFetch
}

/** What an issue call gave. */
sealed interface DeviceOtpIssue {
    data class Issued(val json: String) : DeviceOtpIssue
    data object Offline : DeviceOtpIssue
    /** The server refused (outside the TSO's zones, rate limit, not allowed). */
    data object Refused : DeviceOtpIssue
}

enum class DeviceOtpProblem { OFFLINE, REFUSED, UNREADABLE, ISSUE_OFFLINE, ISSUE_REFUSED }

/**
 * State of the TSO Device OTP screen. [read] fetches a page (null cursor: the first), [issue] asks the server for a new code for one rep
 * the server listed. Online only by nature: nothing here is stored on the phone, and the model lives and dies with the screen.
 * Issuing twice for the same rep at once is one call ([issuing]).
 */
class TsoDeviceOtpModel(
    private val read: suspend (cursor: String?) -> DeviceOtpFetch,
    private val issue: suspend (userId: Long) -> DeviceOtpIssue,
) {
    var rows: List<DeviceOtpRow>? by mutableStateOf(null)
        private set
    var nextCursor: String? by mutableStateOf(null)
        private set
    var problem: DeviceOtpProblem? by mutableStateOf(null)
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var issuing: Long? by mutableStateOf(null)
        private set

    /** The first page again (the screen opened, or pull to refresh). A failed refresh keeps what is on screen and says why. */
    suspend fun refresh() {
        if (loading) return
        loading = true
        try {
            apply(read(null), append = false)
        } finally {
            loading = false
        }
    }

    suspend fun more() {
        val c = nextCursor ?: return
        if (loading) return
        loading = true
        try {
            apply(read(c), append = true)
        } finally {
            loading = false
        }
    }

    private fun apply(r: DeviceOtpFetch, append: Boolean) {
        when (r) {
            DeviceOtpFetch.Unavailable -> problem = DeviceOtpProblem.OFFLINE
            DeviceOtpFetch.Refused -> { rows = emptyList(); nextCursor = null; problem = DeviceOtpProblem.REFUSED }
            is DeviceOtpFetch.Fresh -> {
                val page = TsoDeviceOtps.parse(r.json)
                if (page == null) { problem = DeviceOtpProblem.UNREADABLE; return }
                problem = null
                rows = if (append) (rows.orEmpty().filter { o -> page.rows.none { it.userId == o.userId } } + page.rows) else page.rows
                nextCursor = page.nextCursor
            }
        }
    }

    suspend fun issueFor(userId: Long) {
        if (issuing != null) return
        // only a rep the server listed can be issued a code: the app never invents an id
        if (rows.orEmpty().none { it.userId == userId }) return
        issuing = userId
        try {
            when (val r = issue(userId)) {
                is DeviceOtpIssue.Issued -> {
                    val row = TsoDeviceOtps.parseIssued(r.json)
                    if (row == null) problem = DeviceOtpProblem.UNREADABLE
                    else { rows = TsoDeviceOtps.withIssued(rows.orEmpty(), row); problem = null }
                }
                DeviceOtpIssue.Offline -> problem = DeviceOtpProblem.ISSUE_OFFLINE
                DeviceOtpIssue.Refused -> problem = DeviceOtpProblem.ISSUE_REFUSED
            }
        } finally {
            issuing = null
        }
    }
}
