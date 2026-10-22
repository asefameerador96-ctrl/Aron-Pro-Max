package com.aktcl.aron.backend.analytics.mdm

import com.aktcl.aron.backend.analytics.devices.DeviceDeps
import com.aktcl.aron.backend.analytics.devices.EnrolmentTokenCreateRequest
import com.aktcl.aron.backend.analytics.devices.authDevice
import com.aktcl.aron.backend.analytics.devices.rawBody
import com.aktcl.aron.backend.analytics.devices.unzip
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.decodeStrict
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

class MdmDeps(val service: MdmService, val devices: DeviceDeps, val guard: AuthGuardDeps, val grants: MdmGrants = RoleGrants)

/**
 * `/v1/admin/mdm/...` (user token, the `mdm` module) and the phone's `GET /v1/devices/me/commands` and its `.../ack`
 * (X-Device-Proof, no user token). Mount only behind the flag `ARON_MDM_ENABLED=1` until the db migration lands.
 */
fun Route.mdmRoutes(d: MdmDeps) {
    val s = d.service

    get("/devices/me/commands") {
        val dev = call.authDevice(d.devices, ByteArray(0))
        call.respond(s.pull(dev))
    }
    post("/devices/me/commands/{command_id}/ack") {
        val body = call.rawBody()
        val dev = call.authDevice(d.devices, body)
        call.respond(s.ack(dev, call.parameters["command_id"].orEmpty(), decodeStrict(DeviceCommandAck.serializer(), call.unzip(body).decodeToString())))
    }

    authenticated(d.guard) {
        fun ApplicationCall.read() = d.grants.read(principal)
        fun ApplicationCall.write() = d.grants.write(principal)
        fun ApplicationCall.id(name: String) = parameters[name]?.toLongOrNull()?.takeIf { it >= 1 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad $name", errors = listOf(FieldError("path.$name", "invalid_value")))
        fun ApplicationCall.limit() = request.queryParameters["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..200 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad limit", errors = listOf(FieldError("query.limit", "out_of_range"))) } ?: 50
        fun ApplicationCall.cursor() = request.queryParameters["cursor"]?.let { it.toLongOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad cursor", errors = listOf(FieldError("query.cursor", "invalid_value"))) }
        fun ApplicationCall.q(name: String) = request.queryParameters[name]
        fun ApplicationCall.longQ(name: String) = q(name)?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad $name", errors = listOf(FieldError("query.$name", "invalid_value"))) }

        get("/admin/mdm/devices") {
            call.read()
            val search = (call.q("q") ?: call.q("search"))?.also { if (it.length !in 2..80) throw ApiProblem(ProblemCode.ERR_VALIDATION, "search must be 2..80 characters", errors = listOf(FieldError("query.search", "out_of_range"))) }
            call.respond(s.listDevices(call.q("status"), call.q("flavour"), call.q("flag"), call.longQ("policy_id"), search, call.limit(), call.cursor()))
        }
        get("/admin/mdm/devices/{device_id}") { call.read(); call.respond(s.getDevice(call.id("device_id"))) }
        get("/admin/mdm/devices/{device_id}/activity") { call.read(); call.respond(s.activity(call.id("device_id"), call.limit(), call.cursor())) }
        get("/admin/mdm/activity") { call.read(); call.respond(s.activity(null, call.limit(), call.cursor())) }
        get("/admin/mdm/alerts") { call.read(); call.respond(s.alerts(call.q("flag"), call.limit(), call.cursor())) }

        get("/admin/mdm/devices/{device_id}/commands") { call.read(); call.respond(s.listCommands(call.id("device_id"), call.limit(), call.cursor())) }
        post("/admin/mdm/devices/{device_id}/commands") {
            call.write()
            val (cmd, created) = s.createCommand(call.principal, call.id("device_id"), call.receiveStrict(MdmCommandCreateRequest.serializer()), call.requestId)
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, cmd)
        }
        post("/admin/mdm/commands/{command_id}/decision") {
            call.write()
            call.respond(s.decide(call.principal, call.parameters["command_id"].orEmpty(), call.receiveStrict(MdmCommandDecisionRequest.serializer()), call.requestId))
        }
        put("/admin/mdm/devices/{device_id}/policy") { call.write(); call.respond(s.assignPolicy(call.principal, call.id("device_id"), call.receiveStrict(MdmPolicyAssignRequest.serializer()), call.requestId)) }

        get("/admin/mdm/policies") { call.read(); call.respond(s.listPolicies(call.limit(), call.cursor())) }
        post("/admin/mdm/policies") { call.write(); call.respond(HttpStatusCode.Created, s.createPolicy(call.principal, call.receiveStrict(MdmPolicyWrite.serializer()), call.requestId)) }
        get("/admin/mdm/policies/{policy_id}") { call.read(); call.respond(s.getPolicy(call.id("policy_id"))) }
        put("/admin/mdm/policies/{policy_id}") { call.write(); call.respond(s.updatePolicy(call.principal, call.id("policy_id"), call.receiveStrict(MdmPolicyWrite.serializer()), call.requestId)) }

        get("/admin/mdm/enrolment-tokens") { call.read(); call.respond(s.listTokens(call.q("active_only") != "false", call.limit(), call.cursor())) }
        post("/admin/mdm/enrolment-tokens") { call.write(); call.respond(HttpStatusCode.Created, s.createToken(call.principal, call.receiveStrict(EnrolmentTokenCreateRequest.serializer()))) }
        post("/admin/mdm/enrolment-tokens/{token_id}/revoke") { call.write(); call.respond(s.revokeToken(call.principal, call.id("token_id"))) }
    }
}
