package sh.siava.pixelxpert.xposed

import android.annotation.SuppressLint
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Context.CONTEXT_IGNORE_SECURITY
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.IBinder
import android.os.RemoteException
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.IPixelXpertProxy
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.service.PixelXpertProxy
import sh.siava.pixelxpert.xposed.modpacks.android.StatusbarSize
import sh.siava.pixelxpert.xposed.utils.BootLoopProtector
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

class XPLauncher(base: XposedInterface, param: ModuleLoadedParam) : XposedModule(base, param), ServiceConnection {

    companion object {
        @JvmField
        var processName: String = ""

        @JvmField
        var isSystemServer: Boolean = false

        @JvmField
        val runningMods: MutableList<XposedModPack> = CopyOnWriteArrayList()

        @JvmStatic
        lateinit var instance: XPLauncher
            private set

        @JvmField
        var moduleResources: Resources? = null

        @JvmStatic
        private var rootProxyIPC: IPixelXpertProxy? = null

        private val proxyQueue: Queue<ProxyRunnable> = LinkedList()
        private var telecomServerLoaded = false

        @JvmStatic
        fun getRootProviderProxy(): IPixelXpertProxy? {
            if (rootProxyIPC == null && ::instance.isInitialized) {
                instance.rootProxyCountdown = CountDownLatch(1)
                instance.forceConnectRootService()
                runCatching {
                    instance.rootProxyCountdown.await(5, TimeUnit.SECONDS)
                }
            }
            return rootProxyIPC
        }

        @JvmStatic
        fun enqueueProxyCommand(runnable: ProxyRunnable) {
            val ipc = rootProxyIPC
            if (ipc != null) {
                runCatching {
                    runnable.run(ipc)
                }
            } else {
                synchronized(proxyQueue) {
                    proxyQueue.add(runnable)
                }
                if (::instance.isInitialized) {
                    instance.forceConnectRootService()
                }
            }
        }
    }

    @JvmField
    var mContext: Context? = null

    private var rootProxyCountdown = CountDownLatch(1)

    init {
        instance = this
        Logger.setXposedInterface(this)
        processName = param.processName
        isSystemServer = param.isSystemServer
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        ReflectedClass.setDefaultXposedInterface(this)
        ReflectedClass.setFrameworkClassloader(param.classLoader)

        var noCutoutEnabled = false
        var earlyPreferencesAvailable = false

        runCatching {
            val value = ReflectedClass.of("android.os.SystemProperties", param.classLoader)
                .callStaticMethod("getBoolean", Constants.PROP_NO_CUTOUT_ENABLED, false)
            if (value is Boolean) {
                noCutoutEnabled = value
            }
        }.onFailure { t ->
            Logger.log("PixelXpert: failed to read migrated status bar preferences", t)
        }

        runCatching {
            val earlyPreferences: SharedPreferences = getRemotePreferences(Constants.DEFAULT_PREFS_FILE_NAME)
            if (earlyPreferences.contains(Constants.PREF_NO_CUTOUT_ENABLED)) {
                noCutoutEnabled = earlyPreferences.getBoolean(Constants.PREF_NO_CUTOUT_ENABLED, false)
            }
            earlyPreferencesAvailable = true
        }.onFailure { t ->
            Logger.log("PixelXpert: failed to read early status bar preferences", t)
        }

        if (!earlyPreferencesAvailable) {
            StatusbarSize.suppressNoCutoutHookForBoot()
            Logger.log("PixelXpert: no-cutout hook disabled because boot-loop state is unavailable")
            return
        }

        if (!noCutoutEnabled) {
            StatusbarSize.installEarlyNoCutoutHook(param.classLoader, false)
            return
        }

        val bootLoopState = BootLoopProtector.checkAndRecordEarlySystemServerStart()
        if (bootLoopState == BootLoopProtector.BootLoopState.ALLOWED) {
            StatusbarSize.installEarlyNoCutoutHook(param.classLoader, true)
        } else {
            StatusbarSize.suppressNoCutoutHookForBoot()
            Logger.log("PixelXpert: no-cutout hook disabled for this boot: $bootLoopState")
        }
    }

    private fun hook17BetaAudioManagerSRWorkaround(param: PackageReadyParam) {
        ReflectedClass.of("android.media.AudioManager", param.classLoader)
            .before("requestAudioFocus")
            .run(instance) { hookParam ->
                if (getObjectField(hookParam.thisObject, "mApplicationContext") == null) {
                    setObjectField(
                        hookParam.thisObject,
                        "mApplicationContext",
                        getObjectField(hookParam.thisObject, "mOriginalContext")
                    )
                }
            }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        ReflectedClass.setDefaultXposedInterface(this)

        hook17BetaAudioManagerSRWorkaround(param)

        val targetPkg = param.packageName

        if (isSystemServer && targetPkg != Constants.TELECOM_SERVER_PACKAGE) {
            val phoneWindowManagerClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager")
            phoneWindowManagerClass
                .before("init")
                .run(instance) { hookParam ->
                    runCatching {
                        if (mContext == null) {
                            val ctx = hookParam.args.getOrNull(0) as? Context ?: return@runCatching
                            mContext = ctx
                            moduleResources = ctx.createPackageContext(BuildConfig.APPLICATION_ID, CONTEXT_IGNORE_SECURITY).resources
                            XPrefs.init(ctx)
                            CompletableFuture.runAsync { waitForXprefsLoad(param) }
                        }
                    }.onFailure { t ->
                        Logger.log(t)
                    }
                }
        }

        if (!isSystemServer || targetPkg == Constants.TELECOM_SERVER_PACKAGE) {
            ReflectedClass.of(Instrumentation::class.java)
                .after("newApplication")
                .run(this) { hookParam ->
                    runCatching {
                        if (mContext == null || (targetPkg == Constants.TELECOM_SERVER_PACKAGE && !telecomServerLoaded)) {
                            if (targetPkg == Constants.TELECOM_SERVER_PACKAGE) {
                                telecomServerLoaded = true
                            }
                            val ctx = hookParam.args.lastOrNull() as? Context ?: return@runCatching
                            mContext = ctx
                            moduleResources = ctx.createPackageContext(BuildConfig.APPLICATION_ID, CONTEXT_IGNORE_SECURITY).resources
                            XPrefs.init(ctx)
                            CompletableFuture.runAsync { waitForXprefsLoad(param) }
                        }
                    }.onFailure { t ->
                        Logger.log(t)
                    }
                }
        }
    }

    private fun waitForXprefsLoad(param: PackageReadyParam) {
        var attempts = 0
        while (attempts < 10) {
            try {
                XPrefs.getBoolean("LoadTestBooleanValue", false)
                break
            } catch (_: Throwable) {
                SystemUtils.threadSleep(500)
                attempts++
            }
        }

        Logger.log("Loading PixelXpert version: ${BuildConfig.VERSION_NAME} on ${param.packageName}")
        runCatching {
            Logger.log("PixelXpert Records: ${XPrefs.getAll().size}")
        }

        onXPrefsReady(param)
    }

    private fun onXPrefsReady(param: PackageReadyParam) {
        if (BootLoopProtector.isBootLooped(param.packageName)) {
            Logger.log("PixelXpert: Possible bootloop in ${param.packageName}. Will not load for now")
            return
        }

        val ctx = mContext ?: return
        SystemUtils(ctx)
        XPrefs.setPackagePrefs(param.packageName)

        loadModPacks(param)
        XPrefs.onContentProviderLoaded()
    }

    private fun loadModPacks(param: PackageReadyParam) {
        ReflectedClass.setDefaultClassloader(param.classLoader)

        val res = moduleResources
        if (res != null) {
            val rootRequired = res.getStringArray(R.array.root_requirement).toList()
            if (rootRequired.contains(param.packageName)) {
                forceConnectRootService()
            }
        }

        val allModPacks = ModPacks.getModPacks()
        for (modPackData in allModPacks) {
            val partOfProcessName = if (modPackData.mainProcess) "" else modPackData.childProcessName

            val matchesPackage = modPackData.targetPackage == param.packageName ||
                    modPackData.targetPackage.isEmpty() ||
                    (modPackData.targetPackage == Constants.SYSTEM_FRAMEWORK_PACKAGE && isSystemServer)

            val matchesProcess = processName.contains(partOfProcessName)

            if (matchesPackage && matchesProcess) {
                @Suppress("UNCHECKED_CAST")
                loadModPack(modPackData.clazz as Class<out XposedModPack>, param)
            }
        }
    }

    private fun loadModPack(clazz: Class<out XposedModPack>, param: PackageReadyParam) {
        runCatching {
            val instance = clazz.getConstructor(Context::class.java).newInstance(mContext)
            runCatching {
                instance.onPreferenceUpdated()
            }
            instance.onPackageLoaded(param)
            runningMods.add(instance)
        }.onFailure { t ->
            Logger.log("Start Error Dump - Occurred in ${clazz.name}")
            Logger.log(t)
        }
    }

    private fun forceConnectRootService() {
        Thread {
            while (SystemUtils.UserManager() == null || !SystemUtils.UserManager().isUserUnlocked) {
                SystemUtils.threadSleep(2000)
            }
            SystemUtils.threadSleep(5000)

            while (rootProxyIPC == null) {
                connectRootService()
                SystemUtils.threadSleep(5000)
            }
        }.start()
    }

    private fun connectRootService() {
        val ctx = mContext ?: return
        runCatching {
            val intent = Intent().apply {
                component = ComponentName(BuildConfig.APPLICATION_ID, PixelXpertProxy::class.java.name)
            }
            ctx.bindService(intent, this, Context.BIND_AUTO_CREATE or Context.BIND_ADJUST_WITH_ACTIVITY)
        }.onFailure { t ->
            Logger.log(t)
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val proxy = IPixelXpertProxy.Stub.asInterface(service)
        rootProxyIPC = proxy
        rootProxyCountdown.countDown()

        synchronized(proxyQueue) {
            while (proxyQueue.isNotEmpty()) {
                runCatching {
                    proxyQueue.poll()?.run(proxy)
                }
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        rootProxyIPC = null
        forceConnectRootService()
    }

    fun interface ProxyRunnable {
        @Throws(RemoteException::class)
        fun run(proxy: IPixelXpertProxy)
    }
}
