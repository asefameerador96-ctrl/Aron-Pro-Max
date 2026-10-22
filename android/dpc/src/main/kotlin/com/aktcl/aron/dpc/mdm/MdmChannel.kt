package com.aktcl.aron.dpc.mdm

import android.content.Context
import com.aktcl.aron.dpc.DeviceOwnerPolicy
import java.io.File

/**
 * The app-side entry of the command channel: builds a [CommandProcessor] over the device-owner policy's own directory, so the
 * stored MDM policy, the ledger and the blocking re-evaluation are the ones the DPC already uses. The app supplies only the
 * HTTP side ([CommandApi]), the server key ([CommandVerifier]) and the device uuid.
 */
object MdmChannel {
    fun processor(context: Context, api: CommandApi, verifier: CommandVerifier, deviceUuid: String, afterPolicyChanged: () -> Unit = {}): CommandProcessor {
        val app = context.applicationContext
        val policy = DeviceOwnerPolicy.get(app)
        val dir = File(app.noBackupFilesDir, "dpc")
        return CommandProcessor(
            api, AndroidMdmGateway(app), verifier, deviceUuid, CommandLedger(dir), MdmPolicyStore(dir),
            nowMs = { policy.trustedNow() }, onPolicyChanged = { try { policy.reevaluateBlocking() } finally { runCatching(afterPolicyChanged) } },
        )
    }
}
