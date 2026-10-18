package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import sh.siava.pixelxpert.xposed.XPrefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@FrameworkModPack
class HotSpotController(context: Context) : XposedModPack(context) {

    companion object {
        private var hotSpotTimeoutMillis = 0L
        private var hotSpotHideSSID = false
        private var hotSpotMaxClients = 0
        private var hotspotDisableApproval = false
    }

    override fun onPreferenceUpdated(vararg keys: String) {
        hotSpotMaxClients = XPrefs.getSliderInt("hotSpotMaxClients", 0)
        hotSpotTimeoutMillis = (XPrefs.getSliderFloat("hotSpotTimeoutSecs", 0f) * 1000L).toLong()
        hotSpotHideSSID = XPrefs.getBoolean("hotSpotHideSSID", false)
        hotspotDisableApproval = XPrefs.getBoolean("hotspotDisableApproval", false)
    }

    override fun onPackageLoaded(param: PackageReadyParam) {
        runCatching {
            val softApConfiguration = ReflectedClass.of("android.net.wifi.SoftApConfiguration", param.classLoader)

            softApConfiguration
                .afterConstruction()
                .run { hookParam ->
                    val obj = hookParam.thisObject ?: return@run
                    setObjectField(obj, "mHiddenSsid", hotSpotHideSSID)

                    if (hotspotDisableApproval) {
                        setObjectField(obj, "mClientControlByUser", false)
                    }

                    if (hotSpotTimeoutMillis > 0) {
                        setObjectField(obj, "mShutdownTimeoutMillis", hotSpotTimeoutMillis)
                    }

                    if (hotSpotMaxClients > 0) {
                        setObjectField(obj, "mMaxNumberOfClients", hotSpotMaxClients)
                    }
                }
        }
    }
}
