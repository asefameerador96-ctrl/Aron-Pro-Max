package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.RowPosition

/** One row of a hub list: its label, an optional trailing count or status, and the tap. */
data class HubRow(val label: String, val trailing: String? = null, val onClick: () -> Unit = {})

/** A group of rows under an optional caption. */
data class HubSection(val caption: String?, val rows: List<HubRow>)

/** The AMO hubs (Team, Outlet): captioned groups of rows. Long Bangla labels wrap; the trailing count stays on its row. */
@Composable
fun HubListContent(sections: List<HubSection>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
        sections.forEach { s ->
            s.caption?.let { AronSectionLabel(it) }
            s.rows.forEachIndexed { i, r ->
                AronListRow(
                    r.label, trailing = r.trailing, onClick = r.onClick,
                    position = if (s.rows.size == 1) RowPosition.Only else if (i == 0) RowPosition.First else if (i == s.rows.lastIndex) RowPosition.Last else RowPosition.Middle,
                    divider = i < s.rows.lastIndex, flat = true,
                )
            }
        }
    }
}
