package com.aktcl.aron.dpc.blocking

import com.aktcl.aron.dpc.policy.DevicePolicy
import com.aktcl.aron.rules.BusinessDate
import java.io.File

/** An installed package as the blocking rules see it. */
data class InstalledApp(val packageName: String, val system: Boolean, val launchable: Boolean)

/** The device-owner calls of app blocking (DevicePolicyManager.setPackagesSuspended). */
interface SuspendGateway {
    fun isDeviceOwner(): Boolean
    /** Returns the packages Android refused to change. */
    fun setSuspended(packages: List<String>, suspended: Boolean): List<String>
    fun installed(): List<InstalledApp>
    val ownPackage: String
}

/** The phone's attendance state for blocking, kept across kills and reboots. */
data class BlockingState(
    val checkInDate: String? = null,
    val checkInAtMs: Long? = null,
    val checkedOut: Boolean = false,
    /** Packages this engine suspended and has not released yet. */
    val suspended: List<String> = emptyList(),
    val activeSince: Long? = null,
)

/** One evaluation's outcome, for the status report (`blocking_active`, `blocking_since`, `suspended_packages`). */
data class BlockingOutcome(val active: Boolean, val since: Long?, val suspended: List<String>, val failed: List<String>, val changed: Boolean)

/** Persists [BlockingState] as a small key=value file, replaced atomically. */
class BlockingStore(private val dir: File) {
    private val file get() = File(dir, "blocking-state.txt")

    @Synchronized
    fun load(): BlockingState {
        val m = file.takeIf { it.isFile }?.readLines()?.mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap()
            ?: return BlockingState()
        return BlockingState(
            checkInDate = m["date"]?.ifEmpty { null },
            checkInAtMs = m["in"]?.toLongOrNull(),
            checkedOut = m["out"] == "1",
            suspended = m["suspended"].orEmpty().split(',').filter { it.isNotBlank() },
            activeSince = m["since"]?.toLongOrNull(),
        )
    }

    @Synchronized
    fun save(s: BlockingState) {
        dir.mkdirs()
        val tmp = File(dir, "blocking-state.tmp")
        tmp.writeText(
            listOf("date=${s.checkInDate.orEmpty()}", "in=${s.checkInAtMs ?: ""}", "out=${if (s.checkedOut) 1 else 0}",
                "suspended=${s.suspended.joinToString(",")}", "since=${s.activeSince ?: ""}").joinToString("\n"),
        )
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "cannot store the blocking state" } }
    }
}

/**
 * Scheduled app blocking (docs/24 s10.5, N-032): a committed check-in suspends the configured apps; a committed check-out
 * or the Dhaka `hard_end_time`, whichever comes first, releases them. Everything runs on the phone from the stored policy
 * and the stored attendance state, so it works in airplane mode; suspension is a system setting, so it survives a kill
 * and a reboot, and [evaluate] re-checks it at every start, boot and alarm. The Aron app and `always_allowed_packages`
 * are never suspended. Time comes from [trustedNowMs] (the trusted clock, F-SYS-049).
 */
class BlockingEngine(
    private val gw: SuspendGateway,
    private val store: BlockingStore,
    private val policy: () -> DevicePolicy?,
    private val trustedNowMs: () -> Long,
    /** Business date to working day from the bundle calendar; null when unknown (counted as working: the rep checked in). */
    private val isWorkingDay: (String) -> Boolean? = { null },
) {
    @Synchronized
    fun onCheckIn(): BlockingOutcome {
        val now = trustedNowMs()
        val s = store.load()
        val today = BusinessDate.of(now).toString()
        // A second check-in on the same day keeps the first time; a new day starts fresh.
        val next = if (s.checkInDate == today && !s.checkedOut) s else s.copy(checkInDate = today, checkInAtMs = now, checkedOut = false)
        store.save(next)
        return evaluate()
    }

    @Synchronized
    fun onCheckOut(): BlockingOutcome {
        val s = store.load()
        store.save(s.copy(checkedOut = true))
        return evaluate()
    }

    /** Re-applies the rule from stored state: app start, boot, policy change, the hard-end alarm. */
    @Synchronized
    fun evaluate(): BlockingOutcome {
        val s = store.load()
        val p = policy()
        val now = trustedNowMs()
        if (!gw.isDeviceOwner()) return BlockingOutcome(false, null, s.suspended, emptyList(), false)
        val shouldBlock = p != null && shouldBlock(p, s, now)
        val target = if (shouldBlock) targets(p!!) else emptyList()
        val toRelease = s.suspended.filter { it !in target }
        // Every target is re-asserted each time (idempotent): an OEM that dropped a suspension at reboot is repaired.
        val failedRelease = if (toRelease.isNotEmpty()) runCatching { gw.setSuspended(toRelease, false) }.getOrDefault(toRelease) else emptyList()
        val failedSuspend = if (target.isNotEmpty()) runCatching { gw.setSuspended(target, true) }.getOrDefault(target) else emptyList()
        // Refused releases stay on the list so the next evaluation retries them.
        val suspendedNow = (target.filter { it !in failedSuspend } + failedRelease).distinct().sorted()
        val active = shouldBlock
        val since = if (active) s.activeSince ?: now else null
        store.save(s.copy(suspended = suspendedNow, activeSince = since))
        return BlockingOutcome(active, since, suspendedNow, (failedRelease + failedSuspend).distinct(), suspendedNow != s.suspended.sorted() || (since == null) != (s.activeSince == null))
    }

    fun shouldBlock(p: DevicePolicy, s: BlockingState, nowMs: Long): Boolean {
        val sch = p.schedule
        if (!sch.enabled) return false
        val today = BusinessDate.of(nowMs).toString()
        if (s.checkInDate != today || s.checkedOut || s.checkInAtMs == null) return false
        if (nowMs < s.checkInAtMs) return false // trusted time went backwards past the check-in: do not block
        if (sch.workingDaysOnly && isWorkingDay(today) == false) return false
        val end = sch.hardEndTime?.let { hardEndMs(today, it) }
        return end == null || nowMs < end
    }

    /** The apps to suspend now; never the Aron app or an always-allowed package. */
    fun targets(p: DevicePolicy): List<String> {
        val never = p.appControl.alwaysAllowedPackages.toSet() + gw.ownPackage
        val installed = gw.installed()
        val names = installed.map { it.packageName }.toSet()
        val wanted = when (p.appControl.mode) {
            "allowlist" -> {
                val allowed = p.appControl.allowedPackages.toSet()
                installed.filter { it.launchable && !it.system && it.packageName !in allowed }.map { it.packageName }
            }
            else -> p.appControl.blockedPackages.filter { it in names }
        }
        return wanted.filter { it !in never }.distinct().sorted()
    }

    companion object {
        /** `HH:mm` Dhaka on [businessDate] as epoch ms; null when unparseable (then only a check-out releases). */
        fun hardEndMs(businessDate: String, hhmm: String): Long? {
            val m = Regex("""^([01]\d|2[0-3]):([0-5]\d)$""").matchEntire(hhmm) ?: return null
            val day = kotlinx.datetime.LocalDate.parse(businessDate).toEpochDays().toLong()
            return day * 86_400_000L + (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 60_000L - BusinessDate.DHAKA_OFFSET_MS
        }
    }
}
