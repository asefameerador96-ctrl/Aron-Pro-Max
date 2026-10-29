package com.aktcl.aron.tso

import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.TsoScreenContent

/**
 * The Tickets screens' host ports (lane A2 owns this file after a1-stubs; build contract s5 A2). [host] carries every
 * app-level handle a ticket port needs (API client, outbox, media, trusted time, connectivity), so A2 builds its ports
 * here without touching MainActivity. Built once per signed-in user.
 */
class TicketsHostDeps(val host: TsoHost)

/** TICKET_DASHBOARD, TICKET_LIST, TICKET_QUICK, TICKET_CREATE and TICKET_DETAIL once A2 lands; empty until then (the placeholder shows). */
fun ticketScreens(deps: TicketsHostDeps): Map<TsoScreen, TsoScreenContent> = emptyMap()
