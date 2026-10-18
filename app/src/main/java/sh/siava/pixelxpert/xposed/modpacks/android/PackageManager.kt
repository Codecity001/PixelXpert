package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import android.content.Intent
import android.os.Binder
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.Timer
import java.util.TimerTask
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XPrefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@FrameworkModPack
class PackageManager(context: Context) : XposedModPack(context) {

    companion object {
        private const val AUTO_DISABLE_MINUTES = 5
        private const val ALLOW_SIGNATURE_PREF = "PM_AllowMismatchedSignature"
        private const val ALLOW_DOWNGRADE_PREF = "PM_AllowDowngrade"

        const val PERMISSION = 4
        private const val PERMISSION_GRANTED = 0

        private var pmAllowMismatchedSignature = false
        private var pmAllowDowngrade = false
    }

    override fun onPreferenceUpdated(vararg keys: String) {
        pmAllowMismatchedSignature = XPrefs.getBoolean(ALLOW_SIGNATURE_PREF, false)
        pmAllowDowngrade = XPrefs.getBoolean(ALLOW_DOWNGRADE_PREF, false)

        if (pmAllowDowngrade || pmAllowMismatchedSignature) {
            if (keys.isEmpty()) {
                disablePMMods()
            } else if (keys[0] == ALLOW_SIGNATURE_PREF || keys[0] == ALLOW_DOWNGRADE_PREF) {
                Timer().schedule(
                    object : TimerTask() {
                        override fun run() {
                            disablePMMods()
                        }
                    },
                    (AUTO_DISABLE_MINUTES * 60000).toLong()
                )
            }
        }
    }

    private fun disablePMMods() {
        XPrefs.edit()
            .putBoolean(ALLOW_SIGNATURE_PREF, false)
            .putBoolean(ALLOW_DOWNGRADE_PREF, false)
            .apply()
    }

    override fun onPackageLoaded(param: PackageReadyParam) {
        runCatching {
            val installPackageHelperClass = ReflectedClass.of("com.android.server.pm.InstallPackageHelper", param.classLoader)
            val packageManagerServiceUtilsClass = ReflectedClass.of("com.android.server.pm.PackageManagerServiceUtils", param.classLoader)
            val signingDetailsClass = ReflectedClass.of("android.content.pm.SigningDetails", param.classLoader)

            runCatching {
                val activityManagerServiceClass = ReflectedClass.of("com.android.server.am.ActivityManagerService", param.classLoader)

                activityManagerServiceClass
                    .before("checkBroadcastFromSystem")
                    .run { hookParam ->
                        val intent = hookParam.args.getOrNull(0) as? Intent
                        val action = intent?.action
                        if (action != null && action.startsWith("${BuildConfig.APPLICATION_ID}.ACTION")) {
                            hookParam.setResult(null)
                        }
                    }

                // Granting pixel launcher permission to force stop apps
                activityManagerServiceClass
                    .before("checkCallingPermission")
                    .run { hookParam ->
                        runCatching {
                            if ("android.permission.FORCE_STOP_PACKAGES" == hookParam.args.getOrNull(0)) {
                                val thisObj = hookParam.thisObject ?: return@runCatching
                                val mInternal = getObjectField(thisObj, "mInternal")
                                val callingPid = Binder.getCallingPid()
                                val pkgName = callMethod(mInternal, "getPackageNameByPid", callingPid)
                                if (Constants.LAUNCHER_PACKAGE == pkgName) {
                                    hookParam.setResult(PERMISSION_GRANTED)
                                }
                            }
                        }
                    }
            }

            packageManagerServiceUtilsClass
                .before("checkDowngrade")
                .run { hookParam ->
                    if (pmAllowDowngrade) {
                        hookParam.setResult(null)
                    }
                }

            signingDetailsClass
                .before("checkCapability")
                .run { hookParam ->
                    if (pmAllowMismatchedSignature && hookParam.args.getOrNull(1) != PERMISSION) {
                        hookParam.setResult(true)
                    }
                }

            packageManagerServiceUtilsClass
                .before("verifySignatures")
                .run { hookParam ->
                    runCatching {
                        if (pmAllowMismatchedSignature) {
                            val arg0 = hookParam.args.getOrNull(0) ?: return@runCatching
                            val signingDetails = callMethod(arg0, "getSigningDetails")
                            val signatures = callMethod(signingDetails, "getSignatures")
                            if (signatures != null) {
                                hookParam.setResult(true)
                            }
                        }
                    }
                }

            installPackageHelperClass
                .before("doesSignatureMatchForPermissions")
                .run { hookParam ->
                    runCatching {
                        if (pmAllowMismatchedSignature) {
                            val pkg = hookParam.args.getOrNull(1) ?: return@runCatching
                            val pkgName = callMethod(pkg, "getPackageName")
                            val baseApkPath = callMethod(pkg, "getBaseApkPath") as? String
                            if (pkgName == hookParam.args.getOrNull(0) && baseApkPath != null && baseApkPath.startsWith("/data")) {
                                hookParam.setResult(true)
                            }
                        }
                    }
                }
        }
    }
}
