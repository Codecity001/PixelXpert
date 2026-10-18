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
import java.util.Collections
import java.util.concurrent.Executor
import sh.siava.pixelxpert.xposed.XPrefs
import kotlin.math.max
import kotlin.math.min

class HighBrightnessTorchController private constructor(context: Context) {

    private val cameraManager: CameraManager? = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraThread = HandlerThread("HighBrightnessTorch").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executor { command -> cameraHandler.post(command) }
    private val surfaceTexture = SurfaceTexture(0)
    private val surface = Surface(surfaceTexture)

    private var cameraId: String? = null
    private var maxBrightness = -1
    private var curBrightness = 0
    private var isActivating = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    init {
        updateCameraDetails()
    }

    companion object {
        @Volatile
        private var instance: HighBrightnessTorchController? = null

        private const val NS_2020 = "com.google.pixel.experimental2020"
        private val REQUEST_FLASHLIGHT_BRIGHTNESS =
            CaptureRequest.Key<Int>("$NS_2020.flashlightBrightness", java.lang.Integer.TYPE)
        private val REQUEST_FLASHLIGHT_BRIGHTNESS_ENABLED =
            CaptureRequest.Key<Boolean>("$NS_2020.flashlightBrightnessEnabled", java.lang.Boolean.TYPE)
        private val CHARACTERISTICS_FLASHLIGHT_BRIGHTNESS_LEVEL_MAX =
            CameraCharacteristics.Key<Int>("$NS_2020.flashlightBrightnessLevelMax", java.lang.Integer.TYPE)

        @JvmStatic
        fun init(context: Context) {
            if (instance == null) {
                synchronized(HighBrightnessTorchController::class.java) {
                    if (instance == null) {
                        instance = HighBrightnessTorchController(context)
                    }
                }
            }
        }

        @JvmStatic
        fun getInstance(): HighBrightnessTorchController? = instance
    }

    private fun updateCameraDetails(): Boolean {
        if (cameraManager == null) return false
        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                if (flashAvailable == true) {
                    val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (lensFacing == CameraMetadata.LENS_FACING_BACK) {
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
            // Ignore
        }
        return false
    }

    fun isSupported(): Boolean {
        if (maxBrightness == -1) {
            updateCameraDetails()
        }
        return maxBrightness > 0
    }

    fun getMaxBrightness(): Int {
        if (maxBrightness == -1) {
            updateCameraDetails()
        }
        return maxBrightness
    }

    fun getCurrentBrightness(): Int = curBrightness

    fun isOn(): Boolean = camera != null && (isActivating || curBrightness > 0)

    fun setBrightness(brightness: Int) {
        if (!isSupported()) return

        var targetBrightness = min(max(brightness, 0), maxBrightness)

        if (targetBrightness == 0) {
            closeCamera()
            return
        }

        XPrefs.Xprefs.edit().putInt("high_brightness_flashlight_level", targetBrightness).apply()

        if (camera == null && !isActivating) {
            curBrightness = targetBrightness
            openCamera()
        } else if (session != null) {
            curBrightness = targetBrightness
            performCapture()
        } else {
            curBrightness = targetBrightness
        }
    }

    fun toggle() {
        if (isOn()) {
            setBrightness(0)
        } else {
            val lastBrightness = XPrefs.Xprefs.getInt("high_brightness_flashlight_level", maxBrightness)
            setBrightness(lastBrightness)
        }
    }

    private fun openCamera() {
        val cid = cameraId ?: return
        isActivating = true
        try {
            cameraManager?.openCamera(cid, object : CameraDevice.StateCallback() {
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
            }, cameraHandler)
        } catch (e: Exception) {
            isActivating = false
        }
    }

    private fun createSession() {
        val cam = camera ?: return
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
                }
            )
            cam.createCaptureSession(sessionConfig)
        } catch (e: CameraAccessException) {
            closeCamera()
        }
    }

    private fun performCapture() {
        val s = session ?: return
        if (curBrightness == 0) return
        try {
            val builder = s.device.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL)
            builder.addTarget(surface)
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            builder.set(REQUEST_FLASHLIGHT_BRIGHTNESS_ENABLED, true)
            builder.set(REQUEST_FLASHLIGHT_BRIGHTNESS, curBrightness)
            s.capture(builder.build(), null, cameraHandler)
        } catch (e: CameraAccessException) {
            closeCamera()
        }
    }

    fun closeCamera() {
        curBrightness = 0
        isActivating = false
        if (session != null) {
            try {
                session?.close()
            } catch (ignored: Exception) {}
            session = null
        }
        if (camera != null) {
            try {
                camera?.close()
            } catch (ignored: Exception) {}
            camera = null
        }
    }
}
