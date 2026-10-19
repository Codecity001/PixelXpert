package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import android.content.Intent
import android.os.Binder
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getObjectField
import io.github.libxposed.api.XposedModuleInterface
import java.util.Timer
import java.util.TimerTask
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Patches the package manager's signature and downgrade checks behind two
 * preferences, `PM_AllowMismatchedSignature` and `PM_AllowDowngrade`, so
 * mismatched-signature updates and version downgrades can slip through for
 * testing. The hooks sit on the four framework call sites that decide those
 * questions - `PackageManagerServiceUtils.checkDowngrade` / `verifySignatures`,
 * `SigningDetails.checkCapability` and `InstallPackageHelper.doesSignatureMatchForPermissions`
 * - and are gated entirely on the two cached flags rather than re-reading the
 * preferences per call.
 *
 * Because leaving either bypass armed is a security hole, the preference
 * handler schedules a one-shot `Timer` that writes both keys back to `false`
 * five minutes after they were switched on; an empty key list (the "load
 * everything" pass) resets them immediately instead. The same pack also keeps
 * the module's own broadcasts out of `ActivityManagerService.checkBroadcastFromSystem`
 * by recognising the `BuildConfig.APPLICATION_ID + ".ACTION"` prefix, and hands
 * the launcher the `FORCE_STOP_PACKAGES` permission it needs to manage apps.
 */
@FrameworkModPack
class PackageManager(context: Context) : XposedModPack(context) {

	companion object {
		private const val AUTO_DISABLE_MINUTES = 5
		private const val ALLOW_SIGNATURE_PREF = "PM_AllowMismatchedSignature"
		private const val ALLOW_DOWNGRADE_PREF = "PM_AllowDowngrade"

		const val PERMISSION = 4
		private const val PERMISSION_GRANTED = 0

		private var PM_AllowMismatchedSignature = false
		private var PM_AllowDowngrade = false
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		PM_AllowMismatchedSignature = Xprefs.getBoolean(ALLOW_SIGNATURE_PREF, false)
		PM_AllowDowngrade = Xprefs.getBoolean(ALLOW_DOWNGRADE_PREF, false)

		if (PM_AllowDowngrade || PM_AllowMismatchedSignature) {
			if (key.isEmpty()) {
				disablePMMods()
			} else if (key[0].equals(ALLOW_SIGNATURE_PREF) || key[0].equals(ALLOW_DOWNGRADE_PREF)) {
				Timer().schedule(object : TimerTask() {
					override fun run() {
						disablePMMods()
					}
				},
					AUTO_DISABLE_MINUTES * 60000L,
				)
			}
		}
	}

	private fun disablePMMods() {
		Xprefs.edit()
			.putBoolean(ALLOW_SIGNATURE_PREF, false)
			.putBoolean(ALLOW_DOWNGRADE_PREF, false)
			.apply()
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		try {
			val InstallPackageHelperClass = ReflectedClass.of("com.android.server.pm.InstallPackageHelper")
			val PackageManagerServiceUtilsClass = ReflectedClass.of("com.android.server.pm.PackageManagerServiceUtils")
			val SigningDetailsClass = ReflectedClass.of("android.content.pm.SigningDetails")

			try {
				val ActivityManagerServiceClass = ReflectedClass.of("com.android.server.am.ActivityManagerService")

				ActivityManagerServiceClass
					.before("checkBroadcastFromSystem")
					.run { param ->
						val action = (param.args[0] as Intent).action

						//noinspection DataFlowIssue
						if (action!!.startsWith(BuildConfig.APPLICATION_ID + ".ACTION")) {
							param.setResult(null)
						}
					}

				ActivityManagerServiceClass
					.before("checkCallingPermission")
					.run { param ->
						try {
							if ("android.permission.FORCE_STOP_PACKAGES".equals(param.args[0])) {
								if (Constants.LAUNCHER_PACKAGE.equals(
										callMethod(
											getObjectField(param.thisObject, "mInternal"),
											"getPackageNameByPid",
											Binder.getCallingPid(),
										),
									)
								) {
									param.setResult(PERMISSION_GRANTED)
								}
							}
						} catch (ignored: Throwable) {
						}
					}

			} catch (ignored: Throwable) {
			}

			PackageManagerServiceUtilsClass
				.before("checkDowngrade")
				.run { param ->
					if (PM_AllowDowngrade) {
						param.setResult(null)
					}
				}

			SigningDetailsClass
				.before("checkCapability")
				.run { param ->
					if (PM_AllowMismatchedSignature && !param.args[1]!!.equals(PERMISSION)) {
						param.setResult(true)
					}
				}

			PackageManagerServiceUtilsClass
				.before("verifySignatures")
				.run { param ->
					try {
						if (PM_AllowMismatchedSignature &&
							callMethod(
								callMethod(param.args[0], "getSigningDetails"),
								"getSignatures",
							) != null
						) {
							param.setResult(true)
						}
					} catch (ignored: Throwable) {
					}
				}

			InstallPackageHelperClass
				.before("doesSignatureMatchForPermissions")
				.run { param ->
					try {
						if (PM_AllowMismatchedSignature
							&& callMethod(param.args[1], "getPackageName").equals(param.args[0])
							&& (callMethod(param.args[1], "getBaseApkPath") as String).startsWith("/data")
						) {
							param.setResult(true)
						}
					} catch (ignored: Throwable) {
					}
				}
		} catch (ignored: Throwable) {
		}
	}
}
