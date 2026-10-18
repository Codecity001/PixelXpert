package sh.siava.pixelxpert.xposed.utils.toolkit

import android.util.Log
import io.github.libxposed.api.XposedInterface

open class Logger {
    companion object {
        @JvmField
        var TAG: String = "PixelXpert Lsposed Module"
        private var xposedInterface: XposedInterface? = null

        @JvmStatic
        fun setXposedInterface(xposedInterface: XposedInterface) {
            this.xposedInterface = xposedInterface
        }

        @Synchronized
        @JvmStatic
        fun log(text: String) {
            try {
                xposedInterface?.log(XposedInterface.PRIORITY_DEFAULT, TAG, text) ?: Log.w(TAG, text)
            } catch (_: Throwable) {
                Log.w(TAG, text)
            }
        }

        @Synchronized
        @JvmStatic
        fun log(text: String, t: Throwable) {
            try {
                xposedInterface?.log(XposedInterface.PRIORITY_DEFAULT, TAG, text, t) ?: Log.e(TAG, text, t)
            } catch (_: Throwable) {
                Log.e(TAG, text, t)
            }
        }

        @Synchronized
        @JvmStatic
        fun log(t: Throwable) {
            log("", t)
        }
    }
}
