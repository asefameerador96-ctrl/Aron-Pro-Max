package com.aktcl.aron.feature.memo.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.printing.flow.PrintAttempt
import com.aktcl.aron.feature.memo.R

/**
 * Memo menu reprint dialogs (F-SR-030/031): "readable?" after paper, Retry or Print later after a failure, and the
 * reprint limit. Strings come from core-printing so the wording matches the sale screen's Print.
 */
@Composable
fun ReprintDialogs(vm: MemoViewModel) {
    val s by vm.state.collectAsState()
    when (val a = s.lastPrint) {
        null, PrintAttempt.Done -> Unit
        is PrintAttempt.AwaitingConfirmation -> AlertDialog(
            onDismissRequest = {}, text = { Text(stringResource(com.aktcl.aron.core.printing.R.string.ui_print_readable_question)) },
            confirmButton = { TextButton({ vm.confirmPrint(true) }) { Text(stringResource(R.string.memo_yes)) } },
            dismissButton = { TextButton({ vm.confirmPrint(false) }) { Text(stringResource(R.string.memo_no)) } },
        )
        is PrintAttempt.Failed, PrintAttempt.TooLong -> AlertDialog(
            onDismissRequest = {}, text = { Text(stringResource(com.aktcl.aron.core.printing.R.string.ui_print_failed)) },
            confirmButton = { TextButton({ vm.retryPrint() }) { Text(stringResource(com.aktcl.aron.core.printing.R.string.ui_print_retry)) } },
            dismissButton = { TextButton({ vm.dismissPrint() }) { Text(stringResource(com.aktcl.aron.core.printing.R.string.ui_print_later)) } },
        )
        PrintAttempt.LimitReached -> AlertDialog(
            onDismissRequest = { vm.dismissPrint() }, text = { Text(stringResource(com.aktcl.aron.core.printing.R.string.ui_print_limit_reached)) },
            confirmButton = { TextButton({ vm.dismissPrint() }) { Text(stringResource(R.string.memo_yes)) } },
        )
    }
}
