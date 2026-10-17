package com.aktcl.aron.sr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.rules.Geo
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberMarkerState
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * N-041: the outlet pin, the geofence circle and the phone's position on the out-of-range screen. Started only when the
 * SR taps Map (never preloaded, never part of a sale step); the distances always show in text, so with no network (no
 * tiles) or no Maps services the rep still sees where they stand and can go back to Force Sale.
 */
class OutletMapActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val name = intent.getStringExtra(NAME).orEmpty()
        val outlet = LatLng(intent.getDoubleExtra(LAT, 0.0), intent.getDoubleExtra(LNG, 0.0))
        val radius = intent.getIntExtra(RADIUS, 100)
        val phone = if (intent.hasExtra(PHONE_LAT)) LatLng(intent.getDoubleExtra(PHONE_LAT, 0.0), intent.getDoubleExtra(PHONE_LNG, 0.0)) else null
        setContent {
            AronTheme(AppLocale.current(this)) {
                OutletMapScreen(name, outlet, radius, phone, online, onBack = { finish() })
            }
        }
    }

    companion object {
        private const val NAME = "name"; private const val LAT = "lat"; private const val LNG = "lng"; private const val RADIUS = "radius"
        private const val PHONE_LAT = "phone_lat"; private const val PHONE_LNG = "phone_lng"

        fun intent(context: Context, name: String, lat: Double, lng: Double, radiusM: Int, phoneLat: Double?, phoneLng: Double?) =
            Intent(context, OutletMapActivity::class.java).putExtra(NAME, name).putExtra(LAT, lat).putExtra(LNG, lng).putExtra(RADIUS, radiusM)
                .also { if (phoneLat != null && phoneLng != null) it.putExtra(PHONE_LAT, phoneLat).putExtra(PHONE_LNG, phoneLng) }
    }
}

@Composable
private fun OutletMapScreen(name: String, outlet: LatLng, radiusM: Int, phone: LatLng?, online: Boolean, onBack: () -> Unit) {
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(outlet, 17f) }
    val c = com.aktcl.aron.core.ui.LocalAronColors.current
    val distance = phone?.let {
        val d = Geo.haversineM(it.latitude, it.longitude, outlet.latitude, outlet.longitude)
        stringResource(R.string.sr_map_distance, localizedNumber(Math.round(d)), localizedNumber(radiusM.toLong()))
    }
    com.aktcl.aron.core.ui.AronScreen(
        topBar = { com.aktcl.aron.core.ui.AronTopBar(stringResource(R.string.sr_map_title), subtitle = distance, onBack = onBack) },
    ) {
        Column(Modifier.fillMaxSize()) {
            // The map is a bonus: when it cannot load (no key, no Play services, no network) the tiles stay blank and the distance in the bar remains.
            GoogleMap(Modifier.fillMaxWidth().weight(1f).heightIn(min = 240.dp), cameraPositionState = camera) {
                Marker(state = rememberMarkerState(position = outlet), title = name)
                // The allowed radius: a translucent accent disc with an accent ring (the library's defaults paint an opaque disc).
                Circle(center = outlet, radius = radiusM.toDouble(), fillColor = c.accent.copy(alpha = 0.16f), strokeColor = c.accent, strokeWidth = 3f)
                phone?.let { Marker(state = rememberMarkerState(position = it), title = stringResource(R.string.sr_map_you)) }
            }
            if (!online) AronBanner(stringResource(R.string.sr_map_offline), kind = BannerKind.Offline)
            AronSecondaryButton(stringResource(R.string.sr_map_back), onBack, Modifier.padding(AronTokens.Space.L))
        }
    }
}
