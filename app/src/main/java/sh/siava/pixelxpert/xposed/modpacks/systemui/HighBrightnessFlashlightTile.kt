package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.service.quicksettings.Tile
import android.view.HapticFeedbackConstants
import android.view.View
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.service.tileServices.HighBrightnessFlashlightTileService
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.CustomTileMatcher
import sh.siava.pixelxpert.xposed.utils.HighBrightnessTorchController
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setObjectField
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

/**
 * Gives the module's high-brightness flashlight tile its behaviour inside QQS.
 *
 * The tile is backed by [HighBrightnessFlashlightTileService], a `TileService`
 * living in the module app, so SystemUI creates it through
 * `QSFactoryImpl.createTile` as an opaque `CustomTile` rather than through any
 * factory the module could subclass. The pack therefore captures the live tile
 * object the moment the factory hands it over, then hooks every callback
 * SystemUI would otherwise own: a click steps the torch up by 16 levels
 * (writing the level back to preferences and closing the camera once the top
 * is reached), a long click jumps straight to maximum brightness or closes the
 * camera, `handleUpdateState` mirrors [HighBrightnessTorchController]'s level
 * into the tile's subtitle, and the long click opens display settings instead.
 * Both interactions add a haptic tick, falling back to a system vibration when
 * the tile's view cannot perform one.
 */
@SystemUIModPack
class HighBrightnessFlashlightTile(context: Context) : XposedModPack(context) {

	private var mTile: Any? = null

	override fun onPreferenceUpdated(vararg key: String?) {
	}

	override fun onUnloaded() {
		mTile = null
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val CustomTileClass = ReflectedClass.of("com.android.systemui.qs.external.CustomTile")
		val QSFactoryImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSFactoryImpl")

		QSFactoryImplClass
			.after("createTile")
			.run { param ->
				val arg = param.args[0] as String?
				if (arg != null && arg.contains(HighBrightnessFlashlightTileService::class.java.simpleName)) {
					val result: Any? = param.getResult()
					if (adoptTile(result)) {
						updateTile()
					}
				}
			}

		CustomTileClass
			.before("handleClick")
			.run { param ->
				if (adoptTile(param.thisObject)) {
					val arg = if (param.args.isNotEmpty()) param.args[0] else null
					handleTileClick(arg)
					param.setResult(null)
				}
			}

		val QSTileImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl")
		QSTileImplClass
			.before("handleLongClick")
			.run { param ->
				if (adoptTile(param.thisObject)) {
					val arg = if (param.args.isNotEmpty()) param.args[0] else null
					handleTileLongClick(arg)
					param.setResult(null)
				}
			}

		CustomTileClass
			.after("handleUpdateState")
			.run { param ->
				if (adoptTile(param.thisObject)) {
					val state = param.args[0]

					val isSupported =
						HighBrightnessTorchController.getInstance() != null && HighBrightnessTorchController.getInstance()!!.isSupported()
					val isOn =
						HighBrightnessTorchController.getInstance() != null && HighBrightnessTorchController.getInstance()!!.isOn()

					try {
						setObjectField(state, "value", isOn)
					} catch (ignored: NoSuchFieldError) {
					}

					if (!isSupported) {
						setObjectField(state, "state", Tile.STATE_UNAVAILABLE)
						setObjectField(state, "secondaryLabel", "Not Supported")
					} else {
						setObjectField(state, "state", if (isOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
						if (isOn) {
							val level = HighBrightnessTorchController.getInstance()!!.getCurrentBrightness()
							val max = HighBrightnessTorchController.getInstance()!!.getMaxBrightness()
							setObjectField(state, "secondaryLabel", "Level $level/$max")
						} else {
							setObjectField(state, "secondaryLabel", null)
						}
					}
				}
			}

		CustomTileClass
			.before("getLongClickIntent")
			.run { param ->
				if (adoptTile(param.thisObject)) {
					val intent = Intent("android.settings.DISPLAY_SETTINGS")
					intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
					param.setResult(intent)
				}
			}

		CustomTileClass
			.before("handleDestroy")
			.run { param ->
				if (CustomTileMatcher.matches(param.thisObject, HighBrightnessFlashlightTileService::class.java)) {
					mTile = null
				}
			}
	}

	private fun adoptTile(tile: Any?): Boolean {
		if (!CustomTileMatcher.matches(tile, HighBrightnessFlashlightTileService::class.java)) return false
		mTile = tile
		return true
	}

	private fun handleTileClick(arg: Any?) {
		triggerHapticFeedback(arg)
		val controller = HighBrightnessTorchController.getInstance()
		if (controller == null || !controller.isSupported()) return

		if (controller.isOn()) {
			val current = controller.getCurrentBrightness()
			val max = controller.getMaxBrightness()
			if (current >= max) {
				Xprefs.edit().putInt("high_brightness_flashlight_level", 16).apply()
				controller.closeCamera()
			} else {
				val next = Math.min(current + 16, max)
				controller.setBrightness(next)
			}
		} else {
			val last = Xprefs.getInt("high_brightness_flashlight_level", 16)
			controller.setBrightness(last)
		}
		updateTile()
	}

	private fun handleTileLongClick(arg: Any?) {
		triggerHapticFeedback(arg)
		val controller = HighBrightnessTorchController.getInstance()
		if (controller == null || !controller.isSupported()) return

		if (controller.isOn()) {
			controller.closeCamera()
		} else {
			controller.setBrightness(controller.getMaxBrightness())
		}
		updateTile()
	}

	private fun triggerHapticFeedback(arg: Any?) {
		var performed = false
		try {
			var view: View? = null
			if (arg is View) {
				view = arg
			} else if (arg != null) {
				try {
					val v = callMethod(arg, "asView")
					if (v is View) {
						view = v
					}
				} catch (ignored: Throwable) {
				}
			}

			if (view != null) {
				performed = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
				if (!performed) {
					performed = view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
				}
			}
		} catch (ignored: Throwable) {
		}

		if (!performed) {
			try {
				SystemUtils.vibrate(VibrationEffect.EFFECT_CLICK, VibrationAttributes.USAGE_TOUCH)
			} catch (ignored: Throwable) {
			}
		}
	}

	private fun updateTile() {
		if (mTile == null) return
		try {
			val tile = getObjectField(mTile, "mTile") as Tile?
			if (tile == null) return

			val controller = HighBrightnessTorchController.getInstance()
			val isSupported = controller != null && controller.isSupported()
			val isOn = controller != null && controller.isOn()

			tile.setIcon(Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_qs_flashlight))

			if (!isSupported) {
				tile.setState(Tile.STATE_UNAVAILABLE)
				tile.setSubtitle("Not Supported")
			} else {
				tile.setState(if (isOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
				if (isOn) {
					val level = controller!!.getCurrentBrightness()
					val max = controller!!.getMaxBrightness()
					tile.setSubtitle("Level $level/$max")
				} else {
					tile.setSubtitle(null)
				}
			}

			val label = XPLauncher.moduleResources!!.getString(R.string.high_brightness_flashlight_tile_title)
			tile.setContentDescription(label)

			callMethod(mTile, "refreshState", *arrayOf<Any?>(null))
		} catch (t: Throwable) {
			Logger.log("HighBrightnessFlashlightTile: failed to updateTile: $t")
		}
	}
}
