package com.aktcl.aron.core.session

import com.aktcl.aron.core.common.ClientIds
import java.io.File

/**
 * The phone's `device_uuid` (docs/24 s3.2, s10.4). Enrolment (the device-policy lane) mints it and calls
 * [setEnrolledUuid]. Until a phone is enrolled (dev phones before Day 3) a local UUID v4 is minted once and kept, so
 * login, `X-Device-Id` and per-device rate limits work against the dev API where `cfg.device.require_enrolled` is false.
 */
class DeviceIdentity(private val dir: File) {
    private val file get() = File(dir, "device_uuid")

    @get:Synchronized
    val deviceUuid: String
        get() {
            file.takeIf { it.isFile }?.readText()?.trim()?.takeIf(ClientIds::isUuidV4)?.let { return it }
            val minted = ClientIds.newUuid()
            write(minted)
            return minted
        }

    @Synchronized
    fun setEnrolledUuid(uuid: String) {
        require(ClientIds.isUuidV4(uuid)) { "device_uuid must be a lower-case UUID v4" }
        write(uuid)
    }

    private fun write(value: String) {
        dir.mkdirs()
        val tmp = File(dir, "device_uuid.tmp")
        tmp.writeText(value)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
