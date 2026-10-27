package sh.siava.pixelxpert.xposed.modpacks.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.Surface
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.getStaticObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Gives the module its own answer for "how tall is the status bar?", by
 * scaling the `status_bar_height` dimen measured in a portrait-only
 * configuration by `statusbarHeightFactor` and pushing the result at every
 * place the framework reports it - the `DisplayCutout` the window manager
 * derives, plus `SystemBarUtils.getStatusBarHeight` and its per-rotation
 * sibling. With `noCutoutEnabled` set the before-hook replaces
 * `WmDisplayCutout.getDisplayCutout` outright with `DisplayCutout.NO_CUTOUT`,
 * which is what hides the cutout area from the rest of the UI.
 *
 * Every hook body sits in a `Throwable` swallow because these are framework
 * internals: a build that lacks one of the classes must leave the status bar
 * exactly as the OS drew it rather than crash system server. The height is
 * only recomputed when the factor moved, when this instance already touched
 * it, or when a forced re-apply is pending - otherwise the original value is
 * left alone even at 100%.
 *
 * Kotlin has no class-level `@SuppressWarnings("RedundantThrows")`
 * equivalent, so it is absent here; `onPackageLoaded` does not declare `throws`
 * either way, because the base class already carries `@Throws`.
 */
// We are playing in system framework. should be extra cautious..... many try-catchs, still not enough!
@FrameworkModPack
@SystemUIModPack
class StatusbarSize(context: Context) : XposedModPack(context) {

	companion object {
		private const val BOUNDS_POSITION_TOP = 1

		private var sizeFactor = 100 // % of normal
		private var noCutoutEnabled = true
	}

	private var currentHeight = 0
	private var edited = false // if we touched it once during this instance, we'll have to continue setting it even if it's the original value
	private var mForceApplyHeight = false

	@SuppressLint("DiscouragedApi", "InternalInsetResource")
	@Suppress("SENSELESS_COMPARISON")
	override fun onPreferenceUpdated(vararg key: String?) {
		noCutoutEnabled = Xprefs.getBoolean("noCutoutEnabled", false)

		mForceApplyHeight = Xprefs.getBoolean("allScreenRotations", false) || // Particularly used for rotation Status bar
			noCutoutEnabled ||
			Xprefs.getBoolean("systemIconsMultiRow", false) ||
			Xprefs.getBoolean("notificationAreaMultiRow", false)

		sizeFactor = Xprefs.getSliderInt("statusbarHeightFactor", 100)
		if (sizeFactor != 100 || edited || mForceApplyHeight) {
			val conf = Configuration()
			conf.updateFrom(mContext.resources.configuration)

			conf.orientation = Configuration.ORIENTATION_PORTRAIT
			val portraitContext = mContext.createConfigurationContext(conf)

			currentHeight = Math.round(
				portraitContext.resources.getDimensionPixelSize(
					portraitContext.resources.getIdentifier(
						"status_bar_height",
						"dimen",
						"android",
					),
				) * sizeFactor / 100f,
			)
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		try {
			try {
				val WmDisplayCutoutClass = ReflectedClass.of("com.android.server.wm.utils.WmDisplayCutout")
				val DisplayCutoutClass = ReflectedClass.of("android.view.DisplayCutout")

				val NO_CUTOUT = getStaticObjectField(DisplayCutoutClass.clazz, "NO_CUTOUT")

				WmDisplayCutoutClass
					.before("getDisplayCutout")
					.run { param ->
						if (noCutoutEnabled) {
							param.setResult(NO_CUTOUT)
						}
					}

				WmDisplayCutoutClass
					.after("getDisplayCutout")
					.run { param ->
						if (sizeFactor >= 100 && !edited) return@run
						try {
							if (mContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
								return@run
							}
						} catch (_: Throwable) {}

						val displayCutout: DisplayCutout = param.getResult()

						val boundTop = (
							getObjectField(
								getObjectField(
									displayCutout,
									"mBounds",
								),
								"mRects",
							) as Array<Rect>
							)[BOUNDS_POSITION_TOP]
						boundTop.bottom = Math.min(boundTop.bottom, currentHeight)

						val mSafeInsets = getObjectField(
							displayCutout,
							"mSafeInsets",
						) as Rect
						mSafeInsets.top = Math.min(mSafeInsets.top, currentHeight)
					}
			} catch (ignored: Throwable) {
			}

			val statusBarHeightConsumer = ReflectedClass.ReflectionConsumer { param ->
				try {
					if (sizeFactor == 100 && !edited && !mForceApplyHeight) return@ReflectionConsumer
					val context = param.args.getOrNull(0) as? Context
					val res = context?.resources ?: param.args.getOrNull(0) as? Resources ?: mContext.resources
					if (res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
						return@ReflectionConsumer
					}
					edited = true
					param.setResult(currentHeight)
				} catch (ignored: Throwable) {
				}
			}

			val statusBarHeightForRotationConsumer = ReflectedClass.ReflectionConsumer { param ->
				try {
					if (sizeFactor == 100 && !edited && !mForceApplyHeight) return@ReflectionConsumer
					val context = param.args.getOrNull(0) as? Context
					val rotation = param.args.getOrNull(1) as? Int ?: Surface.ROTATION_0
					if (isLandscapeForRotation(context, rotation)) {
						return@ReflectionConsumer
					}
					edited = true
					param.setResult(currentHeight)
				} catch (ignored: Throwable) {
				}
			}

			try {
				val SystemBarUtilsClass = ReflectedClass.of("com.android.internal.policy.SystemBarUtils")

				SystemBarUtilsClass.before("getStatusBarHeight").run(statusBarHeightConsumer)
				SystemBarUtilsClass.before("getStatusBarHeightForRotation").run(statusBarHeightForRotationConsumer)
			} catch (ignored: Throwable) {
			}
		} catch (ignored: Throwable) {
		}
	}

	private fun isLandscapeForRotation(context: Context?, rotation: Int): Boolean {
		val ctx = context ?: mContext
		try {
			val currentRotation = ctx.display?.rotation ?: Surface.ROTATION_0
			val changesOrientation = ((currentRotation - rotation + 4) % 2) != 0
			val isCurrentLandscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
			return if (changesOrientation) !isCurrentLandscape else isCurrentLandscape
		} catch (_: Throwable) {}
		return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
	}
}
