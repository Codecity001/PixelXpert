package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import sh.siava.pixelxpert.xposed.XPrefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@SystemUIModPack
@FrameworkModPack
class BrightnessRange(context: Context) : XposedModPack(context) {

    companion object {
        private var minimumBrightnessLevel = 0f
        private var maximumBrightnessLevel = 1f
    }

    override fun onPreferenceUpdated(vararg keys: String) {
        runCatching {
            val range = XPrefs.getSliderValues("BrightnessRange", 100f)
            if (range.size == 2) {
                minimumBrightnessLevel = range[0] / 100f
                maximumBrightnessLevel = range[1] / 100f
            }
        }
    }

    override fun onPackageLoaded(param: PackageReadyParam) {
        runCatching {
            // framework
            val displayPowerControllerClass = ReflectedClass.of(
                "com.android.server.display.DisplayPowerController",
                param.classLoader
            )

            displayPowerControllerClass
                .before("clampScreenBrightness")
                .run { hookParam ->
                    if (minimumBrightnessLevel == 0f && maximumBrightnessLevel == 1f) return@run
                    val currentVal = (hookParam.args[0] as? Number)?.toFloat() ?: return@run
                    hookParam.args[0] = minOf(
                        maxOf(currentVal, minimumBrightnessLevel),
                        maximumBrightnessLevel
                    )
                }
        }

        runCatching {
            // SystemUI
            val brightnessInfoClass = ReflectedClass.of(
                "android.hardware.display.BrightnessInfo",
                param.classLoader
            )

            brightnessInfoClass
                .afterConstruction()
                .run { hookParam ->
                    val obj = hookParam.thisObject ?: return@run
                    if (minimumBrightnessLevel > 0f) {
                        setObjectField(obj, "brightnessMinimum", minimumBrightnessLevel)
                    }
                    if (maximumBrightnessLevel < 1f) {
                        setObjectField(obj, "brightnessMaximum", maximumBrightnessLevel)
                    }
                }
        }
    }
}
