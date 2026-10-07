package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.RequestJson
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class DeviceReplaceIn(val mode: String, val reason: String, val new_device_user_id: Long? = null)

@Serializable
data class DeviceReplaceOut(val old_device_id: Long, val old_state: String, val pending_rows: Int, val otp: DeviceOtpDto?)

private val REPLACERS = setOf(Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)

/**
 * `POST /v1/admin/devices/{device_id}/replace` (F-API-086). `revoke_now` revokes the old phone and frees its bindings (uploads still
 * drain on the upload grant); `upload_first` leaves it active until its outbox is empty (it becomes `replaced` when the new phone
 * enrols, which names the replacing device). Either way a device OTP is issued for the user in the same transaction.
 */
fun Route.deviceReplaceRoutes(d: DeviceOtpDeps) {
    authenticated(d.guard) {
        post("/admin/devices/{device_id}/replace") {
            val p = call.principal
            if (p.role !in REPLACERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "not allowed to replace devices")
            val id = call.parameters["device_id"]?.toLongOrNull()?.takeIf { it >= 1 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad device_id", errors = listOf(FieldError("path.device_id", "invalid_value")))
            val req = try { RequestJson.decodeFromString<DeviceReplaceIn>(call.receiveText()) } catch (e: kotlinx.serialization.SerializationException) {
                throw ApiProblem(ProblemCode.ERR_VALIDATION, "malformed request body", errors = listOf(FieldError("body", "invalid_body")))
            }
            if (req.mode != "upload_first" && req.mode != "revoke_now") throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad mode", errors = listOf(FieldError("body.mode", "invalid_value")))
            if (req.reason.length > 500 || req.reason.trim().length < 10) throw ApiProblem(ProblemCode.ERR_VALIDATION, "a reason of 10 to 500 characters is required", errors = listOf(FieldError("body.reason", "length")))
            if (req.new_device_user_id != null && req.new_device_user_id < 1) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad new_device_user_id", errors = listOf(FieldError("body.new_device_user_id", "invalid_value")))
            call.respond(d.db.jdbi.inTransaction<DeviceReplaceOut, Exception> { h ->
                val dev = h.createQuery("SELECT status, COALESCE(pending_rows_reported, 0) FROM app.device WHERE id = :d FOR UPDATE").bind("d", id).map { rs, _ -> rs.getString(1) to rs.getInt(2) }.findOne().orElse(null)
                    ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "device $id")
                if (dev.first == "revoked" || dev.first == "replaced") throw ApiProblem(ProblemCode.ERR_CONFLICT, "the device is already ${dev.first}")
                val bound = h.createQuery("SELECT user_id FROM app.device_binding WHERE device_id = :d AND status = 'active' ORDER BY bind_ordinal LIMIT 1").bind("d", id).mapTo(Long::class.java).findOne().orElse(null)
                val userId = req.new_device_user_id ?: bound ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "the device has no bound user: name new_device_user_id", errors = listOf(FieldError("body.new_device_user_id", "required")))
                val now = OffsetDateTime.ofInstant(d.clock.now(), ZoneOffset.UTC)
                if (req.mode == "revoke_now") {
                    h.createUpdate("UPDATE app.device SET status = 'revoked', status_reason = :r, status_changed_at = :now WHERE id = :d").bind("r", req.reason.trim()).bind("now", now).bind("d", id).execute()
                    h.createUpdate("UPDATE app.device_binding SET status = 'revoked', unbound_at = :now, unbound_by = :by WHERE device_id = :d AND status = 'active'").bind("now", now).bind("by", p.userId).bind("d", id).execute()
                }
                AuditWriter.write(h, p, "device", id.toString(), "replace_" + req.mode, null, buildJsonObject { put("pending_rows", dev.second); put("otp_for_user", userId) }, req.reason, call.requestId)
                val otp = issueOtp(h, d, p, DeviceOtpIssueIn(userId, req.reason), true, emptySet(), call.requestId)
                DeviceReplaceOut(id, if (req.mode == "revoke_now") "revoked" else dev.first, dev.second, otp)
            })
        }
    }
}
