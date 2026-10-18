package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getIntField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@FrameworkModPack
class ScreenRotation(context: Context) : XposedModPack(context) {

    companion object {
        private const val USER_ROTATION_LOCKED = 1
        private var allScreenRotations = false
    }

    override fun onPreferenceUpdated(vararg key: String) {
        allScreenRotations = Xprefs.getBoolean("allScreenRotations", false)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val displayRotationClass = ReflectedClass.of("com.android.server.wm.DisplayRotation")

            displayRotationClass
                .before("rotationForOrientation")
                .run { hookParam ->
                    try {
                        if (!allScreenRotations) return@run

                        val lastRotation = hookParam.args.getOrNull(1) as? Int ?: return@run
                        val thisObj = hookParam.thisObject ?: return@run

                        if (getIntField(thisObj, "mUserRotationMode") == USER_ROTATION_LOCKED) {
                            hookParam.result = lastRotation
                            return@run
                        }

                        val orientationListener = getObjectField(thisObj, "mOrientationListener")
                        var sensorRotation = if (orientationListener != null) {
                            callMethod(orientationListener, "getProposedRotation") as? Int ?: -1
                        } else {
                            -1
                        }

                        if (sensorRotation < 0) {
                            sensorRotation = lastRotation
                        }
                        hookParam.result = sensorRotation
                    } catch (ignored: Throwable) {
                    }
                }
        } catch (ignored: Exception) {
        }
    }
}
