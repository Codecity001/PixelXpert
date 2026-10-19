package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import de.robv.android.xposed.XposedHelpers.callStaticMethod
import de.robv.android.xposed.XposedHelpers.getBooleanField
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.annotations.ModPackPriority
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Publishes the device's battery state to the rest of the module.
 *
 * SystemUI's `BatteryControllerImpl` already receives every battery broadcast,
 * so the pack hooks its `onReceive` to capture level, charging and power-save
 * flags, and hooks settingslib's `BatteryStatus` to decide fast versus slow
 * charging from the intent's max-charging-current/voltage extras. Updates are
 * pushed to [BatteryStatusCallback] and [BatteryInfoCallback] listeners that
 * other packs register through the static [registerStatusCallback] and
 * [registerInfoCallback], while [isCharging], [isFastCharging],
 * [isPowerSaving], [isBatteryDefender] and [getCurrentLevel] answer the
 * synchronous questions the status bar asks on every refresh.
 *
 * `@ModPackPriority(priority = 1)` loads this pack before the consumers of
 * those statics, so the very first refresh already finds the helpers in place.
 */
@SystemUIModPack
@ModPackPriority(priority = 1)
class BatteryDataProvider(context: Context) : XposedModPack(context) {

	companion object {
		const val BATTERY_STATUS_DISCHARGING = 3
		const val EXTRA_MAX_CHARGING_CURRENT = "max_charging_current"
		const val EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage"
		const val MILLION = 1000000
		const val USB_5_WATT = 5
		const val CHARGING_SLOW = 1
		const val CHARGING_FAST = 2

		@SuppressLint("StaticFieldLeak")
		private var instance: BatteryDataProvider? = null

		private var FastChargingWattage: Int = USB_5_WATT

		@JvmStatic
		fun registerStatusCallback(callback: BatteryStatusCallback) {
			try {
				instance!!.mStatusCallbacks.add(callback)
			} catch (ignored: Throwable) {
			}
		}

		@JvmStatic
		fun unRegisterStatusCallback(callback: BatteryStatusCallback) {
			try {
				instance!!.mStatusCallbacks.remove(callback)
			} catch (ignored: Throwable) {
			}
		}

		@JvmStatic
		fun registerInfoCallback(callback: BatteryInfoCallback) {
			try {
				instance!!.mInfoCallbacks.add(callback)
			} catch (ignored: Throwable) {
			}
		}

		@JvmStatic
		fun unRegisterInfoCallback(callback: BatteryInfoCallback) {
			try {
				instance!!.mInfoCallbacks.remove(callback)
			} catch (ignored: Throwable) {
			}
		}

		@JvmStatic
		fun isCharging(): Boolean {
			try {
				return instance!!.mCharging && !instance!!.mIsBatteryDefender
			} catch (ignored: Throwable) {
				return false
			}
		}

		@JvmStatic
		fun isBatteryDefender(): Boolean {
			try {
				return instance!!.mIsBatteryDefender
			} catch (ignored: Throwable) {
				return false
			}
		}

		@JvmStatic
		fun getCurrentLevel(): Int {
			try {
				return instance!!.mCurrentLevel
			} catch (ignored: Throwable) {
				return 0
			}
		}

		@JvmStatic
		fun isPowerSaving(): Boolean {
			try {
				return instance!!.mPowerSave
			} catch (ignored: Throwable) {
				return false
			}
		}

		@JvmStatic
		fun isFastCharging(): Boolean {
			try {
				return instance!!.mCharging && instance!!.mIsFastCharging
			} catch (ignored: Throwable) {
				return false
			}
		}
	}

	internal val mStatusCallbacks: MutableList<BatteryStatusCallback> = ArrayList()
	private var mCharging = false
	private var mCurrentLevel = 0
	private val mInfoCallbacks = ArrayList<BatteryInfoCallback>()
	private var mPowerSave = false
	private var mIsFastCharging = false
	private var mIsBatteryDefender = false

	init {
		instance = this
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		FastChargingWattage = Xprefs.getSliderInt("FastChargingWattage", USB_5_WATT)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val BatteryStatusClass = ReflectedClass.of("com.android.settingslib.fuelgauge.BatteryStatus")
		val BatteryControllerImplClass = ReflectedClass.of("com.android.systemui.statusbar.policy.BatteryControllerImpl")

		//once an intent is received, it's either battery level change, powersave change, or demo mode. we don't expect demo
		// intents normally. So it's safe to assume we'll need to update battery anyway
		BatteryControllerImplClass
			.after("onReceive")
			.run { param ->
				mCurrentLevel = getIntField(param.thisObject, "mLevel")
				mCharging = getBooleanField(param.thisObject, "mPluggedIn")
					|| getBooleanField(param.thisObject, "mCharging")
					|| getBooleanField(param.thisObject, "mWirelessCharging")
				mPowerSave = getBooleanField(param.thisObject, "mPowerSave")

				try {
					mIsBatteryDefender = getBooleanField(param.thisObject, "mIsBatteryDefender")
				} catch (ignored: Throwable) {
					//older versions of Android don't have defender
					mIsBatteryDefender = false
				}

				fireBatteryInfoChanged()
			}

		//old way. removed in new versions. must go away
		BatteryStatusClass
			.before("getChargingSpeed")
			.run { param ->
				if (FastChargingWattage > USB_5_WATT) {
					val maxChargingWattage = (getObjectField(param.thisObject, "maxChargingWattage") as Int) / MILLION
					mIsFastCharging = maxChargingWattage >= FastChargingWattage
					param.setResult(if (mIsFastCharging) CHARGING_FAST else CHARGING_SLOW)
				}
			}

		BatteryStatusClass
			.before("calculateChargingSpeed").run { param ->
				if (FastChargingWattage <= USB_5_WATT)
					return@run //it's default value

				var curr = 0
				var volt = 0

				if (param.args.size > 2) {
					if (param.args[0] is Int) {
						// Old signature: (int current, int voltage, Context context)
						curr = param.args[0] as Int
						volt = param.args[1] as Int
					} else {
						// New signature: (Context context, int current, int voltage)
						curr = param.args[1] as Int
						volt = param.args[2] as Int
					}
				}

				if (volt < 0) {
					volt = 5000000
				}

				if (curr > 0) {
					val wattage = Math.round(volt * curr * 0.000001f)
					if (wattage >= FastChargingWattage)
						param.setResult(CHARGING_FAST)
					//else: leave it for the original method to decide
				}
			}

		BatteryStatusClass
			.afterConstruction()
			.run { param ->

				if (param.args.size > 0 && param.args[0] is Intent) {
					val batteryIntent = param.args[0] as Intent
					val current = batteryIntent.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, -1)
					val voltage = batteryIntent.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, -1)

					try {
						mIsFastCharging = callStaticMethod(param.thisObject!!.javaClass, "calculateChargingSpeed", mContext, current, voltage).equals(CHARGING_FAST)
					} catch (t: Throwable) {
						mIsFastCharging = callStaticMethod(param.thisObject!!.javaClass, "calculateChargingSpeed", current, voltage, mContext).equals(CHARGING_FAST)
					}

					onBatteryStatusChanged(getObjectField(param.thisObject, "status") as Int, param.args[0] as Intent)
				}
			}
	}

	private fun onBatteryStatusChanged(status: Int, intent: Intent) {
		for (callback in mStatusCallbacks) {
			try {
				callback.onBatteryStatusChanged(status, intent)
			} catch (ignored: Throwable) {
			}
		}
	}

	private fun fireBatteryInfoChanged() {
		for (callback in mInfoCallbacks) {
			try {
				callback.onBatteryInfoChanged()
			} catch (ignored: Throwable) {
			}
		}
	}

	interface BatteryInfoCallback {
		fun onBatteryInfoChanged()
	}

	interface BatteryStatusCallback {
		fun onBatteryStatusChanged(batteryStatus: Int, batteryStatusIntent: Intent)
	}
}
