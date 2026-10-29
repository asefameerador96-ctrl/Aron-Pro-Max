package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.Role

/**
 * Who works the support side of the portal (lane X2, lead ruling R-W-09 (6)), in one place for the modules that judge it:
 * the support desk (tickets, masterdata's SupportTicketService), the feedback triage (masterdata's FeedbackApi) and the
 * photo read URL (media's MediaRead), so the three never drift apart.
 */
object SupportRoles {
    /** D-TSO-MAN-22: the support-desk permission (seeded for ADMIN and SUPERADMIN in `app.role_grant_map`; SUPPORT can be granted it). */
    const val DESK_PERMISSION = "support.ticket.manage"

    /** The roles that triage TSO feedback (`PATCH /v1/feedback/{feedback_uuid}`, F-API-032). */
    val FEEDBACK_TRIAGE: Set<Role> = setOf(Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)

    /** The caller holds the support desk (its role's grants join the token's permissions in the guard). */
    fun isDesk(p: AronPrincipal): Boolean = DESK_PERMISSION in p.permissions
}
