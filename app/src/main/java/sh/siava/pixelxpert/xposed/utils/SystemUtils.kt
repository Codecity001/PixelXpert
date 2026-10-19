package sh.siava.pixelxpert.xposed.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.DownloadManager
import android.app.usage.NetworkStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.AudioManager
import android.media.AudioManager.STREAM_MUSIC
import android.media.AudioPlaybackConfiguration
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.telephony.TelephonyManager
import android.view.WindowManager
import de.robv.android.xposed.XposedBridge.invokeOriginalMethod
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getStaticObjectField
import java.lang.reflect.Method
import kotlin.math.max
import kotlin.math.min
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants.SYSTEM_UI_PACKAGE
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedMethod
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

/**
 * Per-process utility hub: torch control, volume, haptics, dark mode and a
 * handful of root-flavoured helpers.
 *
 * The Java shape is kept - one singleton in [instance] plus static facades
 * spelled like a service (`SystemUtils.WifiManager()`,
 * `SystemUtils.AudioManager()`) - because mod packs call that spelling
 * throughout. Java has one namespace, where a `static` method shares a body
 * with instance methods; Kotlin cannot, hence the split into the class body
 * and the [companion object].
 *
 * Three deviations, all recorded rather than silent:
 *
 *  1. `XposedBridge.log` is routed to [Logger]. Under libxposed the
 *     `XposedBridge` sink is only wired up once a hook is installed through
 *     the legacy Xposed API, so a bare `log` is unreliable. Every such call
 *     here sits behind `BuildConfig.DEBUG`, so nothing observable changes.
 *  2. The Java `Xprefs` static import becomes an import of the property from
 *     the `XPrefs` Kotlin object - same value, Kotlin cannot import a field.
 *  3. [doubleToggleDarkMode] substitutes [threadSleep] for
 *     `Thread.currentThread().wait(100)`; see the comment there.
 *
 * Functional calls (`invokeOriginalMethod`, `callMethod`,
 * `getStaticObjectField`) stay on the Xposed legacy API verbatim:
 * `xposed-legacy-api` is compile-only on the classpath and `vector.dex`
 * ships the support classes. Note `invokeOriginalMethod` takes an
 * `Object[]`, not a vararg, and `Math.round` is used rather than
 * `kotlin.math.round` because the latter returns `Float` where Java's
 * returns `int`.
 */
class SystemUtils(context: Context) {

	private val mContext: Context = context
	private var mPackageManager: PackageManager? = null
	private var mCameraManager: CameraManager? = null
	private var mVibrationManager: VibratorManager? = null
	private var mAudioManager: AudioManager? = null
	private var mPowerManager: PowerManager? = null
	private var mConnectivityManager: ConnectivityManager? = null
	private var mTelephonyManager: TelephonyManager? = null
	private var mAlarmManager: AlarmManager? = null
	private var mDownloadManager: DownloadManager? = null
	private var mNetworkStatsManager: NetworkStatsManager? = null
	private var mHasVibrator = false
	private var maxFlashLevel = -1
	private val mVolumeChangeListeners = ArrayList<ChangeListener>()
	private val mFlashlightLevelListeners = ArrayList<ChangeListener>()
	private var mWifiManager: WifiManager? = null
	private var mWindowManager: WindowManager? = null
	private var mUserManager: UserManager? = null
	private var mHandler: Handler? = null
	private var mSetTorchModeMethod: Method? = null
	private var mTurnOnTorchWithStrengthLevelMethod: Method? = null
	private var mFlashAnimator: ValueAnimator? = null

	init {
		instance = this

		HighBrightnessTorchController.init(context)
		registerVolumeChangeReceiver()
	}

	private fun isTorchOnInternal(): Boolean {
		val hbTorch = HighBrightnessTorchController.getInstance()
		if (Xprefs.getBoolean("enable_high_brightness_flashlight", false) &&
			hbTorch != null && hbTorch.isSupported()
		) {
			if (hbTorch.isOn()) return true
		}
		if (getCameraManager() == null) {
			return false
		}
		return isTorchOn
	}

	private fun setFlashInternal(enabled: Boolean, animate: Boolean) {
		if (getCameraManager() == null) {
			return
		}

		// Hoisted to a local so the three-way chain short-circuits the same way;
		// getInstance() is a plain field read, so nothing observable moves.
		val hbTorch = HighBrightnessTorchController.getInstance()
		if (Xprefs.getBoolean("enable_high_brightness_flashlight", false) &&
			hbTorch != null && hbTorch.isSupported()
		) {
			if (enabled) {
				hbTorch.toggle()
			} else {
				hbTorch.closeCamera()
			}
			return
		}

		try {
			val flashID = getFlashID(getCameraManager())
			if (flashID.isEmpty()) {
				return
			}

			setFlashInternalNoLevel(enabled, animate)
		} catch (t: Throwable) {
			if (BuildConfig.DEBUG) {
				Logger.log("PixelXpert Error in setting flashlight")
				Logger.log(t)
			}
		}
	}

	private fun setFlashInternalNoLevel(enabled: Boolean, animate: Boolean) {
		try {
			if (animate && supportsFlashLevels()) {
				if (enabled) {
					animateFlashLightOn(getFlashStrengthInternal())
				} else {
					animateFlashLightOff()
				}
			} else {
				// `!!` throws the same NPE the Java field dereference would, and
				// it happens inside this try, so it degrades identically.
				try {
					isInternalTorchCall = true
					invokeOriginalMethod(
						mSetTorchModeMethod!!,
						getCameraManager(),
						arrayOf<Any>(getFlashID(getCameraManager()), enabled),
					)
				} finally {
					isInternalTorchCall = false
				}
			}
		} catch (ignored: Throwable) {
		}
	}

	private fun animateFlashLightOn(level: Int) {
		mHandler!!.post {
			mFlashAnimator?.cancel()
			val targetLevel = if (level > 0) level else getMaxFlashLevel()
			val valueAnimator = ValueAnimator
				.ofInt(0, targetLevel)
				.setDuration(FADE_DURATION.toLong())
			valueAnimator.addUpdateListener { animation ->
				setFlashLevel(true, animation.animatedValue as Int)
			}
			valueAnimator.addListener(object : AnimatorListenerAdapter() {
				override fun onAnimationEnd(animation: Animator) {
					if (mFlashAnimator === animation) {
						mFlashAnimator = null
					}
				}
			})
			mFlashAnimator = valueAnimator
			valueAnimator.start()
		}
	}

	private fun animateFlashLightOff() {
		mHandler!!.post {
			mFlashAnimator?.cancel()
			val currentLevel = getFlashStrengthInternal()
			val startLevel = if (currentLevel > 0) currentLevel else getMaxFlashLevel()
			val valueAnimator = ValueAnimator
				.ofInt(startLevel, 0)
				.setDuration(FADE_DURATION.toLong())
			valueAnimator.addUpdateListener { animation ->
				setFlashLevel(true, animation.animatedValue as Int)
			}
			valueAnimator.addListener(object : AnimatorListenerAdapter() {
				private var isCancelled = false

				override fun onAnimationCancel(animation: Animator) {
					isCancelled = true
				}

				override fun onAnimationEnd(animation: Animator) {
					if (!isCancelled) {
						setFlashInternalNoLevel(false, false)
					}
					if (mFlashAnimator === animation) {
						mFlashAnimator = null
					}
				}
			})
			mFlashAnimator = valueAnimator
			valueAnimator.start()
		}
	}

	private fun getFlashlightLevelInternal(flashPct: Float): Int =
		max(
			min(
				Math.round(getMaxFlashLevel() * flashPct),
				getMaxFlashLevel(),
			),
			1,
		)

	private fun supportsFlashLevelsInternal(): Boolean {
		if (maxFlashLevel == -1) {
			refreshFlashLevel()
		}
		return maxFlashLevel > 0
	}

	private fun refreshFlashLevel() {
		try {
			val flashID = getFlashID(getCameraManager())
			if (flashID.isEmpty()) {
				maxFlashLevel = -1
				return
			}
			if (maxFlashLevel == -1) {
				val token = android.os.Binder.clearCallingIdentity()
				try {
					@Suppress("UNCHECKED_CAST")
					val flashInfoStrengthMaximumLevel = getStaticObjectField(
						CameraCharacteristics::class.java,
						"FLASH_INFO_STRENGTH_MAXIMUM_LEVEL",
					) as CameraCharacteristics.Key<Int>
					maxFlashLevel = getCameraManager()!!
						.getCameraCharacteristics(flashID)
						.get(flashInfoStrengthMaximumLevel)!!
					try {
						Xprefs.edit().putInt("cachedMaxFlashLevel", maxFlashLevel).apply()
					} catch (ignored: Throwable) {
					}
				} catch (t: Throwable) {
					maxFlashLevel = Xprefs.getInt("cachedMaxFlashLevel", 45)
				} finally {
					android.os.Binder.restoreCallingIdentity(token)
				}
			}
		} catch (ignored: Throwable) {
		}
	}

	private fun getMaxFlashLevelInternal(): Int {
		val hbTorch = HighBrightnessTorchController.getInstance()
		if (Xprefs.getBoolean("enable_high_brightness_flashlight", false) &&
			hbTorch != null && hbTorch.isSupported()
		) {
			return hbTorch.getMaxBrightness()
		}
		if (maxFlashLevel == -1) {
			refreshFlashLevel()
		}
		return maxFlashLevel
	}

	private fun setFlashInternalWithLevel(enabled: Boolean, level: Int, animate: Boolean) {
		if (animate) {
			if (enabled) {
				animateFlashLightOn(level)
			} else {
				animateFlashLightOff()
			}
		} else {
			setFlashLevel(enabled, level)
		}
	}

	private fun setFlashLevel(enabled: Boolean, level: Int) {
		val hbTorch = HighBrightnessTorchController.getInstance()
		if (Xprefs.getBoolean("enable_high_brightness_flashlight", false) &&
			hbTorch != null && hbTorch.isSupported()
		) {
			if (enabled) {
				hbTorch.setBrightness(level)
			} else {
				hbTorch.closeCamera()
			}
			return
		}
		try {
			val flashID = getFlashID(getCameraManager())
			if (enabled) {
				if (supportsFlashLevels()) {
					// 16QPR3 leveled flashlight uses this method and we hook it,
					// so it has to be run manually.
					try {
						val token = android.os.Binder.clearCallingIdentity()
						try {
							isInternalTorchCall = true
							if (mTurnOnTorchWithStrengthLevelMethod != null) {
								invokeOriginalMethod(
									mTurnOnTorchWithStrengthLevelMethod!!,
									getCameraManager(),
									arrayOf<Any>(flashID, max(level, 1)),
								)
							} else {
								ReflectedMethod.ofName(
									ReflectedClass.of(CameraManager::class.java),
									"turnOnTorchWithStrengthLevel",
								).invokeOriginal(getCameraManager(), flashID, max(level, 1))
							}
						} finally {
							isInternalTorchCall = false
							android.os.Binder.restoreCallingIdentity(token)
						}
					} catch (t: Throwable) {
						setFlashInternalNoLevel(true, false)
					}
				} else {
					setFlashInternalNoLevel(true, false)
				}
			} else {
				setFlashInternalNoLevel(false, false)
			}
		} catch (t: Throwable) {
			if (BuildConfig.DEBUG) {
				Logger.log("PixelXpert Error in setting flashlight")
				Logger.log(t)
			}
		}
	}

	private fun informFlashListeners() {
		for (listener in mFlashlightLevelListeners) {
			listener.onChanged(getFlashStrengthInternal())
		}
	}

	private fun toggleFlashInternal(animate: Boolean) {
		setFlashInternal(!isTorchOn, animate)
	}

	/**
	 * `!!` on the parameter throws when [getCameraManager] returns null -
	 * from inside the `try`, so the `catch` still degrades to `"0"`
	 * ("no flash").
	 */
	private fun getFlashID(cameraManager: CameraManager?): String {
		val token = android.os.Binder.clearCallingIdentity()
		try {
			val ids = cameraManager!!.getCameraIdList()
			for (id in ids) {
				if (cameraManager.getCameraCharacteristics(id)
						.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
				) {
					if (cameraManager.getCameraCharacteristics(id)
							.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
					) {
						return id
					}
				}
			}
		} catch (t: Throwable) {
			return "0"
		} finally {
			android.os.Binder.restoreCallingIdentity(token)
		}
		return "0"
	}

	private fun getFlashStrengthInternal(): Int {
		val hbTorch = HighBrightnessTorchController.getInstance()
		if (Xprefs.getBoolean("enable_high_brightness_flashlight", false) &&
			hbTorch != null && hbTorch.isSupported()
		) {
			return hbTorch.getCurrentBrightness()
		}
		val token = android.os.Binder.clearCallingIdentity()
		return try {
			val level = getCameraManager()!!.getTorchStrengthLevel(getFlashID(getCameraManager()))
			try {
				Xprefs.edit().putInt("cachedFlashLevel", level).apply()
			} catch (ignored: Throwable) {
			}
			level
		} catch (e: Throwable) {
			Xprefs.getInt(
				"cachedFlashLevel",
				Math.round(0.5f * getMaxFlashLevel()),
			)
		} finally {
			android.os.Binder.restoreCallingIdentity(token)
		}
	}

	private fun getIsDark(): Boolean =
		(mContext.resources.configuration.uiMode and UI_MODE_NIGHT_YES) == UI_MODE_NIGHT_YES

	private fun getAudioManager(): AudioManager? {
		if (mAudioManager == null) {
			try {
				mAudioManager = mContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
			} catch (t: Throwable) {
				if (BuildConfig.DEBUG) {
					Logger.log(t)
				}
			}
		}
		return mAudioManager
	}

	private fun getPackageManager(): PackageManager? {
		if (mPackageManager == null) {
			try {
				mPackageManager = mContext.packageManager
			} catch (t: Throwable) {
				if (BuildConfig.DEBUG) {
					Logger.log(t)
				}
			}
		}
		return mPackageManager
	}

	private fun getWifiManager(): WifiManager? {
		if (mWifiManager == null) {
			try {
				mWifiManager = mContext.getSystemService(WifiManager::class.java)
			} catch (t: Throwable) {
				if (BuildConfig.DEBUG) {
					Logger.log("PixelXpert Error getting wifi manager")
					Logger.log(t)
				}
			}
		}
		return mWifiManager
	}

	private fun getConnectivityManager(): ConnectivityManager? {
		if (mConnectivityManager == null) {
			try {
				mConnectivityManager =
					mContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
			} catch (t: Throwable) {
				if (BuildConfig.DEBUG) {
					Logger.log("PixelXpert Error getting connection manager")
					Logger.log(t)
				}
			}
		}
		return mConnectivityManager
	}

	private fun getPowerManager(): PowerManager? {
		if (mPowerManager == null) {
			try {
				mPowerManager = mContext.getSystemService(Context.POWER_SERVICE) as PowerManager
			} catch (t: Throwable) {
				if (BuildConfig.DEBUG) {
					Logger.log("PixelXpert Error getting power manager")
					Logger.log(t)
				}
			}
		}
		return mPowerManager
	}

	private fun getAlarmManager(): AlarmManager? {
		if (mAlarmManager == null) {
			try {
				mAlarmManager = mContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
			} catch (t: Throwable) {
				if (BuildConfig.DEBUG) {
					Logger.log("PixelXpert Error getting alarm manager")
					Logger.log(t)
				}
			}
		}
		return mAlarmManager
	}

	private fun getTelephonyManager(): TelephonyManager? {
		if (mTelephonyManager == null) {
			try {
				mTelephonyManager = mContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
			} catch (t: Throwable) {
				if (BuildConfig.DEBUG) {
					Logger.log("PixelXpert Error getting telephony manager")
					Logger.log(t)
				}
			}
		}
		return mTelephonyManager
	}

	private fun getVibrationManager(): VibratorManager? {
		if (mVibrationManager == null) {
			try {
				val manager = mContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
				mVibrationManager = manager
				mHasVibrator = manager.defaultVibrator.hasVibrator()
			} catch (t: Throwable) {
				if (BuildConfig.DEBUG) {
					Logger.log("PixelXpert Error getting vibrator")
					Logger.log(t)
				}
			}
		}
		return mVibrationManager
	}

	private fun getCameraManager(): CameraManager? {
		if (mCameraManager == null) {
			try {
				val thread = HandlerThread("", THREAD_PRIORITY_BACKGROUND)
				thread.start()
				mHandler = Handler(thread.looper)

				@Suppress("UNCHECKED_CAST")
				val cameraManager = mContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
				mCameraManager = cameraManager

				mSetTorchModeMethod = CameraManager::class.java.getMethod(
					"setTorchMode",
					String::class.java,
					java.lang.Boolean.TYPE,
				)

				mTurnOnTorchWithStrengthLevelMethod = try {
					CameraManager::class.java.getMethod(
						"turnOnTorchWithStrengthLevel",
						String::class.java,
						java.lang.Integer.TYPE,
					)
				} catch (ignored: Throwable) {
					null
				}

				cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
					override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
						super.onTorchModeChanged(cameraId, enabled)
						isTorchOn = enabled
					}
				}, mHandler)

				cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
					override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
						informFlashListeners()
					}
				}, mHandler)
			} catch (t: Throwable) {
				mCameraManager = null
				if (BuildConfig.DEBUG) {
					Logger.log(t)
				}
			}
		}
		return mCameraManager
	}

	private fun getDownloadManager(): DownloadManager? {
		if (mDownloadManager == null) {
			mDownloadManager = mContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
		}
		return mDownloadManager
	}

	private fun getWindowManager(): WindowManager? {
		if (mWindowManager == null) {
			try {
				mWindowManager = mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
			} catch (t: Throwable) {
				if (BuildConfig.DEBUG) {
					Logger.log(t)
				}
			}
		}
		return mWindowManager
	}

	private fun getUserManager(): UserManager? {
		if (mUserManager == null) {
			try {
				mUserManager = mContext.getSystemService(Context.USER_SERVICE) as UserManager
			} catch (t: Throwable) {
				if (BuildConfig.DEBUG) {
					Logger.log(t)
				}
			}
		}
		return mUserManager
	}

	private fun getNetworkStatsManager(): NetworkStatsManager? {
		if (mNetworkStatsManager == null) {
			try {
				mNetworkStatsManager =
					mContext.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
			} catch (t: Throwable) {
				if (BuildConfig.DEBUG) {
					Logger.log("PixelXpert Error getting network stats")
					Logger.log(t)
				}
			}
		}
		return mNetworkStatsManager
	}

	private fun registerVolumeChangeReceiver() {
		getAudioManager()!!.registerAudioPlaybackCallback(
			object : AudioManager.AudioPlaybackCallback() {
				override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
					fireVolumeRefresh()
				}
			},
			null,
		)

		val volChangeReceiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context?, intent: Intent?) {
				if (intent!!.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) == STREAM_MUSIC) {
					fireVolumeRefresh()
				}
			}
		}

		val volumeFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
		mContext.registerReceiver(volChangeReceiver, volumeFilter, RECEIVER_EXPORTED)
	}

	private fun fireVolumeRefresh() {
		val level = getAudioManager()!!.getStreamVolume(STREAM_MUSIC)
		mVolumeChangeListeners.forEach { l -> l.onChanged(level) }
	}

	private fun toggleMuteInternal() {
		try {
			if (getAudioManager()!!.isStreamMute(STREAM_MUSIC)) {
				val unMuteVolume = Math.round(
					(
						AudioManager()!!.getStreamMaxVolume(STREAM_MUSIC) -
							AudioManager()!!.getStreamMinVolume(STREAM_MUSIC)
						) * Xprefs.getSliderInt("UnMuteVolumePCT", 50) / 100f,
				)
				mAudioManager!!.setStreamVolume(STREAM_MUSIC, unMuteVolume, 0)
			} else {
				mAudioManager!!.setStreamVolume(STREAM_MUSIC, 0, 0)
			}
		} catch (ignored: Throwable) {
		}
	}

	private fun hasVibrator(): Boolean = getVibrationManager() != null && mHasVibrator

	@SuppressLint("DiscouragedApi")
	private fun resourceIdOfInternal(name: String, type: String): Int =
		mContext.resources.getIdentifier(name, type, mContext.packageName)

	/**
	 * Single-value change notification, shared by the torch and volume
	 * listeners. Declared `fun interface` so a port may pass a lambda where
	 * Java passed an anonymous class; an `object : ChangeListener` still works.
	 */
	fun interface ChangeListener {
		fun onChanged(newVal: Int)
	}

	companion object {
		private const val THREAD_PRIORITY_BACKGROUND = 10

		const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

		const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"

		const val FADE_DURATION = 500

		@SuppressLint("StaticFieldLeak")
		@Volatile
		internal var instance: SystemUtils? = null

		@Volatile
		internal var isTorchOn = false

		@Volatile
		internal var isInternalTorchCall = false

		@Volatile
		internal var darkSwitching = false

		@JvmStatic
		fun restart(what: String) {
			when (what.lowercase()) {
				"systemui" -> {
					BootLoopProtector.resetCounter(SYSTEM_UI_PACKAGE)
					runRootCommand("killall $SYSTEM_UI_PACKAGE")
				}
				"system" -> runRootCommand("am start -a android.intent.action.REBOOT")
				"zygote", "android" -> {
					runRootCommand("kill \$(pidof zygote)")
					runRootCommand("kill \$(pidof zygote64)")
				}
				"bootloader" -> runRootCommand("reboot bootloader")
				else -> runRootCommand("killall %s".format(what))
			}
		}

		private fun runRootCommand(command: String) {
			XPLauncher.enqueueProxyCommand { proxy ->
				try {
					proxy.runRootCommand(command)
				} catch (ignored: Throwable) {
				}
			}
		}

		@JvmStatic
		fun isFlashOn(): Boolean = instance?.isTorchOnInternal() == true

		@JvmStatic
		fun toggleFlash(animate: Boolean) {
			if (instance != null) {
				instance!!.toggleFlashInternal(animate)
			}
		}

		@JvmStatic
		fun setFlash(enabled: Boolean, level: Int, animate: Boolean) {
			if (instance != null) {
				instance!!.setFlashInternalWithLevel(enabled, level, animate)
			}
		}

		@JvmStatic
		fun setFlash(enabled: Boolean, animate: Boolean) {
			if (instance != null) {
				instance!!.setFlashInternal(enabled, animate)
			}
		}

		@JvmStatic
		fun getFlashlightLevel(flashPct: Float): Int {
			if (instance == null) return 1
			return instance!!.getFlashlightLevelInternal(flashPct)
		}

		@JvmStatic
		fun supportsFlashLevels(): Boolean = instance?.supportsFlashLevelsInternal() == true

		@JvmStatic
		fun getMaxFlashLevel(): Int =
			if (instance == null) {
				-1
			} else {
				instance!!.getMaxFlashLevelInternal()
			}

		@JvmStatic
		fun getFlashStrength(): Int = instance!!.getFlashStrengthInternal()

		@JvmStatic
		fun getFlashStrengthPCT(): Float =
			instance!!.getFlashStrengthInternal() / getMaxFlashLevel().toFloat()

		@JvmStatic
		fun isDarkMode(): Boolean = instance?.getIsDark() == true

		@JvmStatic
		fun doubleToggleDarkMode() {
			XPLauncher.enqueueProxyCommand { proxy ->
				val isDark = isDarkMode()
				Thread {
					try {
						// Replaces `Thread.currentThread().wait(100)`.
						//
						// Two things were verified rather than assumed. Kotlin cannot
						// call `wait` at all - it is absent from `kotlin.Any` - so a
						// literal port does not compile. And in Java the call throws
						// IllegalMonitorStateException on the very first iteration,
						// because this freshly started thread owns no monitor, which
						// the catch below swallows: neither `cmd uimode night` would
						// ever run, making the wait a silent no-op behind four live
						// callers (QSTileGrid, LauncherGestureNavbarManager,
						// TaskbarActivator).
						//
						// threadSleep(100) is what the wait was written to do.
						// Nothing ever notifies this Thread, so a working wait could
						// only ever time out at 100ms. With it the `darkSwitching`
						// guard serialises correctly and the toggle actually runs.
						while (darkSwitching) {
							threadSleep(100)
						}
						darkSwitching = true

						proxy.runRootCommand("cmd uimode night " + (if (isDark) "no" else "yes"))
						threadSleep(1000)
						proxy.runRootCommand("cmd uimode night " + (if (isDark) "yes" else "no"))

						threadSleep(500)
						darkSwitching = false
					} catch (ignored: Exception) {
					}
				}.start()
			}
		}

		@JvmStatic
		fun killSelf() {
			BootLoopProtector.resetCounter(android.os.Process.myProcessName())

			android.os.Process.killProcess(android.os.Process.myPid())
		}

		@JvmStatic
		fun registerFlashlightLevelListener(listener: ChangeListener) {
			if (!instance!!.mFlashlightLevelListeners.contains(listener)) {
				instance!!.mFlashlightLevelListeners.add(listener)
			}
		}

		@JvmStatic
		fun unregisterFlashlightLevelListener(listener: ChangeListener) {
			instance!!.mFlashlightLevelListeners.remove(listener)
		}

		@JvmStatic
		fun registerVolumeChangeListener(listener: ChangeListener) {
			if (instance != null) {
				instance!!.mVolumeChangeListeners.add(listener)
			}
		}

		@JvmStatic
		fun unregisterVolumeChangeListener(listener: ChangeListener) {
			if (instance != null) {
				instance!!.mVolumeChangeListeners.remove(listener)
			}
		}

		@JvmStatic
		fun toggleMute() {
			if (instance != null) {
				instance!!.toggleMuteInternal()
			}
		}

		@JvmStatic
		fun vibrate(effect: Int, vibrationUsage: Int?) {
			vibrate(VibrationEffect.createPredefined(effect), vibrationUsage)
		}

		@SuppressLint("MissingPermission")
		@JvmStatic
		fun vibrate(effect: VibrationEffect, vibrationUsage: Int?) {
			if (instance == null || !instance!!.hasVibrator()) return
			try {
				if (vibrationUsage != null) {
					instance!!.getVibrationManager()!!.defaultVibrator.vibrate(
						effect,
						VibrationAttributes.createForUsage(vibrationUsage),
					)
				} else {
					instance!!.getVibrationManager()!!.defaultVibrator.vibrate(effect)
				}
			} catch (ignored: Exception) {
			}
		}

		@JvmStatic
		fun sleep() {
			if (instance != null) {
				try {
					// 4 = GO_TO_SLEEP_REASON_POWER_BUTTON, forces immediate lock
					callMethod(PowerManager(), "goToSleep", SystemClock.uptimeMillis(), 4, 0)
				} catch (ignored: Throwable) {
					try {
						callMethod(PowerManager(), "goToSleep", SystemClock.uptimeMillis())
					} catch (ignored2: Throwable) {
					}
				}
			}
		}

		@JvmStatic
		fun threadSleep(millis: Int) {
			try {
				Thread.sleep(millis.toLong())
			} catch (ignored: Throwable) {
			}
		}

		@JvmStatic
		fun idOf(name: String): Int = resourceIdOf(name, "id")

		@JvmStatic
		fun dimenIdOf(name: String): Int = resourceIdOf(name, "dimen")

		@JvmStatic
		fun resourceIdOf(name: String, type: String): Int = instance!!.resourceIdOfInternal(name, type)

		@JvmStatic
		fun WifiManager(): WifiManager? = instance?.getWifiManager()

		@JvmStatic
		fun NetworkStatsManager(): NetworkStatsManager? = instance?.getNetworkStatsManager()

		@JvmStatic
		fun CameraManager(): CameraManager? = instance?.getCameraManager()

		@JvmStatic
		fun PackageManager(): PackageManager? = instance?.getPackageManager()

		@JvmStatic
		fun AudioManager(): AudioManager? = instance?.getAudioManager()

		@JvmStatic
		fun WindowManager(): WindowManager? = instance?.getWindowManager()

		@JvmStatic
		fun UserManager(): UserManager? = instance?.getUserManager()

		@JvmStatic
		fun ConnectivityManager(): ConnectivityManager? = instance?.getConnectivityManager()

		@JvmStatic
		fun PowerManager(): PowerManager? = instance?.getPowerManager()

		@JvmStatic
		fun AlarmManager(): AlarmManager? = instance?.getAlarmManager()

		@JvmStatic
		fun TelephonyManager(): TelephonyManager? = instance?.getTelephonyManager()

		@JvmStatic
		fun DownloadManager(): DownloadManager? = instance?.getDownloadManager()
	}
}
