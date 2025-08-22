package sh.siava.pixelxpert.xposed;

import static android.content.Context.CONTEXT_IGNORE_SECURITY;
import static de.robv.android.xposed.XposedBridge.log;
import static sh.siava.pixelxpert.BuildConfig.APPLICATION_ID;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
import static sh.siava.pixelxpert.xposed.utils.BootLoopProtector.isBootLooped;

import android.annotation.SuppressLint;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.IPixelXpertProxy;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;
import sh.siava.pixelxpert.service.PixelXpertProxy;

@SuppressWarnings("RedundantThrows")
public class XPLauncher implements ServiceConnection {

	private boolean mIsChildProcess = false;
	public static String processName = "";

	public static ArrayList<XposedModPack> runningMods = new ArrayList<>();
	public Context mContext = null;
	@SuppressLint("StaticFieldLeak")
	static XPLauncher instance;

	private CountDownLatch rootProxyCountdown = new CountDownLatch(1);
	private static IPixelXpertProxy rootProxyIPC;
	private static final Queue<ProxyRunnable> proxyQueue = new LinkedList<>();
	private static boolean TELECOM_SERVER_LOADED = false;

	/**
	 * @noinspection FieldCanBeLocal
	 */
	public XPLauncher() {
		instance = this;
	}

	public static IPixelXpertProxy getRootProviderProxy() {
		if (rootProxyIPC == null) {
			instance.rootProxyCountdown = new CountDownLatch(1);
			instance.forceConnectRootService();
			try {
				//noinspection ResultOfMethodCallIgnored
				instance.rootProxyCountdown.await(5, TimeUnit.SECONDS);
			} catch (Throwable ignored) {
			}
		}
		return rootProxyIPC;
	}

	public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		try {
			processName = lpParam.processName;
			mIsChildProcess = processName.contains(":");
		} catch (Throwable ignored) {
			mIsChildProcess = false;
		}

		if (lpParam.packageName.equals(Constants.SYSTEM_FRAMEWORK_PACKAGE)) {
			ReflectedClass PhoneWindowManagerClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager");

			PhoneWindowManagerClass
					.before("init")
					.run(param -> {
						try {
							if (mContext == null) {
								mContext = (Context) param.args[0];

								ResourceManager.modRes = mContext.createPackageContext(APPLICATION_ID, CONTEXT_IGNORE_SECURITY)
										.getResources();

								XPrefs.init(mContext);

								CompletableFuture.runAsync(() -> waitForXprefsLoad(lpParam));
							}
						} catch (Throwable t) {
							log(t);
						}
					});
		} else {
			ReflectedClass.of(Instrumentation.class)
					.after("newApplication")
					.run(param -> {
						try {
							if (mContext == null || (lpParam.packageName.equals(Constants.TELECOM_SERVER_PACKAGE) && !TELECOM_SERVER_LOADED)) { //telecom service launches as a secondary process in framework, but has its own package name. context is not null when it loads
								if(lpParam.packageName.equals(Constants.TELECOM_SERVER_PACKAGE))
									TELECOM_SERVER_LOADED = true;

								mContext = (Context) param.args[param.args.length - 1];

								ResourceManager.modRes = mContext.createPackageContext(APPLICATION_ID, CONTEXT_IGNORE_SECURITY)
										.getResources();

								XPrefs.init(mContext);

								waitForXprefsLoad(lpParam);
							}
						} catch (Throwable t) {
							log(t);
						}
					});
		}
	}

	private void onXPrefsReady(XC_LoadPackage.LoadPackageParam lpParam) {
		if (isBootLooped(lpParam.packageName)) {
			log(String.format("PixelXpert: Possible bootloop in %s. Will not load for now", lpParam.packageName));
			return;
		}

		new SystemUtils(mContext);
		XPrefs.setPackagePrefs(lpParam.packageName);

		loadModPacks(lpParam);

		XPrefs.onContentProviderLoaded();
	}

	private void loadModPacks(XC_LoadPackage.LoadPackageParam lpParam) {
		if (Arrays.asList(ResourceManager.modRes.getStringArray(R.array.root_requirement)).contains(lpParam.packageName)) {
			forceConnectRootService();
		}

		ModPacks.getModPacks()
				.forEach(modPackData -> {
					if((modPackData.targetPackage.equals(lpParam.packageName) || modPackData.targetPackage.isEmpty() /*common mod packs*/)
					&& ((mIsChildProcess && modPackData.targetsChildProcess && processName.contains(modPackData.childProcessName))
						|| (!mIsChildProcess && modPackData.targetsMainProcess)))
					{
						//noinspection unchecked
						loadModPack((Class<? extends XposedModPack>) modPackData.clazz, lpParam);
					}
		});
	}

	private void loadModPack(Class<? extends XposedModPack> thisClass, XC_LoadPackage.LoadPackageParam lpParam) {
		try {
			XposedModPack instance = thisClass.getConstructor(Context.class).newInstance(mContext);
			try {
				instance.onPreferenceUpdated();
			} catch (Throwable ignored) {
			}
			instance.onPackageLoadedInternal(lpParam);
			runningMods.add(instance);
		} catch (Throwable T) {
			log("Start Error Dump - Occurred in " + thisClass.getName());
			log(T);
		}
	}

	private void forceConnectRootService() {
		new Thread(() -> {
			while (SystemUtils.UserManager() == null
					|| !SystemUtils.UserManager().isUserUnlocked()) //device is still CE encrypted
			{
				SystemUtils.threadSleep(2000);
			}
			SystemUtils.threadSleep(5000); //wait for the unlocked account to settle down a bit

			while (rootProxyIPC == null) {
				connectRootService();
				SystemUtils.threadSleep(5000);
			}
		}).start();
	}

	private void connectRootService() {
		try {
			Intent intent = new Intent();
			intent.setComponent(new ComponentName(APPLICATION_ID, PixelXpertProxy.class.getName()));
			mContext.bindService(intent, instance, Context.BIND_AUTO_CREATE | Context.BIND_ADJUST_WITH_ACTIVITY);
		} catch (Throwable t) {
			log(t);
		}
	}

	@Override
	public void onServiceConnected(ComponentName name, IBinder service) {
		rootProxyIPC = IPixelXpertProxy.Stub.asInterface(service);
		rootProxyCountdown.countDown();

		synchronized (proxyQueue) {
			while (!proxyQueue.isEmpty()) {
				try {
					Objects.requireNonNull(proxyQueue.poll()).run(rootProxyIPC);
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private void waitForXprefsLoad(XC_LoadPackage.LoadPackageParam lpParam) {
		while (true) {
			try {
				Xprefs.getBoolean("LoadTestBooleanValue", false);
				break;
			} catch (Throwable ignored) {
				SystemUtils.threadSleep(1000);
			}
		}

		log(String.format("Loading PixelXpert version: %s on %s", BuildConfig.VERSION_NAME, lpParam.packageName));
		try {
			log("PixelXpert Records: " + Xprefs.getAll().size());
		} catch (Throwable ignored) {
		}

		onXPrefsReady(lpParam);
	}

	@Override
	public void onServiceDisconnected(ComponentName name) {
		rootProxyIPC = null;

		forceConnectRootService();
	}

	public static void enqueueProxyCommand(ProxyRunnable runnable) {
		if (rootProxyIPC != null) {
			try {
				runnable.run(rootProxyIPC);
			} catch (RemoteException ignored) {
			}
		} else {
			synchronized (proxyQueue) {
				proxyQueue.add(runnable);
			}
			instance.forceConnectRootService();
		}
	}

	public interface ProxyRunnable {
		void run(IPixelXpertProxy proxy) throws RemoteException;
	}
}