package com.fox.vpad

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class ControlId {
    DPAD, ABXY, LEFT_STICK, RIGHT_STICK, L1, L2, R1, R2, L3, R3, SELECT, START, HOME
}

data class ControlPlacement(
    val normX: Float,
    val normY: Float,
    val scale: Float = 1.0f,
    val hidden: Boolean = false
)

enum class VibIntensity(val level: Int, val label: String, val durationMs: Long, val amplitude: Int) {
    OFF(0, "VIB: OFF", 0L, 0),
    LOW(1, "VIB: LOW", 8L, 65),
    MED(2, "VIB: MED", 12L, 145),
    HIGH(3, "VIB: HIGH", 16L, 255);

    fun next(): VibIntensity = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromLevel(level: Int): VibIntensity = entries.firstOrNull { it.level == level } ?: MED
    }
}

class MainActivity : ComponentActivity() {
    private val backend = GamepadBackend()
    private var rearController: RearCoverController? = null

    private var frontState = GamepadState()
    private var rearState = GamepadState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        backend.initShizuku()

        setContent {
            VpadApp(
                onFrontStateUpdate = { newFront ->
                    frontState = newFront
                    backend.updateState(frontState.mergeWith(rearState))
                },
                onRearToggleChanged = { enabled, rearPlacements, editRear, selectedRear, snapGrid, onRearHaptic, onEditedFromRear ->
                    updateRearCoverDisplay(
                        enabled = enabled,
                        rearPlacements = rearPlacements,
                        editRear = editRear,
                        selectedRear = selectedRear,
                        snapGrid = snapGrid,
                        onRearButtonPress = onRearHaptic,
                        onEditedFromRear = onEditedFromRear
                    )
                },
                onRearConfigSync = { rearPlacements, editRear, selectedRear, snapGrid ->
                    rearController?.syncConfig(rearPlacements, editRear, selectedRear, snapGrid)
                }
            )
        }
    }

    private fun updateRearCoverDisplay(
        enabled: Boolean,
        rearPlacements: Map<ControlId, ControlPlacement>,
        editRear: Boolean,
        selectedRear: ControlId?,
        snapGrid: Boolean,
        onRearButtonPress: () -> Unit,
        onEditedFromRear: (Map<ControlId, ControlPlacement>, ControlId?) -> Unit
    ) {
        rearController?.dismiss()
        rearController = null
        rearState = GamepadState()

        if (!enabled) {
            backend.setRearDisplayPower(this, false) {}
            return
        }

        backend.setRearDisplayPower(this, true) { helperOutput ->
            runOnUiThread {
                val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val allDisplays: Array<Display> =
                    dm.getDisplays("android.hardware.display.category.ALL_INCLUDING_DISABLED")
                        .ifEmpty { dm.displays }

                val currentDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
                val coverDisplay = allDisplays.firstOrNull { it.displayId != currentDisplayId }

                if (coverDisplay != null) {
                    try {
                        rearController = RearCoverController(
                            baseContext = this,
                            coverDisplay = coverDisplay,
                            onRearStateChanged = { newRear ->
                                if (didPressNewButton(rearState, newRear)) {
                                    onRearButtonPress()
                                }
                                rearState = newRear
                                backend.updateState(frontState.mergeWith(rearState))
                            },
                            onRearPlacementsEdited = { updatedMap, sel ->
                                runOnUiThread {
                                    onEditedFromRear(updatedMap, sel)
                                }
                            }
                        ).also {
                            it.show(rearPlacements, editRear, selectedRear, snapGrid)
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            this,
                            "Rear Display error: ${e.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    android.widget.Toast.makeText(
                        this,
                        "No secondary display exposed by ROM ($helperOutput)",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        rearController?.dismiss()
        rearController = null
        backend.setRearDisplayPower(this, false) {}
        super.onDestroy()
    }
}

fun defaultPlacements(): Map<ControlId, ControlPlacement> = mapOf(
    ControlId.L2 to ControlPlacement(0.15f, 0.20f, 1.0f, false),
    ControlId.L1 to ControlPlacement(0.15f, 0.35f, 1.0f, false),
    ControlId.R2 to ControlPlacement(0.85f, 0.20f, 1.0f, false),
    ControlId.R1 to ControlPlacement(0.85f, 0.35f, 1.0f, false),
    ControlId.DPAD to ControlPlacement(0.20f, 0.60f, 1.0f, false),
    ControlId.ABXY to ControlPlacement(0.80f, 0.60f, 1.0f, false),
    ControlId.LEFT_STICK to ControlPlacement(0.35f, 0.80f, 1.0f, false),
    ControlId.RIGHT_STICK to ControlPlacement(0.65f, 0.80f, 1.0f, false),
    ControlId.L3 to ControlPlacement(0.20f, 0.90f, 0.85f, false),
    ControlId.R3 to ControlPlacement(0.80f, 0.90f, 0.85f, false),
    ControlId.SELECT to ControlPlacement(0.40f, 0.50f, 1.0f, false),
    ControlId.HOME to ControlPlacement(0.50f, 0.50f, 1.0f, false),
    ControlId.START to ControlPlacement(0.60f, 0.50f, 1.0f, false)
)

@Composable
fun VpadApp(
    onFrontStateUpdate: (GamepadState) -> Unit,
    onRearToggleChanged: (
        Boolean,
        Map<ControlId, ControlPlacement>,
        Boolean,
        ControlId?,
        Boolean,
        () -> Unit,
        (Map<ControlId, ControlPlacement>, ControlId?) -> Unit
    ) -> Unit,
    onRearConfigSync: (Map<ControlId, ControlPlacement>, Boolean, ControlId?, Boolean) -> Unit
) {
    val context = LocalContext.current
    val textMeasurer = rememberTextMeasurer()
    val prefs = remember { context.getSharedPreferences("vpad_layout", Context.MODE_PRIVATE) }

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    var editMode by remember { mutableStateOf(false) }
    var editingRear by remember { mutableStateOf(false) } // false = TARGET: FRONT, true = TARGET: REAR
    var selectedControl by remember { mutableStateOf<ControlId?>(null) }
    var placements by remember { mutableStateOf(loadPlacements(prefs)) }
    var rearPlacements by remember { mutableStateOf(loadRearPlacements(prefs)) }

    var vibIntensity by remember {
        mutableStateOf(VibIntensity.fromLevel(prefs.getInt("vpad_vib_level", VibIntensity.MED.level)))
    }
    var snapGrid by remember {
        mutableStateOf(prefs.getBoolean("vpad_snap_grid", false))
    }
    var rearCoverEnabled by remember {
        mutableStateOf(prefs.getBoolean("vpad_rear_cover", false))
    }

    // Active map being edited when in Edit Mode (Front vs Rear)
    val activeEditMap = if (editingRear) rearPlacements else placements

    fun updateActiveEditMap(updated: Map<ControlId, ControlPlacement>) {
        if (editingRear) {
            rearPlacements = updated
            saveRearPlacements(prefs, updated)
        } else {
            placements = updated
            savePlacements(prefs, updated)
        }
    }

    // Attach / detach rear display when rearCoverEnabled changes
    LaunchedEffect(rearCoverEnabled, vibIntensity) {
        onRearToggleChanged(
            rearCoverEnabled,
            rearPlacements,
            editMode && editingRear,
            selectedControl,
            snapGrid,
            { triggerVibration(vibrator, vibIntensity) },
            { updatedFromRear, selFromRear ->
                rearPlacements = updatedFromRear
                selectedControl = selFromRear
                saveRearPlacements(prefs, updatedFromRear)
            }
        )
    }

    // Live-sync rear layout / edit state to the cover display
    LaunchedEffect(rearPlacements, editMode, editingRear, selectedControl, snapGrid) {
        if (rearCoverEnabled) {
            onRearConfigSync(rearPlacements, editMode && editingRear, selectedControl, snapGrid)
        }
    }

    var gamepadState by remember { mutableStateOf(GamepadState()) }
    var leftStickKnob by remember { mutableStateOf(Offset.Zero) }
    var rightStickKnob by remember { mutableStateOf(Offset.Zero) }

    val gridStep = 0.05f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Pure #000000 OLED Black for battery life
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val baseUnit = minOf(widthPx, heightPx) * 0.16f

        val touchModifier = if (editMode) {
            Modifier.pointerInput(snapGrid, editingRear) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val currentMap = if (editingRear) rearPlacements else placements
                    val hit = findNearestControl(down.position, currentMap, widthPx, heightPx, baseUnit)
                    selectedControl = hit
                    val startPlacement = hit?.let { currentMap[it] }
                    var totalDrag = Offset.Zero

                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.isEmpty()) break

                        val liveMap = if (editingRear) rearPlacements else placements

                        if (active.size >= 2 && hit != null) {
                            val p0 = active[0].position
                            val p1 = active[1].position
                            val prev0 = active[0].previousPosition
                            val prev1 = active[1].previousPosition
                            val curDist = hypot(p0.x - p1.x, p0.y - p1.y)
                            val prevDist = hypot(prev0.x - prev1.x, prev0.y - prev1.y)
                            if (prevDist > 10f) {
                                val zoom = curDist / prevDist
                                val cur = liveMap[hit]
                                if (cur != null) {
                                    val updated = cur.copy(
                                        scale = (cur.scale * zoom).coerceIn(0.45f, 2.2f)
                                    )
                                    updateActiveEditMap(liveMap.toMutableMap().apply { put(hit, updated) })
                                }
                            }
                        } else if (hit != null && startPlacement != null) {
                            val pointer = active.firstOrNull { it.id == down.id } ?: active.first()
                            val delta = pointer.position - pointer.previousPosition
                            totalDrag += delta

                            val rawX = ((startPlacement.normX * widthPx + totalDrag.x) / widthPx).coerceIn(0.05f, 0.95f)
                            val rawY = ((startPlacement.normY * heightPx + totalDrag.y) / heightPx).coerceIn(0.05f, 0.95f)

                            val finalX = if (snapGrid) {
                                ((rawX / gridStep).roundToInt() * gridStep).coerceIn(0.05f, 0.95f)
                            } else rawX
                            val finalY = if (snapGrid) {
                                ((rawY / gridStep).roundToInt() * gridStep).coerceIn(0.05f, 0.95f)
                            } else rawY

                            val cur = liveMap[hit]
                            if (cur != null) {
                                val updated = cur.copy(normX = finalX, normY = finalY)
                                updateActiveEditMap(liveMap.toMutableMap().apply { put(hit, updated) })
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        } else {
            Modifier.pointerInput(placements, vibIntensity) {
                val stickPointers = mutableMapOf<PointerId, ControlId>()
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        val activePoints = event.changes.filter { it.pressed }

                        var newState = GamepadState()
                        var lKnob = Offset.Zero
                        var rKnob = Offset.Zero

                        val activeIds = activePoints.map { it.id }.toSet()
                        stickPointers.keys.retainAll(activeIds)

                        for (change in activePoints) {
                            val pos = change.position
                            val assignedStick = stickPointers[change.id]

                            val lsPlace = placements[ControlId.LEFT_STICK]
                            if (lsPlace != null && !lsPlace.hidden) {
                                val lsCenter = Offset(lsPlace.normX * widthPx, lsPlace.normY * heightPx)
                                val lsRadius = baseUnit * 1.15f * lsPlace.scale
                                if (assignedStick == ControlId.LEFT_STICK ||
                                    (assignedStick == null && hypot(pos.x - lsCenter.x, pos.y - lsCenter.y) <= lsRadius)
                                ) {
                                    stickPointers[change.id] = ControlId.LEFT_STICK
                                    val dx = pos.x - lsCenter.x
                                    val dy = pos.y - lsCenter.y
                                    val clampedDist = minOf(hypot(dx, dy), lsRadius)
                                    val angle = atan2(dy, dx)
                                    lKnob = Offset(cos(angle) * clampedDist, sin(angle) * clampedDist)
                                    newState = newState.copy(
                                        lx = (lKnob.x / lsRadius).coerceIn(-1f, 1f),
                                        ly = (lKnob.y / lsRadius).coerceIn(-1f, 1f)
                                    )
                                    continue
                                }
                            }

                            val rsPlace = placements[ControlId.RIGHT_STICK]
                            if (rsPlace != null && !rsPlace.hidden) {
                                val rsCenter = Offset(rsPlace.normX * widthPx, rsPlace.normY * heightPx)
                                val rsRadius = baseUnit * 1.15f * rsPlace.scale
                                if (assignedStick == ControlId.RIGHT_STICK ||
                                    (assignedStick == null && hypot(pos.x - rsCenter.x, pos.y - rsCenter.y) <= rsRadius)
                                ) {
                                    stickPointers[change.id] = ControlId.RIGHT_STICK
                                    val dx = pos.x - rsCenter.x
                                    val dy = pos.y - rsCenter.y
                                    val clampedDist = minOf(hypot(dx, dy), rsRadius)
                                    val angle = atan2(dy, dx)
                                    rKnob = Offset(cos(angle) * clampedDist, sin(angle) * clampedDist)
                                    newState = newState.copy(
                                        rx = (rKnob.x / rsRadius).coerceIn(-1f, 1f),
                                        ry = (rKnob.y / rsRadius).coerceIn(-1f, 1f)
                                    )
                                    continue
                                }
                            }

                            val dpadPlace = placements[ControlId.DPAD]
                            if (dpadPlace != null && !dpadPlace.hidden) {
                                val dpadCenter = Offset(dpadPlace.normX * widthPx, dpadPlace.normY * heightPx)
                                val dpadRadius = baseUnit * 1.25f * dpadPlace.scale
                                val dDist = hypot(pos.x - dpadCenter.x, pos.y - dpadCenter.y)
                                if (dDist in (dpadRadius * 0.18f)..(dpadRadius * 1.15f)) {
                                    val deg = Math.toDegrees(
                                        atan2((pos.y - dpadCenter.y).toDouble(), (pos.x - dpadCenter.x).toDouble())
                                    )
                                    newState = newState.copy(
                                        dpadUp = newState.dpadUp || (deg in -150.0..-30.0),
                                        dpadDown = newState.dpadDown || (deg in 30.0..150.0),
                                        dpadLeft = newState.dpadLeft || (deg >= 120.0 || deg <= -120.0),
                                        dpadRight = newState.dpadRight || (deg in -60.0..60.0)
                                    )
                                    continue
                                }
                            }

                            val abxyPlace = placements[ControlId.ABXY]
                            if (abxyPlace != null && !abxyPlace.hidden) {
                                val abxyCenter = Offset(abxyPlace.normX * widthPx, abxyPlace.normY * heightPx)
                                val btnOffset = baseUnit * 0.72f * abxyPlace.scale
                                val btnHitRadius = baseUnit * 0.48f * abxyPlace.scale

                                if (hypot(pos.x - abxyCenter.x, pos.y - (abxyCenter.y - btnOffset)) <= btnHitRadius)
                                    newState = newState.copy(btnTriangle = true)
                                if (hypot(pos.x - (abxyCenter.x + btnOffset), pos.y - abxyCenter.y) <= btnHitRadius)
                                    newState = newState.copy(btnCircle = true)
                                if (hypot(pos.x - abxyCenter.x, pos.y - (abxyCenter.y + btnOffset)) <= btnHitRadius)
                                    newState = newState.copy(btnCross = true)
                                if (hypot(pos.x - (abxyCenter.x - btnOffset), pos.y - abxyCenter.y) <= btnHitRadius)
                                    newState = newState.copy(btnSquare = true)
                            }

                            if (hitPill(pos, ControlId.L1, placements, widthPx, heightPx, baseUnit)) newState = newState.copy(l1 = true)
                            if (hitPill(pos, ControlId.R1, placements, widthPx, heightPx, baseUnit)) newState = newState.copy(r1 = true)
                            if (hitPill(pos, ControlId.L2, placements, widthPx, heightPx, baseUnit)) newState = newState.copy(l2 = 1f)
                            if (hitPill(pos, ControlId.R2, placements, widthPx, heightPx, baseUnit)) newState = newState.copy(r2 = 1f)
                            if (hitCircle(pos, ControlId.L3, placements, widthPx, heightPx, baseUnit * 0.42f)) newState = newState.copy(l3 = true)
                            if (hitCircle(pos, ControlId.R3, placements, widthPx, heightPx, baseUnit * 0.42f)) newState = newState.copy(r3 = true)
                            if (hitPill(pos, ControlId.SELECT, placements, widthPx, heightPx, baseUnit * 0.65f)) newState = newState.copy(select = true)
                            if (hitPill(pos, ControlId.START, placements, widthPx, heightPx, baseUnit * 0.65f)) newState = newState.copy(start = true)
                            if (hitCircle(pos, ControlId.HOME, placements, widthPx, heightPx, baseUnit * 0.45f)) newState = newState.copy(home = true)
                        }

                        if (newState != gamepadState) {
                            if (vibIntensity != VibIntensity.OFF && didPressNewButton(gamepadState, newState)) {
                                triggerVibration(vibrator, vibIntensity)
                            }
                            gamepadState = newState
                            onFrontStateUpdate(newState)
                        }
                        leftStickKnob = lKnob
                        rightStickKnob = rKnob
                    }
                }
            }
        }

        // Full-screen Gamepad Canvas (Pure Black)
        Canvas(modifier = Modifier.fillMaxSize().then(touchModifier)) {
            if (editMode && snapGrid) {
                drawSnapGrid(widthPx, heightPx, gridStep)
            }

            if (editMode && editingRear) {
                // Preview & edit Rear Cover Display pads right from the front screen (or rear screen)
                val boxW = widthPx * 0.42f
                val boxH = heightPx * 0.24f
                for (id in RearControlIds) {
                    val p = rearPlacements[id] ?: continue
                    val w = boxW * p.scale
                    val h = boxH * p.scale
                    val center = Offset(p.normX * widthPx, p.normY * heightPx)
                    val topLeft = Offset(center.x - w / 2f, center.y - h / 2f)
                    val a = alphaFactor(p.hidden)
                    val isSel = selectedControl == id

                    // Pure outline + text over black
                    drawRoundRect(
                        color = (if (isSel) StrokeSelected else StrokeNormal).copy(alpha = (if (isSel) 1f else 0.8f) * a),
                        topLeft = topLeft,
                        size = Size(w, h),
                        cornerRadius = CornerRadius(28f * p.scale, 28f * p.scale),
                        style = Stroke(width = if (isSel) 6f else 3.5f)
                    )

                    val textLayout = textMeasurer.measure(
                        text = id.name,
                        style = TextStyle(
                            color = Color.White.copy(alpha = a),
                            fontSize = (15f * p.scale).sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    drawText(
                        textLayout,
                        topLeft = Offset(center.x - textLayout.size.width / 2f, center.y - textLayout.size.height / 2f)
                    )
                }
            } else {
                // Front Screen Controls
                placements[ControlId.DPAD]?.let { p ->
                    if (!p.hidden || editMode) {
                        drawPSDpad(
                            center = Offset(p.normX * widthPx, p.normY * heightPx),
                            scale = p.scale,
                            baseUnit = baseUnit,
                            state = gamepadState,
                            isSelected = selectedControl == ControlId.DPAD && editMode,
                            isHiddenGhost = p.hidden
                        )
                    }
                }
                placements[ControlId.ABXY]?.let { p ->
                    if (!p.hidden || editMode) {
                        drawPSFaceCluster(
                            center = Offset(p.normX * widthPx, p.normY * heightPx),
                            scale = p.scale,
                            baseUnit = baseUnit,
                            state = gamepadState,
                            isSelected = selectedControl == ControlId.ABXY && editMode,
                            isHiddenGhost = p.hidden
                        )
                    }
                }
                placements[ControlId.LEFT_STICK]?.let { p ->
                    if (!p.hidden || editMode) {
                        drawPSStick(
                            center = Offset(p.normX * widthPx, p.normY * heightPx),
                            knobOffset = leftStickKnob,
                            scale = p.scale,
                            baseUnit = baseUnit,
                            isSelected = selectedControl == ControlId.LEFT_STICK && editMode,
                            isHiddenGhost = p.hidden
                        )
                    }
                }
                placements[ControlId.RIGHT_STICK]?.let { p ->
                    if (!p.hidden || editMode) {
                        drawPSStick(
                            center = Offset(p.normX * widthPx, p.normY * heightPx),
                            knobOffset = rightStickKnob,
                            scale = p.scale,
                            baseUnit = baseUnit,
                            isSelected = selectedControl == ControlId.RIGHT_STICK && editMode,
                            isHiddenGhost = p.hidden
                        )
                    }
                }

                drawPillIfVisible(textMeasurer, "L2", ControlId.L2, placements, widthPx, heightPx, baseUnit, 1f, gamepadState.l2 > 0.5f, selectedControl, editMode)
                drawPillIfVisible(textMeasurer, "L1", ControlId.L1, placements, widthPx, heightPx, baseUnit, 1f, gamepadState.l1, selectedControl, editMode)
                drawPillIfVisible(textMeasurer, "R2", ControlId.R2, placements, widthPx, heightPx, baseUnit, 1f, gamepadState.r2 > 0.5f, selectedControl, editMode)
                drawPillIfVisible(textMeasurer, "R1", ControlId.R1, placements, widthPx, heightPx, baseUnit, 1f, gamepadState.r1, selectedControl, editMode)

                drawCircleIfVisible(textMeasurer, "L3", ControlId.L3, placements, widthPx, heightPx, baseUnit * 0.38f, gamepadState.l3, selectedControl, editMode)
                drawCircleIfVisible(textMeasurer, "R3", ControlId.R3, placements, widthPx, heightPx, baseUnit * 0.38f, gamepadState.r3, selectedControl, editMode)

                drawPillIfVisible(textMeasurer, "SELECT", ControlId.SELECT, placements, widthPx, heightPx, baseUnit, 0.72f, gamepadState.select, selectedControl, editMode)
                drawPillIfVisible(textMeasurer, "START", ControlId.START, placements, widthPx, heightPx, baseUnit, 0.72f, gamepadState.start, selectedControl, editMode)

                placements[ControlId.HOME]?.let { p ->
                    if (!p.hidden || editMode) {
                        drawHomeButton(
                            center = Offset(p.normX * widthPx, p.normY * heightPx),
                            scale = p.scale,
                            radius = baseUnit * 0.42f,
                            pressed = gamepadState.home,
                            isSelected = selectedControl == ControlId.HOME && editMode,
                            isHiddenGhost = p.hidden
                        )
                    }
                }
            }
        }

        // Floating Normal Buttons (Only EDIT shows in normal mode; controls appear in Edit Mode)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = if (editMode) Arrangement.SpaceBetween else Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (editMode) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FloatingButton(
                        text = if (rearCoverEnabled) "REAR: ON" else "REAR: OFF",
                        inverted = rearCoverEnabled,
                        onClick = {
                            rearCoverEnabled = !rearCoverEnabled
                            if (!rearCoverEnabled) editingRear = false
                            prefs.edit().putBoolean("vpad_rear_cover", rearCoverEnabled).apply()
                        }
                    )
                    if (rearCoverEnabled) {
                        FloatingButton(
                            text = if (editingRear) "TARGET: REAR" else "TARGET: FRONT",
                            inverted = editingRear,
                            onClick = {
                                editingRear = !editingRear
                                selectedControl = null
                            }
                        )
                    }
                    FloatingButton(
                        text = if (snapGrid) "GRID: ON" else "GRID: OFF",
                        inverted = snapGrid,
                        onClick = {
                            snapGrid = !snapGrid
                            prefs.edit().putBoolean("vpad_snap_grid", snapGrid).apply()
                        }
                    )
                    FloatingButton(
                        text = vibIntensity.label,
                        inverted = vibIntensity != VibIntensity.OFF,
                        onClick = {
                            val next = vibIntensity.next()
                            vibIntensity = next
                            prefs.edit().putInt("vpad_vib_level", next.level).apply()
                            if (next != VibIntensity.OFF) {
                                triggerVibration(vibrator, next)
                            }
                        }
                    )
                    selectedControl?.let { sel ->
                        val isHidden = activeEditMap[sel]?.hidden == true
                        FloatingButton(
                            text = "-",
                            onClick = {
                                val cur = activeEditMap[sel] ?: return@FloatingButton
                                updateActiveEditMap(
                                    activeEditMap.toMutableMap().apply {
                                        put(sel, cur.copy(scale = (cur.scale - 0.1f).coerceIn(0.45f, 2.2f)))
                                    }
                                )
                            }
                        )
                        FloatingButton(
                            text = "+",
                            onClick = {
                                val cur = activeEditMap[sel] ?: return@FloatingButton
                                updateActiveEditMap(
                                    activeEditMap.toMutableMap().apply {
                                        put(sel, cur.copy(scale = (cur.scale + 0.1f).coerceIn(0.45f, 2.2f)))
                                    }
                                )
                            }
                        )
                        FloatingButton(
                            text = if (isHidden) "SHOW" else "HIDE",
                            inverted = isHidden,
                            onClick = {
                                val cur = activeEditMap[sel] ?: return@FloatingButton
                                updateActiveEditMap(
                                    activeEditMap.toMutableMap().apply {
                                        put(sel, cur.copy(hidden = !cur.hidden))
                                    }
                                )
                            }
                        )
                    }
                    FloatingButton(
                        text = "RESET",
                        onClick = {
                            if (editingRear) {
                                updateActiveEditMap(defaultRearPlacements())
                            } else {
                                updateActiveEditMap(defaultPlacements())
                            }
                        }
                    )
                }
            }
            FloatingButton(
                text = if (editMode) "DONE" else "EDIT",
                inverted = editMode,
                onClick = {
                    editMode = !editMode
                    if (!editMode) {
                        editingRear = false
                        selectedControl = null
                    }
                }
            )
        }
    }
}

fun triggerVibration(vibrator: Vibrator, intensity: VibIntensity) {
    if (intensity == VibIntensity.OFF || !vibrator.hasVibrator()) return
    try {
        vibrator.vibrate(
            VibrationEffect.createOneShot(intensity.durationMs, intensity.amplitude)
        )
    } catch (_: Exception) {
    }
}

fun didPressNewButton(old: GamepadState, cur: GamepadState): Boolean {
    return (!old.btnCross && cur.btnCross) ||
            (!old.btnCircle && cur.btnCircle) ||
            (!old.btnSquare && cur.btnSquare) ||
            (!old.btnTriangle && cur.btnTriangle) ||
            (!old.l1 && cur.l1) ||
            (!old.r1 && cur.r1) ||
            (old.l2 == 0f && cur.l2 > 0f) ||
            (old.r2 == 0f && cur.r2 > 0f) ||
            (!old.l3 && cur.l3) ||
            (!old.r3 && cur.r3) ||
            (!old.select && cur.select) ||
            (!old.start && cur.start) ||
            (!old.home && cur.home) ||
            (!old.dpadUp && cur.dpadUp) ||
            (!old.dpadDown && cur.dpadDown) ||
            (!old.dpadLeft && cur.dpadLeft) ||
            (!old.dpadRight && cur.dpadRight)
}

@Composable
private fun FloatingButton(
    text: String,
    inverted: Boolean = false,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = if (inverted) Color.Black else Color(0xFFE4E4E7),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(
                color = if (inverted) Color.White else Color.Black,
                shape = RoundedCornerShape(8.dp)
            )
            .border(1.dp, Color(0x88FFFFFF), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 6.dp)
    )
}

// --- Pure Outline + Text on OLED Black Vector Drawing Helpers ---

private val FillPressed = Color(0xAAFFFFFF)
private val StrokeNormal = Color(0xCCFFFFFF)
private val StrokeSelected = Color(0xFFFFFFFF)

private fun alphaFactor(isHiddenGhost: Boolean): Float = if (isHiddenGhost) 0.25f else 1f

private fun DrawScope.drawSnapGrid(w: Float, h: Float, step: Float) {
    val steps = (1f / step).roundToInt()
    for (i in 1 until steps) {
        val frac = i * step
        val isMajor = (i % 5 == 0)
        val lineColor = if (isMajor) Color(0x44FFFFFF) else Color(0x1AFFFFFF)
        val strokeW = if (isMajor) 2f else 1f

        val x = w * frac
        drawLine(lineColor, Offset(x, 0f), Offset(x, h), strokeWidth = strokeW)

        val y = h * frac
        drawLine(lineColor, Offset(0f, y), Offset(w, y), strokeWidth = strokeW)
    }
}

private fun DrawScope.drawPillIfVisible(
    textMeasurer: TextMeasurer, label: String, id: ControlId,
    placements: Map<ControlId, ControlPlacement>, w: Float, h: Float,
    baseUnit: Float, scaleMult: Float, pressed: Boolean,
    selected: ControlId?, editMode: Boolean
) {
    val p = placements[id] ?: return
    if (p.hidden && !editMode) return
    drawPSPillButton(
        textMeasurer = textMeasurer,
        label = label,
        center = Offset(p.normX * w, p.normY * h),
        scale = p.scale * scaleMult,
        baseUnit = baseUnit,
        pressed = pressed,
        isSelected = selected == id && editMode,
        isHiddenGhost = p.hidden
    )
}

private fun DrawScope.drawCircleIfVisible(
    textMeasurer: TextMeasurer, label: String, id: ControlId,
    placements: Map<ControlId, ControlPlacement>, w: Float, h: Float,
    radius: Float, pressed: Boolean, selected: ControlId?, editMode: Boolean
) {
    val p = placements[id] ?: return
    if (p.hidden && !editMode) return
    drawPSCircleButton(
        textMeasurer = textMeasurer,
        label = label,
        center = Offset(p.normX * w, p.normY * h),
        scale = p.scale,
        radius = radius,
        pressed = pressed,
        isSelected = selected == id && editMode,
        isHiddenGhost = p.hidden
    )
}

private fun DrawScope.drawPSFaceCluster(
    center: Offset, scale: Float, baseUnit: Float, state: GamepadState,
    isSelected: Boolean, isHiddenGhost: Boolean
) {
    val a = alphaFactor(isHiddenGhost)
    val dist = baseUnit * 0.72f * scale
    val r = baseUnit * 0.38f * scale
    val strokeW = 3.5f * scale

    if (isSelected) {
        drawCircle(Color(0x55FFFFFF), radius = dist + r * 1.25f, center = center, style = Stroke(width = 4f))
    }

    val top = Offset(center.x, center.y - dist)
    drawButtonBase(top, r, state.btnTriangle, strokeW, a)
    val triR = r * 0.48f
    val triPath = Path().apply {
        moveTo(top.x, top.y - triR)
        lineTo(top.x - triR * 0.9f, top.y + triR * 0.75f)
        lineTo(top.x + triR * 0.9f, top.y + triR * 0.75f)
        close()
    }
    drawPath(triPath, color = (if (state.btnTriangle) Color.Black else StrokeNormal).copy(alpha = a), style = Stroke(width = strokeW * 1.1f))

    val right = Offset(center.x + dist, center.y)
    drawButtonBase(right, r, state.btnCircle, strokeW, a)
    drawCircle(
        color = (if (state.btnCircle) Color.Black else StrokeNormal).copy(alpha = a),
        radius = r * 0.45f,
        center = right,
        style = Stroke(width = strokeW * 1.1f)
    )

    val bottom = Offset(center.x, center.y + dist)
    drawButtonBase(bottom, r, state.btnCross, strokeW, a)
    val xR = r * 0.38f
    val xColor = (if (state.btnCross) Color.Black else StrokeNormal).copy(alpha = a)
    drawLine(xColor, Offset(bottom.x - xR, bottom.y - xR), Offset(bottom.x + xR, bottom.y + xR), strokeWidth = strokeW * 1.15f)
    drawLine(xColor, Offset(bottom.x + xR, bottom.y - xR), Offset(bottom.x - xR, bottom.y + xR), strokeWidth = strokeW * 1.15f)

    val left = Offset(center.x - dist, center.y)
    drawButtonBase(left, r, state.btnSquare, strokeW, a)
    val sqR = r * 0.40f
    drawRect(
        color = (if (state.btnSquare) Color.Black else StrokeNormal).copy(alpha = a),
        topLeft = Offset(left.x - sqR, left.y - sqR),
        size = Size(sqR * 2, sqR * 2),
        style = Stroke(width = strokeW * 1.1f)
    )
}

private fun DrawScope.drawButtonBase(center: Offset, radius: Float, pressed: Boolean, strokeW: Float, alpha: Float) {
    // Only fill when pressed; unpressed buttons are pure outlines on #000000 black
    if (pressed) {
        drawCircle(color = FillPressed.copy(alpha = 0.67f * alpha), radius = radius, center = center)
    }
    drawCircle(color = StrokeNormal.copy(alpha = 0.8f * alpha), radius = radius, center = center, style = Stroke(width = strokeW))
}

private fun DrawScope.drawPSDpad(
    center: Offset, scale: Float, baseUnit: Float, state: GamepadState,
    isSelected: Boolean, isHiddenGhost: Boolean
) {
    val a = alphaFactor(isHiddenGhost)
    val armLen = baseUnit * 0.95f * scale
    val armHalfW = baseUnit * 0.30f * scale
    val innerGap = baseUnit * 0.16f * scale
    val cornerR = baseUnit * 0.11f * scale
    val strokeW = 3.5f * scale

    if (isSelected) {
        drawCircle(Color(0x55FFFFFF), radius = armLen * 1.18f, center = center, style = Stroke(width = 4f))
    }

    val dirs = listOf(
        Pair(state.dpadRight, Offset(1f, 0f)),
        Pair(state.dpadDown, Offset(0f, 1f)),
        Pair(state.dpadLeft, Offset(-1f, 0f)),
        Pair(state.dpadUp, Offset(0f, -1f))
    )

    for ((pressed, dir) in dirs) {
        val perp = Offset(-dir.y, dir.x)
        val pTip = center + dir * innerGap
        val pInnerL = center + dir * (innerGap + armHalfW) + perp * armHalfW
        val pOuterL = center + dir * armLen + perp * armHalfW
        val pOuterR = center + dir * armLen - perp * armHalfW
        val pInnerR = center + dir * (innerGap + armHalfW) - perp * armHalfW

        val pOuterLBefore = pOuterL - dir * cornerR
        val pOuterLAfter = pOuterL - perp * cornerR
        val pOuterRBefore = pOuterR + perp * cornerR
        val pOuterRAfter = pOuterR - dir * cornerR

        val path = Path().apply {
            moveTo(pTip.x, pTip.y)
            lineTo(pInnerL.x, pInnerL.y)
            lineTo(pOuterLBefore.x, pOuterLBefore.y)
            quadraticTo(pOuterL.x, pOuterL.y, pOuterLAfter.x, pOuterLAfter.y)
            lineTo(pOuterRBefore.x, pOuterRBefore.y)
            quadraticTo(pOuterR.x, pOuterR.y, pOuterRAfter.x, pOuterRAfter.y)
            lineTo(pInnerR.x, pInnerR.y)
            close()
        }

        if (pressed) {
            drawPath(path, color = FillPressed.copy(alpha = 0.67f * a))
        }
        drawPath(path, color = StrokeNormal.copy(alpha = 0.8f * a), style = Stroke(width = strokeW))
    }
}

private fun DrawScope.drawPSStick(
    center: Offset, knobOffset: Offset, scale: Float, baseUnit: Float,
    isSelected: Boolean, isHiddenGhost: Boolean
) {
    val a = alphaFactor(isHiddenGhost)
    val outerR = baseUnit * 0.95f * scale
    val knobR = baseUnit * 0.52f * scale

    drawCircle(
        color = (if (isSelected) StrokeSelected else Color(0x66FFFFFF)).copy(alpha = (if (isSelected) 1f else 0.4f) * a),
        radius = outerR,
        center = center,
        style = Stroke(width = if (isSelected) 5f else 2.5f)
    )

    val knobPos = center + knobOffset
    // Solid black fill so the thumbstick cap hides the outer ring cleanly when dragged over it
    drawCircle(color = Color.Black, radius = knobR, center = knobPos)
    drawCircle(color = StrokeNormal.copy(alpha = 0.8f * a), radius = knobR, center = knobPos, style = Stroke(width = 3.5f * scale))
}

private fun DrawScope.drawPSPillButton(
    textMeasurer: TextMeasurer, label: String, center: Offset, scale: Float,
    baseUnit: Float, pressed: Boolean, isSelected: Boolean, isHiddenGhost: Boolean
) {
    val a = alphaFactor(isHiddenGhost)
    val w = baseUnit * 1.35f * scale
    val h = baseUnit * 0.56f * scale
    val topLeft = Offset(center.x - w / 2f, center.y - h / 2f)

    if (pressed) {
        drawRoundRect(
            color = FillPressed.copy(alpha = 0.67f * a),
            topLeft = topLeft,
            size = Size(w, h),
            cornerRadius = CornerRadius(h / 2f, h / 2f)
        )
    }
    drawRoundRect(
        color = (if (isSelected) StrokeSelected else StrokeNormal).copy(alpha = (if (isSelected) 1f else 0.8f) * a),
        topLeft = topLeft,
        size = Size(w, h),
        cornerRadius = CornerRadius(h / 2f, h / 2f),
        style = Stroke(width = if (isSelected) 5f else 3f * scale)
    )

    val textLayout = textMeasurer.measure(
        text = label,
        style = TextStyle(
            color = (if (pressed) Color.Black else Color.White).copy(alpha = a),
            fontSize = (12f * scale).sp,
            fontWeight = FontWeight.Bold
        )
    )
    drawText(
        textLayout,
        topLeft = Offset(center.x - textLayout.size.width / 2f, center.y - textLayout.size.height / 2f)
    )
}

private fun DrawScope.drawPSCircleButton(
    textMeasurer: TextMeasurer, label: String, center: Offset, scale: Float,
    radius: Float, pressed: Boolean, isSelected: Boolean, isHiddenGhost: Boolean
) {
    val a = alphaFactor(isHiddenGhost)
    val r = radius * scale

    drawButtonBase(center, r, pressed, if (isSelected) 5f else 3f * scale, a)

    val textLayout = textMeasurer.measure(
        text = label,
        style = TextStyle(
            color = (if (pressed) Color.Black else Color.White).copy(alpha = a),
            fontSize = (11f * scale).sp,
            fontWeight = FontWeight.Bold
        )
    )
    drawText(
        textLayout,
        topLeft = Offset(center.x - textLayout.size.width / 2f, center.y - textLayout.size.height / 2f)
    )
}

private fun DrawScope.drawHomeButton(
    center: Offset, scale: Float, radius: Float, pressed: Boolean,
    isSelected: Boolean, isHiddenGhost: Boolean
) {
    val a = alphaFactor(isHiddenGhost)
    val r = radius * scale

    drawButtonBase(center, r, pressed, if (isSelected) 5f else 3f * scale, a)

    val iconColor = (if (pressed) Color.Black else StrokeNormal).copy(alpha = a)
    val s = r * 0.48f
    val path = Path().apply {
        moveTo(center.x - s, center.y - s * 0.1f)
        lineTo(center.x, center.y - s * 0.95f)
        lineTo(center.x + s, center.y - s * 0.1f)

        moveTo(center.x - s * 0.68f, center.y - s * 0.2f)
        lineTo(center.x - s * 0.68f, center.y + s * 0.85f)
        lineTo(center.x + s * 0.68f, center.y + s * 0.85f)
        lineTo(center.x + s * 0.68f, center.y - s * 0.2f)
    }
    drawPath(path, color = iconColor, style = Stroke(width = 3.2f * scale))
}

// --- Hit Testing & Persistence Helpers ---

private fun hitPill(pos: Offset, id: ControlId, map: Map<ControlId, ControlPlacement>, w: Float, h: Float, baseUnit: Float): Boolean {
    val p = map[id] ?: return false
    if (p.hidden) return false
    val c = Offset(p.normX * w, p.normY * h)
    val hw = baseUnit * 0.72f * p.scale
    val hh = baseUnit * 0.34f * p.scale
    return pos.x in (c.x - hw)..(c.x + hw) && pos.y in (c.y - hh)..(c.y + hh)
}

private fun hitCircle(pos: Offset, id: ControlId, map: Map<ControlId, ControlPlacement>, w: Float, h: Float, radius: Float): Boolean {
    val p = map[id] ?: return false
    if (p.hidden) return false
    val c = Offset(p.normX * w, p.normY * h)
    val r = radius * p.scale
    return hypot(pos.x - c.x, pos.y - c.y) <= r * 1.15f
}

private fun findNearestControl(pos: Offset, map: Map<ControlId, ControlPlacement>, w: Float, h: Float, baseUnit: Float): ControlId? {
    return map.entries.minByOrNull { (_, p) ->
        hypot(pos.x - p.normX * w, pos.y - p.normY * h)
    }?.takeIf { (_, p) ->
        hypot(pos.x - p.normX * w, pos.y - p.normY * h) <= baseUnit * 1.6f * p.scale
    }?.key
}

private fun savePlacements(prefs: SharedPreferences, map: Map<ControlId, ControlPlacement>) {
    prefs.edit().apply {
        map.forEach { (id, p) ->
            putFloat("${id.name}_x", p.normX)
            putFloat("${id.name}_y", p.normY)
            putFloat("${id.name}_s", p.scale)
            putBoolean("${id.name}_h", p.hidden)
        }
        apply()
    }
}

private fun loadPlacements(prefs: SharedPreferences): Map<ControlId, ControlPlacement> {
    val defaults = defaultPlacements()
    return defaults.mapValues { (id, def) ->
        ControlPlacement(
            normX = prefs.getFloat("${id.name}_x", def.normX),
            normY = prefs.getFloat("${id.name}_y", def.normY),
            scale = prefs.getFloat("${id.name}_s", def.scale),
            hidden = prefs.getBoolean("${id.name}_h", def.hidden)
        )
    }
}
