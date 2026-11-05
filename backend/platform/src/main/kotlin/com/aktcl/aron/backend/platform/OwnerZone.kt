package com.aktcl.aron.backend.platform

/**
 * The zone a record's owner belongs to when a portal read is judged by the owner's place: the user's home zone, else the
 * zone of its primary route assignment valid on `:today`. One SQL fragment for masterdata's owner-reach lists
 * (`AdminSupport.userZone`, as FeedbackApi's `ownerInReach`) and media's feedback-photo read (lane X2 fix round, checker
 * L3), so a feedback photo is never readable wider than the feedback record it belongs to.
 */
object OwnerZone {
    /** SQL expression over the `app.app_user` alias [alias]; the statement binds `:today`. */
    fun sql(alias: String): String =
        "COALESCE($alias.home_zone_id, (SELECT r.zone_id FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id WHERE a.user_id = $alias.id AND a.ended_at IS NULL " +
            "AND (a.valid_to IS NULL OR a.valid_to > :today) AND a.kind = 'primary' AND a.valid_from <= :today ORDER BY a.valid_from DESC LIMIT 1))"
}
