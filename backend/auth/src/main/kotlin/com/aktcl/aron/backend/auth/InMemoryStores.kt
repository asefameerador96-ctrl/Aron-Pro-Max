package com.aktcl.aron.backend.auth

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Refresh store for tests and the pure logic; the JDBI store implements the same contract against PostgreSQL. */
class InMemoryRefreshStore : RefreshStore {
    private val ids = AtomicLong(0)
    private val families = ConcurrentHashMap<Long, FamilyView>()
    private data class Row(val familyId: Long, val expiresAt: Instant, var usedAt: Instant? = null, var replacedBy: String? = null)
    private val tokens = ConcurrentHashMap<String, Row>()

    override fun createFamily(family: NewFamily, tokenHash: String, tokenExpiresAt: Instant): Long {
        val id = ids.incrementAndGet()
        families[id] = FamilyView(id, family.userId, family.deviceId, family.grant, family.flavour, family.absoluteExpiresAt, null, null)
        tokens[tokenHash] = Row(id, tokenExpiresAt)
        return id
    }

    override fun findToken(hash: String): TokenView? {
        val r = tokens[hash] ?: return null
        return TokenView(hash, families.getValue(r.familyId), r.expiresAt, r.usedAt, r.replacedBy)
    }

    @Synchronized
    override fun rotate(hash: String, at: Instant, childHash: String, childExpiresAt: Instant): Boolean {
        val r = tokens[hash] ?: return false
        if (r.usedAt != null) return false
        r.usedAt = at
        r.replacedBy = childHash
        tokens[childHash] = Row(r.familyId, childExpiresAt)
        return true
    }

    override fun revokeFamily(familyId: Long, at: Instant, reason: String) {
        families.computeIfPresent(familyId) { _, f -> if (f.revokedAt == null) f.copy(revokedAt = at, revokeReason = reason) else f }
    }

    fun familyCount(): Int = families.size
}

/** Lockout counters in memory, per replica. */
class InMemoryLockoutStore : LockoutStore {
    private class State { val failures = ArrayDeque<Instant>(); var lockedUntil: Instant? = null; var locks = 0 }
    private val states = ConcurrentHashMap<String, State>()

    override fun lockedUntil(key: String, now: Instant): Instant? =
        states[key]?.let { synchronized(it) { it.lockedUntil?.takeIf { u -> u.isAfter(now) } } }

    override fun recordFailure(key: String, now: Instant, window: Duration): Int {
        val s = states.computeIfAbsent(key) { State() }
        synchronized(s) {
            s.failures.addLast(now)
            while (s.failures.isNotEmpty() && !s.failures.first().isAfter(now.minus(window))) s.failures.removeFirst()
            return s.failures.size
        }
    }

    override fun lock(key: String, now: Instant, base: Duration): Instant {
        val s = states.computeIfAbsent(key) { State() }
        synchronized(s) {
            s.lockedUntil?.let { if (it.isAfter(now)) return it }  // already locked: a burst never locks twice
            val until = now.plus(minOf(base.multipliedBy(1L shl s.locks.coerceAtMost(10)), Duration.ofHours(24)))
            s.locks++
            s.lockedUntil = until
            s.failures.clear()
            return until
        }
    }

    override fun reset(key: String) { states.remove(key) }
}
