package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Blocks sensitive Quick Settings connectivity tiles (Internet, Wifi, Cellular,
 * Mobile Data, Bluetooth, Airplane Mode, Hotspot), related dialogs, and the
 * SystemUI power menu dialog while the device is locked, when
 * [BlockQSPowerOnLockscreen] is enabled.
 * Rejects attempts silently with tactile vibration feedback.
 */
@SystemUIModPack
class LockscreenProtectionSystemUI(context: Context) : XposedModPack(context) {

	companion object {
		private var blockQSPowerOnLockscreen = false
		private var lastVibrateTime = 0L

		private val SENSITIVE_TILES = arrayOf(
			"com.android.systemui.qs.tiles.InternetTile",
			"com.android.systemui.qs.tiles.InternetTileNewImpl",
			"com.android.systemui.qs.tiles.CellularTile",
			"com.android.systemui.qs.tiles.MobileDataTile",
			"com.android.systemui.qs.tiles.WifiTile",
			"com.android.systemui.qs.tiles.BluetoothTile",
			"com.android.systemui.qs.tiles.AirplaneModeTile",
			"com.android.systemui.qs.tiles.HotspotTile",
		)

		private val USER_ACTION_INTERACTORS = arrayOf(
			"com.android.systemui.qs.tiles.impl.internet.domain.interactor.InternetTileUserActionInteractor",
			"com.android.systemui.qs.tiles.impl.wifi.domain.interactor.WifiTileUserActionInteractor",
			"com.android.systemui.qs.tiles.impl.cell.domain.interactor.MobileDataTileUserActionInteractor",
			"com.android.systemui.qs.tiles.impl.cellular.domain.interactor.CellularTileUserActionInteractor",
			"com.android.systemui.qs.tiles.impl.bluetooth.domain.interactor.BluetoothTileUserActionInteractor",
			"com.android.systemui.qs.tiles.impl.airplane.domain.interactor.AirplaneModeTileUserActionInteractor",
			"com.android.systemui.qs.tiles.impl.hotspot.domain.interactor.HotspotTileUserActionInteractor",
		)
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		blockQSPowerOnLockscreen = Xprefs.getBoolean("BlockQSPowerOnLockscreen", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		hookQSTiles()
		hookUserActionInteractors()
		hookDialogs()
		hookPowerMenu()
	}

	private fun hookQSTiles() {
		val QSTileImplClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.tileimpl.QSTileImpl")
		arrayOf("click", "secondaryClick", "longClick").forEach { methodName ->
			QSTileImplClass
				.before(methodName)
				.run { param ->
					if (!blockQSPowerOnLockscreen) return@run
					if (isSensitiveTile(param.thisObject) && isLocked(param.thisObject)) {
						param.setResult(null)
						rejectFeedback()
					}
				}
		}

		SENSITIVE_TILES.forEach { className ->
			val tileClass = ReflectedClass.ofIfPossible(className)
			arrayOf("handleClick", "handleSecondaryClick", "handleLongClick", "handleClickWithSatelliteCheck").forEach { methodName ->
				tileClass
					.before(methodName)
					.run { param ->
						if (!blockQSPowerOnLockscreen) return@run
						if (isLocked(param.thisObject)) {
							param.setResult(null)
							rejectFeedback()
						}
					}
			}
		}
	}

	private fun hookUserActionInteractors() {
		USER_ACTION_INTERACTORS.forEach { className ->
			val interactorClass = ReflectedClass.ofIfPossible(className)
			interactorClass
				.before("handleInput")
				.run { param ->
					if (!blockQSPowerOnLockscreen) return@run
					if (isLocked()) {
						param.setResult(Unit)
						rejectFeedback()
					}
				}
		}
	}

	private fun hookDialogs() {
		val InternetDialogManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.tiles.dialog.InternetDialogManager")
		InternetDialogManagerClass
			.before("create")
			.run { param ->
				if (!blockQSPowerOnLockscreen) return@run
				if (isLocked()) {
					param.setResult(null)
					rejectFeedback()
				}
			}

		val BluetoothTileDialogDelegateClass = ReflectedClass.ofIfPossible("com.android.systemui.bluetooth.qsdialog.BluetoothTileDialogDelegate")
		BluetoothTileDialogDelegateClass
			.before("createDialog")
			.run { param ->
				if (!blockQSPowerOnLockscreen) return@run
				if (isLocked()) {
					param.setResult(null)
					rejectFeedback()
				}
			}

		val InternetDialogControllerImplClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.tiles.dialog.InternetDialogControllerImpl")
		InternetDialogControllerImplClass
			.before("onUserClickedInternetDialog")
			.run { param ->
				if (!blockQSPowerOnLockscreen) return@run
				if (isLocked()) {
					param.setResult(null)
					rejectFeedback()
				}
			}
	}

	private fun hookPowerMenu() {
		val GlobalActionsDialogLiteClass = ReflectedClass.ofIfPossible("com.android.systemui.globalactions.GlobalActionsDialogLite")
		GlobalActionsDialogLiteClass
			.before("showOrHideDialog")
			.run { param ->
				if (!blockQSPowerOnLockscreen) return@run
				val keyguardShowingArg = param.args.getOrNull(0) as? Boolean
				if (keyguardShowingArg == true || isLocked()) {
					param.setResult(null)
					rejectFeedback()
				}
			}

		GlobalActionsDialogLiteClass
			.before("handleShow")
			.run { param ->
				if (!blockQSPowerOnLockscreen) return@run
				if (isLocked()) {
					param.setResult(null)
					rejectFeedback()
				}
			}

		val GlobalActionsImplClass = ReflectedClass.ofIfPossible("com.android.systemui.globalactions.GlobalActionsImpl")
		GlobalActionsImplClass
			.before("showGlobalActions")
			.run { param ->
				if (!blockQSPowerOnLockscreen) return@run
				if (isLocked()) {
					param.setResult(null)
					rejectFeedback()
				}
			}
	}

	private fun isSensitiveTile(tile: Any?): Boolean {
		if (tile == null) return false
		try {
			val spec = getObjectField(tile, "mTileSpec") as? String
			if (spec != null && isSensitiveSpec(spec)) return true
		} catch (ignored: Throwable) {}
		try {
			val spec = callMethod(tile, "getTileSpec") as? String
			if (spec != null && isSensitiveSpec(spec)) return true
		} catch (ignored: Throwable) {}
		return isSensitiveClassName(tile.javaClass.name)
	}

	private fun isSensitiveSpec(spec: String): Boolean {
		val s = spec.lowercase()
		return s == "internet" || s == "wifi" || s == "cell" || s == "cellular" ||
			s == "mobiledata" || s == "bt" || s == "bluetooth" || s == "airplane" ||
			s == "hotspot"
	}

	private fun isSensitiveClassName(className: String): Boolean {
		val n = className.lowercase()
		return n.contains("internettile") || n.contains("wifitile") ||
			n.contains("cellulartile") || n.contains("mobiledatatile") ||
			n.contains("bluetoothtile") || n.contains("airplanemodetile") ||
			n.contains("hotspottile")
	}

	private fun isLocked(tile: Any? = null): Boolean {
		try {
			if (tile != null) {
				val ssc = getObjectField(tile, "mStatusBarStateController")
				if (ssc != null) {
					val state = callMethod(ssc, "getState") as? Int
					// 1 = KEYGUARD, 2 = SHADE_LOCKED
					if (state == 1 || state == 2) return true
				}
			}
		} catch (ignored: Throwable) {}
		try {
			val km = mContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
			if (km != null && (km.isKeyguardLocked || km.isDeviceLocked || km.inKeyguardRestrictedInputMode())) {
				return true
			}
		} catch (ignored: Throwable) {}
		return false
	}

	private fun rejectFeedback() {
		val now = SystemClock.uptimeMillis()
		if (now - lastVibrateTime < 300) return
		lastVibrateTime = now
		try {
			val pattern = longArrayOf(0, 40, 50, 40)
			val effect = VibrationEffect.createWaveform(pattern, -1)
			SystemUtils.vibrate(effect, VibrationAttributes.USAGE_TOUCH)
		} catch (t: Throwable) {
			try {
				val vib = mContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
				vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 50, 40), -1))
			} catch (ignored: Throwable) {}
		}
	}
}
