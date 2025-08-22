package sh.siava.pixelxpert.xposed.utils;

import static android.content.Context.RECEIVER_EXPORTED;
import static android.content.res.Configuration.UI_MODE_NIGHT_YES;
import static android.media.AudioManager.STREAM_MUSIC;
import static java.lang.Math.round;
import static de.robv.android.xposed.XposedBridge.invokeOriginalMethod;
import static de.robv.android.xposed.XposedBridge.log;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;
import static sh.siava.pixelxpert.xposed.Constants.SYSTEM_UI_PACKAGE;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.DownloadManager;
import android.app.usage.NetworkStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraManager.TorchCallback;
import android.hardware.camera2.CameraMetadata;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.VibratorManager;
import android.telephony.TelephonyManager;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.Contract;

import java.lang.reflect.Method;
import java.util.ArrayList;

import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.xposed.XPLauncher;

public class SystemUtils {
	private static final int THREAD_PRIORITY_BACKGROUND = 10;
	public static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
	public static final String EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
	public static final String ACTION_FLASH_LEVEL_CHANGED = BuildConfig.APPLICATION_ID + ".action.FLASH_LEVEL_CHANGED";

	public static final int FADE_DURATION = 500;

	@SuppressLint("StaticFieldLeak")
	static SystemUtils instance;
	Context mContext;
	PackageManager mPackageManager;
	CameraManager mCameraManager;
	VibratorManager mVibrationManager;
	AudioManager mAudioManager;
	PowerManager mPowerManager;
	ConnectivityManager mConnectivityManager;
	TelephonyManager mTelephonyManager;
	AlarmManager mAlarmManager;
	DownloadManager mDownloadManager = null;
	NetworkStatsManager mNetworkStatsManager = null;
	boolean mHasVibrator = false;
	int maxFlashLevel = -1;
	static boolean isTorchOn = false;

	ArrayList<ChangeListener> mVolumeChangeListeners = new ArrayList<>();
	ArrayList<ChangeListener> mFlashlightLevelListeners = new ArrayList<>();
	private WifiManager mWifiManager;
	private WindowManager mWindowManager;
	private UserManager mUserManager;
	private Handler mHandler;
	private Method mSetTorchModeMethod;

	public static void restart(String what) {
		switch (what.toLowerCase())
		{
			case "systemui":
				BootLoopProtector.resetCounter(SYSTEM_UI_PACKAGE);
				runRootCommand("killall " +  SYSTEM_UI_PACKAGE);
				break;
			case "system":
				runRootCommand("am start -a android.intent.action.REBOOT");
				break;
			case "zygote":
			case "android":
				runRootCommand("kill $(pidof zygote)");
				runRootCommand("kill $(pidof zygote64)");
				break;
			case "bootloader":
				runRootCommand("reboot bootloader");
				break;
			default:
				runRootCommand(String.format("killall %s", what));
		}
	}

	private static void runRootCommand(String command)
	{
		XPLauncher.enqueueProxyCommand(proxy -> {
			try {
				proxy.runRootCommand(command);
			} catch (Throwable ignored) {}
		});
	}

	public static boolean isFlashOn() {
		return isTorchOn;
	}

	public static void toggleFlash(boolean animate) {
		if (instance != null)
			instance.toggleFlashInternal(animate);
	}

	public static void setFlash(boolean enabled, int level, boolean animate) {
		if (instance != null)
			instance.setFlashInternalWithLevel(enabled, level, animate);
	}

	public static void setFlash(boolean enabled, boolean animate) {
		if (instance != null)
			instance.setFlashInternal(enabled, animate);
	}

	@Nullable
	@Contract(pure = true)
	public static WifiManager WifiManager()
	{
		return instance == null
				? null
				: instance.getWifiManager();
	}
	@Nullable
	@Contract(pure = true)
	public static NetworkStatsManager NetworkStatsManager() {
		return instance == null
				? null
				: instance.getNetworkStatsManager();
	}

	@Nullable
	@Contract(pure = true)
	public static CameraManager CameraManager() {
		return instance == null
				? null
				: instance.getCameraManager();
	}

	@Nullable
	@Contract(pure = true)
	public static PackageManager PackageManager() {
		return instance == null
				? null
				: instance.getPackageManager();
	}

	@Nullable
	@Contract(pure = true)
	public static AudioManager AudioManager() {
		return instance == null
				? null
				: instance.getAudioManager();
	}

	@Nullable
	@Contract(pure = true)
	public static WindowManager WindowManager() {
		return instance == null
				? null
				: instance.getWindowManager();
	}

	@Nullable
	@Contract(pure = true)
	public static UserManager UserManager() {
		return instance == null
				? null
				: instance.getUserManager();
	}

	@Nullable
	@Contract(pure = true)
	public static ConnectivityManager ConnectivityManager() {
		return instance == null
				? null
				: instance.getConnectivityManager();
	}

	@Nullable
	@Contract(pure = true)
	public static PowerManager PowerManager() {
		return instance == null
				? null
				: instance.getPowerManager();
	}

	@Nullable
	@Contract(pure = true)
	public static AlarmManager AlarmManager() {
		return instance == null
				? null
				: instance.getAlarmManager();
	}

	@Nullable
	@Contract(pure = true)
	public static TelephonyManager TelephonyManager() {
		return instance == null
				? null
				: instance.getTelephonyManager();
	}

	/** @noinspection unused*/
	public static DownloadManager DownloadManager() {
		return instance == null
				? null
				: instance.getDownloadManager();
	}

	public static void vibrate(int effect, @Nullable Integer vibrationUsage) {
		vibrate(VibrationEffect.createPredefined(effect), vibrationUsage);
	}

	@SuppressLint("MissingPermission")
	public static void vibrate(VibrationEffect effect, @Nullable Integer vibrationUsage) {
		if (instance == null || !instance.hasVibrator()) return;
		try {
			if(vibrationUsage != null) {
				instance.getVibrationManager().getDefaultVibrator().vibrate(effect, VibrationAttributes.createForUsage(vibrationUsage));
			}
			else
			{
				instance.getVibrationManager().getDefaultVibrator().vibrate(effect);
			}
		} catch (Exception ignored) {
		}
	}

	private boolean hasVibrator() {
		return getVibrationManager() != null && mHasVibrator;
	}

	public static void sleep() {
		if (instance != null)
		{
			try {
				callMethod(PowerManager(), "goToSleep", SystemClock.uptimeMillis());
			} catch (Throwable ignored) {}
		}
	}

	public SystemUtils(Context context) {
		mContext = context;

		instance = this;

		registerVolumeChangeReceiver();
	}
	public static void threadSleep(int millis)
	{
		try {
			Thread.sleep(millis);
		} catch (Throwable ignored) {}
	}

	private void registerVolumeChangeReceiver() {
		BroadcastReceiver volChangeReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if(intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) == STREAM_MUSIC)
				{
					int newLevel = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, 0);
					for(ChangeListener listener : mVolumeChangeListeners)
					{
						listener.onChanged(newLevel);
					}
				}
			}
		};

		IntentFilter volumeFilter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
		mContext.registerReceiver(volChangeReceiver, volumeFilter, RECEIVER_EXPORTED);
	}
	public static void registerFlashlightLevelListener(ChangeListener listener)
	{
		instance.mFlashlightLevelListeners.add(listener);
	}

	public static void unregisterFlashlightLevelListener(ChangeListener listener)
	{
		instance.mFlashlightLevelListeners.remove(listener);
	}
	public static void registerVolumeChangeListener(ChangeListener listener)
	{
		if(instance != null)
			instance.mVolumeChangeListeners.add(listener);
	}

	/** @noinspection unused*/
	public static void unregisterVolumeChangeListener(ChangeListener listener)
	{
		if(instance != null)
			instance.mVolumeChangeListeners.remove(listener);
	}


	private void setFlashInternal(boolean enabled, boolean animate) {
		if(getCameraManager() == null)
			return;

		try {
			String flashID = getFlashID(getCameraManager());
			if (flashID.isEmpty()) {
				return;
			}

			if (Xprefs.getBoolean("leveledFlashTile", false)
					&& Xprefs.getBoolean("isFlashLevelGlobal", false)
					&& supportsFlashLevelsInternal()) {
				float currentPct = Xprefs.getInt("flashPCT", 50) / 100f;

				setFlashInternalWithLevel(enabled, getFlashlightLevelInternal(currentPct), animate);
			}
			else {
				setFlashInternalNoLevel(enabled, animate);
			}
		} catch (Throwable t) {
			if (BuildConfig.DEBUG) {
				log("PixelXpert Error in setting flashlight");
				log(t);
			}
		}
	}

	private void setFlashInternalNoLevel(boolean enabled, boolean animate) {
		try {
			if(animate && supportsFlashLevels())
			{
				if(enabled) {
					animateFlashLightOn(getFlashStrengthInternal());
				}
				else
				{
					animateFlashLightOff();
				}
			}
			else
			{
				invokeOriginalMethod(mSetTorchModeMethod, getCameraManager(),new Object[]{getFlashID(getCameraManager()), enabled});
			}
		} catch (Throwable ignored) {}
	}

	private void animateFlashLightOn(int level) {
		mHandler.post(() -> {
			ValueAnimator valueAnimator = ValueAnimator
					.ofInt(0, level)
					.setDuration(FADE_DURATION);
			valueAnimator.addUpdateListener(animation -> setFlashLevel(true, (Integer) animation.getAnimatedValue(), false));
			valueAnimator.start();
		});
	}

	private void animateFlashLightOff() {
		mHandler.post(() -> {
			ValueAnimator valueAnimator = ValueAnimator
					.ofInt(getFlashStrengthInternal(), 0)
					.setDuration(500);
			valueAnimator.addUpdateListener(animation -> setFlashLevel(true, (Integer) animation.getAnimatedValue(), false));
			valueAnimator.addListener(new AnimatorListenerAdapter() {
				@Override
				public void onAnimationEnd(Animator animation) {
					setFlashInternalNoLevel(false, false);
				}
			});
			valueAnimator.start();
		});
	}
	public static int getFlashlightLevel(float flashPct)
	{
		if(instance == null) return 1;
		return instance.getFlashlightLevelInternal(flashPct);
	}

	private int getFlashlightLevelInternal(float flashPct) {
		return
				Math.max(
						Math.min(
								round(SystemUtils.getMaxFlashLevel() * flashPct),
								SystemUtils.getMaxFlashLevel())
						, 1);
	}

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public static boolean supportsFlashLevels() {
		return instance != null
				&& instance.supportsFlashLevelsInternal();
	}

	private boolean supportsFlashLevelsInternal() {
		if(maxFlashLevel == -1)
		{
			refreshFlashLevel();
		}
		return maxFlashLevel > 0;
	}

	private void refreshFlashLevel()
	{
		try {
			String flashID = getFlashID(getCameraManager());
			if (flashID.isEmpty()) {
				maxFlashLevel = -1;
				return;
			}
			if (maxFlashLevel == -1) {
				@SuppressWarnings("unchecked")
				CameraCharacteristics.Key<Integer> FLASH_INFO_STRENGTH_MAXIMUM_LEVEL = (CameraCharacteristics.Key<Integer>) getStaticObjectField(CameraCharacteristics.class, "FLASH_INFO_STRENGTH_MAXIMUM_LEVEL");
				maxFlashLevel = getCameraManager().getCameraCharacteristics(flashID).get(FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
			}
		} catch (Throwable ignored) {}
	}

	public static int getMaxFlashLevel()
	{
		return instance == null
				? -1
				: instance.getMaxFlashLevelInternal();
	}

	private int getMaxFlashLevelInternal() {
		if(maxFlashLevel == -1)
		{
			refreshFlashLevel();
		}
		return maxFlashLevel;
	}
	private void setFlashInternalWithLevel(boolean enabled, int level, boolean animate) {
		if(animate) {
			if (enabled) {
				animateFlashLightOn(level);
			} else {
				animateFlashLightOff();
			}
		}
		else {
			setFlashLevel(enabled, level, true);
		}
	}

		private void setFlashLevel(boolean enabled, int level, boolean broadcast) {
		try {
			String flashID = getFlashID(getCameraManager());
			if (enabled) {
				if (supportsFlashLevels()) //good news. we can set levels
				{
					getCameraManager().turnOnTorchWithStrengthLevel(flashID,Math.max(level, 1));

					if(broadcast)
					{
						mContext.sendBroadcast(new Intent(ACTION_FLASH_LEVEL_CHANGED));
					}
				} else //flash doesn't support levels: go normal
				{
					setFlashInternalNoLevel(true, false);
				}
			} else {
				setFlashInternalNoLevel(false, false);
			}
		} catch (Throwable t) {
			if (BuildConfig.DEBUG) {
				log("PixelXpert Error in setting flashlight");
				log(t);
			}
		}
	}

	private void informFlashListeners() {
		for(ChangeListener listener : mFlashlightLevelListeners)
		{
			listener.onChanged(getFlashStrengthInternal());
		}
	}

	private void toggleFlashInternal(boolean animate) {
		setFlashInternal(!isTorchOn, animate);
	}

	private String getFlashID(@NonNull CameraManager cameraManager) throws CameraAccessException {
		String[] ids = cameraManager.getCameraIdList();
		for (String id : ids) {
			//noinspection DataFlowIssue
			if (cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK) {
				//noinspection DataFlowIssue
				if (cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) {
					return id;
				}
			}
		}
		return "";
	}

	/** @noinspection unused*/
	public static int getFlashStrength()
	{
		return instance.getFlashStrengthInternal();
	}

	public static float getFlashStrengthPCT()
	{
		return instance.getFlashStrengthInternal() / (float) SystemUtils.getMaxFlashLevel();
	}

	private int getFlashStrengthInternal()
	{
		try {
			return getCameraManager().getTorchStrengthLevel(getFlashID(getCameraManager()));
		} catch (CameraAccessException e) {
			return 0;
		}
	}

	public static boolean isDarkMode() {
		return instance != null
				&& instance.getIsDark();
	}

	private boolean getIsDark() {
		return (mContext.getResources().getConfiguration().uiMode & UI_MODE_NIGHT_YES) == UI_MODE_NIGHT_YES;
	}

	static boolean darkSwitching = false;

	public static void doubleToggleDarkMode() {
		XPLauncher.enqueueProxyCommand(proxy -> {
			boolean isDark = isDarkMode();
			new Thread(() -> {
				try {
					while (darkSwitching) {
						Thread.currentThread().wait(100);
					}
					darkSwitching = true;

					proxy.runRootCommand("cmd uimode night " + (isDark ? "no" : "yes"));
					threadSleep(1000);
					proxy.runRootCommand("cmd uimode night " + (isDark ? "yes" : "no"));

					threadSleep(500);
					darkSwitching = false;
				} catch (Exception ignored) {
				}
			}).start();
		});
	}

	public static void killSelf()
	{
		BootLoopProtector.resetCounter(android.os.Process.myProcessName());

		android.os.Process.killProcess(android.os.Process.myPid());
	}

	private AudioManager getAudioManager() {
		if (mAudioManager == null) {
			try {
				mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
			} catch (Throwable t) {
				if (BuildConfig.DEBUG) {
					log(t);
				}
			}
		}
		return mAudioManager;
	}

	private PackageManager getPackageManager() {
		if (mPackageManager == null) {
			try {
				mPackageManager = mContext.getPackageManager();
			} catch (Throwable t) {
				if (BuildConfig.DEBUG) {
					log(t);
				}
			}
		}
		return mPackageManager;
	}

	private WifiManager getWifiManager() {
		if(mWifiManager == null)
		{
			try
			{
				mWifiManager = mContext.getSystemService(WifiManager.class);
			} catch (Throwable t) {
				if (BuildConfig.DEBUG) {
					log("PixelXpert Error getting wifi manager");
					log(t);
				}
			}
		}
		return mWifiManager;
	}

	private ConnectivityManager getConnectivityManager() {
		if(mConnectivityManager == null)
		{
			try
			{
				mConnectivityManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
			} catch (Throwable t) {
				if (BuildConfig.DEBUG) {
					log("PixelXpert Error getting connection manager");
					log(t);
				}
			}
		}
		return mConnectivityManager;
	}


	private PowerManager getPowerManager() {
		if(mPowerManager == null)
		{
			try {
				mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
			} catch (Throwable t) {
				if (BuildConfig.DEBUG) {
					log("PixelXpert Error getting power manager");
					log(t);
				}
			}
		}
		return mPowerManager;
	}

	private AlarmManager getAlarmManager() {
		if(mAlarmManager == null)
		{
			try {
				mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
			} catch (Throwable t) {
				if (BuildConfig.DEBUG) {
					log("PixelXpert Error getting alarm manager");
					log(t);
				}
			}
		}
		return mAlarmManager;
	}

	private TelephonyManager getTelephonyManager() {
		if(mTelephonyManager == null)
		{
			try {
				mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
			} catch (Throwable t) {
				if (BuildConfig.DEBUG) {
					log("PixelXpert Error getting telephony manager");
					log(t);
				}
			}
		}
		return mTelephonyManager;
	}

	private VibratorManager getVibrationManager()
	{
		if(mVibrationManager == null)
		{
			try {
				mVibrationManager = (VibratorManager) mContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
				mHasVibrator = mVibrationManager.getDefaultVibrator().hasVibrator();
			} catch (Throwable t) {
				if (BuildConfig.DEBUG) {
					log("PixelXpert Error getting vibrator");
					log(t);
				}
			}
		}
		return mVibrationManager;
	}

	private NetworkStatsManager getNetworkStatsManager()
	{
		if(mNetworkStatsManager == null)
		{
			try
			{
				mNetworkStatsManager = (NetworkStatsManager) mContext.getSystemService(Context.NETWORK_STATS_SERVICE);
			} catch (Throwable t) {
				if (BuildConfig.DEBUG) {
					log("PixelXpert Error getting network stats");
					log(t);
				}
			}
		}
		return mNetworkStatsManager;
	}

	private CameraManager getCameraManager() {
		if(mCameraManager == null)
		{
			try {
				HandlerThread thread = new HandlerThread("", THREAD_PRIORITY_BACKGROUND);
				thread.start();
				mHandler = new Handler(thread.getLooper());
				mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);

				mSetTorchModeMethod = CameraManager.class.getMethod("setTorchMode", String.class, boolean.class);

				mCameraManager.registerTorchCallback(new TorchCallback() {
					@Override
					public void onTorchModeChanged(@NonNull String cameraId, boolean enabled) {
						super.onTorchModeChanged(cameraId, enabled);
						isTorchOn = enabled;
					}
				}, mHandler);

				mContext.registerReceiver(
						new BroadcastReceiver() {
							@Override
							public void onReceive(Context context, Intent intent) {
								informFlashListeners();
							}
						},
						new IntentFilter(ACTION_FLASH_LEVEL_CHANGED),
						RECEIVER_EXPORTED);
			} catch (Throwable t) {
				mCameraManager = null;
				if (BuildConfig.DEBUG) {
					log(t);
				}
			}
		}
		return mCameraManager;
	}

	private DownloadManager getDownloadManager() {
		if (mDownloadManager == null) {
			mDownloadManager = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
		}
		return mDownloadManager;
	}

	private WindowManager getWindowManager() {
		if (mWindowManager == null) {
			try {
				mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
			} catch (Throwable t) {
				if (BuildConfig.DEBUG) {
					log(t);
				}
			}
		}
		return mWindowManager;
	}

	private UserManager getUserManager() {
		if (mUserManager == null) {
			try {
				mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
			} catch (Throwable t) {
				if (BuildConfig.DEBUG) {
					log(t);
				}
			}
		}
		return mUserManager;
	}

	public static void toggleMute()
	{
		if(instance != null)
		{
			instance.toggleMuteInternal();
		}
	}

	private void toggleMuteInternal() {
		try {
			if (getAudioManager().isStreamMute(STREAM_MUSIC)) {
				//noinspection DataFlowIssue
				int unMuteVolume = round(
						(AudioManager().getStreamMaxVolume(STREAM_MUSIC)
								- AudioManager().getStreamMinVolume(STREAM_MUSIC)
						) * (float) Xprefs.getSliderInt("UnMuteVolumePCT", 50) / 100f);
				mAudioManager.setStreamVolume(STREAM_MUSIC, unMuteVolume, 0);
			} else {
				mAudioManager.setStreamVolume(STREAM_MUSIC, 0, 0);
			}
		} catch (Throwable ignored){}
	}

	public interface ChangeListener
	{
		void onChanged(int newVal);
	}

	public static int idOf(String name) {
		return resourceIdOf(name, "id");
	}
	public static int dimenIdOf(String name)
	{
		return resourceIdOf(name, "dimen");
	}

	public static int resourceIdOf(String name, String type)
	{
		return instance.resourceIdOfInternal(name, type);
	}

	@SuppressLint("DiscouragedApi")
	private int resourceIdOfInternal(String name, String type)
	{
		return mContext.getResources().getIdentifier(name, type, mContext.getPackageName());
	}

}