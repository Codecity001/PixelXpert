package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.CountDownTimer
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.service.quicksettings.Tile
import android.view.HapticFeedbackConstants
import android.view.View
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.service.tileServices.CaffeineTileService
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.CustomTileMatcher
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setObjectField
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

/**
 * Gives the module's caffeine (keep-awake) tile its behaviour inside QQS.
 *
 * The tile is backed by [CaffeineTileService], so SystemUI creates it through
 * `QSFactoryImpl.createTile` as an opaque `CustomTile` rather than through
 * any factory the module could subclass. The pack captures the live tile the
 * moment the factory hands it over, then owns the keep-awake itself: a click
 * takes a `FULL_WAKE_LOCK` and starts a countdown through the 5, 10 and 30
 * minute durations, a second click inside five seconds advances to the next
 * duration and past the last one switches everything off, and a long click
 * jumps straight to the infinite duration. A screen-off receiver releases the
 * lock and resets the tile, a ticking `CountDownTimer` feeds the remaining
 * time into the tile's subtitle and secondary label, the long-click intent is
 * swapped for the screen-timeout settings activity (falling back to display
 * settings) so accessibility can never crash on the real one, and both
 * interactions tap with CONFIRM / CONTEXT_CLICK haptics before falling back
 * to a system vibration.
 */
@SystemUIModPack
class CaffeineTile(context: Context) : XposedModPack(context) {

	companion object {
		private const val TAG = "CaffeineTile"

		private val DURATIONS = intArrayOf(
			5 * 60, // 5 min
			10 * 60, // 10 min
			30 * 60, // 30 min
			-1, // infinity
		)
		private val INFINITE_DURATION_INDEX = DURATIONS.size - 1
	}

	private var mTile: Any? = null

	private var mWakeLock: PowerManager.WakeLock? = null
	private var mSecondsRemaining = 0
	private var mDuration = -1 // -1 means off/uninitialized. Index into DURATIONS
	private var mCountdownTimer: CountDownTimer? = null
	var mLastClickTime: Long = -1
	private var mScreenOffReceiver: BroadcastReceiver? = null

	override fun onPreferenceUpdated(vararg key: String?) {
	}

	override fun onUnloaded() {
		destroyCaffeine()
		mTile = null
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val CustomTileClass = ReflectedClass.of("com.android.systemui.qs.external.CustomTile")
		val QSFactoryImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSFactoryImpl")

		QSFactoryImplClass
			.after("createTile")
			.run { param ->
				val arg = param.args[0] as String?
				if (arg != null && arg.contains(CaffeineTileService::class.java.simpleName)) {
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
					param.setResult(null) // Prevent invoking TileService over IPC
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

					val isHeld = mWakeLock != null && mWakeLock!!.isHeld()
					try {
						setObjectField(state, "value", isHeld)
					} catch (ignored: NoSuchFieldError) {
					}
					setObjectField(state, "state", if (isHeld) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)

					if (isHeld) {
						setObjectField(state, "secondaryLabel", formatValueWithRemainingTime())
					} else {
						setObjectField(state, "secondaryLabel", null)
					}
				}
			}

		CustomTileClass
			.before("getLongClickIntent")
			.run { param ->
				if (adoptTile(param.thisObject)) {
					// Return a safe dummy intent to prevent crashes if accessibility invokes it
					var intent = Intent("android.settings.SCREEN_TIMEOUT_SETTINGS")
					intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
					if (intent.resolveActivity(mContext.getPackageManager()) == null) {
						intent = Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
						intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
					}
					param.setResult(intent)
				}
			}

		CustomTileClass
			.before("handleDestroy")
			.run { param ->
				if (CustomTileMatcher.matches(param.thisObject, CaffeineTileService::class.java)) {
					destroyCaffeine()
					mTile = null
				}
			}
	}

	private fun adoptTile(tile: Any?): Boolean {
		if (!CustomTileMatcher.matches(tile, CaffeineTileService::class.java)) return false
		if (mTile !== tile) {
			mTile = tile
			initCaffeine()
		}
		return true
	}

	private fun initCaffeine() {
		if (mWakeLock == null) {
			val pm = mContext.getSystemService(PowerManager::class.java)
			if (pm != null) {
				mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "PixelXpert:CaffeineTile")
			}
		}
		if (mScreenOffReceiver == null) {
			mScreenOffReceiver = object : BroadcastReceiver() {
				override fun onReceive(context: Context, intent: Intent) {
					if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
						stopCountDown()
						if (mWakeLock != null && mWakeLock!!.isHeld()) {
							mWakeLock!!.release()
						}
						mDuration = -1
						updateTile()
					}
				}
			}
			val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
			mContext.registerReceiver(mScreenOffReceiver, filter, null, null)
		}
	}

	private fun destroyCaffeine() {
		stopCountDown()
		if (mScreenOffReceiver != null) {
			try {
				mContext.unregisterReceiver(mScreenOffReceiver)
			} catch (ignored: Exception) {
			}
			mScreenOffReceiver = null
		}
		if (mWakeLock != null && mWakeLock!!.isHeld()) {
			mWakeLock!!.release()
		}
	}

	private fun handleTileClick(arg: Any?) {
		triggerHapticFeedback(arg)

		if (mWakeLock == null) return

		if (mWakeLock!!.isHeld() && (mLastClickTime != -1L) &&
			(SystemClock.elapsedRealtime() - mLastClickTime < 5000)
		) {
			mDuration++
			if (mDuration >= DURATIONS.size) {
				mDuration = -1
				stopCountDown()
				if (mWakeLock!!.isHeld()) {
					mWakeLock!!.release()
				}
			} else {
				startCountDown(DURATIONS[mDuration].toLong())
				if (!mWakeLock!!.isHeld()) {
					mWakeLock!!.acquire()
				}
			}
		} else {
			if (mWakeLock!!.isHeld()) {
				mWakeLock!!.release()
				stopCountDown()
			} else {
				mWakeLock!!.acquire()
				mDuration = 0
				startCountDown(DURATIONS[mDuration].toLong())
			}
		}
		mLastClickTime = SystemClock.elapsedRealtime()
		updateTile()
	}

	private fun handleTileLongClick(arg: Any?) {
		triggerHapticFeedback(arg)

		if (mWakeLock == null) return

		if (mWakeLock!!.isHeld()) {
			if (mDuration == INFINITE_DURATION_INDEX) {
				return
			}
		} else {
			mWakeLock!!.acquire()
		}
		mDuration = INFINITE_DURATION_INDEX
		startCountDown(DURATIONS[INFINITE_DURATION_INDEX].toLong())
		updateTile()
	}

	private fun startCountDown(duration: Long) {
		stopCountDown()
		mSecondsRemaining = duration.toInt()
		if (duration == -1L) {
			return
		}
		mCountdownTimer = object : CountDownTimer(duration * 1000, 1000) {
			override fun onTick(millisUntilFinished: Long) {
				mSecondsRemaining = (millisUntilFinished / 1000).toInt()
				updateTile()
			}

			override fun onFinish() {
				if (mWakeLock != null && mWakeLock!!.isHeld()) {
					mWakeLock!!.release()
				}
				updateTile()
			}
		}.start()
	}

	private fun stopCountDown() {
		if (mCountdownTimer != null) {
			mCountdownTimer!!.cancel()
			mCountdownTimer = null
		}
	}

	private fun formatValueWithRemainingTime(): String {
		if (mSecondsRemaining == -1) {
			return "∞" // infinity
		}
		return String.format("%02d:%02d", mSecondsRemaining / 60 % 60, mSecondsRemaining % 60)
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
			val tile = getObjectField(mTile, "mTile") as? Tile ?: return

			val isHeld = mWakeLock != null && mWakeLock!!.isHeld()
			tile.setIcon(Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_qs_caffeine))
			tile.setState(if (isHeld) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)

			val formatted = formatValueWithRemainingTime()
			if (isHeld) {
				tile.setSubtitle(formatted)
			} else {
				tile.setSubtitle(null)
			}

			val label = XPLauncher.moduleResources!!.getString(R.string.caffeine_tile_title)
			if (isHeld) {
				tile.setContentDescription(label + ": " + formatted)
			} else {
				tile.setContentDescription(label + ": Off")
			}

			callMethod(mTile, "refreshState", *arrayOf<Any?>(null))
		} catch (t: Throwable) {
			Logger.log("CaffeineTile: failed to updateTile: $t")
		}
	}
}
