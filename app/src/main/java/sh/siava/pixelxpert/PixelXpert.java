package sh.siava.pixelxpert;

import static sh.siava.pixelxpert.utils.AppUtils.restartSelf;
import static sh.siava.pixelxpert.Constants.DEFAULT_PREFS_FILE_NAME;
import static sh.siava.pixelxpert.Constants.LAUNCH_REASON_XPOSED_SERVICE_FAIL;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.downloader.PRDownloader;
import com.downloader.PRDownloaderConfig;
import com.google.android.material.color.DynamicColors;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dagger.hilt.android.HiltAndroidApp;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import sh.siava.pixelxpert.service.RootProvider;
import sh.siava.pixelxpert.utils.ExtendedSharedPreferences;
import sh.siava.pixelxpert.utils.PreferenceXMLParser;

@HiltAndroidApp
public class PixelXpert extends Application {

	/** @noinspection unused*/
	public static final String TAG = "PixelXpertSingleton";
	private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());


	private static PixelXpert instance;
	private boolean mCoreRootServiceBound = false;
	public final CountDownLatch mRootServiceConnected = new CountDownLatch(1);

	private ServiceConnection mCoreRootServiceConnection;
	private IRootProviderService mCoreRootService;
	private XposedService mXposedService;

	public void onCreate() {
		super.onCreate();
		instance = this;

		PRDownloaderConfig config = PRDownloaderConfig.newBuilder()
				.setReadTimeout(30_000)
				.setConnectTimeout(30_000)
				.build();
		PRDownloader.initialize(getApplicationContext(), config);

		initiatePreferences(false);

		tryConnectRootService();
		DynamicColors.applyToActivitiesIfAvailable(this);

		tryConnectXposedService(service -> {});
	}

	private void tryConnectXposedService(XposedServiceCallback callback) {
		XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
			@Override
			public void onServiceBind(@NonNull XposedService service) {
				mXposedService = service;
				callback.serviceReady(service);
			}

			@Override
			public void onServiceDied(@NonNull XposedService service) {
				mXposedService = null;
			}
		});
	}

	public ExtendedSharedPreferences getDefaultPreferences()
	{
		return ExtendedSharedPreferences.from(this.createDeviceProtectedStorageContext()
				.getSharedPreferences(DEFAULT_PREFS_FILE_NAME, Context.MODE_PRIVATE));
	}

	@SuppressLint("ApplySharedPref")
	public void initiatePreferences(boolean resetAll) {
		CompletableFuture.runAsync(() -> {
			try {
				if(resetAll)
					getDefaultPreferences().edit().clear().commit();

				setPrefsValidity(false);

				Class<?> xmlClass = getClassLoader().loadClass(R.xml.class.getName());
				Field[] prefPages = xmlClass.getFields();
				for (Field prefPage : prefPages)
				{
					//noinspection DataFlowIssue
					initiatePref((int) prefPage.get(null));
				}

				setPrefsValidity(true);
			} catch (Throwable ignored) {}
		});
	}

	@SuppressLint("ApplySharedPref")
	public void setPrefsValidity(boolean valid)
	{
		getDefaultPreferences().edit().putBoolean(ExtendedSharedPreferences.IS_PREFS_INITIATED_KEY, valid).commit();
	}

	private void initiatePref(int resID)
	{
		try {
			PreferenceXMLParser.setDefaultsFromXml(this, resID, getDefaultPreferences());
		}
		catch (Throwable ignored){}
	}

	/** @noinspection unused*/
	public IRootProviderService getRootService()
	{
		return mCoreRootService;
	}

	public static PixelXpert get() {
		if (instance == null) {
			instance = new PixelXpert();
		}
		return instance;
	}

	/** @noinspection BooleanMethodIsAlwaysInverted*/
	public boolean isCoreRootServiceBound() {
		return mCoreRootServiceBound;
	}

	public boolean hasRootAccess()
	{
		return Shell.getShell().isRoot();
	}

	public void tryConnectRootService()
	{
		new Thread(() -> {
			for (int i = 0; i < 2; i++) {
				if (connectRootService())
					break;
			}
		}).start();
	}

	private boolean connectRootService() {
		try {
			// Start RootService connection
			Intent intent = new Intent(this, RootProvider.class);
			mCoreRootServiceConnection = new ServiceConnection() {
				@Override
				public void onServiceConnected(ComponentName name, IBinder service) {
					mCoreRootServiceBound = true;
					mRootServiceConnected.countDown();
					mCoreRootService = IRootProviderService.Stub.asInterface(service);
				}

				@Override
				public void onServiceDisconnected(ComponentName name) {
					mCoreRootServiceBound = false;
					mRootServiceConnected.countDown();
				}
			};

			mainThreadHandler.post(() -> RootService.bind(intent, mCoreRootServiceConnection));

			return mRootServiceConnected.await(5, TimeUnit.SECONDS);
		} catch (Exception ignored) {
			return false;
		}
	}

	public void getXposedService(XposedServiceCallback callback, boolean restartOnFail)
	{
		new Thread(() -> {
			int counter = 0;
			//we give it 1 second to bind to service. Otherwise, we'll FC
			while (mXposedService == null && counter < 5)
			{
				counter++;
				try {
					//noinspection BusyWait
					Thread.sleep(200);
				} catch (InterruptedException ignored) {}
			}
			if(mXposedService != null) {
				callback.serviceReady(mXposedService);
			}
			else
			{
				//Xposed Service can't be bound because of a bug of on their side. FC will fix it
				if(restartOnFail) {
					restartSelf(LAUNCH_REASON_XPOSED_SERVICE_FAIL);
				}
				else
				{
					Log.d(TAG, "getXposedService: didn't get xposed service but won't retry");
				}
			}
		}).start();
	}


	public String[] runRootCommand(String command) {
		try {
			List<String> result = Shell.cmd(command).exec().getOut();
			return result.toArray(new String[0]);
		}
		catch (Throwable t)
		{
			return new String[0];
		}
	}


	public interface XposedServiceCallback
	{
		void serviceReady(XposedService service);
	}
}
