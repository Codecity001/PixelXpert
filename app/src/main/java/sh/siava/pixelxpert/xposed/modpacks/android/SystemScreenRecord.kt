package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import android.os.Binder
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod

/**
 * Implements the "Allow screen recording from restricted content" toggle in
 * system_server. `DisplayManagerService.canProjectSecureVideo` is the gate the
 * media-projection path consults before it lets a caller capture a secure
 * (lock screen, DRM) display, and it refuses everyone who is not the owner of
 * that display - which is why a recording started from SystemUI is denied
 * exactly when the user most wants it.
 *
 * While [InsecureScreenRecord] is on, the before-hook answers "yes" only for
 * the caller the framework already trusts - the package that passes
 * `validatePackageName` for the calling uid and is SystemUI - and every other
 * caller keeps the untouched framework path. Both `Throwable` swallows stay:
 * a build without the method, or a validation that blows up, must degrade to
 * stock behaviour rather than break screen recording altogether.
 */
@FrameworkModPack
class SystemScreenRecord(context: Context) : XposedModPack(context) {

	companion object {
		private var InsecureScreenRecord = false
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		InsecureScreenRecord = Xprefs.getBoolean("InsecureScreenRecord", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		try {
			val DisplayManagerServiceClass = ReflectedClass.of("com.android.server.display.DisplayManagerService")

			DisplayManagerServiceClass
				.before("canProjectSecureVideo")
				.run { param ->
					try {
						if (InsecureScreenRecord &&
							(
								callMethod(
									param.thisObject,
									"validatePackageName",
									Binder.getCallingUid(),
									Constants.SYSTEM_UI_PACKAGE,
								) as Boolean
								)
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
