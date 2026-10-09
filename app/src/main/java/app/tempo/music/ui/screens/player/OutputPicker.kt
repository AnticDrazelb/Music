package app.tempo.music.ui.screens.player

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.tempo.music.playback.cast.CastSupport
import kotlinx.coroutines.delay

/** Where the music is coming out. */
data class AudioOutput(val name: String, val icon: ImageVector, val casting: Boolean)

/**
 * Tracks the phone's own audio output (speaker, wired or Bluetooth headphones and speakers)
 * and Cast devices (Chromecast, Google TV, Nest and other Cast speakers) on the same Wi-Fi.
 * Cast devices are only searched for actively while the picker is open.
 */
class OutputsState(private val context: Context) {
    private val router = MediaRouter.getInstance(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    val castSupported = CastSupport.init(context)
    private val selector = if (castSupported) {
        MediaRouteSelector.Builder().addControlCategory(CastSupport.controlCategory()).build()
    } else {
        MediaRouteSelector.Builder().addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK).build()
    }

    var castDevices by mutableStateOf<List<MediaRouter.RouteInfo>>(emptyList())
        private set
    var castingTo by mutableStateOf<MediaRouter.RouteInfo?>(null)
        private set
    var local by mutableStateOf(localOutput())
        private set
    /** Bumped on every route change so volume and connection states recompose. */
    var version by mutableStateOf(0)
        private set

    val current: AudioOutput
        get() = castingTo?.let { AudioOutput(it.name, Icons.Rounded.CastConnected, casting = true) } ?: local

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        override fun onRouteVolumeChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun start(discover: Boolean) {
        router.addCallback(selector, callback, if (discover) MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY else 0)
        audio?.registerAudioDeviceCallback(devices, null)
        refresh()
    }

    fun stop() {
        router.removeCallback(callback)
        audio?.unregisterAudioDeviceCallback(devices)
    }

    private fun refresh() {
        castDevices = router.routes.filter { !it.isDefaultOrBluetooth && it.isEnabled && it.matchesSelector(selector) }
        castingTo = router.selectedRoute.takeIf { !it.isDefaultOrBluetooth && it.matchesSelector(selector) }
        local = localOutput()
        version++
    }

    fun castTo(route: MediaRouter.RouteInfo) = router.selectRoute(route)

    fun stopCasting() = router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)

    /** Android's own picker for Bluetooth and other connected devices, or Bluetooth settings. */
    fun openSystemPicker() {
        if (SystemOutputSwitcherDialogController.showDialog(context)) return
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun localOutput(): AudioOutput {
        val outputs = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val bluetooth = outputs.firstOrNull { it.type in BLUETOOTH_TYPES }
        if (bluetooth != null) {
            val name = bluetooth.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
            return AudioOutput(name, Icons.Rounded.BluetoothAudio, casting = false)
        }
        if (outputs.any { it.type in WIRED_TYPES }) return AudioOutput("Headphones", Icons.Rounded.Headphones, casting = false)
        return AudioOutput("This Phone", Icons.Rounded.PhoneAndroid, casting = false)
    }

    private companion object {
        val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
        )
    }
}

private val MediaRouter.RouteInfo.isDefaultOrBluetooth: Boolean get() = isDefault || isBluetooth

/** Follows the current output while on screen, without searching the network. */
@Composable
fun rememberOutputs(discover: Boolean = false): OutputsState {
    val context = LocalContext.current
    val state = remember { OutputsState(context.applicationContext) }
    DisposableEffect(discover) {
        state.start(discover)
        onDispose { state.stop() }
    }
    return state
}

/** Apple Music's AirPlay sheet: pick the phone, Bluetooth, or a TV or speaker to cast to. */
@Composable
fun OutputPickerSheet(onDismiss: () -> Unit) {
    val outputs = rememberOutputs(discover = true)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchedAWhile by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(8_000)
        searchedAWhile = true
    }
    outputs.version // read so route changes (connecting, connected) recompose the sheet

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text("Play On", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

            OutputGroup {
                OutputRow(
                    outputs.local.icon,
                    outputs.local.name,
                    selected = outputs.castingTo == null,
                    showDivider = false,
                ) { if (outputs.castingTo != null) outputs.stopCasting() }
            }

            SectionLabel("Speakers & TVs")
            OutputGroup {
                when {
                    !outputs.castSupported -> Note("Casting needs Google Play services on this phone.")
                    outputs.castDevices.isEmpty() && !searchedAWhile -> Searching()
                    outputs.castDevices.isEmpty() -> Note("No speakers or TVs found. Make sure they're switched on and on the same Wi-Fi as this phone.")
                    else -> outputs.castDevices.forEachIndexed { i, route ->
                        OutputRow(
                            route.deviceIcon(),
                            route.name,
                            detail = route.description,
                            selected = route.isSelected && route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED,
                            connecting = route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING,
                            showDivider = i < outputs.castDevices.lastIndex,
                        ) { if (!route.isSelected) outputs.castTo(route) }
                    }
                }
            }

            OutputGroup {
                OutputRow(Icons.Rounded.Bluetooth, "Bluetooth & Other Devices…", showDivider = outputs.castingTo != null) {
                    outputs.openSystemPicker()
                }
                if (outputs.castingTo != null) {
                    OutputRow(Icons.Rounded.Cast, "Stop Casting", tint = MaterialTheme.colorScheme.error, showDivider = false) {
                        outputs.stopCasting()
                    }
                }
            }
            if (outputs.castingTo != null) {
                Text(
                    "The equalizer, karaoke and crossfade work when playing on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private fun MediaRouter.RouteInfo.deviceIcon(): ImageVector = when (deviceType) {
    MediaRouter.RouteInfo.DEVICE_TYPE_TV -> Icons.Rounded.Tv
    MediaRouter.RouteInfo.DEVICE_TYPE_GROUP -> Icons.Rounded.SpeakerGroup
    MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> Icons.Rounded.Speaker
    else -> Icons.Rounded.Cast
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun OutputGroup(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
    ) { content() }
}

@Composable
private fun OutputRow(
    icon: ImageVector,
    label: String,
    detail: String? = null,
    selected: Boolean = false,
    connecting: Boolean = false,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (!detail.isNullOrBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            connecting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            selected -> Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
    }
    if (showDivider) {
        HorizontalDivider(Modifier.padding(start = 54.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Searching() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(54.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(14.dp))
        Text("Looking for speakers and TVs…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
