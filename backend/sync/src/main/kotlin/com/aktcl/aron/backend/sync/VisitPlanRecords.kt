package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RecordRefusal
import com.aktcl.aron.contract.RecordOutcomeCode
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jdbi.v3.core.Handle
import java.time.LocalDate

/**
 * The ingest side of Set Plan (tso-manual B3, SP-03, D-TSO-MAN-35; screenshots 31 to 33): the `visit_plan` and
 * `visit_plan_outlet` records of the TSO's My Call. The generic writer stores the envelope's `route_id` on the plan
 * (`app.visit_plan.route_id`, V0137: the route chosen in Set Plan), and ingest's scope step already holds the route and
 * the outlet to the TSO's reach. These checks run after it, in this order:
 * - the uploader is a TSO (another role's plan is quarantined `scope_out_of_reach`, the catalogue's class for a record
 *   outside the uploader's part);
 * - `visit_plan`: the plan date is on or after the day the TSO set it (the record's business date); an earlier one is
 *   held `business_date_out_of_window` for review (the phone never offers it: Set Plan starts at today);
 * - `visit_plan_outlet`: the outlet is on the plan's route, the envelope's `route_id` or, without one (an app before
 *   V0137), the stored plan's; another route's outlet is held `scope_out_of_reach` (it moved routes since the bundle, or
 *   the record is wrong). Without either route there is nothing to compare and the row is stored as before.
 * A replay of a stored row is a duplicate before any check (the registry answers first).
 */
class VisitPlanRecords : RecordHandler {
    override val types = setOf("visit_plan", "visit_plan_outlet")

    override fun check(h: Handle, rec: IngestRecord): RecordRefusal? {
        if (rec.role != Role.TSO) return RecordRefusal(RecordOutcomeCode.SCOPE_OUT_OF_REACH, "${rec.type} is the TSO's (My Call), not a ${rec.role.wire}'s")
        return when (rec.type) {
            "visit_plan" -> {
                val planDate = rec.payload.str("plan_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
                if (planDate.isBefore(rec.businessDate)) RecordRefusal(RecordOutcomeCode.BUSINESS_DATE_OUT_OF_WINDOW, "plan_date $planDate is before the day it was set (${rec.businessDate})")
                else null
            }
            else -> {
                val outlet = (rec.payload["outlet_id"] as? JsonPrimitive)?.longOrNull ?: return null
                val route = (rec.envelope["route_id"] as? JsonPrimitive)?.longOrNull ?: rec.payload.str("plan_client_uuid")?.let { plan ->
                    h.createQuery("SELECT route_id FROM app.visit_plan WHERE client_uuid = CAST(:p AS uuid)").bind("p", plan).mapTo(Long::class.javaObjectType).findOne().orElse(null)
                } ?: return null
                val outletRoute = h.createQuery("SELECT route_id FROM app.outlet WHERE id = :o").bind("o", outlet).mapTo(Long::class.javaObjectType).findOne().orElse(null)
                if (outletRoute == route) null else RecordRefusal(RecordOutcomeCode.SCOPE_OUT_OF_REACH, "outlet $outlet is on route $outletRoute, not on the plan's route $route")
            }
        }
    }
}
