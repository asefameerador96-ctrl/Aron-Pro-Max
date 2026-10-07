package com.aktcl.aron.sr

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/** Keeps the day's services across a language switch (recreate) or a rotation; one per logged-in user. */
class SrDayHolder : ViewModel() {
    val day = MutableStateFlow<SrDay?>(null)
}
