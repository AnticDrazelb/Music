package app.tempo.music.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.tempo.music.playback.eq.Eq
import app.tempo.music.playback.eq.EqCurve
import app.tempo.music.playback.eq.EqPreset
import app.tempo.music.playback.eq.EqSettings
import app.tempo.music.ui.components.DetailScaffold
import app.tempo.music.ui.components.belowTopBar
import app.tempo.music.ui.navigation.LocalAppContainer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen() {
    val controller = LocalAppContainer.current.equalizer
    val saved by controller.saved.collectAsState()
    var draft by remember { mutableStateOf(saved) }
    LaunchedEffect(saved) { draft = saved }
    val listState = rememberLazyListState()

    fun commit(value: EqSettings) {
        draft = value
        controller.save(value)
    }

    // Moving a slider turns the equaliser on and makes the curve the listener's own.
    fun setBand(index: Int, gain: Float, finished: Boolean) {
        val gains = draft.gains.toMutableList().also { it[index] = gain }
        val next = draft.copy(enabled = true, preset = null, customGains = gains)
        if (finished) commit(next) else {
            draft = next
            controller.preview(next)
        }
    }

    DetailScaffold(title = "Equalizer", listState = listState) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow),
            state = listState,
            contentPadding = padding,
        ) {
            item {
                Text(
                    "Equalizer",
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.belowTopBar().padding(start = 20.dp, bottom = 8.dp),
                )
            }
            item {
                Group(
                    "Sound",
                    "Drag a slider to boost or cut that range, or double-tap it to reset. Boosts lower the " +
                        "overall volume slightly so loud songs don't distort.",
                ) {
                    SwitchRow("Equalizer", draft.enabled) { commit(draft.copy(enabled = it)) }
                    EqEditor(
                        draft,
                        onBand = ::setBand,
                        onReset = { commit(draft.copy(enabled = true, preset = EqPreset.FLAT)) },
                    )
                }
            }
            item {
                Group("Presets") {
                    val showCustom = draft.preset == null || draft.customGains.any { it != 0f }
                    EqPreset.entries.forEachIndexed { i, preset ->
                        PresetRow(
                            preset.title,
                            selected = draft.enabled && draft.preset == preset,
                            last = !showCustom && i == EqPreset.entries.lastIndex,
                        ) { commit(draft.copy(enabled = true, preset = preset)) }
                    }
                    if (showCustom) {
                        PresetRow("Custom", selected = draft.enabled && draft.preset == null, last = true) {
                            commit(draft.copy(enabled = true, preset = null))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun EqEditor(settings: EqSettings, onBand: (Int, Float, Boolean) -> Unit, onReset: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (settings.enabled) 1f else 0.45f }
            .padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
    ) {
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                settings.preset?.title ?: "Custom",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReset, enabled = settings.gains.any { it != 0f }) { Text("Reset") }
        }
        ResponseCurve(settings.gains, Modifier.fillMaxWidth().height(96.dp))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            settings.gains.forEachIndexed { i, gain ->
                BandSlider(Eq.label(i), gain, Modifier.weight(1f)) { value, done -> onBand(i, value, done) }
            }
        }
    }
}

/** The curve the filters actually produce, with each band's centre above its slider. */
@Composable
private fun ResponseCurve(gains: List<Float>, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    val curve = remember(gains) { EqCurve(gains, 48_000.0) }
    Canvas(modifier) {
        val range = Eq.MAX_GAIN_DB + 3f
        fun yFor(db: Double) = size.height / 2 - (db.toFloat().coerceIn(-range, range) / range) * size.height / 2
        listOf(-12.0, -6.0, 0.0, 6.0, 12.0).forEach { db ->
            drawLine(
                grid.copy(alpha = if (db == 0.0) 0.9f else 0.35f),
                Offset(0f, yFor(db)),
                Offset(size.width, yFor(db)),
                strokeWidth = 1.dp.toPx(),
            )
        }
        // x is in octaves from the first band, so band i sits at the centre of column i.
        val first = Eq.FREQUENCIES.first().toDouble()
        val line = Path()
        val fill = Path().apply { moveTo(0f, yFor(0.0)) }
        val steps = 160
        for (step in 0..steps) {
            val x = size.width * step / steps
            val octaves = x / size.width * Eq.BAND_COUNT - 0.5
            val y = yFor(curve.responseDb(first * 2.0.pow(octaves)))
            if (step == 0) line.moveTo(x, y) else line.lineTo(x, y)
            fill.lineTo(x, y)
        }
        fill.lineTo(size.width, yFor(0.0))
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.04f))))
        drawPath(line, accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun BandSlider(label: String, gain: Float, modifier: Modifier, onChange: (Float, Boolean) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val latestGain by rememberUpdatedState(gain)
    val latestOnChange by rememberUpdatedState(onChange)
    val max = Eq.MAX_GAIN_DB

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(formatGain(gain), style = MaterialTheme.typography.labelSmall, color = if (gain != 0f) accent else muted)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(34.dp)
                .height(190.dp)
                .semantics {
                    contentDescription = "$label hertz"
                    stateDescription = "${formatGain(gain)} decibels"
                    progressBarRangeInfo = ProgressBarRangeInfo(gain, -max..max, steps = (max * 4).toInt() - 1)
                    setProgress { value ->
                        latestOnChange(snap(value), true)
                        true
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { latestOnChange(0f, true) }) }
                .pointerInput(Unit) {
                    val knob = KNOB_RADIUS.toPx()
                    fun gainAt(y: Float): Float {
                        val fraction = ((y - knob) / (size.height - 2 * knob)).coerceIn(0f, 1f)
                        return snap(max - fraction * 2 * max)
                    }
                    detectVerticalDragGestures(
                        onDragEnd = { latestOnChange(latestGain, true) },
                        onDragCancel = { latestOnChange(latestGain, true) },
                    ) { change, _ ->
                        change.consume()
                        val value = gainAt(change.position.y)
                        if (value != latestGain) latestOnChange(value, false)
                    }
                },
        ) {
            Canvas(Modifier.matchParentSize()) {
                val knob = KNOB_RADIUS.toPx()
                val top = knob
                val bottom = size.height - knob
                val cx = size.width / 2
                val zeroY = (top + bottom) / 2
                val y = zeroY - gain / max * (bottom - top) / 2
                val width = 4.dp.toPx()
                drawLine(track, Offset(cx, top), Offset(cx, bottom), width, StrokeCap.Round)
                drawLine(muted.copy(alpha = 0.5f), Offset(cx - 7.dp.toPx(), zeroY), Offset(cx + 7.dp.toPx(), zeroY), 1.dp.toPx())
                if (gain != 0f) drawLine(accent, Offset(cx, zeroY), Offset(cx, y), width, StrokeCap.Round)
                drawCircle(Color.Black.copy(alpha = 0.16f), knob + 1.dp.toPx(), Offset(cx, y + 1.dp.toPx()))
                drawCircle(Color.White, knob, Offset(cx, y))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = muted)
    }
}

@Composable
private fun PresetRow(title: String, selected: Boolean, last: Boolean, onClick: () -> Unit) {
    RowBase(last, onClick) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

private val KNOB_RADIUS = 11.dp

/** Half-decibel steps. */
private fun snap(db: Float) = ((db * 2).roundToInt() / 2f).coerceIn(-Eq.MAX_GAIN_DB, Eq.MAX_GAIN_DB)

private fun formatGain(db: Float): String {
    val text = if (db % 1f == 0f) db.toInt().toString().removePrefix("-") else "%.1f".format(abs(db))
    return when {
        db > 0f -> "+$text"
        db < 0f -> "−$text"
        else -> "0"
    }
}
