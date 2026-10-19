package sh.siava.pixelxpert

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.downloader.PRDownloader
import com.downloader.PRDownloaderConfig
import com.google.android.material.color.DynamicColors
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import sh.siava.pixelxpert.Constants.DEFAULT_PREFS_FILE_NAME
import sh.siava.pixelxpert.Constants.LAUNCH_REASON_XPOSED_SERVICE_FAIL
import sh.siava.pixelxpert.Constants.PREF_NO_CUTOUT_ENABLED
import sh.siava.pixelxpert.data.PrefStore
import sh.siava.pixelxpert.service.RootProvider
import sh.siava.pixelxpert.ui.misc.StateManager
import sh.siava.pixelxpert.utils.AppUtils
import sh.siava.pixelxpert.utils.ExtendedSharedPreferences
import sh.siava.pixelxpert.utils.PreferenceXMLParser

val appModule = module {
    single { PrefStore(androidContext()) }
    single { StateManager() }
}

class PixelXpert : Application() {

    companion object {
        const val TAG: String = "PixelXpertSingleton"

        @JvmStatic
        lateinit var instance: PixelXpert
            private set

        @JvmStatic
        fun get(): PixelXpert = instance
    }

    private val mainThreadHandler = Handler(Looper.getMainLooper())

    var isCoreRootServiceBound: Boolean = false
        private set

    val mRootServiceConnected = CountDownLatch(1)
    private var mCoreRootServiceConnection: ServiceConnection? = null
    var rootService: IRootProviderService? = null
        private set

    var xposedService: XposedService? = null
        private set

    val mXposedServiceConnected = CountDownLatch(1)
    private var mEarlyPreferenceSource: SharedPreferences? = null

    private val mEarlyPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (PREF_NO_CUTOUT_ENABLED == key) {
                syncEarlyStatusBarPreferences(xposedService, sharedPreferences)
            }
        }

    val stateManager: StateManager by lazy {
        GlobalContext.get().get<StateManager>()
    }

    val prefStore: PrefStore by lazy {
        GlobalContext.get().get<PrefStore>()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        startKoin {
            androidContext(this@PixelXpert)
            modules(appModule)
        }

        PrefStore.markReady(this)

        val config = PRDownloaderConfig.newBuilder()
            .setReadTimeout(30_000)
            .setConnectTimeout(30_000)
            .build()
        PRDownloader.initialize(applicationContext, config)

        mEarlyPreferenceSource = createDeviceProtectedStorageContext()
            .getSharedPreferences(DEFAULT_PREFS_FILE_NAME, Context.MODE_PRIVATE).apply {
                registerOnSharedPreferenceChangeListener(mEarlyPreferenceListener)
            }

        initiatePreferences(false)
        tryConnectRootService()
        DynamicColors.applyToActivitiesIfAvailable(this)
        tryConnectXposedService {}
    }

    private fun tryConnectXposedService(callback: XposedServiceCallback) {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                mXposedServiceConnected.countDown()
                syncEarlyStatusBarPreferences(service, mEarlyPreferenceSource)
                callback.serviceReady(service)
            }

            override fun onServiceDied(service: XposedService) {
                xposedService = null
            }
        })
    }

    fun awaitXposedService(timeout: Long, unit: TimeUnit): Boolean {
        if (xposedService != null) return true
        return try {
            mXposedServiceConnected.await(timeout, unit)
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun syncEarlyStatusBarPreferences(service: XposedService?, source: SharedPreferences?) {
        if (service == null || source == null) return
        runCatching {
            val committed = service.getRemotePreferences(DEFAULT_PREFS_FILE_NAME)
                .edit()
                .putBoolean(PREF_NO_CUTOUT_ENABLED, source.getBoolean(PREF_NO_CUTOUT_ENABLED, false))
                .commit()
            if (!committed) {
                Log.e(TAG, "Unable to commit early status bar preferences")
            }
        }.onFailure { e ->
            Log.e(TAG, "Unable to sync early status bar preferences", e)
        }
    }

    val defaultPreferences: ExtendedSharedPreferences
        get() = getDefaultPreferences()

    fun getDefaultPreferences(): ExtendedSharedPreferences {
        return ExtendedSharedPreferences.from(
            createDeviceProtectedStorageContext().getSharedPreferences(DEFAULT_PREFS_FILE_NAME, Context.MODE_PRIVATE)
        )
    }

    @SuppressLint("ApplySharedPref")
    fun initiatePreferences(resetAll: Boolean) {
        CompletableFuture.runAsync {
            runCatching {
                if (resetAll) {
                    defaultPreferences.edit().clear().commit()
                }
                setPrefsValidity(false)

                val xmlClass = classLoader.loadClass(R.xml::class.java.name)
                val prefPages = xmlClass.fields
                for (prefPage in prefPages) {
                    val resId = prefPage.getInt(null)
                    initiatePref(resId)
                }
                setPrefsValidity(true)
            }
        }
    }

    @SuppressLint("ApplySharedPref")
    fun setPrefsValidity(valid: Boolean) {
        defaultPreferences.edit().putBoolean(ExtendedSharedPreferences.IS_PREFS_INITIATED_KEY, valid).commit()
    }

    private fun initiatePref(resID: Int) {
        runCatching {
            PreferenceXMLParser.setDefaultsFromXml(this, resID, defaultPreferences)
        }
    }

    fun hasRootAccess(): Boolean {
        return Shell.getShell().isRoot
    }

    fun tryConnectRootService() {
        Thread {
            for (i in 0 until 2) {
                if (connectRootService()) break
            }
        }.start()
    }

    private fun connectRootService(): Boolean {
        return try {
            val intent = Intent(this, RootProvider::class.java)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    isCoreRootServiceBound = true
                    mRootServiceConnected.countDown()
                    rootService = IRootProviderService.Stub.asInterface(service)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    isCoreRootServiceBound = false
                    mRootServiceConnected.countDown()
                }
            }
            mCoreRootServiceConnection = conn

            mainThreadHandler.post { RootService.bind(intent, conn) }
            mRootServiceConnected.await(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
    }

    fun getXposedService(callback: XposedServiceCallback, restartOnFail: Boolean) {
        Thread {
            var counter = 0
            while (xposedService == null && counter < 5) {
                counter++
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                }
            }
            val service = xposedService
            if (service != null) {
                callback.serviceReady(service)
            } else {
                if (restartOnFail) {
                    AppUtils.restartSelf(LAUNCH_REASON_XPOSED_SERVICE_FAIL)
                } else {
                    Log.d(TAG, "getXposedService: didn't get xposed service but won't retry")
                }
            }
        }.start()
    }

    fun runRootCommand(command: String): Array<String> {
        return runCatching {
            val result = Shell.cmd(command).exec().out
            result.toTypedArray()
        }.getOrDefault(emptyArray())
    }

    fun interface XposedServiceCallback {
        fun serviceReady(service: XposedService)
    }
}
