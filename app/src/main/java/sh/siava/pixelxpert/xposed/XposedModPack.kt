package sh.siava.pixelxpert.xposed

import android.content.Context
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

abstract class XposedModPack(protected val mContext: Context) : Logger() {
    open fun onPreferenceUpdated(vararg keys: String) {}
    open fun onPackageLoaded(param: PackageReadyParam) {}
}
