package com.aktcl.aron.tso

import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.TsoScreenContent
import com.aktcl.aron.feature.tso.feedback.FeedbackListEntry
import com.aktcl.aron.feature.tso.feedback.FeedbackNewEntry
import com.aktcl.aron.feature.tso.feedback.FeedbackPhotos
import com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitFlow
import com.aktcl.aron.feature.tso.leave.LeaveFlow
import com.aktcl.aron.feature.tso.mycall.MyCallDeps
import com.aktcl.aron.feature.tso.mycall.SetPlanEntry
import com.aktcl.aron.feature.tso.mycall.VisitPlanEntry
import com.aktcl.aron.feature.tso.periphery.MyTeamEntry
import com.aktcl.aron.feature.tso.periphery.PeripheryDeps
import com.aktcl.aron.feature.tso.periphery.RetailerEntry

/**
 * The area screens' host ports (lane A3 owns this file after a1-stubs; build contract s5 A3): Leave, Final Submit, My
 * Periphery, My Call and My Feedback. Built once per signed-in user (MainActivity remembers it by user id); each port is
 * created on first use and then kept, as the TsoApp parameters were before a1-stubs.
 */
class AreasHostDeps(val host: TsoHost) {
    private val reads get() = host.readModels
    val leave by lazy { reads.leave(host.userId) }
    val leaveQueue by lazy { reads.leaveQueue(host.userId) }
    val feedback by lazy { reads.feedback(host.userId) }
    val feedbackQueue by lazy { reads.feedbackQueue(host.userId) { host.mediaFor() } }
    val finalSubmit by lazy { reads.finalSubmit(host.userId, reads.plan(host.userId)) }
    val periphery by lazy {
        PeripheryDeps(reads.periphery(host.userId, reads.plan(host.userId)), reads.fix(host.context), online = host.online, nowMs = host.nowMs, asOfNow = { reads.asOfClock() })
    }
    val myCall by lazy {
        val src = reads.plan(host.userId)
        MyCallDeps(
            src, reads.planQueue(host.userId, src), reads.queryQueue(host.userId), reads.taskQueue(host.userId),
            { reads.rubric(host.userId) }, { reads.taskTypes(host.userId) },
        )
    }

    /** The feedback photo port: null until this user's media components are ready (reads compose state, so the row appears then). */
    fun feedbackPhotos(): FeedbackPhotos? = host.media()?.let { reads.feedbackPhotos(it, host.context.contentResolver) }
}

/**
 * The area screens by destination (tso-manual A3 step 2, D-TSO-MAN-14/34/35/36): one entry per drawer row, so the drawer
 * opens My Team, Retailer, My Visit Plan, Set Plan, Add new feedback and My feedback directly (the hubs of the former tile
 * Home are gone from the app). Each entry keeps its own sub-steps (Select Outlets; Visit Plan Outlets, Visit Query, Assign
 * Task) with the system back and the on-screen back going to the same place (CR-4).
 */
fun areaScreens(deps: AreasHostDeps): Map<TsoScreen, TsoScreenContent> = mapOf<TsoScreen, TsoScreenContent>(
    TsoScreen.LEAVE to { nav, _ -> LeaveFlow(deps.leave, deps.leaveQueue, deps.host.userKey, deps.host.today, onBack = { nav.back() }) },
    TsoScreen.FINAL_SUBMIT to { nav, _ -> FinalSubmitFlow(deps.finalSubmit, deps.host.userKey, deps.host.userId, deps.host.today, onBack = { nav.back() }) },
    TsoScreen.MY_TEAM to { nav, _ -> MyTeamEntry(deps.periphery, deps.host.userKey, deps.host.today, nav) },
    TsoScreen.RETAILER to { nav, _ -> RetailerEntry(deps.periphery, deps.host.userKey, nav) },
    TsoScreen.MY_VISIT_PLAN to { nav, _ -> VisitPlanEntry(deps.myCall, deps.host.userKey, deps.host.today, nav) },
    TsoScreen.SET_PLAN to { nav, _ -> SetPlanEntry(deps.myCall, deps.host.userKey, deps.host.today, nav) },
    TsoScreen.FEEDBACK_NEW to { nav, _ -> FeedbackNewEntry(deps.feedback, deps.feedbackQueue, deps.feedbackPhotos(), deps.host.userKey, deps.host.nowIso, nav) },
    TsoScreen.FEEDBACK_LIST to { nav, _ -> FeedbackListEntry(deps.feedback, canWrite = true, deps.host.userKey, nav) },
)
