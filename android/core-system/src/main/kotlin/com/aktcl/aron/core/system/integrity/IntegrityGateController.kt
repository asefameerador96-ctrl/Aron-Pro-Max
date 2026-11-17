package com.aktcl.aron.core.system.integrity

import java.io.File

/**
 * Reads the settings, applies [DevOptionsPolicy], and records one [IntegrityEvent] per gate ONSET (not per resume): the
 * flags currently recorded as gated are kept in a small file, so a restart while the gate is still up adds nothing and a
 * new onset (flags changed, or gate lifted and raised again) adds one.
 *
 * Data safety (D-OWNER-MDM-02 i): this class only reads settings and appends to its own two small files. It never touches
 * the session, the databases, the outbox or WorkManager, so a raised gate cannot lose or discard anything; background
 * sync keeps draining queued records because it does not consult the gate.
 */
class IntegrityGateController(
    private val flagsReader: () -> DevFlags,
    private val exemption: LabExemptionStore,
    private val log: IntegrityEventLog,
    private val dir: File,
    /** [Exemption.DEBUG_BUILD] or [Exemption.BENCHMARK_BUILD] for those variants; null for release. */
    private val buildExemption: Exemption?,
    private val appVersion: String,
    private val nowUtcMs: () -> Long,
    /**
     * Whether a stored lab exemption is honoured in this build. FALSE until the server piece lands: today the file is not
     * authenticated, so a release build ignores it (D-OWNER-MDM-02 iii). With slice 3 the grant is bound to the device by a
     * server signature over device id and expiry, and this becomes true.
     */
    private val labExemptionHonoured: Boolean = false,
) {
    private val stateFile get() = File(dir, "gate-state.txt")

    /** A fresh decision from the settings now. Cheap: two settings reads and a small file read when gated. */
    fun check(): GateState {
        val flags = runCatching(flagsReader).getOrDefault(DevFlags(devOptions = false, adb = false))
        val lab = flags.any && buildExemption == null && labExemptionHonoured && runCatching { exemption.isActive() }.getOrDefault(false)
        return DevOptionsPolicy.decide(flags, buildExemption, lab)
    }

    /** Records the onset of [state] once (call off the main thread). Returns true when an event was written. */
    @Synchronized
    fun noteOnset(state: GateState): Boolean {
        if (!state.flags.any) { com.aktcl.aron.core.common.AtomicText.delete(stateFile); return false }
        val key = state.flags.codes.joinToString(",") + "/" + state.exempt.name
        if (com.aktcl.aron.core.common.AtomicText.read(stateFile) == key) return false
        log.record(IntegrityEvent(nowUtcMs(), state.flags.codes, state.exempt.name.lowercase(), appVersion))
        runCatching { com.aktcl.aron.core.common.AtomicText.write(stateFile, key) }
        return true
    }
}
