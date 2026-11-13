package com.fox.vpad

import android.app.Presentation
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.roundToInt

val RearControlIds = listOf(
    ControlId.L2, ControlId.L1, ControlId.L3,
    ControlId.R2, ControlId.R1, ControlId.R3
)

fun defaultRearPlacements(): Map<ControlId, ControlPlacement> = mapOf(
    // Left column on rear screen = Right hand fingers (R2, R1, R3)
    ControlId.R2 to ControlPlacement(0.25f, 0.20f, 1.0f, false),
    ControlId.R1 to ControlPlacement(0.25f, 0.50f, 1.0f, false),
    ControlId.R3 to ControlPlacement(0.25f, 0.80f, 1.0f, false),
    // Right column on rear screen = Left hand fingers (L2, L1, L3)
    ControlId.L2 to ControlPlacement(0.75f, 0.20f, 1.0f, false),
    ControlId.L1 to ControlPlacement(0.75f, 0.50f, 1.0f, false),
    ControlId.L3 to ControlPlacement(0.75f, 0.80f, 1.0f, false)
)

fun saveRearPlacements(prefs: SharedPreferences, map: Map<ControlId, ControlPlacement>) {
    prefs.edit().apply {
        map.forEach { (id, p) ->
            putFloat("rear_${id.name}_x", p.normX)
            putFloat("rear_${id.name}_y", p.normY)
            putFloat("rear_${id.name}_s", p.scale)
            putBoolean("rear_${id.name}_h", p.hidden)
        }
        apply()
    }
}

fun loadRearPlacements(prefs: SharedPreferences): Map<ControlId, ControlPlacement> {
    val defaults = defaultRearPlacements()
    return defaults.mapValues { (id, def) ->
        ControlPlacement(
            normX = prefs.getFloat("rear_${id.name}_x", def.normX),
            normY = prefs.getFloat("rear_${id.name}_y", def.normY),
            scale = prefs.getFloat("rear_${id.name}_s", def.scale),
            hidden = prefs.getBoolean("rear_${id.name}_h", def.hidden)
        )
    }
}

class RearCoverController(
    private val baseContext: Context,
    private val coverDisplay: Display,
    private val onRearStateChanged: (GamepadState) -> Unit,
    private val onRearPlacementsEdited: (Map<ControlId, ControlPlacement>, ControlId?) -> Unit
) {
    private var overlayWindowManager: WindowManager? = null
    private var padView: RearTriggerPadView? = null
    private var fallbackPresentation: Presentation? = null

    fun show(
        initialPlacements: Map<ControlId, ControlPlacement>,
        editMode: Boolean,
        selectedControl: ControlId?,
        snapGrid: Boolean
    ) {
        try {
            val dispContext = baseContext.createDisplayContext(coverDisplay)
            val winContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dispContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    null
                )
            } else {
                dispContext
            }

            val wm = winContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = RearTriggerPadView(
                winContext,
                initialPlacements,
                editMode,
                selectedControl,
                snapGrid,
                onRearStateChanged,
                onRearPlacementsEdited
            )

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            wm.addView(view, params)
            overlayWindowManager = wm
            padView = view
            return
        } catch (_: Throwable) {
        }

        val presentation = object : Presentation(baseContext, coverDisplay) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                window?.addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                )
                val view = RearTriggerPadView(
                    context,
                    initialPlacements,
                    editMode,
                    selectedControl,
                    snapGrid,
                    onRearStateChanged,
                    onRearPlacementsEdited
                )
                padView = view
                setContentView(view)
            }
        }
        presentation.show()
        fallbackPresentation = presentation
    }

    fun syncConfig(
        placements: Map<ControlId, ControlPlacement>,
        editMode: Boolean,
        selectedControl: ControlId?,
        snapGrid: Boolean
    ) {
        padView?.updateConfig(placements, editMode, selectedControl, snapGrid)
    }

    fun dismiss() {
        try {
            padView?.let { v ->
                overlayWindowManager?.removeViewImmediate(v)
            }
        } catch (_: Throwable) {
        }
        padView = null
        overlayWindowManager = null

        try {
            fallbackPresentation?.dismiss()
        } catch (_: Throwable) {
        }
        fallbackPresentation = null
    }
}

private class RearTriggerPadView(
    context: Context,
    private var placements: Map<ControlId, ControlPlacement>,
    private var editMode: Boolean,
    private var selectedControl: ControlId?,
    private var snapGrid: Boolean,
    private val onStateChanged: (GamepadState) -> Unit,
    private val onPlacementsEdited: (Map<ControlId, ControlPlacement>, ControlId?) -> Unit
) : View(context) {

    private var currentState = GamepadState()
    private var draggingId: ControlId? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val fillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val strokeNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(204, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
    }
    private val strokeSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val gridMinorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(26, 255, 255, 255)
        strokeWidth = 1f
    }
    private val gridMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        strokeWidth = 2f
    }
    private val textNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val textPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()
    private val gridStep = 0.05f

    fun updateConfig(
        newPlacements: Map<ControlId, ControlPlacement>,
        newEditMode: Boolean,
        newSelected: ControlId?,
        newSnapGrid: Boolean
    ) {
        placements = newPlacements
        editMode = newEditMode
        selectedControl = newSelected
        snapGrid = newSnapGrid
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val baseW = w * 0.42f
        val baseH = h * 0.24f

        if (editMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val x = event.x
                    val y = event.y
                    lastTouchX = x
                    lastTouchY = y
                    draggingId = placements.entries.minByOrNull { (_, p) ->
                        hypot(x - p.normX * w, y - p.normY * h)
                    }?.takeIf { (_, p) ->
                        hypot(x - p.normX * w, y - p.normY * h) <= maxOf(baseW, baseH) * 0.7f * p.scale
                    }?.key
                    selectedControl = draggingId
                    onPlacementsEdited(placements, selectedControl)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val hit = draggingId ?: return true
                    val cur = placements[hit] ?: return true
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y

                    val rawX = ((cur.normX * w + dx) / w).coerceIn(0.08f, 0.92f)
                    val rawY = ((cur.normY * h + dy) / h).coerceIn(0.08f, 0.92f)
                    val finalX = if (snapGrid) ((rawX / gridStep).roundToInt() * gridStep).coerceIn(0.08f, 0.92f) else rawX
                    val finalY = if (snapGrid) ((rawY / gridStep).roundToInt() * gridStep).coerceIn(0.08f, 0.92f) else rawY

                    val updated = cur.copy(normX = finalX, normY = finalY)
                    placements = placements.toMutableMap().apply { put(hit, updated) }
                    onPlacementsEdited(placements, selectedControl)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingId = null
                }
            }
            return true
        }

        // Gameplay Multi-touch Mode (ignores hidden rear controls)
        var l2 = 0f
        var l1 = false
        var l3 = false
        var r2 = 0f
        var r1 = false
        var r3 = false

        val action = event.actionMasked
        val upIndex = if (
            action == MotionEvent.ACTION_POINTER_UP ||
            action == MotionEvent.ACTION_UP ||
            action == MotionEvent.ACTION_CANCEL
        ) {
            event.actionIndex
        } else -1

        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until event.pointerCount) {
                if (i == upIndex) continue
                val x = event.getX(i)
                val y = event.getY(i)

                for (id in RearControlIds) {
                    val p = placements[id] ?: continue
                    if (p.hidden) continue

                    val halfW = (baseW * p.scale) * 0.5f
                    val halfH = (baseH * p.scale) * 0.5f
                    val cx = p.normX * w
                    val cy = p.normY * h

                    if (x in (cx - halfW)..(cx + halfW) && y in (cy - halfH)..(cy + halfH)) {
                        when (id) {
                            ControlId.L2 -> l2 = 1f
                            ControlId.L1 -> l1 = true
                            ControlId.L3 -> l3 = true
                            ControlId.R2 -> r2 = 1f
                            ControlId.R1 -> r1 = true
                            ControlId.R3 -> r3 = true
                            else -> {}
                        }
                    }
                }
            }
        }

        val next = GamepadState(l2 = l2, l1 = l1, l3 = l3, r2 = r2, r1 = r1, r3 = r3)
        if (next != currentState) {
            currentState = next
            onStateChanged(next)
            invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Pure #000000 OLED Black background for battery saving
        canvas.drawColor(Color.BLACK)

        val w = width.toFloat()
        val h = height.toFloat()
        val baseW = w * 0.42f
        val baseH = h * 0.24f

        if (editMode && snapGrid) {
            val steps = (1f / gridStep).roundToInt()
            for (i in 1 until steps) {
                val frac = i * gridStep
                val paint = if (i % 5 == 0) gridMajorPaint else gridMinorPaint
                canvas.drawLine(w * frac, 0f, w * frac, h, paint)
                canvas.drawLine(0f, h * frac, w, h * frac, paint)
            }
        }

        for (id in RearControlIds) {
            val p = placements[id] ?: continue
            if (p.hidden && !editMode) continue

            val halfW = (baseW * p.scale) * 0.5f
            val halfH = (baseH * p.scale) * 0.5f
            val cx = p.normX * w
            val cy = p.normY * h

            val pressed = when (id) {
                ControlId.L2 -> currentState.l2 > 0.5f
                ControlId.L1 -> currentState.l1
                ControlId.L3 -> currentState.l3
                ControlId.R2 -> currentState.r2 > 0.5f
                ControlId.R1 -> currentState.r1
                ControlId.R3 -> currentState.r3
                else -> false
            }

            val alphaMult = if (p.hidden) 0.25f else 1f
            rect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
            val cornerR = 36f * p.scale

            strokeNormal.alpha = (204 * alphaMult).toInt()
            textNormal.alpha = (255 * alphaMult).toInt()
            textNormal.textSize = 40f * p.scale
            textPressed.textSize = 40f * p.scale

            // Unpressed buttons are pure outline + text over #000000; only fill while pressed
            if (pressed) {
                canvas.drawRoundRect(rect, cornerR, cornerR, fillPressed)
            }
            val borderPaint = if (editMode && selectedControl == id) strokeSelected else strokeNormal
            canvas.drawRoundRect(rect, cornerR, cornerR, borderPaint)

            val textY = cy - (textNormal.descent() + textNormal.ascent()) * 0.5f
            canvas.drawText(id.name, cx, textY, if (pressed) textPressed else textNormal)
        }
    }
}
