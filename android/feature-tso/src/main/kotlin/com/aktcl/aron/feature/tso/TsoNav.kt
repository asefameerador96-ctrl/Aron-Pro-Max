package com.aktcl.aron.feature.tso

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/**
 * Every destination of the TSO app (build contract s5 A1, D-TSO-MAN-14). The order is fixed by the contract; lanes add
 * screens through the host maps (A1 dashboard, settings, targets and inbox; A2 tickets; A3 areas), never a second enum.
 */
enum class TsoScreen {
    DASHBOARD, LOGIN_SUBMIT_STATUS, NOTIFICATIONS, SETTINGS, CHANGE_PASSWORD, SUPPORT_UPLOAD,
    TICKET_DASHBOARD, TICKET_LIST, TICKET_QUICK, TICKET_CREATE, TICKET_DETAIL, LEAVE, FINAL_SUBMIT, MY_TEAM, RETAILER,
    MY_VISIT_PLAN, SET_PLAN, TARGET_STATUS, TARGET_DETAILS, FEEDBACK_NEW, FEEDBACK_LIST,
}

/**
 * How a TSO screen moves: [open] pushes a destination (with an optional plain-string argument such as a ticket uuid or a
 * node id; never a scope list), [back] pops one, [home] returns to the root (the Dashboard, D-TSO-MAN-14). The system back
 * and the top-bar back button both call [back]; the Home button calls [home].
 */
interface TsoNav {
    fun open(screen: TsoScreen, arg: String? = null)
    fun back()
    fun home()
}

/**
 * What a tapped push notice asks the TSO app to open (tso-manual X4b, R-W-12 (1)). The app module builds it from the
 * notice's open extra (the inbox, or the Ticket Dashboard through A2's `TicketPush.open`); [TsoApp] applies it once on the
 * Dashboard (the stack is emptied first), so back from the opened screen returns to the Dashboard.
 */
fun interface TsoOpenRequest {
    fun open(nav: TsoNav)

    companion object {
        /** A plain screen without an argument (the inbox). */
        fun of(screen: TsoScreen): TsoOpenRequest = TsoOpenRequest { it.open(screen) }
    }
}

/** One screen of a host map: drawn with the navigator and the argument it was opened with. */
typealias TsoScreenContent = @Composable (nav: TsoNav, arg: String?) -> Unit

/** One entry of the back stack. */
@Immutable
data class TsoRoute(val screen: TsoScreen, val arg: String? = null)

/**
 * The saved back stack behind [TsoNav]. An empty stack is the root, the Dashboard (D-TSO-MAN-14). A drawer row opens its
 * screen on the root ([openFromDrawer]: the stack becomes that one screen), so back from any drawer screen is the Dashboard.
 * Opening the screen already on top is a no-op, so a double tap never stacks the same screen twice.
 */
internal class TsoNavState(initial: List<TsoRoute> = emptyList()) : TsoNav {
    var stack: List<TsoRoute> by mutableStateOf(initial)
        private set

    val top: TsoRoute? get() = stack.lastOrNull()

    override fun open(screen: TsoScreen, arg: String?) {
        val route = TsoRoute(screen, arg)
        if (stack.lastOrNull() != route) stack = stack + route
    }

    override fun back() { if (stack.isNotEmpty()) stack = stack.dropLast(1) }

    override fun home() { stack = emptyList() }

    /** A drawer row: [screen] on the Dashboard (the Dashboard itself empties the stack). */
    fun openFromDrawer(screen: TsoScreen) {
        stack = if (screen == TsoScreen.DASHBOARD) emptyList() else listOf(TsoRoute(screen))
    }

    companion object {
        private const val SEP = '\u0001'

        /** Survives recreation and process death as plain strings (`NAME` or `NAME<SEP>arg`). */
        val Saver: Saver<TsoNavState, ArrayList<String>> = Saver(
            save = { s -> ArrayList(s.stack.map { r -> if (r.arg == null) r.screen.name else r.screen.name + SEP + r.arg }) },
            restore = { saved ->
                TsoNavState(
                    saved.mapNotNull { e ->
                        val name = e.substringBefore(SEP)
                        val screen = TsoScreen.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                        TsoRoute(screen, if (SEP in e) e.substringAfter(SEP) else null)
                    },
                )
            },
        )
    }
}
