package com.aktcl.aron.core.system.logout

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.system.R
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.localizedNumber

/** TSO logout refused (F-SYS-022): "N items not yet sent", with Sync now and Cancel. */
@Composable
fun UnsentItemsDialog(unsent: Int, onSyncNow: () -> Unit, onCancel: () -> Unit) {
    AronConfirmDialog(
        stringResource(R.string.logout_unsent_title),
        pluralStringResource(R.plurals.logout_unsent_message, unsent, localizedNumber(unsent.toLong())),
        stringResource(R.string.logout_sync_now),
        stringResource(R.string.logout_cancel),
        onConfirm = onSyncNow,
        onDismiss = onCancel,
    )
}
