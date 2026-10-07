package com.aktcl.aron.core.session

/**
 * What one enrolment attempt (docs/24 s10.4) came to, as the enrolment screen shows it. The device-policy wiring
 * (core-sync `DeviceEnrolment`) produces it; feature-auth renders it. Never carries the token.
 */
sealed interface EnrolmentOutcome {
    /** The server enrolled this phone; login comes next. */
    data object Enrolled : EnrolmentOutcome

    /** The token is stored; the server could not be reached or asked to try again (offline, timeout, 5xx). */
    data class Waiting(val offline: Boolean) : EnrolmentOutcome

    /** The server refused the token for good ([code], e.g. `ERR_ENROLMENT_TOKEN_EXPIRED`): a new token is needed. */
    data class Refused(val code: String) : EnrolmentOutcome

    /** The text is not an enrolment token or QR of this app (wrong app, wrong server, typo). */
    data class Unreadable(val reason: String) : EnrolmentOutcome
}
