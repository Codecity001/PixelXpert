package sh.siava.pixelxpert.xposed.utils;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Icon;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XposedBridge;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.xposed.XPLauncher;
import sh.siava.pixelxpert.xposed.XPrefs;

public class HighBrightnessTorchController {
    public interface TorchCallback {
        void onTorchStateChanged(boolean isOn, int brightness);
    }

    private static HighBrightnessTorchController instance;
    private final Context context;
    private final NotificationManager notificationManager;
    private final CameraManager cameraManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread cameraThread = new HandlerThread("HighBrightnessTorch");
    private final Handler cameraHandler;
    private final Executor cameraExecutor;
    private final SurfaceTexture surfaceTexture = new SurfaceTexture(0);
    private final Surface surface = new Surface(surfaceTexture);
    private final List<TorchCallback> callbacks = new CopyOnWriteArrayList<>();

    private String cameraId;
    private int maxBrightness = -1;
    private int curBrightness = 0;
    private boolean isActivating = false;
    private CameraDevice camera;
    private CameraCaptureSession session;

    private static final String NOTIFICATION_CHANNEL_ID = "high_brightness_torch_channel";
    private static final String NOTIFICATION_TAG = "pixelxpert_torch";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_TURN_OFF = BuildConfig.APPLICATION_ID + ".ACTION_TURN_OFF_HIGH_BRIGHTNESS_FLASHLIGHT";

    private final BroadcastReceiver turnOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_TURN_OFF.equals(intent.getAction())) {
                closeCamera();
            }
        }
    };

    private static final String NS_2020 = "com.google.pixel.experimental2020";
    private static final CaptureRequest.Key<Integer> REQUEST_FLASHLIGHT_BRIGHTNESS =
            new CaptureRequest.Key<>(NS_2020 + ".flashlightBrightness", Integer.TYPE);
    private static final CaptureRequest.Key<Boolean> REQUEST_FLASHLIGHT_BRIGHTNESS_ENABLED =
            new CaptureRequest.Key<>(NS_2020 + ".flashlightBrightnessEnabled", Boolean.TYPE);
    private static final CameraCharacteristics.Key<Integer> CHARACTERISTICS_FLASHLIGHT_BRIGHTNESS_LEVEL_MAX =
            new CameraCharacteristics.Key<>(NS_2020 + ".flashlightBrightnessLevelMax", Integer.TYPE);

    public static void init(Context context) {
        if (instance == null && context != null) {
            instance = new HighBrightnessTorchController(context);
        }
    }

    public static HighBrightnessTorchController getInstance() {
        return instance;
    }

    private HighBrightnessTorchController(Context context) {
        this.context = context;
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            IntentFilter filter = new IntentFilter(ACTION_TURN_OFF);
            context.registerReceiver(turnOffReceiver, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XposedBridge.log("HighBrightnessTorchController: Failed to register receiver: " + t);
        }

        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraExecutor = command -> cameraHandler.post(command);
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        updateCameraDetails();
    }

    public void addCallback(TorchCallback callback) {
        if (callback != null && !callbacks.contains(callback)) {
            callbacks.add(callback);
        }
    }

    public void removeCallback(TorchCallback callback) {
        callbacks.remove(callback);
    }

    private void notifyStateChanged() {
        boolean on = isOn();
        int brightness = curBrightness;
        mainHandler.post(() -> {
            updateNotification();
            for (TorchCallback callback : callbacks) {
                try {
                    callback.onTorchStateChanged(on, brightness);
                } catch (Throwable ignored) {}
            }
        });
    }

    private void createNotificationChannel() {
        if (notificationManager == null) return;
        try {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "High Brightness Flashlight",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("High Brightness Flashlight active status");
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        } catch (Throwable ignored) {}
    }

    private void updateNotification() {
        if (notificationManager == null || context == null) return;
        if (!isOn()) {
            dismissNotification();
            return;
        }

        try {
            createNotificationChannel();

            Intent turnOffIntent = new Intent(ACTION_TURN_OFF);
            turnOffIntent.setPackage(context.getPackageName());
            turnOffIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            PendingIntent turnOffPendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    turnOffIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            String title = "High Brightness Flashlight";
            String turnOff = "Turn off";
            String contentText = "Flashlight is on (Level " + curBrightness + "/" + maxBrightness + ")";

            if (XPLauncher.moduleResources != null) {
                try {
                    title = XPLauncher.moduleResources.getString(R.string.high_brightness_flashlight_tile_title);
                } catch (Throwable ignored) {}
                try {
                    turnOff = XPLauncher.moduleResources.getString(R.string.high_brightness_flashlight_turn_off);
                } catch (Throwable ignored) {}
                try {
                    contentText = XPLauncher.moduleResources.getString(R.string.high_brightness_flashlight_notification_desc, curBrightness, maxBrightness);
                } catch (Throwable ignored) {}
            }

            Icon icon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_qs_flashlight);

            Notification.Action turnOffAction = new Notification.Action.Builder(
                    icon,
                    turnOff,
                    turnOffPendingIntent
            ).setAuthenticationRequired(false).build();

            Notification.Builder builder = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(contentText)
                    .setSmallIcon(icon)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .addAction(turnOffAction)
                    .setContentIntent(turnOffPendingIntent);

            notificationManager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, builder.build());
        } catch (Throwable t) {
            XposedBridge.log("HighBrightnessTorchController: Failed to show notification: " + t);
        }
    }

    private void dismissNotification() {
        if (notificationManager == null) return;
        try {
            notificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
        } catch (Throwable ignored) {}
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
            notifyStateChanged();
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
            notifyStateChanged();
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
        notifyStateChanged();
    }
}
