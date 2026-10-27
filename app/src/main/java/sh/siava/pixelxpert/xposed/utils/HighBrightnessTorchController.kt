package sh.siava.pixelxpert.xposed.utils

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import sh.siava.pixelxpert.xposed.XPrefs
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger
import java.util.Collections
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

/**
 * Holds the torch open through camera2 while pushing the Google
 * `experimental2020` flashlight brightness keys, which is what lets the panel
 * run brighter than the usual ceiling while the light is on.
 *
 * It is a lazily built singleton: [init] creates the one instance and
 * [getInstance] hands it out (null until [init] has run).
 */
class HighBrightnessTorchController private constructor(context: Context) {
	private val cameraManager: CameraManager?
	private val mainHandler = Handler(Looper.getMainLooper())
	private val cameraThread = HandlerThread("HighBrightnessTorch")
	private val cameraHandler: Handler
	private val cameraExecutor: Executor
	private val surfaceTexture = SurfaceTexture(0)
	private val surface = Surface(surfaceTexture)

	private var cameraId: String? = null
	private var maxBrightness = -1
	private var curBrightness = 0
	private var isActivating = false
	private var camera: CameraDevice? = null
	private var session: CameraCaptureSession? = null

	companion object {
		private var instance: HighBrightnessTorchController? = null

		private const val NS_2020 = "com.google.pixel.experimental2020"
		private val REQUEST_FLASHLIGHT_BRIGHTNESS =
			CaptureRequest.Key(NS_2020 + ".flashlightBrightness", Integer.TYPE)
		private val REQUEST_FLASHLIGHT_BRIGHTNESS_ENABLED =
			CaptureRequest.Key(NS_2020 + ".flashlightBrightnessEnabled", java.lang.Boolean.TYPE)
		private val CHARACTERISTICS_FLASHLIGHT_BRIGHTNESS_LEVEL_MAX =
			CameraCharacteristics.Key(NS_2020 + ".flashlightBrightnessLevelMax", Integer.TYPE)

		/** Builds the shared instance on the first call; later calls do nothing. */
		@JvmStatic
		fun init(context: Context) {
			if (instance == null) {
				instance = HighBrightnessTorchController(context)
			}
		}

		/** The shared instance, or null when [init] has not run yet. */
		@JvmStatic
		fun getInstance(): HighBrightnessTorchController? = instance

		/** Shuts the controller down and drops the singleton before a hot reload. */
		@JvmStatic
		fun release() {
			instance?.releaseInstance()
			instance = null
		}
	}

	init {
		cameraThread.start()
		cameraHandler = Handler(cameraThread.looper)
		cameraExecutor = Executor { command -> cameraHandler.post(command) }
		cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
		updateCameraDetails()
	}

	private fun updateCameraDetails(): Boolean {
		if (cameraManager == null) return false
		try {
			for (id in cameraManager.cameraIdList) {
				val characteristics = cameraManager.getCameraCharacteristics(id)
				val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
				if (flashAvailable != null && flashAvailable) {
					val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
					if (lensFacing != null && lensFacing == CameraMetadata.LENS_FACING_BACK) {
						try {
							val max = characteristics.get(CHARACTERISTICS_FLASHLIGHT_BRIGHTNESS_LEVEL_MAX)
							if (max != null && max > 0) {
								cameraId = id
								maxBrightness = max
								return true
							}
						} catch (e: IllegalArgumentException) {
							// Key not supported
						}
					}
				}
			}
		} catch (e: Exception) {
		}
		return false
	}

	/** Whether the `experimental2020` flashlight brightness keys exist on this device. */
	fun isSupported(): Boolean {
		if (maxBrightness == -1) {
			updateCameraDetails()
		}
		return maxBrightness > 0
	}

	/** Highest level the device accepts, re-probing the camera if it was never read. */
	fun getMaxBrightness(): Int {
		if (maxBrightness == -1) {
			updateCameraDetails()
		}
		return maxBrightness
	}

	/** The level currently applied to the torch, or 0 when it is off. */
	fun getCurrentBrightness(): Int = curBrightness

	/** True while the torch is open or an activation is in flight. */
	fun isOn(): Boolean = camera != null && (isActivating || curBrightness > 0)

	/**
	 * Applies [brightness] clamped to `0..maxBrightness`. Zero closes the
	 * camera; anything else is persisted and either opens the camera, re-captures,
	 * or just records the level while a previous activation is still running.
	 */
	fun setBrightness(brightness: Int) {
		if (!isSupported()) return

		val level = min(max(brightness, 0), maxBrightness)

		if (level == 0) {
			closeCamera()
			return
		}

		XPrefs.Xprefs.edit().putInt("high_brightness_flashlight_level", level).apply()

		if (camera == null && !isActivating) {
			curBrightness = level
			openCamera()
		} else if (session != null) {
			curBrightness = level
			performCapture()
		} else {
			curBrightness = level
		}
	}

	/** Turns the torch off if it is on, otherwise restores the last saved level. */
	fun toggle() {
		if (isOn()) {
			setBrightness(0)
		} else {
			val lastBrightness = XPrefs.Xprefs.getInt("high_brightness_flashlight_level", maxBrightness)
			setBrightness(lastBrightness)
		}
	}

	private fun openCamera() {
		isActivating = true
		try {
			cameraManager!!.openCamera(
				cameraId!!,
				object : CameraDevice.StateCallback() {
					override fun onOpened(c: CameraDevice) {
						camera = c
						createSession()
					}

					override fun onDisconnected(c: CameraDevice) {
						closeCamera()
					}

					override fun onError(c: CameraDevice, error: Int) {
						closeCamera()
					}
				},
				cameraHandler,
			)
		} catch (e: CameraAccessException) {
			isActivating = false
		} catch (e: SecurityException) {
			isActivating = false
		}
	}

	private fun createSession() {
		try {
			val sessionConfig = SessionConfiguration(
				SessionConfiguration.SESSION_REGULAR,
				Collections.singletonList(OutputConfiguration(surface)),
				cameraExecutor,
				object : CameraCaptureSession.StateCallback() {
					override fun onConfigured(s: CameraCaptureSession) {
						session = s
						isActivating = false
						performCapture()
					}

					override fun onConfigureFailed(s: CameraCaptureSession) {
						closeCamera()
					}
				},
			)
			camera!!.createCaptureSession(sessionConfig)
		} catch (e: CameraAccessException) {
			closeCamera()
		}
	}

	private fun performCapture() {
		if (session == null || curBrightness == 0) return
		try {
			val builder = session!!.device.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL)
			builder.addTarget(surface)
			builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
			builder.set(REQUEST_FLASHLIGHT_BRIGHTNESS_ENABLED, true)
			builder.set(REQUEST_FLASHLIGHT_BRIGHTNESS, curBrightness)
			session!!.capture(builder.build(), null, cameraHandler)
		} catch (e: CameraAccessException) {
			closeCamera()
		}
	}

	/** Zeroes the level and releases the session and device, ignoring close failures. */
	fun closeCamera() {
		curBrightness = 0
		isActivating = false
		if (session != null) {
			try {
				session!!.close()
			} catch (ignored: Exception) {
			}
			session = null
		}
		if (camera != null) {
			try {
				camera!!.close()
			} catch (ignored: Exception) {
			}
			camera = null
		}
	}

	/** Closes the camera and stops the private handler thread. */
	private fun releaseInstance() {
		closeCamera()
		cameraThread.quitSafely()
		runCatching { surface.release() }
		runCatching { surfaceTexture.release() }
	}
}
