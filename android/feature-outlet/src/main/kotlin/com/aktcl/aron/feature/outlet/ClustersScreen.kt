package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronField
import com.aktcl.aron.core.ui.aron.AronEmptyMessage

object ClustersTags {
    const val LIST = "cl_list"
    const val FAB = "cl_fab"
    const val NAME = "cl_name"
    const val SUBMIT = "cl_submit"
    const val CANCEL = "cl_cancel"
    const val EMPTY = "cl_empty"
    fun card(key: String) = "cl_card_$key"
}

/**
 * The Clusters screen (SRM-B-43 to 45, screenshot 45): a white card per cluster with the building icon, the cluster name and
 * "Route: <route name>", and a red + button that opens the "ক্লাস্টার তৈরি করুন" dialog (a name box, বাতিল / সাবমিট). A cluster the SR
 * created and the server has not confirmed shows a "pending" tag. Reads local lists only; works offline.
 *
 * [onCreate] gets the trimmed name and the client uuid generated when the dialog opened (so a double tap or a retry is one record);
 * it returns the validation error, or null when the cluster was stored.
 */
@Composable
fun ClustersContent(clusters: List<SrCluster>, onCreate: suspend (name: String, clientUuid: String) -> ClusterNameError?, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    var creating by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(look.page)) {
        if (clusters.isEmpty()) {
            Box(Modifier.fillMaxSize().testTag(ClustersTags.EMPTY)) { AronEmptyMessage(stringResource(R.string.out_clusters_empty)) }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().testTag(ClustersTags.LIST),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(clusters, key = { it.clusterId?.toString() ?: ("u" + it.clientUuid) }) { c -> ClusterCard(c) }
            }
        }
        val add = stringResource(R.string.out_cluster_create_title)
        Box(
            Modifier.align(Alignment.BottomEnd).padding(20.dp).size(56.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFD94545))
                .clickable(role = Role.Button) { creating = true }.semantics { contentDescription = add }.testTag(ClustersTags.FAB),
            contentAlignment = Alignment.Center,
        ) { Icon(AronIcons.Add, contentDescription = null, tint = Color.White) }
    }
    if (creating) CreateClusterDialog(onDismiss = { creating = false }, onCreate = onCreate)
}

@Composable
private fun ClusterCard(c: SrCluster) {
    val look = LocalAronLook.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF8F9FC)).padding(14.dp).testTag(ClustersTags.card(c.clusterId?.toString() ?: ("u" + c.clientUuid))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF5E6E8)), contentAlignment = Alignment.Center) {
            Icon(painterResource(com.aktcl.aron.core.ui.R.drawable.aron_ic_cluster), contentDescription = null, tint = Color(0xFF3B1A7A), modifier = Modifier.size(26.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, color = look.ink)
            Text(stringResource(R.string.out_cluster_route, c.routeName), style = MaterialTheme.typography.bodyMedium, color = look.secondaryText)
            if (c.pending) Text(stringResource(R.string.out_cluster_pending), style = MaterialTheme.typography.labelMedium, color = look.purple)
        }
    }
}

@Composable
private fun CreateClusterDialog(onDismiss: () -> Unit, onCreate: suspend (String, String) -> ClusterNameError?) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val look = LocalAronLook.current
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<ClusterNameError?>(null) }
    // generated once per opening: a double tap on সাবমিট can only ever store one record
    val uuid = rememberSaveable { SrClusters.newUuid() }
    // NOT saveable: a recreate mid-save cancels the launch below, so a saved "busy" flag would leave the button off for good. The uuid IS
    // saved, so tapping again after a recreate is a replay of the same record (stored once, reported as saved).
    var done by remember { mutableStateOf(false) }
    // read outside the dialog window: its content composes with the window's own context, which may not carry the app language
    val title = stringResource(R.string.out_cluster_create_title)
    val hint = stringResource(R.string.out_cluster_name_hint)
    val cancel = stringResource(R.string.out_cluster_cancel)
    val submit = stringResource(R.string.out_cluster_submit)
    val errorMessage = error?.let { stringResource(clusterErrorText(it)) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color(0xFFEBE5F0)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = look.ink)
            AronField(
                name, { name = it; error = null }, hint, Modifier.fillMaxWidth(), fieldModifier = Modifier.testTag(ClustersTags.NAME),
                errorText = errorMessage,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M, Alignment.End)) {
                Text(
                    cancel, color = Color(0xFFD32F2F), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.clickable(role = Role.Button) { onDismiss() }.padding(horizontal = 16.dp, vertical = 12.dp).testTag(ClustersTags.CANCEL),
                )
                Text(
                    submit, color = Color(0xFF2E9E44), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.clickable(role = Role.Button, enabled = !done) {
                        if (!done) {
                            done = true // one tap, one record: the button is off while the (day-scoped) save runs
                            scope.launch {
                                val e = onCreate(name.trim(), uuid)
                                if (e == null) onDismiss() else { error = e; done = false }
                            }
                        }
                    }.padding(horizontal = 16.dp, vertical = 12.dp).testTag(ClustersTags.SUBMIT),
                )
            }
        }
    }
}

private fun clusterErrorText(e: ClusterNameError): Int = when (e) {
    ClusterNameError.EMPTY, ClusterNameError.TOO_SHORT -> R.string.out_cluster_err_short
    ClusterNameError.TOO_LONG -> R.string.out_cluster_err_long
    ClusterNameError.DUPLICATE -> R.string.out_cluster_err_dup
    ClusterNameError.NOT_SAVED -> R.string.out_cluster_err_not_saved
}
