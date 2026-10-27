package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.annotations.ModPackPriority
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getFloatField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField

/**
 * Hands the rest of the module the device's thermal readings.
 *
 * SystemUI's `PowerUI` already talks to `IThermalService`, so the pack hooks
 * `PowerUI.start` to grab that service handle, and the static
 * `getTemperature*` helpers then query it on demand - max or average, as
 * Celsius or Fahrenheit per the `TemperatureUnitF` preference. Every query
 * degrades to `-999` when the service is not available yet rather than
 * throwing, because callers (status bar formatting, battery styles) read the
 * value on every refresh.
 *
 * `@ModPackPriority(priority = 1)` loads this pack before the consumers of
 * its statics, so the first refresh already finds the helpers in place.
 */
@SystemUIModPack
@ModPackPriority(priority = 1)
class ThermalProvider(context: Context) : XposedModPack(context) {

	override fun onPreferenceUpdated(vararg key: String?) {
		TemperatureUnitF = Xprefs.getBoolean("TemperatureUnitF", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val PowerUIClass = ReflectedClass.of("com.android.systemui.power.PowerUI")

		PowerUIClass
			.after("start")
			.run { param -> thermalService = getObjectField(param.thisObject, "mThermalService") }
	}

	// The preference, the captured service handle and the four query helpers
	// are read by other files through `ThermalProvider.getTemperature*`, so
	// they live together in the companion rather than hanging off an instance.
	companion object {
		const val CPU = 0
		const val GPU = 1
		const val BATTERY = 2
		const val SKIN = 3

		private var TemperatureUnitF = false

		private var thermalService: Any? = null

		@JvmStatic
		fun getTemperatureMaxFloat(type: Int): Float {
			val temperatures = getTemperatures(type)

			val maxValue = floatArrayOf(-999f)

			for (temperature in temperatures) {
				maxValue[0] = Math.max(maxValue[0], getFloatField(temperature, "mValue"))
			}

			if (TemperatureUnitF) {
				maxValue[0] = toFahrenheit(maxValue[0])
			}

			return maxValue[0]
		}

		@JvmStatic
		fun getTemperatureMaxInt(type: Int): Int = Math.round(getTemperatureMaxFloat(type))

		@JvmStatic
		fun getTemperatureAvgFloat(type: Int): Float {
			val temperatures = getTemperatures(type)

			if (temperatures.isNotEmpty()) {
				val totalValue = floatArrayOf(0f)
				for (temperature in temperatures) {
					totalValue[0] += getFloatField(temperature, "mValue")
				}

				var ret = totalValue[0] / temperatures.size

				if (TemperatureUnitF) {
					ret = toFahrenheit(ret)
				}

				return ret
			}
			return -999f
		}

		@JvmStatic
		fun getTemperatureAvgInt(type: Int): Int = Math.round(getTemperatureAvgFloat(type))

		@JvmStatic
		private fun getTemperatures(type: Int): Array<*> = try {
			callMethod(thermalService, "getCurrentTemperaturesWithType", type) as? Array<*> ?: emptyArray<Any?>()
		} catch (ignored: Throwable) {
			emptyArray<Any?>()
		}

		@JvmStatic
		private fun toFahrenheit(celsius: Float): Float = (celsius * 1.8f) + 32f
	}
}
