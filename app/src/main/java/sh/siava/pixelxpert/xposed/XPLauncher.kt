package sh.siava.pixelxpert.xposed

import android.annotation.SuppressLint
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Context.CONTEXT_IGNORE_SECURITY
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Resources
import android.os.IBinder
import android.os.RemoteException
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.LinkedList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.IPixelXpertProxy
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.service.PixelXpertProxy
import sh.siava.pixelxpert.xposed.utils.BootLoopProtector.isBootLooped
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

/**
 * The module's entry point, and everything that has to happen before a mod pack
 * can be constructed.
 *
 * The sequence, which is the whole reason this class exists, is:
 *
 *  1. [onModuleLoaded] records the process name, because the process identity
 *     decides which packs are allowed in (a child process only gets a pack that
 *     asked for it).
 *  2. [onSystemServerStarting] publishes the framework class loader, so
 *     [ReflectedClass] can resolve `com.android.server.*` without being handed
 *     a loader every time.
 *  3. [onPackageReady] installs the two context hooks. System server gets its
 *     context from `PhoneWindowManager.init`; every other package - and the
 *     telecom server specifically, which also runs in system server - gets it
 *     from `Instrumentation.newApplication`. Both then wait for the preferences
 *     provider to answer before going further.
 *  4. [waitForXprefsLoad] spins until the remote store is readable, then
 *     [onXPrefsReady] constructs `SystemUtils`, walks the generated
 *     [ModPacks] table and starts everything that matches this package.
 *
 * Step 4 also runs `XPrefs.onContentProviderLoaded()`, which is what first fans
 * a preference change out to the packs added in step 3.
 */
class XPLauncher : XposedModule(), ServiceConnection {

	/** Package under hook; null until [XPrefs.init] has run. */
	var mContext: Context? = null

	private var rootProxyCountdown = CountDownLatch(1)

	init {
		instance = this
	}

	override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
		super.onModuleLoaded(param)

		processName = param.processName
		isSystemServer = param.isSystemServer
	}

	override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
		ReflectedClass.setFrameworkClassloader(param.classLoader)
	}

	override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
		ReflectedClass.setDefaultXposedInterface(this)

		hook17BetaAudioManagerSRWorkaround(param)

		if (isSystemServer && param.packageName != Constants.TELECOM_SERVER_PACKAGE) {
			val phoneWindowManagerClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager")

			phoneWindowManagerClass
				.before("init")
				.run(instance) { hookParam ->
					try {
						if (mContext == null) {
							val context = hookParam.args[0] as Context
							mContext = context

							moduleResources = context
								.createPackageContext(BuildConfig.APPLICATION_ID, CONTEXT_IGNORE_SECURITY)
								.resources

							XPrefs.init(context)

							// System server must not block on the provider: it is
							// still booting, and the module app may not even be
							// startable yet.
							CompletableFuture.runAsync { waitForXprefsLoad(param) }
						}
					} catch (t: Throwable) {
						Logger.log(t)
					}
				}
		}

		if (!isSystemServer || param.packageName == Constants.TELECOM_SERVER_PACKAGE) {
			ReflectedClass.of(Instrumentation::class.java)
				.after("newApplication")
				.run(this) { hookParam ->
					try {
						val telecomCase = param.packageName == Constants.TELECOM_SERVER_PACKAGE
						if (mContext == null || (telecomCase && !TELECOM_SERVER_LOADED)) {
							if (telecomCase) TELECOM_SERVER_LOADED = true

							// The context is the last argument of newApplication.
							val context = hookParam.args[hookParam.args.size - 1] as Context
							mContext = context

							moduleResources = context
								.createPackageContext(BuildConfig.APPLICATION_ID, CONTEXT_IGNORE_SECURITY)
								.resources

							XPrefs.init(context)

							waitForXprefsLoad(param)
						}
					} catch (t: Throwable) {
						Logger.log(t)
					}
				}
		}
	}

	/**
	 * Blocks until the remote preferences store answers, then starts the packs.
	 *
	 * The probe key does not exist; what is being tested is whether the read
	 * throws at all, which it does until the module app's provider is reachable.
	 */
	private fun waitForXprefsLoad(param: XposedModuleInterface.PackageReadyParam) {
		while (true) {
			try {
				XPrefs.Xprefs.getBoolean("LoadTestBooleanValue", false)
				break
			} catch (ignored: Throwable) {
				SystemUtils.threadSleep(1000)
			}
		}

		Logger.log(
			"Loading PixelXpert version: %s on %s".format(BuildConfig.VERSION_NAME, param.packageName),
		)
		try {
			Logger.log("PixelXpert Records: " + XPrefs.Xprefs.all.size)
		} catch (ignored: Throwable) {
		}

		onXPrefsReady(param)
	}

	private fun onXPrefsReady(param: XposedModuleInterface.PackageReadyParam) {
		if (isBootLooped(param.packageName)) {
			Logger.log(
				"PixelXpert: Possible bootloop in %s. Will not load for now".format(param.packageName),
			)
			return
		}

		SystemUtils(mContext!!)
		XPrefs.setPackagePrefs(param.packageName)

		loadModPacks(param)

		XPrefs.onContentProviderLoaded()
	}

	private fun loadModPacks(param: XposedModuleInterface.PackageReadyParam) {
		ReflectedClass.setDefaultClassloader(param.classLoader)

		if (moduleResources!!.getStringArray(R.array.root_requirement).contains(param.packageName)) {
			forceConnectRootService()
		}

		for (modPackData in ModPacks.getModPacks()) {
			// A pack that is not main-process-only is restricted to the child
			// processes whose name contains its marker; everything else runs in
			// every process of the target package.
			val partOfProcessName = if (modPackData.targetsMainProcess) "" else modPackData.childProcessName

			val target = modPackData.targetPackage
			val matchesTarget = target == param.packageName ||
				// common mod packs
				target.isEmpty() ||
				(target == Constants.SYSTEM_FRAMEWORK_PACKAGE && isSystemServer)

			if (matchesTarget && processName.contains(partOfProcessName)) {
				@Suppress("UNCHECKED_CAST")
				loadModPack(modPackData.clazz as Class<out XposedModPack>, param)
			}
		}
	}

	private fun loadModPack(thisClass: Class<out XposedModPack>, param: XposedModuleInterface.PackageReadyParam) {
		try {
			val modInstance = thisClass.getConstructor(Context::class.java).newInstance(mContext)
			try {
				modInstance.onPreferenceUpdated()
			} catch (ignored: Throwable) {
			}

			modInstance.onPackageLoaded(param)
			runningMods.add(modInstance)
		} catch (t: Throwable) {
			Logger.log("Start Error Dump - Occurred in " + thisClass.name)
			Logger.log(t)
		}
	}

	/**
	 * Retries [connectRootService] from a background thread until the binder
	 * arrives.
	 *
	 * The first wait is for credential-encrypted storage, which the UserManager
	 * only reports as unlocked once boot has genuinely finished - binding before
	 * that would bind into a half-initialised module app. The second five
	 * seconds lets the unlocked state settle before the first attempt, and
	 * repeats are spaced the same way afterwards.
	 */
	private fun forceConnectRootService() {
		Thread {
			while (SystemUtils.UserManager() == null ||
				!SystemUtils.UserManager()!!.isUserUnlocked // device is still CE encrypted
			) {
				SystemUtils.threadSleep(2000)
			}
			SystemUtils.threadSleep(5000) // wait for the unlocked account to settle a bit

			while (rootProxyIPC == null) {
				connectRootService()
				SystemUtils.threadSleep(5000)
			}
		}.start()
	}

	private fun connectRootService() {
		try {
			val intent = Intent()
			intent.component = ComponentName(BuildConfig.APPLICATION_ID, PixelXpertProxy::class.java.name)
			mContext!!.bindService(
				intent,
				instance!!,
				Context.BIND_AUTO_CREATE or Context.BIND_ADJUST_WITH_ACTIVITY,
			)
		} catch (t: Throwable) {
			Logger.log(t)
		}
	}

	override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
		rootProxyIPC = IPixelXpertProxy.Stub.asInterface(service)
		rootProxyCountdown.countDown()

		synchronized(proxyQueue) {
			while (proxyQueue.isNotEmpty()) {
				try {
					proxyQueue.poll()!!.run(rootProxyIPC!!)
				} catch (ignored: Throwable) {
				}
			}
		}
	}

	override fun onServiceDisconnected(name: ComponentName?) {
		rootProxyIPC = null

		forceConnectRootService()
	}

	companion object {
		var processName: String = ""
		var isSystemServer: Boolean = false

		val runningMods: CopyOnWriteArrayList<XposedModPack> = CopyOnWriteArrayList()

		@SuppressLint("StaticFieldLeak")
		var instance: XPLauncher? = null

		private var rootProxyIPC: IPixelXpertProxy? = null
		private val proxyQueue = LinkedList<ProxyRunnable>()
		private var TELECOM_SERVER_LOADED = false

		/** Resources of the module app, read for arrays such as `root_requirement`. */
		var moduleResources: Resources? = null

		/**
		 * Audio focus on Android 17 beta returns a null context on retry.
		 *
		 * This walks the working fallback at each request so a hook in a
		 * framework service never sees the null context from that path.
		 */
		private fun hook17BetaAudioManagerSRWorkaround(param: XposedModuleInterface.PackageReadyParam) {
			ReflectedClass.of("android.media.AudioManager", param.classLoader)
				.before("requestAudioFocus")
				.run(instance) { hookParam ->
					if (getObjectField(hookParam.thisObject, "mApplicationContext") == null) {
						setObjectField(
							hookParam.thisObject,
							"mApplicationContext",
							getObjectField(hookParam.thisObject, "mOriginalContext"),
						)
					}
				}
		}

		/** Binder to the module app's root proxy, connecting on demand. */
		@JvmStatic
		fun getRootProviderProxy(): IPixelXpertProxy? {
			if (rootProxyIPC == null) {
				val target = instance ?: return null
				target.rootProxyCountdown = CountDownLatch(1)
				target.forceConnectRootService()
				try {
					//noinspection ResultOfMethodCallIgnored
					target.rootProxyCountdown.await(5, TimeUnit.SECONDS)
				} catch (ignored: Throwable) {
				}
			}
			return rootProxyIPC
		}

		/**
		 * Runs [runnable] now if the proxy is up, otherwise queues it and
		 * connects so it runs as soon as the binder arrives.
		 */
		@JvmStatic
		fun enqueueProxyCommand(runnable: ProxyRunnable) {
			val proxy = rootProxyIPC
			if (proxy != null) {
				try {
					runnable.run(proxy)
				} catch (ignored: RemoteException) {
				}
			} else {
				synchronized(proxyQueue) {
					proxyQueue.add(runnable)
				}
				instance?.forceConnectRootService()
			}
		}
	}

	fun interface ProxyRunnable {
		@Throws(RemoteException::class)
		fun run(proxy: IPixelXpertProxy)
	}
}
