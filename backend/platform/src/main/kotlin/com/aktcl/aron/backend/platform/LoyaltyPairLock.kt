package com.aktcl.aron.backend.platform

/**
 * The advisory-lock key of one loyalty (outlet, programme) pair: `pg_advisory_xact_lock(hashtextextended(key, 0))`. ONE function for every writer of a pair's balance (the ledger job, the portal's
 * points adjustment) and for the tests that probe it, so a change of the key format cannot make one of them silently stop serialising with the others.
 */
object LoyaltyPairLock {
    fun key(outlet: Long, programme: Long): String = "loyalty:$outlet:$programme"
}
