package sh.siava.pixelxpert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.ipc.RootService
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import sh.siava.pixelxpert.data.PrefStore
import sh.siava.pixelxpert.di.pxnModule
import sh.siava.pixelxpert.service.RootProvider

/**
 * Application entry point for the standalone Compose rewrite.
 *
 * It also owns the connection to the Xposed framework, because that is
 * process-wide state with a single owner: the Hooks page and any future
 * feature that needs the framework all ask here rather than each binding
 * their own listener.
 */
class PxnApp : android.app.Application() {

	/**
	 * The framework's service, or null when no Xposed implementation is
	 * active. Read from the main thread by the UI, written by the binder
	 * thread, hence the volatility.
	 */
	@Volatile
	var xposedService: XposedService? = null
		private set

	override fun onCreate() {
		super.onCreate()
		instance = this

		startKoin {
			androidContext(this@PxnApp)
			modules(pxnModule)
		}

		// Must happen before the content provider that serves the hooks is
		// created (Android makes providers after Application.onCreate), or
		// ExtendedRemotePreferences would see "not ready" and swallow every
		// preference change the mod packs are waiting for.
		//
		// Direct boot is the exception: this process may be started pre-unlock
		// by the directBootAware provider, in which case markReady defers the
		// write to ACTION_USER_UNLOCKED instead of throwing out of onCreate.
		PrefStore.markReady(this)

		createNotificationChannel()
		bindXposedService()
	}

	/**
	 * The update channel, created before anything can post to it: a
	 * notification posted against an unknown channel is dropped on Android
	 * 8 and up rather than merely mis-categorised.
	 */
	private fun createNotificationChannel() {
		val manager = getSystemService(NotificationManager::class.java) ?: return
		manager.createNotificationChannel(
			NotificationChannel(
				Constants.UPDATES_CHANNEL_ID,
				getString(R.string.update_channel_name),
				NotificationManager.IMPORTANCE_DEFAULT,
			),
		)
	}

	/**
	 * Registers for framework bind/death callbacks: `XposedServiceHelper`
	 * delivers on the binder thread and the framework may die and come back,
	 * so the holder has to be updated wherever that happens rather than at
	 * the moment a screen happens to ask.
	 */
	private fun bindXposedService() {
		XposedServiceHelper.registerListener(
			object : XposedServiceHelper.OnServiceListener {
				override fun onServiceBind(service: XposedService) {
					xposedService = service
				}

				override fun onServiceDied(service: XposedService) {
					xposedService = null
				}
			},
		)
	}

	// -----------------------------------------------------------------------
	// libsu root service
	//
	// It lives on the Application because the splash screen waits on it
	// before letting the user in, and the connection has to outlive any single
	// activity.
	//
	// libsu's RootService extends android.content.ContextWrapper rather than
	// android.app.Service: it is spawned as a root process and bound through
	// libsu's own IPC, so nothing about it is declared in the manifest.
	// -----------------------------------------------------------------------

	/** True once libsu reports the root service bound. */
	@Volatile
	var rootServiceBound = false
		private set

	/** The binder, for callers that need more than the fact of a connection. */
	@Volatile
	var rootServiceIPC: IRootProviderService? = null
		private set

	/**
	 * Counts down exactly once, whether the service connects or drops. This
	 * is what lets the splash distinguish "settled and connected" from
	 * "settled and failed" by reading [CountDownLatch.getCount] rather than
	 * by tracking a separate error flag.
	 */
	val rootServiceConnected = CountDownLatch(1)

	private val mainThreadHandler = Handler(Looper.getMainLooper())

	/**
	 * Connects off the calling thread, retrying once.
	 */
	fun tryConnectRootService() {
		thread(name = "pxn-root-service") {
			repeat(2) {
				if (connectRootService()) return@thread
			}
		}
	}

	/** @return true when the service settled within the retry window. */
	private fun connectRootService(): Boolean = try {
		val connection = object : ServiceConnection {
			override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
				rootServiceBound = true
				rootServiceConnected.countDown()
				rootServiceIPC = IRootProviderService.Stub.asInterface(service)
			}

			override fun onServiceDisconnected(name: ComponentName?) {
				rootServiceBound = false
				rootServiceConnected.countDown()
			}
		}

		// libsu requires the bind to be issued from the main thread.
		mainThreadHandler.post {
			RootService.bind(Intent(this, RootProvider::class.java), connection)
		}

		// Five seconds here is only the retry budget. The splash waits on the
		// latch itself without a timeout.
		rootServiceConnected.await(5, TimeUnit.SECONDS)
	} catch (e: Exception) {
		false
	}

	/**
	 * Hands [callback] the framework service, waiting up to a second for it
	 * to bind.
	 *
	 * The wait matters: binding is asynchronous, and a screen that asked the
	 * instant it appeared would see nothing and conclude the module was
	 * inactive.
	 */
	fun getXposedService(callback: (XposedService) -> Unit) {
		thread(name = "pxn-xposed-bind") {
			var waited = 0
			while (xposedService == null && waited < 5) {
				waited++
				try {
					Thread.sleep(200)
				} catch (ignored: InterruptedException) {
					break
				}
			}
			xposedService?.let(callback)
		}
	}

	companion object {
		@Volatile
		private var instance: PxnApp? = null

		@JvmStatic
		fun get(): PxnApp = requireNotNull(instance) { "PxnApp not initialized" }
	}
}
