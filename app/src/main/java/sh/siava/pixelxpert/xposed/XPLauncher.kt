package sh.siava.pixelxpert.xposed

import android.annotation.SuppressLint
import android.app.AppComponentFactory
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Context.CONTEXT_IGNORE_SECURITY
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.ArrayMap
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.IPixelXpertProxy
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.service.PixelXpertProxy
import sh.siava.pixelxpert.xposed.utils.BootLoopProtector.isBootLooped
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setObjectField
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger
import java.util.LinkedList
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The module's entry point, and everything that has to happen before a mod pack
 * can be constructed.
 *
 * The sequence, which is the whole reason this class exists, is:
 *
 *  1. [onModuleLoaded] records the process name and attaches a fresh
 *     [XposedGeneration], because the process identity decides which packs are
 *     allowed in (a child process only gets a pack that asked for it).
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
 * Hot reload does not replay steps 2-4. [onHotReloading] hands the framework a
 * classloader-neutral snapshot of the process/package state and retires the old
 * generation, and [onHotReloaded] rebuilds the same state against the new
 * generation before unhooking whatever the new code did not replace.
 */
class XPLauncher :
	XposedModule(),
	ServiceConnection {

	/** Package under hook; null until [XPrefs.init] has run. */
	var mContext: Context? = null

	private var rootProxyCountdown = CountDownLatch(1)

	/** Package state captured for a hot reload; framework objects only. */
	private var loadedPackageName: String? = null
	private var loadedClassLoader: ClassLoader? = null
	private var loadedDefaultClassLoader: ClassLoader? = null
	private var loadedAppComponentFactory: AppComponentFactory? = null
	private var loadedApplicationInfo: ApplicationInfo? = null

	/**
	 * Package state that actually initialized the mod packs.
	 *
	 * A process can receive more than one package-ready callback, and the last
	 * one is not necessarily the package the packs were built for. Keeping the
	 * snapshot taken when the packs were loaded is what makes a later hot
	 * reload rebuild the same package instead of whichever callback happened
	 * last.
	 */
	private var packsPackageName: String? = null
	private var packsClassLoader: ClassLoader? = null
	private var packsDefaultClassLoader: ClassLoader? = null
	private var packsAppComponentFactory: AppComponentFactory? = null
	private var packsApplicationInfo: ApplicationInfo? = null
	private var packsLoaded = false
	private var pendingPackStates: Map<*, *>? = null

	private var boundToRootService = false
	private var rootConnectThread: Thread? = null

	init {
		instance = this
	}

	override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
		super.onModuleLoaded(param)

		processName = param.processName
		isSystemServer = param.isSystemServer

		val generation = XposedRuntime.attach(this, processName, isSystemServer)
		registerGenerationCleanups(generation)
	}

	override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
		ReflectedClass.setFrameworkClassloader(param.classLoader)
	}

	override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
		loadedPackageName = param.packageName
		loadedClassLoader = param.classLoader
		loadedDefaultClassLoader = param.defaultClassLoader
		loadedAppComponentFactory = param.appComponentFactory
		loadedApplicationInfo = param.applicationInfo

		ReflectedClass.setDefaultXposedInterface(this)
		installPackageHooks(param)
	}

	/**
	 * Saves only classloader-neutral state: strings, primitives, and framework
	 * objects (context, class loaders, resources, application info).
	 *
	 * The old generation is retired only after the state has been accepted, so
	 * a rejected transfer leaves the running module intact.
	 */
	override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
		val generation = XposedRuntime.generation ?: return false

		val packStates = ArrayMap<String, Any?>()
		for (mod in runningMods.toList()) {
			try {
				val modState = ArrayMap<String, Any?>()
				mod.saveHotReloadState(modState)
				if (modState.isNotEmpty()) {
					packStates[mod.javaClass.name] = modState
				}
			} catch (t: Throwable) {
				Logger.log("PixelXpert: could not save hot reload state for ${mod.javaClass.name}")
				Logger.log(t)
			}
		}

		val state = arrayOf<Any?>(
			processName,
			isSystemServer,
			packsPackageName ?: loadedPackageName,
			packsClassLoader ?: loadedClassLoader,
			packsDefaultClassLoader ?: loadedDefaultClassLoader,
			packsAppComponentFactory ?: loadedAppComponentFactory,
			packsApplicationInfo ?: loadedApplicationInfo,
			mContext,
			moduleResources,
			ReflectedClass.getFrameworkClassloader(),
			packsLoaded,
			packStates,
		)

		return try {
			param.setSavedInstanceState(state)
			generation.retire()
			true
		} catch (t: Throwable) {
			Logger.log("PixelXpert: hot reload state could not be saved")
			Logger.log(t)
			false
		}
	}

	override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
		processName = param.processName
		isSystemServer = param.isSystemServer

		val generation = XposedRuntime.attach(this, processName, isSystemServer)
		registerGenerationCleanups(generation)
		ReflectedClass.setDefaultXposedInterface(this)

		val saved = param.savedInstanceState as? Array<*>

		try {
			if (saved != null) {
				restoreState(saved)
				reloadLoadedPackage()
			}
		} catch (t: Throwable) {
			Logger.log("PixelXpert: hot reload initialization failed")
			Logger.log(t)
		} finally {
			// Hooks the new generation registered with the same id replaced
			// their old handles atomically; unhooking those is a documented
			// no-op. Anything not replaced is an old-generation hook that must
			// not keep running old code.
			param.oldHookHandles.forEach { handle -> runCatching(handle::unhook) }
		}
	}

	private fun registerGenerationCleanups(generation: XposedGeneration) {
		generation.registerCleanup { XPrefs.release() }
		generation.registerCleanup { SystemUtils.release() }
		generation.registerCleanup { unbindRootService() }
	}

	private fun installPackageHooks(param: XposedModuleInterface.PackageReadyParam) {
		hook17BetaAudioManagerSRWorkaround(param)

		if (isSystemServer && param.packageName != Constants.TELECOM_SERVER_PACKAGE) {
			ReflectedClass.of("com.android.server.policy.PhoneWindowManager")
				.before("init")
				.run(instance) { hookParam ->
					try {
						if (mContext == null) {
							initializeWithContext(param, hookParam.args[0] as Context)
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
							initializeWithContext(param, hookParam.args[hookParam.args.size - 1] as Context)
						}
					} catch (t: Throwable) {
						Logger.log(t)
					}
				}
		}
	}

	/**
	 * First point at which the hooked process has a usable [Context].
	 *
	 * System server must not block on the provider: it is still booting, and the
	 * module app may not even be startable yet, so its wait runs on a tracked
	 * background thread. Every other process may block its main thread briefly,
	 * which is what the original entry point did.
	 */
	private fun initializeWithContext(param: XposedModuleInterface.PackageReadyParam, context: Context) {
		if (mContext != null || !XposedRuntime.isActive()) return

		mContext = context
		moduleResources = runCatching {
			context.createPackageContext(BuildConfig.APPLICATION_ID, CONTEXT_IGNORE_SECURITY).resources
		}.getOrNull() ?: moduleResources

		XPrefs.init(context)

		val generation = XposedRuntime.generation
		if (isSystemServer && param.packageName != Constants.TELECOM_SERVER_PACKAGE && generation != null) {
			generation.startThread("pxn-prefs-${param.packageName}") {
				waitForXprefsLoad(param)
			}
		} else {
			waitForXprefsLoad(param)
		}
	}

	/**
	 * Blocks until the remote preferences store answers, then starts the packs.
	 *
	 * The probe key does not exist; what is being tested is whether the read
	 * throws at all, which it does until the module app's provider is reachable.
	 */
	private fun waitForXprefsLoad(param: XposedModuleInterface.PackageReadyParam) {
		while (XposedRuntime.isActive() && !Thread.currentThread().isInterrupted) {
			try {
				XPrefs.Xprefs.getBoolean("LoadTestBooleanValue", false)
				break
			} catch (ignored: Throwable) {
				SystemUtils.threadSleep(1000)
			}
		}

		if (!XposedRuntime.isActive()) return

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

		val generation = XposedRuntime.generation ?: return
		if (!generation.isActive()) return

		generation.installOnce("packs:${param.packageName}") {
			ensureSystemUtils(mContext ?: return@installOnce, param)
			XPrefs.setPackagePrefs(param.packageName)

			capturePackPackage(param)
			loadModPacks(param)

			XPrefs.onContentProviderLoaded()
			packsLoaded = true
		}
	}

	private fun loadModPacks(param: XposedModuleInterface.PackageReadyParam) {
		ReflectedClass.setDefaultClassloader(param.classLoader)

		if (moduleResources?.getStringArray(R.array.root_requirement)?.contains(param.packageName) == true) {
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

			val savedState = pendingPackStates?.get(thisClass.name)
			if (savedState is Map<*, *>) {
				try {
					@Suppress("UNCHECKED_CAST")
					modInstance.restoreHotReloadState(savedState as Map<String, Any?>)
				} catch (t: Throwable) {
					Logger.log("PixelXpert: could not restore hot reload state for ${thisClass.name}")
					Logger.log(t)
				}
			}

			runningMods.add(modInstance)
			XposedRuntime.generation?.registerCleanup { modInstance.onUnloaded() }
		} catch (t: Throwable) {
			Logger.log("Start Error Dump - Occurred in " + thisClass.name)
			Logger.log(t)
		}
	}

	/**
	 * Rebuilds the state that package callbacks would have produced.
	 *
	 * Uses the saved package name/classloader/context rather than replaying
	 * [onPackageReady], which the framework deliberately does not call again.
	 */
	private fun reloadLoadedPackage() {
		val packageName = loadedPackageName ?: return
		val classLoader = loadedClassLoader ?: return
		val defaultClassLoader = loadedDefaultClassLoader ?: classLoader
		val generation = XposedRuntime.generation ?: return

		ReflectedClass.setDefaultClassloader(classLoader)

		val param =
			SavedPackageReadyParam(
				packageName = packageName,
				classLoader = classLoader,
				defaultClassLoader = defaultClassLoader,
				applicationInfo = loadedApplicationInfo,
				appComponentFactory = loadedAppComponentFactory,
			)

		val context = mContext
		if (context != null) {
			moduleResources = moduleResources ?: runCatching {
				context.createPackageContext(BuildConfig.APPLICATION_ID, CONTEXT_IGNORE_SECURITY).resources
			}.getOrNull()

			// Have the utilities and preferences ready before any reinstalled
			// hook can fire; a layout listener or a first callback may run as
			// soon as it is registered.
			XPrefs.init(context)
			ensureSystemUtils(context, param)
		}

		// Reinstall the package hooks even when the context has not arrived
		// yet: the framework does not replay package callbacks after a hot
		// reload, so the hook that delivers the context has to exist in this
		// generation or it would never fire.
		installPackageHooks(param)

		if (context == null) return

		// If the old generation had not finished waiting for the provider, do
		// that hand-off again instead of reading preferences too early.
		if (!packsLoaded) {
			waitForXprefsLoad(param)
			return
		}

		generation.installOnce("hot-reload-packs:$packageName") {
			ensureSystemUtils(context, param)
			XPrefs.setPackagePrefs(packageName)
			capturePackPackage(param)
			runOnMainThread { loadModPacks(param) }
			XPrefs.onContentProviderLoaded()
			packsLoaded = true
		}
	}

	/**
	 * Runs pack construction on the process main thread.
	 *
	 * A hot reload arrives on the framework's reload thread, but packs are
	 * normally built on the main thread and some of them construct
	 * `GestureDetector`/`Handler` state that requires a Looper there.
	 */
	private fun runOnMainThread(action: () -> Unit) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			action()
			return
		}

		val latch = CountDownLatch(1)
		val failure = AtomicReference<Throwable?>()
		Handler(Looper.getMainLooper()).post {
			try {
				action()
			} catch (t: Throwable) {
				failure.set(t)
			} finally {
				latch.countDown()
			}
		}

		if (!latch.await(30, TimeUnit.SECONDS)) {
			throw IllegalStateException("Timed out loading mod packs on the main thread")
		}
		failure.get()?.let { throw it }
	}

	private fun ensureSystemUtils(context: Context, param: XposedModuleInterface.PackageReadyParam) {
		XposedRuntime.generation?.installOnce("systemutils:${param.packageName}") {
			SystemUtils(context)
		}
	}

	private fun capturePackPackage(param: XposedModuleInterface.PackageReadyParam) {
		packsPackageName = param.packageName
		packsClassLoader = param.classLoader
		packsDefaultClassLoader = param.defaultClassLoader
		packsAppComponentFactory = param.appComponentFactory
		packsApplicationInfo = param.applicationInfo
	}

	private fun restoreState(saved: Array<*>) {
		loadedPackageName = saved.getOrNull(2) as? String
		loadedClassLoader = saved.getOrNull(3) as? ClassLoader
		loadedDefaultClassLoader = saved.getOrNull(4) as? ClassLoader
		loadedAppComponentFactory = saved.getOrNull(5) as? AppComponentFactory
		loadedApplicationInfo = saved.getOrNull(6) as? ApplicationInfo
		mContext = saved.getOrNull(7) as? Context
		moduleResources = saved.getOrNull(8) as? Resources
		ReflectedClass.setFrameworkClassloader(saved.getOrNull(9) as? ClassLoader)
		packsLoaded = saved.getOrNull(10) as? Boolean ?: false
		pendingPackStates = saved.getOrNull(11) as? Map<*, *>

		// Older generations could overwrite the package snapshot when a
		// process received a package-ready callback for another package. The
		// application context always belongs to this process, so it is the
		// reliable source for a non-system-server target.
		val context = mContext
		if (!isSystemServer && context != null) {
			loadedPackageName = context.packageName
			loadedClassLoader = context.classLoader
			loadedDefaultClassLoader = context.classLoader
			loadedApplicationInfo = context.applicationInfo
			loadedAppComponentFactory = null
		}

		packsPackageName = loadedPackageName
		packsClassLoader = loadedClassLoader
		packsDefaultClassLoader = loadedDefaultClassLoader
		packsAppComponentFactory = loadedAppComponentFactory
		packsApplicationInfo = loadedApplicationInfo
	}

	/**
	 * Retries [connectRootService] from a tracked background thread until the
	 * binder arrives.
	 *
	 * The first wait is for credential-encrypted storage, which the UserManager
	 * only reports as unlocked once boot has genuinely finished - binding before
	 * that would bind into a half-initialised module app. The second five
	 * seconds lets the unlocked state settle before the first attempt, and
	 * repeats are spaced the same way afterwards.
	 */
	private fun forceConnectRootService() {
		val generation = XposedRuntime.generation ?: return
		if (!generation.isActive()) return
		if (rootConnectThread?.isAlive == true) return

		rootConnectThread = generation.startThread("pxn-root-${processName.ifEmpty { "unknown" }}") {
			while (generation.isActive() &&
				(
					SystemUtils.UserManager() == null ||
						SystemUtils.UserManager()?.isUserUnlocked != true
					) // device is still CE encrypted
			) {
				SystemUtils.threadSleep(2000)
			}
			if (!generation.isActive()) return@startThread
			SystemUtils.threadSleep(5000) // wait for the unlocked account to settle a bit

			while (generation.isActive() && rootProxyIPC == null) {
				connectRootService()
				SystemUtils.threadSleep(5000)
			}
		}
	}

	private fun connectRootService() {
		if (!XposedRuntime.isActive()) return

		try {
			val intent = Intent()
			intent.component = ComponentName(BuildConfig.APPLICATION_ID, PixelXpertProxy::class.java.name)
			val bound = mContext?.bindService(
				intent,
				instance ?: return,
				Context.BIND_AUTO_CREATE or Context.BIND_ADJUST_WITH_ACTIVITY,
			) == true
			boundToRootService = boundToRootService || bound
		} catch (t: Throwable) {
			Logger.log(t)
		}
	}

	private fun unbindRootService() {
		if (boundToRootService) {
			try {
				mContext?.unbindService(this)
			} catch (ignored: Throwable) {
			}
			boundToRootService = false
		}

		rootProxyIPC = null
		synchronized(proxyQueue) {
			proxyQueue.clear()
		}
	}

	override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
		rootProxyIPC = IPixelXpertProxy.Stub.asInterface(service)
		rootProxyCountdown.countDown()

		synchronized(proxyQueue) {
			while (proxyQueue.isNotEmpty()) {
				try {
					proxyQueue.poll()?.run(rootProxyIPC ?: break)
				} catch (ignored: Throwable) {
				}
			}
		}
	}

	override fun onServiceDisconnected(name: ComponentName?) {
		rootProxyIPC = null

		if (XposedRuntime.isActive()) {
			forceConnectRootService()
		}
	}

	/** [XposedModuleInterface.PackageReadyParam] rebuilt from hot-reload state. */
	private class SavedPackageReadyParam(
		private val packageName: String,
		private val classLoader: ClassLoader,
		private val defaultClassLoader: ClassLoader,
		private val applicationInfo: ApplicationInfo?,
		private val appComponentFactory: AppComponentFactory?,
	) : XposedModuleInterface.PackageReadyParam {
		override fun getPackageName(): String = packageName

		override fun getApplicationInfo(): ApplicationInfo =
			applicationInfo ?: ApplicationInfo().apply { this.packageName = packageName }

		override fun isFirstPackage(): Boolean = false

		override fun getDefaultClassLoader(): ClassLoader = defaultClassLoader

		override fun getClassLoader(): ClassLoader = classLoader

		override fun getAppComponentFactory(): AppComponentFactory = appComponentFactory ?: AppComponentFactory()
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
