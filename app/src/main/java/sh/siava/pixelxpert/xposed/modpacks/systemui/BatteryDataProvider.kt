package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callStaticMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getBooleanField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getIntField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.annotations.ModPackPriority
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import java.util.ArrayList
import kotlin.math.roundToInt

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

        private var fastChargingWattage = USB_5_WATT

        @JvmStatic
        fun registerStatusCallback(callback: BatteryStatusCallback) {
            try {
                instance?.mStatusCallbacks?.add(callback)
            } catch (ignored: Throwable) {
            }
        }

        @JvmStatic
        fun unRegisterStatusCallback(callback: BatteryStatusCallback) {
            try {
                instance?.mStatusCallbacks?.remove(callback)
            } catch (ignored: Throwable) {
            }
        }

        @JvmStatic
        fun registerInfoCallback(callback: BatteryInfoCallback) {
            try {
                instance?.mInfoCallbacks?.add(callback)
            } catch (ignored: Throwable) {
            }
        }

        @JvmStatic
        fun unRegisterInfoCallback(callback: BatteryInfoCallback) {
            try {
                instance?.mInfoCallbacks?.remove(callback)
            } catch (ignored: Throwable) {
            }
        }

        @JvmStatic
        fun isCharging(): Boolean {
            return try {
                val inst = instance ?: return false
                inst.mCharging && !inst.mIsBatteryDefender
            } catch (ignored: Throwable) {
                false
            }
        }

        @JvmStatic
        fun isBatteryDefender(): Boolean {
            return try {
                instance?.mIsBatteryDefender ?: false
            } catch (ignored: Throwable) {
                false
            }
        }

        @JvmStatic
        fun getCurrentLevel(): Int {
            return try {
                instance?.mCurrentLevel ?: 0
            } catch (ignored: Throwable) {
                0
            }
        }

        @JvmStatic
        fun isPowerSaving(): Boolean {
            return try {
                instance?.mPowerSave ?: false
            } catch (ignored: Throwable) {
                false
            }
        }

        @JvmStatic
        fun isFastCharging(): Boolean {
            return try {
                val inst = instance ?: return false
                inst.mCharging && inst.mIsFastCharging
            } catch (ignored: Throwable) {
                false
            }
        }
    }

    private val mStatusCallbacks: MutableList<BatteryStatusCallback> = ArrayList()
    private val mInfoCallbacks: MutableList<BatteryInfoCallback> = ArrayList()

    private var mCharging = false
    private var mCurrentLevel = 0
    private var mPowerSave = false
    private var mIsFastCharging = false
    private var mIsBatteryDefender = false

    init {
        instance = this
    }

    override fun onPreferenceUpdated(vararg key: String) {
        fastChargingWattage = Xprefs.getSliderInt("FastChargingWattage", USB_5_WATT)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val batteryStatusClass = ReflectedClass.of("com.android.settingslib.fuelgauge.BatteryStatus")
            val batteryControllerImplClass = ReflectedClass.of("com.android.systemui.statusbar.policy.BatteryControllerImpl")

            batteryControllerImplClass
                .after("onReceive")
                .run { hookParam ->
                    val thisObj = hookParam.thisObject ?: return@run
                    mCurrentLevel = getIntField(thisObj, "mLevel")
                    mCharging = getBooleanField(thisObj, "mPluggedIn")
                            || getBooleanField(thisObj, "mCharging")
                            || getBooleanField(thisObj, "mWirelessCharging")
                    mPowerSave = getBooleanField(thisObj, "mPowerSave")

                    try {
                        mIsBatteryDefender = getBooleanField(thisObj, "mIsBatteryDefender")
                    } catch (ignored: Throwable) {
                        mIsBatteryDefender = false
                    }

                    fireBatteryInfoChanged()
                }

            batteryStatusClass
                .before("getChargingSpeed")
                .run { hookParam ->
                    val thisObj = hookParam.thisObject ?: return@run
                    if (fastChargingWattage > USB_5_WATT) {
                        val maxChargingWattage = (getObjectField(thisObj, "maxChargingWattage") as? Int ?: 0) / MILLION
                        mIsFastCharging = maxChargingWattage >= fastChargingWattage
                        hookParam.result = if (mIsFastCharging) CHARGING_FAST else CHARGING_SLOW
                    }
                }

            batteryStatusClass
                .before("calculateChargingSpeed")
                .run { hookParam ->
                    if (fastChargingWattage <= USB_5_WATT) return@run

                    var curr = 0
                    var volt = 0

                    if (hookParam.args.size > 2) {
                        if (hookParam.args.getOrNull(0) is Int) {
                            curr = hookParam.args.getOrNull(0) as? Int ?: 0
                            volt = hookParam.args.getOrNull(1) as? Int ?: 0
                        } else {
                            curr = hookParam.args.getOrNull(1) as? Int ?: 0
                            volt = hookParam.args.getOrNull(2) as? Int ?: 0
                        }
                    }

                    if (volt < 0) {
                        volt = 5000000
                    }

                    if (curr > 0) {
                        val wattage = (volt * curr * 0.000001f).roundToInt()
                        if (wattage >= fastChargingWattage) {
                            hookParam.result = CHARGING_FAST
                        }
                    }
                }

            batteryStatusClass
                .afterConstruction()
                .run { hookParam ->
                    val batteryIntent = hookParam.args.getOrNull(0) as? Intent
                    val thisObj = hookParam.thisObject ?: return@run

                    if (batteryIntent != null) {
                        val current = batteryIntent.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, -1)
                        val voltage = batteryIntent.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, -1)

                        try {
                            mIsFastCharging = callStaticMethod<Int>(
                                thisObj.javaClass,
                                "calculateChargingSpeed",
                                mContext,
                                current,
                                voltage
                            ) == CHARGING_FAST
                        } catch (t: Throwable) {
                            mIsFastCharging = callStaticMethod<Int>(
                                thisObj.javaClass,
                                "calculateChargingSpeed",
                                current,
                                voltage,
                                mContext
                            ) == CHARGING_FAST
                        }

                        val status = getObjectField(thisObj, "status") as? Int ?: 0
                        onBatteryStatusChanged(status, batteryIntent)
                    }
                }
        } catch (ignored: Throwable) {
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

    fun interface BatteryInfoCallback {
        fun onBatteryInfoChanged()
    }

    fun interface BatteryStatusCallback {
        fun onBatteryStatusChanged(batteryStatus: Int, batteryStatusIntent: Intent)
    }
}
