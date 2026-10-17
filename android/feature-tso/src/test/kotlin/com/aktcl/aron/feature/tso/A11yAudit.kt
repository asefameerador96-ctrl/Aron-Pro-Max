package com.aktcl.aron.feature.tso

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp

/**
 * Accessibility sweep over the semantics tree on screen (lane item 6, docs/32): every control that takes a click is at least
 * 48 dp in both directions, and has a name TalkBack can say (text or content description, merged); a switch, checkbox or
 * radio says its state. Returns the list of problems, empty when clean, so a test can assert with the screen's name.
 */
internal fun ComposeContentTestRule.a11yProblems(): List<String> {
    val problems = mutableListOf<String>()
    // a node that was never placed (zero size) is not on screen: nothing to touch or read
    val clickable = onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.size.width > 0 && it.size.height > 0 }
    with(density) {
        for (n in clickable) {
            val label = n.label()
            if (n.size.height.toDp() < 47.5.dp || n.size.width.toDp() < 47.5.dp) problems += "touch target ${n.size} too small: '$label'"
            if (label.isBlank()) problems += "clickable without a name at ${n.boundsInRoot} tag=${n.config.getOrNull(SemanticsProperties.TestTag)} role=${n.config.getOrNull(SemanticsProperties.Role)}"
            if (n.config.getOrNull(SemanticsProperties.Role)?.toString() in STATEFUL_ROLES &&
                n.config.getOrNull(SemanticsProperties.ToggleableState) == null && n.config.getOrNull(SemanticsProperties.Selected) == null
            ) problems += "'$label' is a toggle without a state"
        }
    }
    return problems
}

private val STATEFUL_ROLES = setOf("Switch", "Checkbox", "RadioButton")

/** The words TalkBack reads for a node: its own text and content description, else its children's (the merged tree already folds them in). */
private fun SemanticsNode.label(): String {
    val texts = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    val desc = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
    return listOf(texts, desc).filter { it.isNotBlank() }.joinToString(" ")
}

