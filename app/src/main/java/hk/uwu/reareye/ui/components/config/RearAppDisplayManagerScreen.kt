package hk.uwu.reareye.ui.components.config

import android.widget.Toast
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.RoundedCorner
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearAppDefaults
import hk.uwu.reareye.ui.config.PrefsManager
import kotlinx.coroutines.delay
import org.json.JSONArray
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_REGION_SIZE = .2f
private data class RearDisplayGeometry(
    val width: Int = 976,
    val height: Int = 596,
    val safeRegion: RearDisplayRegion = RearDisplayRegion(296f / 976f, 0f, 1f, 1f),
    val cornerRadius: Float = 101f,
) {
    companion object {
        @Suppress("DEPRECATION")
        fun read(context: Context): RearDisplayGeometry = runCatching {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(1)
                ?: error("Rear display unavailable")
            val dimensions = Point().also(display::getRealSize)
            require(dimensions.x > 0 && dimensions.y > 0)
            val cutout = display.cutout
            val safe = RearDisplayRegion(
                (cutout?.safeInsetLeft ?: 0).toFloat() / dimensions.x,
                (cutout?.safeInsetTop ?: 0).toFloat() / dimensions.y,
                1f - (cutout?.safeInsetRight ?: 0).toFloat() / dimensions.x,
                1f - (cutout?.safeInsetBottom ?: 0).toFloat() / dimensions.y,
            )
            val corner = listOf(RoundedCorner.POSITION_TOP_RIGHT, RoundedCorner.POSITION_BOTTOM_RIGHT)
                .mapNotNull { position -> display.getRoundedCorner(position)?.radius?.takeIf { it > 0 } }
                .maxOrNull()?.toFloat() ?: (101f * dimensions.x / 976f)
            RearDisplayGeometry(dimensions.x, dimensions.y, safe, corner)
        }.getOrDefault(RearDisplayGeometry())
    }
}

private data class RearDisplayRegion(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    fun encode(): String = JSONArray(listOf(left, top, right, bottom)).toString()

    companion object {
        fun read(value: String, defaultRegion: RearDisplayRegion): RearDisplayRegion = runCatching {
            val safe = RearDisplayRegion()
            val array = JSONArray(value)
            require(array.length() == 4)
            val values = (0..3).map { array.getDouble(it).toFloat().also { value -> require(value.isFinite()) } }
            val left = values[0].coerceIn(safe.left, maxOf(safe.left, safe.right - MIN_REGION_SIZE))
            val top = values[1].coerceIn(safe.top, maxOf(safe.top, safe.bottom - MIN_REGION_SIZE))
            RearDisplayRegion(left, top, values[2].coerceIn(minOf(safe.right, left + MIN_REGION_SIZE), safe.right),
                values[3].coerceIn(minOf(safe.bottom, top + MIN_REGION_SIZE), safe.bottom))
        }.getOrDefault(defaultRegion)
    }
}

@Composable
fun RearAppDisplayManagerScreen(prefsManager: PrefsManager, onBack: () -> Unit) {
    val context = LocalContext.current
    val geometry = remember(context) { RearDisplayGeometry.read(context) }
    val safe = geometry.safeRegion
    val limits = RearDisplayRegion()
    val defaultRegion = RearDisplayRegion(.292f, 0f, 1f, 1f)
    var region by remember { mutableStateOf(defaultRegion) }
    var automatic by remember { mutableStateOf(false) }
    var rounded by remember { mutableStateOf(true) }
    var radius by remember { mutableStateOf(RearAppDefaults.CORNER_RADIUS) }
    var loaded by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        repeat(40) {
            if (prefsManager.isRemoteReady()) {
                val raw = prefsManager.getString(ConfigKeys.REAR_APP_REGION, RearAppDefaults.REGION)
                region = RearDisplayRegion.read(raw, safe)
                automatic = raw.isBlank()
                rounded = prefsManager.getBoolean(ConfigKeys.REAR_APP_ROUNDED, true)
                radius = prefsManager.getFloat(ConfigKeys.REAR_APP_CORNER_RADIUS, RearAppDefaults.CORNER_RADIUS).coerceIn(0f, 180f)
                loaded = true
                return@LaunchedEffect
            }
            delay(200L)
        }
        unavailable = true
    }
    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.rear_app_display_title),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!loaded) {
                item { Text(stringResource(if (unavailable) R.string.rear_app_region_unavailable else R.string.rear_app_region_loading)) }
            } else {
                item {
                    Text(stringResource(R.string.rear_app_region_hint),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                item {
                    RearDisplayRegionPreview(region, geometry, rounded, radius) {
                        region = it
                        automatic = false
                    }
                }
                item {
                    Text(stringResource(if (automatic) R.string.rear_app_region_auto else R.string.rear_app_region_custom))
                    Text(stringResource(R.string.rear_app_region_coordinates,
                        region.left * 100f, region.top * 100f, region.right * 100f, region.bottom * 100f),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                item {
                    Card {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RegionEdgeSlider(R.string.rear_app_region_left, region.left,
                                limits.left..maxOf(limits.left, region.right - MIN_REGION_SIZE)) {
                                region = region.copy(left = it); automatic = false
                            }
                            RegionEdgeSlider(R.string.rear_app_region_top, region.top,
                                limits.top..maxOf(limits.top, region.bottom - MIN_REGION_SIZE)) {
                                region = region.copy(top = it); automatic = false
                            }
                            RegionEdgeSlider(R.string.rear_app_region_right, region.right,
                                minOf(limits.right, region.left + MIN_REGION_SIZE)..limits.right) {
                                region = region.copy(right = it); automatic = false
                            }
                            RegionEdgeSlider(R.string.rear_app_region_bottom, region.bottom,
                                minOf(limits.bottom, region.top + MIN_REGION_SIZE)..limits.bottom) {
                                region = region.copy(bottom = it); automatic = false
                            }
                        }
                    }
                }
                item {
                    Card {
                        SwitchPreference(title = stringResource(R.string.rear_app_rounded),
                            checked = rounded, onCheckedChange = { rounded = it })
                        if (rounded) {
                            SwitchPreference(title = stringResource(R.string.rear_app_corner_auto),
                                checked = radius == 0f,
                                onCheckedChange = { radius = if (it) 0f else RearAppDefaults.CORNER_RADIUS })
                            if (radius > 0f) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(stringResource(R.string.rear_app_corner_radius, radius.roundToInt()))
                                    Slider(value = radius, onValueChange = { radius = it.roundToInt().toFloat() },
                                        valueRange = 1f..180f, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
                item {
                    Button(onClick = { region = safe; automatic = true },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.rear_app_region_reset))
                    }
                }
                item {
                    Text(stringResource(R.string.rear_app_region_unsaved),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Button(onClick = {
                        prefsManager.prefs.edit()
                            .putString(ConfigKeys.REAR_APP_REGION, if (automatic) "" else region.encode())
                            .putBoolean(ConfigKeys.REAR_APP_ROUNDED, rounded)
                            .putFloat(ConfigKeys.REAR_APP_CORNER_RADIUS, radius)
                            .apply()
                        Toast.makeText(context, R.string.rear_app_region_saved, Toast.LENGTH_SHORT).show()
                        onBack()
                    }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(stringResource(R.string.rear_app_region_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionEdgeSlider(titleRes: Int, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit) {
    val title = stringResource(titleRes)
    Text("$title  ${(value * 1000f).roundToInt() / 10f}%")
    // A tiny remaining range can occur at the minimum size. Keep Slider's range nonempty.
    Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = {
        onChange(it.coerceIn(range.start, range.endInclusive))
    }, valueRange = range.start..maxOf(range.start + .00001f, range.endInclusive),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = title })
}

@Composable
private fun RearDisplayRegionPreview(region: RearDisplayRegion, geometry: RearDisplayGeometry, rounded: Boolean, radius: Float,
    onChange: (RearDisplayRegion) -> Unit) {
    val latest by rememberUpdatedState(region)
    val change by rememberUpdatedState(onChange)
    val currentRounded by rememberUpdatedState(rounded)
    val currentRadius by rememberUpdatedState(radius)
    val description = stringResource(R.string.rear_app_region_preview_description)
    val safe = geometry.safeRegion
    Canvas(Modifier.fillMaxWidth().aspectRatio(geometry.width.toFloat() / geometry.height)
        .semantics { contentDescription = description }
        .pointerInput(geometry) {
            var handle = -1
            detectDragGestures(onDragStart = { start ->
                val area = latest
                val corners = listOf(Offset(area.left, area.top), Offset(area.right, area.top),
                    Offset(area.left, area.bottom), Offset(area.right, area.bottom))
                val point = Offset(start.x / size.width, start.y / size.height)
                handle = corners.indices.minByOrNull { (corners[it] - point).getDistance() }
                    ?.takeIf { abs(corners[it].x - point.x) * size.width < 32.dp.toPx() &&
                        abs(corners[it].y - point.y) * size.height < 32.dp.toPx() } ?: -1
                if (handle == -1 && point.x >= area.left && point.x <= area.right &&
                    point.y >= area.top && point.y <= area.bottom) handle = 4
            }, onDrag = { event, delta ->
                if (handle < 0) return@detectDragGestures
                event.consume()
                val area = latest
                val dx = delta.x / size.width
                val dy = delta.y / size.height
                val next = if (handle == 4) {
                    val width = area.right - area.left
                    val height = area.bottom - area.top
                    val left = (area.left + dx).coerceIn(0f, maxOf(0f, 1f - width))
                    val top = (area.top + dy).coerceIn(0f, maxOf(0f, 1f - height))
                    RearDisplayRegion(left, top, minOf(1f, left + width), minOf(1f, top + height))
                } else area.copy(
                    left = if (handle == 0 || handle == 2) (area.left + dx)
                        .coerceIn(0f, maxOf(0f, area.right - MIN_REGION_SIZE)) else area.left,
                    top = if (handle == 0 || handle == 1) (area.top + dy)
                        .coerceIn(0f, maxOf(0f, area.bottom - MIN_REGION_SIZE)) else area.top,
                    right = if (handle == 1 || handle == 3) (area.right + dx)
                        .coerceIn(minOf(1f, area.left + MIN_REGION_SIZE), 1f) else area.right,
                    bottom = if (handle == 2 || handle == 3) (area.bottom + dy)
                        .coerceIn(minOf(1f, area.top + MIN_REGION_SIZE), 1f) else area.bottom,
                )
                change(next)
            }, onDragEnd = { handle = -1 }, onDragCancel = { handle = -1 })
        }) {
        val physicalRadius = size.width * geometry.cornerRadius / geometry.width
        val physicalOutline = Path().apply {
            addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(physicalRadius)))
        }
        // The camera mask and app preview share the physical screen's rounded outline.
        // App rounding remains a separate draft setting and never changes this outer frame.
        clipPath(physicalOutline) {
            drawRect(Color(0xFF181C25))
            drawRect(Color(0xFF464A53), size = Size(safe.left * size.width, size.height))
            drawRect(Color(0xFF464A53), size = Size(size.width, safe.top * size.height))
            drawRect(Color(0xFF464A53), topLeft = Offset(safe.right * size.width, 0f),
                size = Size((1f - safe.right) * size.width, size.height))
            drawRect(Color(0xFF464A53), topLeft = Offset(0f, safe.bottom * size.height),
                size = Size(size.width, (1f - safe.bottom) * size.height))
            val origin = Offset(region.left * size.width, region.top * size.height)
            val activeSize = Size((region.right - region.left) * size.width,
                (region.bottom - region.top) * size.height)
            val pixelRadius = if (currentRadius == 0f) geometry.cornerRadius else currentRadius
            val curve = if (currentRounded) min(pixelRadius * size.width / geometry.width,
                min(activeSize.width, activeSize.height) / 2f) else 0f
            drawRoundRect(Color(0xFF364F7B), origin, activeSize, CornerRadius(curve))
            drawRoundRect(Color(0xFF95BBFF), origin, activeSize, CornerRadius(curve), style = Stroke(2.dp.toPx()))
            // Physical camera lenses remain visible even when the application is deliberately
            // expanded into the surrounding camera area, matching the stock illustration.
            if (safe.left > .1f) listOf(.25f, .75f).forEach { y ->
                val lens = min(size.height * .22f, size.width * safe.left * .46f)
                val center = Offset(size.width * safe.left / 2f, size.height * y)
                drawCircle(Color(0xFF0B0D11), lens, center)
                drawCircle(Color(0xFF747983), lens, center, style = Stroke(size.height * .012f))
            }
        }
        listOf(Offset(region.left, region.top), Offset(region.right, region.top),
            Offset(region.left, region.bottom), Offset(region.right, region.bottom)).forEach {
            drawCircle(Color(0xFFE7F0FF), 6.dp.toPx(), Offset(
                (it.x * size.width).coerceIn(6.dp.toPx(), size.width - 6.dp.toPx()),
                (it.y * size.height).coerceIn(6.dp.toPx(), size.height - 6.dp.toPx())))
        }
    }
}
