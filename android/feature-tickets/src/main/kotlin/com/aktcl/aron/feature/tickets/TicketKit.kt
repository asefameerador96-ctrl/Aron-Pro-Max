package com.aktcl.aron.feature.tickets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.common.LocaleDigits
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronLookColors
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronScaffold
import com.aktcl.aron.core.ui.aron.AronTopBar
import com.aktcl.aron.rules.TicketMath
import com.aktcl.aron.rules.UiLocale
import java.time.Instant
import java.time.ZoneId
import com.aktcl.aron.core.ui.R as CoreR

/**
 * The frame a host draws around a ticket screen: its top bar with [title] and back, and its page. The TSO host passes
 * its TsoFrame (screenshots 11 to 17: grey round back button, gradient title); [plainTicketFrame] is the kit's.
 */
typealias TicketFrame = @Composable (title: String, content: @Composable () -> Unit) -> Unit

/** The kit frame: the existing-Aron gradient top bar with the back chevron. */
fun plainTicketFrame(onBack: () -> Unit): TicketFrame = { title, content ->
    AronScaffold(topBar = { AronTopBar(title, onBack = onBack, backLabel = stringResource(CoreR.string.core_ui_back)) }) { content() }
}

/**
 * The ticket screens' colours. D-A2-09: a night look (the TSO look, D-TSO-MAN-13: page luminance under 0.18) gets
 * [Night], sampled from screenshots 11 to 17; any other look (SR and AMO later, and Sunlight, which overrides every
 * look) is derived from its tokens, so Sunlight keeps black ink on white. A host may also provide [LocalTicketPalette].
 */
@Immutable
data class TicketPalette(
    val night: Boolean,
    val page: Color, val card: Color, val cardBorder: Color, val stat: Color, val raised: Color, val panel: Color,
    val ink: Color, val inkMuted: Color, val inkFaint: Color,
    val chip: Color, val chipInk: Color,
    val accent: Color, val onAccent: Color, val accentInk: Color, val figure: Color,
    val success: Color, val warning: Color, val danger: Color,
    val track: Color, val trackFill: Color,
    val field: Color, val fieldBorder: Color,
    val sheet: Color, val sheetField: Color, val sheetFieldBorder: Color, val scrim: Color,
    val bubble: Color, val bubbleBorder: Color, val bubbleMine: Color, val supportAvatar: Color, val supportIcon: Color,
    val helpStart: Color, val helpEnd: Color, val onHelp: Color,
) {
    companion object {
        private fun c(v: Long) = Color(v)

        val Night = TicketPalette(
            night = true,
            page = c(0xFF03103A), card = c(0xFF0A183F), cardBorder = c(0xFF17244B), stat = c(0xFF111B45), raised = c(0xFF08163D), panel = c(0xFF0E1732),
            ink = c(0xFFFFFFFF), inkMuted = c(0xFFA3ABBD), inkFaint = c(0xFF8790A6),
            chip = c(0xFF293250), chipInk = c(0xFFAEB5C6),
            accent = c(0xFF2E5BFE), onAccent = c(0xFFFFFFFF), accentInk = c(0xFF4D8DFC), figure = c(0xFF58A6FF),
            success = c(0xFF34D399), warning = c(0xFFF5B969), danger = c(0xFFFF7A7A),
            track = c(0xFF252C46), trackFill = c(0xFF5672E1),
            field = c(0xFF0F1D44), fieldBorder = c(0xFF22305C),
            sheet = c(0xFF1F1E2C), sheetField = c(0xFF2A2A36), sheetFieldBorder = c(0xFF3A3946), scrim = c(0xB3000000),
            bubble = c(0xFF1E1D2D), bubbleBorder = c(0xFF7A5A3A), bubbleMine = c(0xFF16285E), supportAvatar = c(0xFF3A2E33), supportIcon = c(0xFFF0A050),
            helpStart = c(0xFF7B4FF5), helpEnd = c(0xFF9D7BF7), onHelp = c(0xFFFFFFFF),
        )

        /** Any day look (the SR look, Sunlight): its own tokens. */
        fun of(look: AronLookColors): TicketPalette = TicketPalette(
            night = false,
            page = look.page, card = look.card, cardBorder = if (look.sunlight) look.underline else look.tileHairline, stat = look.card, raised = look.card, panel = look.card,
            ink = look.ink, inkMuted = look.inkMuted, inkFaint = look.inkMuted,
            chip = look.track, chipInk = look.ink,
            accent = look.purple, onAccent = look.onPanel, accentInk = look.purple, figure = look.purple,
            success = look.goStart, warning = look.amber, danger = look.red,
            track = look.track, trackFill = look.purple,
            field = look.card, fieldBorder = look.underline,
            sheet = look.card, sheetField = look.card, sheetFieldBorder = look.underline, scrim = c(0x99000000),
            bubble = look.rowAlt, bubbleBorder = look.amber, bubbleMine = look.tileMenu, supportAvatar = look.rowAlt, supportIcon = look.amber,
            helpStart = look.barStart, helpEnd = look.barEnd, onHelp = look.onBar,
        )

        fun from(look: AronLookColors): TicketPalette = if (!look.sunlight && look.page.luminance() < NIGHT_LUMINANCE) Night else of(look)

        private const val NIGHT_LUMINANCE = 0.18f
    }
}

/** A host's own palette for the ticket screens; null takes [TicketPalette.from] the look in force. */
val LocalTicketPalette = compositionLocalOf<TicketPalette?> { null }

@Composable
fun ticketPalette(): TicketPalette = LocalTicketPalette.current ?: TicketPalette.from(LocalAronLook.current)

/** The eight issue icons (V0131 attrs.icon 0..7, screenshot 15) and their hues. */
internal object TicketHues {
    val hues = listOf(0xFFE0457E, 0xFFC98A4B, 0xFFD0455A, 0xFF3D9BEA, 0xFFB45CF0, 0xFF3CC19A, 0xFF7B6CF0, 0xFF5BE0C8).map { Color(it) }
    fun of(icon: Int): Color = hues[Math.floorMod(icon, hues.size)]
    val total = Color(0xFFC07BFF)
    val new = Color(0xFF6FA8FF)
    val progress = Color(0xFFF5B969)
    val resolved = Color(0xFF6EE7D8)
}

/**
 * Glyphs the core kit has no counterpart for (Material Design path data, Apache 2.0, or simple shapes). Decorative: the
 * label beside each carries the meaning. D-A2-10: core-ui is lane A1's; these live here until the kit has them.
 */
internal object TicketGlyphs {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) b.addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
        return b.build()
    }

    val Key by lazy { icon("key", "M12.65 10C11.83 7.67 9.61 6 7 6c-3.31 0-6 2.69-6 6s2.69 6 6 6c2.61 0 4.83-1.67 5.65-4H17v4h4v-4h2v-4H12.65zM7 14c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z") }
    val Fingerprint by lazy {
        icon(
            "fingerprint",
            "M12 3a9 9 0 0 1 9 9h-2a7 7 0 0 0-14 0H3a9 9 0 0 1 9-9z",
            "M12 7a5 5 0 0 1 5 5v3h-2v-3a3 3 0 0 0-6 0v6H7v-6a5 5 0 0 1 5-5z",
            "M11 11h2v10h-2z",
            "M16 17h2v3h-2z",
        )
    }
    val ScreenOff by lazy {
        icon(
            "screen_off",
            "M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-6v2h3v2H7v-2h3v-2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zm0 2v10h16V6H4z",
            "M3.4 2L22 20.6 20.6 22 2 3.4z",
        )
    }
    val Navigation by lazy { icon("navigation", "M21 3L3 10.53v.98l6.84 2.65L12.48 21h.98L21 3z") }
    val Inbox by lazy { icon("inbox", "M19 3H4.99c-1.11 0-1.98.89-1.98 2L3 19c0 1.1.88 2 1.99 2H19c1.1 0 2-.9 2-2V5c0-1.11-.9-2-2-2zm0 12h-4c0 1.66-1.35 3-3 3s-3-1.34-3-3H4.99V5H19v10z") }
    val Alert by lazy { icon("error_outline", "M11 15h2v2h-2zm0-8h2v6h-2zm.99-5C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z") }
    val CheckOutline by lazy { icon("check_circle_outline", "M16.59 7.58L10 14.17l-3.59-3.58L5 12l5 5 8-8zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z") }
    val Bolt by lazy { icon("bolt", "M7 2v11h3v9l7-12h-4l4-8z") }
    val Gallery by lazy { icon("photo_library", "M22 16V4c0-1.1-.9-2-2-2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2zm-11-4l2.03 2.71L16 11l4 5H8l3-4zM2 6v14c0 1.1.9 2 2 2h14v-2H4V6H2z") }
    val AddPhoto by lazy { icon("add_photo", "M19 2h2v3h3v2h-3v3h-2V7h-3V5h3z", "M17 11v8H5V7h8V5H5c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2v-8h-2z", "M7 17l3-4 2 3 2-2 3 3z") }
    val Headset by lazy { icon("headset_mic", "M12 1c-4.97 0-9 4.03-9 9v7c0 1.66 1.34 3 3 3h3v-8H5v-2c0-3.87 3.13-7 7-7s7 3.13 7 7v2h-4v8h4v1h-7v2h6c1.66 0 3-1.34 3-3V10c0-4.97-4.03-9-9-9z") }
    val ExpandMore by lazy { icon("expand_more", "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z") }
    val Send by lazy { icon("send", "M2.01 21L23 12 2.01 3 2 10l15 2-15 2z") }

    /** The issue tile icon of attrs.icon: key, fingerprint, screen_off, sync, navigation, receipt, cart, battery (s2.2). */
    fun issue(icon: Int): ImageVector = when (Math.floorMod(icon, 8)) {
        0 -> Key
        1 -> Fingerprint
        2 -> ScreenOff
        3 -> AronIcons.Sync
        4 -> Navigation
        5 -> AronIcons.Memo
        6 -> AronIcons.Sale
        else -> AronIcons.Battery
    }
}

/** Test tags of the ticket screens. */
object TicketTags {
    const val DASHBOARD = "tickets_dashboard"
    const val LIST = "tickets_list"
    const val SEARCH = "tickets_search"
    const val OFFLINE = "tickets_offline"
    const val EMPTY = "tickets_empty"
    const val HELP = "tickets_help"
    const val SEE_ALL = "tickets_see_all"
    const val RESOLVED_PCT = "tickets_resolved_pct"
    const val OPEN_PCT = "tickets_open_pct"
    const val CLOSE = "tickets_close"
    const val CLOSING = "tickets_closing"
    const val COMPOSER = "tickets_composer"
    const val COMMENT = "tickets_comment"
    const val SEND = "tickets_send"
    const val NO_COMMENTS = "tickets_no_comments"
    const val NOT_FOUND = "tickets_not_found"
    const val SHEET = "tickets_sheet"
    const val SHEET_CLOSE = "tickets_sheet_close"
    const val DESCRIPTION = "tickets_description"
    const val TITLE = "tickets_title"
    const val AFFECTED = "tickets_affected"
    const val ISSUE_FIELD = "tickets_issue_field"
    const val ISSUE_LIST = "tickets_issue_list"
    const val GALLERY = "tickets_gallery"
    const val CAMERA = "tickets_camera"
    const val UPLOAD = "tickets_upload"
    const val SUBMIT = "tickets_submit"
    const val ERRORS = "tickets_errors"
    const val REPORT_DIFFERENT = "tickets_report_different"
    const val NO_ISSUE_TYPES = "tickets_no_issue_types"
    fun stat(i: Int) = "tickets_stat_$i"
    fun tab(t: TicketTab) = "tickets_tab_${t.name.lowercase()}"
    fun card(uuid: String) = "tickets_card_$uuid"
    fun line(uuid: String) = "tickets_line_$uuid"
    fun refused(uuid: String) = "tickets_refused_$uuid"
    fun tile(code: String) = "tickets_tile_$code"
    fun issue(code: String) = "tickets_issue_$code"
    fun event(uuid: String) = "tickets_event_$uuid"
    fun photo(uuid: String) = "tickets_photo_$uuid"
}

// ---- text helpers

internal val AppLanguage.ui: UiLocale get() = if (this == AppLanguage.BN) UiLocale.BN else UiLocale.EN

/** Times on the ticket screens: Asia/Dhaka, 12-hour clock ("5:34 PM"); a day other than today carries its date first. */
object TicketTime {
    private val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")

    fun text(ms: Long, nowMs: Long, language: AppLanguage, am: String, pm: String): String {
        val t = Instant.ofEpochMilli(ms).atZone(DHAKA)
        val today = Instant.ofEpochMilli(nowMs).atZone(DHAKA).toLocalDate()
        val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        val clock = "$h12:${t.minute.toString().padStart(2, '0')} ${if (t.hour < 12) am else pm}"
        val shown = if (t.toLocalDate() == today) clock else "${t.toLocalDate()} $clock"
        return LocaleDigits.localize(shown, language)
    }
}

@Composable
internal fun timeText(ms: Long, nowMs: Long): String =
    TicketTime.text(ms, nowMs, LocalAppLanguage.current, stringResource(R.string.tickets_time_am), stringResource(R.string.tickets_time_pm))

@Composable
internal fun ageText(nowMs: Long, capturedMs: Long): String = TicketMath.ageText(nowMs, capturedMs, LocalAppLanguage.current.ui)

@Composable
internal fun statusText(status: String): String = when (TicketStatus.of(status)) {
    TicketStatus.NEW -> stringResource(R.string.tickets_status_new)
    TicketStatus.IN_PROGRESS -> stringResource(R.string.tickets_status_in_progress)
    TicketStatus.RESOLVED -> stringResource(R.string.tickets_status_resolved)
    TicketStatus.CLOSED -> stringResource(R.string.tickets_status_closed)
    TicketStatus.CANCELLED -> stringResource(R.string.tickets_status_cancelled)
    null -> status.uppercase()
}

@Composable
internal fun priorityText(priority: String): String = when (TicketPriority.of(priority)) {
    TicketPriority.P1 -> stringResource(R.string.tickets_priority_p1)
    TicketPriority.P2 -> stringResource(R.string.tickets_priority_p2)
    TicketPriority.P3 -> stringResource(R.string.tickets_priority_p3)
    TicketPriority.P4 -> stringResource(R.string.tickets_priority_p4)
    null -> priority.uppercase()
}

/** A server refusal's reason (D-TSO-MAN-21, s5 A2: "a server refusal shows its reason"). */
@Composable
internal fun refusalText(code: String): String = stringResource(
    R.string.tickets_refused,
    when (code) {
        "ticket_state_invalid" -> stringResource(R.string.tickets_why_state)
        "issue_type_unknown" -> stringResource(R.string.tickets_why_issue_type)
        "scope_out_of_reach" -> stringResource(R.string.tickets_why_scope)
        else -> stringResource(R.string.tickets_why_other, code)
    },
)

/** The first line of a ticket card: "INC010700 · Login OTP issue", or "Not sent yet · …" for a ticket still on the phone. */
@Composable
internal fun ticketLine(t: Ticket): String {
    val head = t.number ?: when (t.delivery) {
        Delivery.Pending -> stringResource(R.string.tickets_not_sent)
        Delivery.Sent -> stringResource(R.string.tickets_number_follows)
        is Delivery.Refused -> stringResource(R.string.tickets_not_sent)
    }
    return stringResource(R.string.tickets_line, head, t.issueLabel(LocalAppLanguage.current))
}

@Composable
internal fun digits(n: Long): String = com.aktcl.aron.rules.Formats.integer(n, LocalAppLanguage.current.ui)

// ---- small parts

@Composable
internal fun TicketPill(text: String, modifier: Modifier = Modifier, fill: Color = ticketPalette().chip, ink: Color = ticketPalette().chipInk) {
    Box(modifier.clip(RoundedCornerShape(50)).background(fill).padding(horizontal = 10.dp, vertical = 4.dp)) {
        // two lines at most: a long Bangla status at 200 % text ("সমাধান হয়েছে") wraps instead of losing its second word
        Text(text, style = MaterialTheme.typography.labelMedium, color = ink, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A card: rounded, the palette's card and hairline; the whole card one button when [onClick] is set. */
@Composable
internal fun TicketSurface(
    modifier: Modifier = Modifier,
    fill: Color = ticketPalette().card,
    border: Color = ticketPalette().cardBorder,
    radius: Int = 14,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius.dp)
    Column(
        modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else it },
        content = content,
    )
}

/** One ticket row of the dashboard and the list (screenshots 11 to 13). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TicketCard(t: Ticket, nowMs: Long, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = ticketPalette()
    val line = ticketLine(t)
    TicketSurface(
        modifier.fillMaxWidth().testTag(TicketTags.card(t.uuid)),
        onClick = { onOpen(t.uuid) },
        onClickLabel = stringResource(R.string.tickets_open_ticket, t.number ?: t.title),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    line, Modifier.testTag(TicketTags.line(t.uuid)), style = MaterialTheme.typography.bodySmall,
                    color = if (t.delivery == Delivery.Pending) p.warning else p.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(t.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // a flow, so the two pills wrap instead of clipping at large text
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TicketPill(priorityText(t.priority))
                    TicketPill(statusText(t.status))
                }
                (t.delivery as? Delivery.Refused)?.let { r ->
                    Text(refusalText(r.code), Modifier.testTag(TicketTags.refused(t.uuid)), style = MaterialTheme.typography.bodySmall, color = p.danger)
                }
            }
            Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Icon(AronIcons.ChevronRight, contentDescription = null, tint = p.inkMuted, modifier = Modifier.size(22.dp))
                Text(ageText(nowMs, t.capturedAtMs), style = MaterialTheme.typography.labelSmall, color = p.inkFaint, maxLines = 1)
            }
        }
    }
}

/** A one-line note under a header: offline copy, refusal, empty state. */
@Composable
internal fun TicketNote(text: String, modifier: Modifier = Modifier, color: Color = ticketPalette().inkMuted) {
    Text(text, modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium, color = color)
}

/** A field with the palette's fill and outline; the label (when given) sits above it, never in the outline notch. */
@Composable
internal fun TicketField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    isError: Boolean = false,
    fill: Color = ticketPalette().field,
    border: Color = ticketPalette().fieldBorder,
    leading: ImageVector? = null,
) {
    val p = ticketPalette()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = p.ink)
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth().semantics { contentDescription = label ?: placeholder },
            enabled = enabled, singleLine = singleLine, minLines = minLines, isError = isError, shape = RoundedCornerShape(12.dp),
            placeholder = { Text(placeholder, color = p.inkFaint) },
            leadingIcon = if (leading == null) null else { { Icon(leading, contentDescription = null, tint = p.inkMuted) } },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = fill, unfocusedContainerColor = fill, disabledContainerColor = fill, errorContainerColor = fill,
                focusedTextColor = p.ink, unfocusedTextColor = p.ink, disabledTextColor = p.inkMuted, errorTextColor = p.ink,
                focusedBorderColor = p.accentInk, unfocusedBorderColor = border, disabledBorderColor = border, errorBorderColor = p.danger,
                cursorColor = p.accentInk,
            ),
        )
    }
}

/** The full-width accent button (Submit Ticket, screenshots 16 and 17). A busy or disabled button is not pressable. */
@Composable
internal fun TicketPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = ticketPalette()
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) p.accent else p.chip)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (enabled) p.onAccent else p.inkMuted)
    }
}

/** A row action with an icon (Pick from Gallery / Capture photo, screenshot 16). */
@Composable
internal fun TicketActionRow(icon: ImageVector, tint: Color, text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = ticketPalette()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else p.inkFaint, modifier = Modifier.size(32.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) p.ink else p.inkFaint)
    }
}

/** The staged photos of a form, each with its remove button. */
@Composable
internal fun PhotoStrip(ids: List<String>, photos: TicketPhotos?, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    if (ids.isEmpty() || photos == null) return
    val p = ticketPalette()
    val language = LocalAppLanguage.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ids.forEachIndexed { i, id ->
            Box(Modifier.size(72.dp).testTag(TicketTags.photo(id))) {
                photos.Thumbnail(id, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).border(1.dp, p.cardBorder, RoundedCornerShape(10.dp)))
                val label = stringResource(R.string.tickets_photo_remove, LocaleDigits.localize((i + 1).toString(), language))
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = label) { onRemove(id) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(Modifier.padding(2.dp).size(22.dp).clip(CircleShape).background(p.scrim), contentAlignment = Alignment.Center) {
                        Icon(AronIcons.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** Gallery or camera, from one tap on an upload box or the composer's clip (screenshot 17: "Tap to browse or take a photo"). */
@Composable
internal fun PhotoChoiceMenu(expanded: Boolean, onDismiss: () -> Unit, onGallery: () -> Unit, onCamera: () -> Unit) {
    val p = ticketPalette()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = p.sheet) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.tickets_pick_gallery), color = p.ink) },
            leadingIcon = { Icon(TicketGlyphs.Gallery, contentDescription = null, tint = TicketHues.new) },
            onClick = { onDismiss(); onGallery() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.tickets_capture_photo), color = p.ink) },
            leadingIcon = { Icon(AronIcons.Camera, contentDescription = null, tint = TicketHues.resolved) },
            onClick = { onDismiss(); onCamera() },
        )
    }
}

/**
 * A bottom sheet drawn inside the screen (screenshot 16): a scrim over the page, the sheet with rounded top corners, system
 * back and a scrim tap close it. In-layout (not a dialog window), so the screen's goldens capture it.
 */
@Composable
internal fun BoxScope.TicketSheet(open: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (!open) return
    val p = ticketPalette()
    BackHandler(onBack = onDismiss)
    val none = remember { MutableInteractionSource() }
    val close = stringResource(R.string.tickets_close_sheet)
    Box(Modifier.matchParentSize().background(p.scrim).clickable(interactionSource = none, indication = null, onClickLabel = close, onClick = onDismiss))
    Column(
        modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(p.sheet)
            // the sheet swallows taps, so a tap inside never reaches the scrim
            .pointerInput(Unit) { detectTapGestures { } }
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** The page fill behind a ticket screen's content (the host's frame paints its own page, which may differ). */
@Composable
internal fun TicketPage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(ticketPalette().page), content = content)
}

/** A whole-number bar (the Ticket Resolution Status bars): [pct] 0..100 of the width, from the integer percent. */
@Composable
internal fun TicketBar(pct: Long, modifier: Modifier = Modifier) {
    val p = ticketPalette()
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(p.track)) {
        // 0 % draws no fill; any other value at least the bar's round end (screenshot 11: 2 % is a dot)
        val f = (pct.coerceIn(0L, 100L).toInt()) / 100f
        if (f > 0f) Box(Modifier.fillMaxWidth(f).widthIn(min = 8.dp).fillMaxHeight().clip(RoundedCornerShape(50)).background(p.trackFill))
    }
}

internal fun hueFill(page: Color, hue: Color, fraction: Float): Color = lerp(page, hue, fraction)

internal fun helpBrush(p: TicketPalette): Brush = Brush.horizontalGradient(listOf(p.helpStart, p.helpEnd))
