package sh.siava.pixelxpert.xposed.modpacks.launcher

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.findFieldIfExists
import de.robv.android.xposed.XposedHelpers.getAdditionalInstanceField
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.utils.GoogleMonochromeIconFactory
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Supplies a monochrome (themed) icon layer for launchers that lost theirs.
 *
 * Launcher builds from 16QPR2 on ship a built-in themed-icon generator and
 * dropped `BaseIconFactory.mIconBitmapSize`; the pack detects that shape and
 * records the answer in the `DisableThemedIconsPref` preference so the UI can
 * grey the switch, then stops. On older builds it hooks
 * `AdaptiveIconDrawable.getMonochrome` and, when the launcher returned null
 * and `ForceThemedLauncherIcons` is on, renders the layer itself with
 * [GoogleMonochromeIconFactory] - caching the factory on the drawable as an
 * additional instance field so repeated queries reuse it.
 *
 * The scan of `Thread.currentThread().stackTrace` for a `getIcon` frame is a
 * recursion guard: the factory draws the icon back into the launcher, and
 * letting that inner draw ask for a monochrome layer again would recurse
 * forever. When the preference changes, `LauncherAppState`'s icon cache and
 * model are force-reloaded on the main thread so existing icons re-theme.
 */
@LauncherModPack
class LauncherThemedIcons(context: Context) : XposedModPack(context) {
	private var mIconBitmapSize = 0
	private var LAS: Any? = null

	override fun onPreferenceUpdated(vararg key: String?) {
		ForceThemedLauncherIcons = Xprefs.getBoolean("ForceThemedLauncherIcons", false)

		if (key.isNotEmpty()) {
			if (key[0] == "ForceThemedLauncherIcons") {
				reloadIcons()
			}
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val BaseIconFactoryClass = ReflectedClass.of("com.android.launcher3.icons.BaseIconFactory")
		val LauncherAppStateClass = ReflectedClass.of("com.android.launcher3.LauncherAppState")

		if (findFieldIfExists(BaseIconFactoryClass.clazz, "mIconBitmapSize") == null) {
			//it's 16qpr2 with built-in generator - no need to patch
			Xprefs.edit().putBoolean("DisableThemedIconsPref", true).apply()
			return
		}

		Xprefs.edit().putBoolean("DisableThemedIconsPref", false).apply()

		LauncherAppStateClass
			.afterConstruction()
			.run { param -> LAS = param.thisObject }

		BaseIconFactoryClass
			.afterConstruction()
			.run { param -> mIconBitmapSize = getIntField(param.thisObject, "mIconBitmapSize") }

		ReflectedClass.of(AdaptiveIconDrawable::class.java)
			.after("getMonochrome")
			.run { param ->
				try {
					if (param.getResult<Any?>() == null && ForceThemedLauncherIcons) {
						for (stackTraceElement in Thread.currentThread().stackTrace) {
							if (stackTraceElement.methodName == "getIcon") {
								return@run
							}
						}

						var mono = getAdditionalInstanceField(param.thisObject, "mMonoFactoryPX") as GoogleMonochromeIconFactory?
						if (mono == null) {
							mono = GoogleMonochromeIconFactory(param.thisObject as AdaptiveIconDrawable, mIconBitmapSize)
							setAdditionalInstanceField(param.thisObject, "mMonoFactoryPX", mono)
						}
						param.setResult(mono)
					}
				} catch (ignored: Throwable) {
				}
			}
	}

	private fun reloadIcons() {
		val iconCache = getObjectField(LAS, "iconCache")

		Handler(Looper.getMainLooper()).post {
			callMethod(getObjectField(iconCache, "cache"), "clear")
			callMethod(getObjectField(iconCache, "iconDb"), "clear")
			callMethod(getObjectField(LAS, "model"), "forceReload")
		}
	}

	companion object {
		private var ForceThemedLauncherIcons = false
	}
}
