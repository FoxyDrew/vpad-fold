package com.fox.vpad

import android.annotation.SuppressLint
import android.os.IBinder

object RearDisplayHelper {
    @JvmStatic
    @SuppressLint("PrivateApi")
    fun main(args: Array<String>) {
        val turnOn = args.firstOrNull() != "off"

        try {
            val scClass = Class.forName("android.view.SurfaceControl")
            val setPower = scClass.getMethod(
                "setDisplayPowerMode",
                IBinder::class.java,
                Int::class.javaPrimitiveType
            )

            // Fetch all physical display IDs (Inner + Cover)
            val physIds: LongArray? = try {
                val dcClass = Class.forName("com.android.server.display.DisplayControl")
                dcClass.getMethod("getPhysicalDisplayIds").invoke(null) as? LongArray
            } catch (_: Throwable) {
                scClass.getMethod("getPhysicalDisplayIds").invoke(null) as? LongArray
            }

            if (physIds != null) {
                println("Found ${physIds.size} physical displays: ${physIds.joinToString()}")
                for (id in physIds) {
                    val token: IBinder? = try {
                        val dcClass = Class.forName("com.android.server.display.DisplayControl")
                        dcClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                            .invoke(null, id) as? IBinder
                    } catch (_: Throwable) {
                        scClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                            .invoke(null, id) as? IBinder
                    }
                    if (token != null) {
                        // Never turn off the primary active screen; when turning on, wake ALL physical screens
                        if (turnOn) {
                            setPower.invoke(null, token, 2)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
}
