package sh.siava.pixelxpert.xposed.utils;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import java.util.Collections;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XposedBridge;
import sh.siava.pixelxpert.xposed.XPrefs;

public class HighBrightnessTorchController {
    private static HighBrightnessTorchController instance;
    private final CameraManager cameraManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread cameraThread = new HandlerThread("HighBrightnessTorch");
    private final Handler cameraHandler;
    private final Executor cameraExecutor;
    private final SurfaceTexture surfaceTexture = new SurfaceTexture(0);
    private final Surface surface = new Surface(surfaceTexture);

    private String cameraId;
    private int maxBrightness = -1;
    private int curBrightness = 0;
    private boolean isActivating = false;
    private CameraDevice camera;
    private CameraCaptureSession session;

    private static final String NS_2020 = "com.google.pixel.experimental2020";
    private static final CaptureRequest.Key<Integer> REQUEST_FLASHLIGHT_BRIGHTNESS =
            new CaptureRequest.Key<>(NS_2020 + ".flashlightBrightness", Integer.TYPE);
    private static final CaptureRequest.Key<Boolean> REQUEST_FLASHLIGHT_BRIGHTNESS_ENABLED =
            new CaptureRequest.Key<>(NS_2020 + ".flashlightBrightnessEnabled", Boolean.TYPE);
    private static final CameraCharacteristics.Key<Integer> CHARACTERISTICS_FLASHLIGHT_BRIGHTNESS_LEVEL_MAX =
            new CameraCharacteristics.Key<>(NS_2020 + ".flashlightBrightnessLevelMax", Integer.TYPE);

    public static void init(Context context) {
        if (instance == null) {
            instance = new HighBrightnessTorchController(context);
        }
    }

    public static HighBrightnessTorchController getInstance() {
        return instance;
    }

    private HighBrightnessTorchController(Context context) {
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraExecutor = command -> cameraHandler.post(command);
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        updateCameraDetails();
    }

    private boolean updateCameraDetails() {
        if (cameraManager == null) return false;
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flashAvailable != null && flashAvailable) {
                    Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (lensFacing != null && lensFacing == CameraMetadata.LENS_FACING_BACK) {
                        try {
                            Integer max = characteristics.get(CHARACTERISTICS_FLASHLIGHT_BRIGHTNESS_LEVEL_MAX);
                            if (max != null && max > 0) {
                                cameraId = id;
                                maxBrightness = max;
                                return true;
                            }
                        } catch (IllegalArgumentException e) {
                            // Key not supported
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    public boolean isSupported() {
        if (maxBrightness == -1) {
            updateCameraDetails();
        }
        return maxBrightness > 0;
    }

    public int getMaxBrightness() {
        if (maxBrightness == -1) {
            updateCameraDetails();
        }
        return maxBrightness;
    }

    public int getCurrentBrightness() {
        return curBrightness;
    }

    public boolean isOn() {
        return camera != null && (isActivating || curBrightness > 0);
    }

    public void setBrightness(int brightness) {
        if (!isSupported()) return;

        brightness = Math.min(Math.max(brightness, 0), maxBrightness);

        if (brightness == 0) {
            closeCamera();
            return;
        }

        XPrefs.Xprefs.edit().putInt("high_brightness_flashlight_level", brightness).apply();

        if (camera == null && !isActivating) {
            curBrightness = brightness;
            openCamera();
        } else if (session != null) {
            curBrightness = brightness;
            performCapture();
        } else {
            curBrightness = brightness;
        }
    }

    public void toggle() {
        if (isOn()) {
            setBrightness(0);
        } else {
            int lastBrightness = XPrefs.Xprefs.getInt("high_brightness_flashlight_level", maxBrightness);
            setBrightness(lastBrightness);
        }
    }

    private void openCamera() {
        isActivating = true;
        try {
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice c) {
                    camera = c;
                    createSession();
                }

                @Override
                public void onDisconnected(CameraDevice c) {
                    closeCamera();
                }

                @Override
                public void onError(CameraDevice c, int error) {
                    closeCamera();
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            isActivating = false;
        }
    }

    private void createSession() {
        try {
            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(new OutputConfiguration(surface)),
                    cameraExecutor,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            isActivating = false;
                            performCapture();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            closeCamera();
                        }
                    }
            );
            camera.createCaptureSession(sessionConfig);
        } catch (CameraAccessException e) {
            closeCamera();
        }
    }

    private void performCapture() {
        if (session == null || curBrightness == 0) return;
        try {
            CaptureRequest.Builder builder = session.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_MANUAL);
            builder.addTarget(surface);
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
            builder.set(REQUEST_FLASHLIGHT_BRIGHTNESS_ENABLED, true);
            builder.set(REQUEST_FLASHLIGHT_BRIGHTNESS, curBrightness);
            session.capture(builder.build(), null, cameraHandler);
        } catch (CameraAccessException e) {
            closeCamera();
        }
    }

    public void closeCamera() {
        curBrightness = 0;
        isActivating = false;
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {}
            session = null;
        }
        if (camera != null) {
            try {
                camera.close();
            } catch (Exception ignored) {}
            camera = null;
        }
    }
}
