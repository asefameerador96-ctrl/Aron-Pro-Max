package com.aktcl.aron.feature.home

/** A user bound to this phone. */
data class BoundUser(val userId: Long, val name: String, val code: String)

/** The answer stored for a business date on a phone (survives a kill: backed by a small store). */
data class IdentityAnswer(val businessDate: String, val actingForUserId: Long?)

/** Persistence of the day's answer; production: SharedPreferences or the user's Room database. */
interface IdentityStore {
    fun answerFor(businessDate: String): IdentityAnswer?
    fun save(answer: IdentityAnswer)
}

sealed interface IdentityPrompt {
    /** No question: one bound user, or already answered for this date. */
    data class NotNeeded(val actingForUserId: Long?) : IdentityPrompt

    /** Ask 'are you <name> (<code>)?' for the route's assignee; [choices] are the other bound users for a "no". */
    data class Ask(val assignee: BoundUser, val choices: List<BoundUser>) : IdentityPrompt
}

/**
 * Identity confirmation on a shared phone (F-SR-072): the first capture of a business date on a phone with two bound
 * users asks 'are you <name> (<code>)?' for the route's assignee. "Yes" stores nothing special; a different person
 * working the route is stored as `acting_for_user_id` on every record of that date (the cover model of docs/24 s4.3).
 */
class IdentityConfirmation(private val store: IdentityStore) {
    /** [loggedIn] is the session user; [routeAssignee] the user the route is assigned to for the date (primary or cover). */
    fun promptFor(businessDate: String, boundUsers: List<BoundUser>, loggedIn: BoundUser, routeAssignee: BoundUser): IdentityPrompt {
        store.answerFor(businessDate)?.let { return IdentityPrompt.NotNeeded(it.actingForUserId) }
        // Cover work: whoever captures when they are not the route's assignee acts for the assignee, asked or not.
        val cover = routeAssignee.userId.takeIf { it != loggedIn.userId }
        if (boundUsers.map { it.userId }.distinct().size < 2) return IdentityPrompt.NotNeeded(cover)
        return IdentityPrompt.Ask(routeAssignee, boundUsers.filter { it.userId != routeAssignee.userId })
    }

    /** "Yes, I am the assignee": the logged-in user works the route as themself. */
    fun confirmSelf(businessDate: String, loggedIn: BoundUser, routeAssignee: BoundUser): Long? {
        val acting = routeAssignee.userId.takeIf { it != loggedIn.userId }
        store.save(IdentityAnswer(businessDate, acting))
        return acting
    }

    /** "No": [actingFor] is the person whose route is being worked when it differs from the logged-in user. */
    fun denyAndChoose(businessDate: String, loggedIn: BoundUser, actingFor: BoundUser): Long? {
        val acting = actingFor.userId.takeIf { it != loggedIn.userId }
        store.save(IdentityAnswer(businessDate, acting))
        return acting
    }
}
