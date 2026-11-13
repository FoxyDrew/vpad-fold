package com.fox.vpad

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import kotlin.math.abs

data class GamepadState(
    val lx: Float = 0f, val ly: Float = 0f,
    val rx: Float = 0f, val ry: Float = 0f,
    val l2: Float = 0f, val r2: Float = 0f,
    val dpadUp: Boolean = false, val dpadDown: Boolean = false,
    val dpadLeft: Boolean = false, val dpadRight: Boolean = false,
    val btnCross: Boolean = false,
    val btnCircle: Boolean = false,
    val btnSquare: Boolean = false,
    val btnTriangle: Boolean = false,
    val l1: Boolean = false, val r1: Boolean = false,
    val l3: Boolean = false, val r3: Boolean = false,
    val select: Boolean = false, val start: Boolean = false,
    val home: Boolean = false
) {
    fun mergeWith(other: GamepadState): GamepadState = GamepadState(
        lx = if (abs(other.lx) > abs(lx)) other.lx else lx,
        ly = if (abs(other.ly) > abs(ly)) other.ly else ly,
        rx = if (abs(other.rx) > abs(rx)) other.rx else rx,
        ry = if (abs(other.ry) > abs(ry)) other.ry else ry,
        l2 = maxOf(l2, other.l2),
        r2 = maxOf(r2, other.r2),
        dpadUp = dpadUp || other.dpadUp,
        dpadDown = dpadDown || other.dpadDown,
        dpadLeft = dpadLeft || other.dpadLeft,
        dpadRight = dpadRight || other.dpadRight,
        btnCross = btnCross || other.btnCross,
        btnCircle = btnCircle || other.btnCircle,
        btnSquare = btnSquare || other.btnSquare,
        btnTriangle = btnTriangle || other.btnTriangle,
        l1 = l1 || other.l1,
        r1 = r1 || other.r1,
        l3 = l3 || other.l3,
        r3 = r3 || other.r3,
        select = select || other.select,
        start = start || other.start,
        home = home || other.home
    )
}

class GamepadBackend {
    private var prevState = GamepadState()

    private val injectMethod by lazy {
        val binder: IBinder = SystemServiceHelper.getSystemService("input")
        val wrapper = ShizukuBinderWrapper(binder)
        val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        val iInputManager = asInterface.invoke(null, wrapper)
        val method = iInputManager.javaClass.getMethod(
            "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
        )
        Pair(iInputManager, method)
    }

    fun initShizuku() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(1001)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Runs a privileged shell script via Shizuku (UID 2000) to:
     * 1. Grant SYSTEM_ALERT_WINDOW to com.fox.vpad
     * 2. Enable foldable concurrent/dual display state if supported by DeviceStateManager
     * 3. Execute RearDisplayHelper via app_process under UID 2000 to power on the cover screen
     */
    fun setRearDisplayPower(context: Context, turnOn: Boolean, onComplete: (String) -> Unit) {
        Thread {
            try {
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    onComplete("Shizuku permission not granted")
                    return@Thread
                }

                val apkPath = context.applicationInfo.sourceDir
                val arg = if (turnOn) "on" else "off"

                val script = if (turnOn) {
                    """
                    appops set com.fox.vpad SYSTEM_ALERT_WINDOW allow
                    # Check if DeviceStateManager has a CONCURRENT / DUAL / REAR display state
                    STATES=${'$'}(cmd device_state print-states 2>/dev/null)
                    CONC_ID=${'$'}(echo "${'$'}STATES" | grep -iE "CONCURRENT|DUAL|BOTH" | grep -oE "[0-9]+" | head -n 1)
                    if [ -n "${'$'}CONC_ID" ]; then
                        cmd device_state state "${'$'}CONC_ID" >/dev/null 2>&1 &
                        sleep 0.2
                    fi
                    CLASSPATH="$apkPath" app_process /system/bin com.fox.vpad.RearDisplayHelper $arg
                    """.trimIndent()
                } else {
                    """
                    cmd device_state state reset >/dev/null 2>&1
                    CLASSPATH="$apkPath" app_process /system/bin com.fox.vpad.RearDisplayHelper $arg
                    """.trimIndent()
                }

                // Invoke Shizuku.newProcess via reflection (since it is marked private in newer Shizuku API jars)
                val newProcMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcMethod.isAccessible = true
                val proc = newProcMethod.invoke(null, arrayOf("sh", "-c", script), null, null) as Process
                val output = proc.inputStream.bufferedReader().readText().trim()
                proc.waitFor()
                onComplete(output)
            } catch (e: Exception) {
                onComplete("Error: ${e.message}")
            }
        }.start()
    }

    fun updateState(newState: GamepadState) {
        checkButton(prevState.btnCross, newState.btnCross, KeyEvent.KEYCODE_BUTTON_A)
        checkButton(prevState.btnCircle, newState.btnCircle, KeyEvent.KEYCODE_BUTTON_B)
        checkButton(prevState.btnSquare, newState.btnSquare, KeyEvent.KEYCODE_BUTTON_X)
        checkButton(prevState.btnTriangle, newState.btnTriangle, KeyEvent.KEYCODE_BUTTON_Y)
        checkButton(prevState.l1, newState.l1, KeyEvent.KEYCODE_BUTTON_L1)
        checkButton(prevState.r1, newState.r1, KeyEvent.KEYCODE_BUTTON_R1)
        checkButton(prevState.l3, newState.l3, KeyEvent.KEYCODE_BUTTON_THUMBL)
        checkButton(prevState.r3, newState.r3, KeyEvent.KEYCODE_BUTTON_THUMBR)
        checkButton(prevState.select, newState.select, KeyEvent.KEYCODE_BUTTON_SELECT)
        checkButton(prevState.start, newState.start, KeyEvent.KEYCODE_BUTTON_START)
        checkButton(prevState.home, newState.home, KeyEvent.KEYCODE_BUTTON_MODE)
        checkButton(prevState.dpadUp, newState.dpadUp, KeyEvent.KEYCODE_DPAD_UP)
        checkButton(prevState.dpadDown, newState.dpadDown, KeyEvent.KEYCODE_DPAD_DOWN)
        checkButton(prevState.dpadLeft, newState.dpadLeft, KeyEvent.KEYCODE_DPAD_LEFT)
        checkButton(prevState.dpadRight, newState.dpadRight, KeyEvent.KEYCODE_DPAD_RIGHT)

        if (newState.lx != prevState.lx || newState.ly != prevState.ly ||
            newState.rx != prevState.rx || newState.ry != prevState.ry ||
            newState.l2 != prevState.l2 || newState.r2 != prevState.r2
        ) {
            injectJoystickMotion(newState)
        }
        prevState = newState
    }

    private fun checkButton(old: Boolean, current: Boolean, keyCode: Int) {
        if (old == current) return
        val action = if (current) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now, now, action, keyCode, 0, 0,
            -1, 0, 0, InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD
        )
        sendInputEvent(event)
    }

    private fun injectJoystickMotion(state: GamepadState) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_UNKNOWN
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, state.lx)
            setAxisValue(MotionEvent.AXIS_Y, state.ly)
            setAxisValue(MotionEvent.AXIS_Z, state.rx)
            setAxisValue(MotionEvent.AXIS_RZ, state.ry)
            setAxisValue(MotionEvent.AXIS_LTRIGGER, state.l2)
            setAxisValue(MotionEvent.AXIS_RTRIGGER, state.r2)
        })
        val motion = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_MOVE, 1, props, coords,
            0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD, 0
        )
        sendInputEvent(motion)
        motion.recycle()
    }

    private fun sendInputEvent(event: InputEvent) {
        try {
            val (instance, method) = injectMethod
            method.invoke(instance, event, 0)
        } catch (_: Exception) {
        }
    }
}
