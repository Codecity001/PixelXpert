package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import android.os.SystemClock.uptimeMillis
import android.os.VibrationAttributes.USAGE_ACCESSIBILITY
import android.os.VibrationEffect
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import sh.siava.pixelxpert.annotations.ChildProcessModPack
import sh.siava.pixelxpert.annotations.MainProcessModPack
import sh.siava.pixelxpert.xposed.XPrefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.TelecomServerModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@TelecomServerModPack
@MainProcessModPack
@ChildProcessModPack(processNameContains = "")
class CallVibrator(context: Context) : XposedModPack(context) {

    companion object {
        const val DIALING = 3
        const val ACTIVE = 5
        const val DISCONNECTED = 7

        private val mVibrationEffect = VibrationEffect.createWaveform(longArrayOf(0, 100, 100, 100), -1)
        private var mLastVibration = 0L
        private var vibrateOnAnswered = false
        private var vibrateOnDrop = false
    }

    override fun onPreferenceUpdated(vararg keys: String) {
        vibrateOnAnswered = XPrefs.getBoolean("vibrateOnAnswered", false)
        vibrateOnDrop = XPrefs.getBoolean("vibrateOnDrop", false)
    }

    override fun onPackageLoaded(param: PackageReadyParam) {
        runCatching {
            val inCallControllerClass = ReflectedClass.of("com.android.server.telecom.InCallController", param.classLoader)

            inCallControllerClass
                .before("onCallStateChanged")
                .run { hookParam ->
                    runCatching {
                        val oldState = (hookParam.args.getOrNull(1) as? Number)?.toInt() ?: return@runCatching
                        val newState = (hookParam.args.getOrNull(2) as? Number)?.toInt() ?: return@runCatching

                        if ((vibrateOnAnswered && oldState == DIALING && newState == ACTIVE) ||
                            (vibrateOnDrop && oldState == ACTIVE && newState == DISCONNECTED)
                        ) {
                            vibrate()
                        }
                    }
                }
        }
    }

    private fun vibrate() {
        if (uptimeMillis() - mLastVibration > 200L) {
            mLastVibration = uptimeMillis()
            SystemUtils.vibrate(mVibrationEffect, USAGE_ACCESSIBILITY)
        }
    }
}
