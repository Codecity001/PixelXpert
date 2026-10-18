package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.annotations.ModPackPriority
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getFloatField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import kotlin.math.roundToInt

@SystemUIModPack
@ModPackPriority(priority = 1)
class ThermalProvider(context: Context) : XposedModPack(context) {

    companion object {
        const val CPU: Int = 0
        const val GPU: Int = 1
        const val BATTERY: Int = 2
        const val SKIN: Int = 3

        private var temperatureUnitF = false
        private var thermalService: Any? = null

        @JvmStatic
        fun getTemperatureMaxFloat(type: Int): Float {
            val temperatures = getTemperatures(type)
            var maxValue = -999f

            for (temperature in temperatures) {
                if (temperature != null) {
                    maxValue = maxOf(maxValue, getFloatField(temperature, "mValue"))
                }
            }

            if (temperatureUnitF) {
                maxValue = toFahrenheit(maxValue)
            }

            return maxValue
        }

        @JvmStatic
        fun getTemperatureMaxInt(type: Int): Int {
            return getTemperatureMaxFloat(type).roundToInt()
        }

        @JvmStatic
        fun getTemperatureAvgFloat(type: Int): Float {
            val temperatures = getTemperatures(type)

            if (temperatures.isNotEmpty()) {
                var totalValue = 0f
                for (temperature in temperatures) {
                    if (temperature != null) {
                        totalValue += getFloatField(temperature, "mValue")
                    }
                }

                var ret = totalValue / temperatures.size
                if (temperatureUnitF) {
                    ret = toFahrenheit(ret)
                }
                return ret
            }
            return -999f
        }

        @JvmStatic
        fun getTemperatureAvgInt(type: Int): Int {
            return getTemperatureAvgFloat(type).roundToInt()
        }

        private fun getTemperatures(type: Int): Array<*> {
            return try {
                thermalService?.let {
                    callMethod(it, "getCurrentTemperaturesWithType", type) as? Array<*>
                } ?: emptyArray<Any>()
            } catch (_: Throwable) {
                emptyArray<Any>()
            }
        }

        private fun toFahrenheit(celsius: Float): Float {
            return (celsius * 1.8f) + 32f
        }
    }

    override fun onPreferenceUpdated(vararg key: String) {
        temperatureUnitF = Xprefs.getBoolean("TemperatureUnitF", false)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val powerUIClass = ReflectedClass.of("com.android.systemui.power.PowerUI")

        powerUIClass
            .after("start")
            .run { runParam ->
                thermalService = getObjectField(runParam.thisObject, "mThermalService")
            }
    }
}
