package com.aktcl.aron.feature.memo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.aktcl.aron.core.printing.ui.PrintAttemptDialogs

/**
 * Memo menu reprint dialogs (F-SR-030/031): core-printing's shared dialogs, so a tap outside or Back never records "not
 * readable" and the wording matches the sale screen's Print. The answer runs in the ViewModel scope.
 */
@Composable
fun ReprintDialogs(vm: MemoViewModel) {
    val s by vm.state.collectAsState()
    PrintAttemptDialogs(s.lastPrint, onAnswer = { _, readable -> vm.confirmPrint(readable) }, onClose = vm::dismissPrint)
}
