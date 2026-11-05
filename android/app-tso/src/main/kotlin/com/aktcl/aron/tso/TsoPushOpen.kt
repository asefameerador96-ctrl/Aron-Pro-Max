package com.aktcl.aron.tso

import android.content.Intent
import com.aktcl.aron.core.sync.push.PushNotices
import com.aktcl.aron.feature.tso.TsoOpenRequest
import com.aktcl.aron.feature.tso.TsoScreen

/**
 * tso-manual X4b (R-W-12 (1)): what a tapped push notice opens in the TSO app, read from the open extra that
 * [PushNotices.openIntent] puts on the launch intent. [MainActivity] reads it in onCreate and onNewIntent, and TsoApp applies
 * the request once on the Dashboard, so back from the opened screen is the Dashboard.
 *
 * - a ticket notice (`ticket_updated`, extra [PushNotices.OPEN_TICKETS]) opens the Ticket Dashboard through A2's
 *   [TicketPush.open], with the reason as its argument (it then reads online even when its copy is fresh, D-A2-30);
 * - an inbox notice (`notification`, extra [PushNotices.OPEN_INBOX]) opens the inbox, where a ticket row opens that ticket
 *   (TICKET_DETAIL with its uuid, D-A1-28);
 * - anything else (a task, the day notice, an announcement, no extra) opens nothing.
 */
object TsoPushOpen {
    fun request(intent: Intent?): TsoOpenRequest? = request(PushNotices.opens(intent))

    fun request(extra: String?): TsoOpenRequest? = when {
        extra == PushNotices.OPEN_INBOX -> TsoOpenRequest.of(TsoScreen.NOTIFICATIONS)
        TicketPush.target(extra) != null -> TsoOpenRequest { nav -> TicketPush.open(nav, extra) }
        else -> null
    }
}
