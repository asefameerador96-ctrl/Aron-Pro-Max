package com.aktcl.aron.feature.programmes

/**
 * How a programme save ended. SAVED and ALREADY_SAVED (a replay of the same basket) both end in "saved on this phone"; CONFLICT means the uuid held a DIFFERENT
 * basket (nothing written; the host mints a new uuid and the SR confirms again); DAY_SUBMITTED means the route-day was already submitted; CHECK_IN_FIRST means there is no open check-in on the date (or the day was checked out): nothing is written;
 * REFUSED is any other refusal. Nothing is written unless SAVED.
 */
enum class SaveOutcome { SAVED, ALREADY_SAVED, CONFLICT, DAY_SUBMITTED, CHECK_IN_FIRST, REFUSED }
