package com.aktcl.aron.sr

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.session.SessionState
import com.aktcl.aron.core.session.UnlockMode
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.auth.LoginScreen
import com.aktcl.aron.feature.auth.LoginViewModel
import com.aktcl.aron.feature.home.HomeUser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.geo.FixManager
import com.aktcl.aron.core.sync.SyncScheduler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import javax.inject.Inject

/**
 * Single activity of ARON SR (docs/24 s5.1). Day-1 shell (N-001): login, then the home placeholder. The language is
 * applied in [attachBaseContext] so every string resource resolves in Bangla or English (docs/24 s5.6).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var components: SessionComponents
    @Inject lateinit var databases: UserDatabases
    @Inject lateinit var scheduler: SyncScheduler
    @Inject lateinit var fixManager: FixManager
    @Inject lateinit var bundleDownloaders: com.aktcl.aron.core.sync.BundleDownloaders

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val language = AppLocale.current(this)
        val onLanguageSelect: (AppLanguage) -> Unit = { if (AppLocale.set(this, it)) recreate() }
        val versionName = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
        setContent {
            AronTheme(language) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by components.session.state.collectAsStateWithLifecycle()
                    when (val s = state) {
                        SessionState.LoggedOut -> {
                            val vm = viewModel { LoginViewModel(components.session::login) }
                            LoginScreen(vm, stringResource(R.string.app_name), versionName, onLanguageSelect)
                        }
                        is SessionState.Active -> {
                            val holder = viewModel(key = "sr-day-" + s.user.userId) { SrDayHolder() }
                            val day by holder.day.collectAsStateWithLifecycle()
                            LaunchedEffect(s.user.userId) {
                                if (holder.day.value == null) {
                                    holder.day.value = SrDay(s.user.userId, applicationContext, databases.of(s.user.userId), components, scheduler, fixManager)
                                }
                            }
                            day?.let { d ->
                                SrApp(
                                    day = d,
                                    user = HomeUser(
                                        fullName = s.user.fullName, username = s.user.username, role = s.user.role,
                                        offline = s.mode == UnlockMode.OFFLINE, reauthRequired = s.reauthRequired, updateRequired = s.updateRequired,
                                    ),
                                    health = null, versionText = versionName,
                                    onLanguageSelect = onLanguageSelect,
                                    onLogout = { lifecycleScope.launch { components.session.logout() } },
                                    onOtherTile = { },
                                    startBundleDownload = { day?.downloadBundle(bundleDownloaders) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
