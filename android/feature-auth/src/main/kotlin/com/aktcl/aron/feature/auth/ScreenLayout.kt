package com.aktcl.aron.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * The content of a screen over its own action bar: the bar is a LAYOUT SLOT below the content (android-glass s6.7,
 * never an overlay: rows never scroll under it). Under a bounded parent (the `AronScreen` content slot) the content
 * takes the rest of the height and scrolls inside it; under an unbounded parent (a host that still scrolls) the
 * content takes its full height and the bar follows it, so the screen never collapses to its bar.
 */
@Composable
internal fun ScreenWithBar(modifier: Modifier = Modifier, bar: @Composable () -> Unit, content: @Composable () -> Unit) {
    Layout({ Box { content() }; Box { bar() } }, modifier) { measurables, constraints ->
        val barPlaceable = measurables[1].measure(constraints.copy(minHeight = 0))
        val contentMax = if (constraints.hasBoundedHeight) (constraints.maxHeight - barPlaceable.height).coerceAtLeast(0) else Constraints.Infinity
        val contentPlaceable = measurables[0].measure(constraints.copy(minHeight = 0, maxHeight = contentMax))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else maxOf(contentPlaceable.width, barPlaceable.width)
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else contentPlaceable.height + barPlaceable.height
        layout(width, height) {
            contentPlaceable.placeRelative(0, 0)
            barPlaceable.placeRelative(0, height - barPlaceable.height)
        }
    }
}

/**
 * A column that scrolls ONLY under a bounded parent (the `AronScreen` content slot). Under a host that still wraps the
 * screen in its own `verticalScroll` the parent hands down an infinite height, and a nested scrollable there is a
 * measure-time crash in Compose foundation ("measured with an infinity maximum height"); this guard lays the content
 * out at its full height instead, so the screen is safe under both hosts. [contentPadding] scrolls with the content.
 */
@Composable
internal fun ScrollColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val scrollState = rememberScrollState()
        val scroll = if (constraints.hasBoundedHeight) Modifier.verticalScroll(scrollState) else Modifier
        Column(Modifier.fillMaxSize().then(scroll).padding(contentPadding), verticalArrangement = verticalArrangement, content = content)
    }
}
