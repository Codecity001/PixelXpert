package sh.siava.pixelxpert

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.koin.android.ext.android.inject
import sh.siava.pixelxpert.data.PrefStore
import sh.siava.pixelxpert.theme.PxnTheme
import sh.siava.pixelxpert.ui.nav.Route
import sh.siava.pixelxpert.util.ModuleInstaller

/**
 * The app's single Compose host activity.
 *
 * Last stop of the FakeSplashActivity -> SplashActivity -> MainActivity
 * chain: [FakeSplashActivity] owns the platform splash and hands off to
 * [SplashActivity], which runs the root checks and hands over here, per the
 * Material 3 Expressive rebuild.
 *
 * The window background needs no code here: Theme.PXN paints it from
 * pxn_window_background, the system background role, so it already matches the
 * Compose content from the instant the window is created - before any [onCreate]
 * could run.
 *
 * It also owns three intents: the update notification, the Magisk install
 * callback and the "new update" broadcast.
 * They all concern the updater, but they land here because this is the only
 * activity, so the work happens here and the destination is handed to
 * [PxnRoot] as [startRoute] rather than reaching into the composition.
 */
class MainActivity : ComponentActivity() {

	private val store: PrefStore by inject()

	/**
	 * The tab an incoming intent asked for.
	 *
	 * Held per activity rather than in a shared object: a second
	 * notification tap starts a second instance, and the one that was
	 * launched has to be the one that navigates.
	 */
	private var startRoute by mutableStateOf<Route?>(null)

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)

		handleIntent(intent)

		setContent {
			PxnTheme {
				PxnRoot(startRoute = startRoute)
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleIntent(intent)
	}

	private fun handleIntent(intent: Intent?) {
		if (intent == null) return

		when {
			// The install notification: Magisk has the zip staged, and
			// installing it here is what "tap to install" means.
			intent.getBooleanExtra(EXTRA_UPDATE_TAPPED, false) -> {
				intent.getStringExtra(EXTRA_FILE_PATH)?.let { path ->
					// A file copy plus two root commands: never on the frame
					// that delivered the intent.
					Thread { ModuleInstaller.installDoubleZip(path) }.start()
				}
				startRoute = Route.Update
			}

			// Magisk calls back after unpacking the module, so the staged
			// module's system.prop can still be written before the reboot
			// that promotes it.
			intent.getStringExtra(EXTRA_MIGRATE_PREFS) == "true" -> {
				Thread {
					ModuleInstaller.applyVolumeSteps(
						volumeSteps = store.getSliderInt(KEY_VOLUME_STEPS, 0),
						stagedModuleDir = ModuleInstaller.updateDir,
					)
				}.start()
				startRoute = Route.Update
			}

			intent.getBooleanExtra(EXTRA_NEW_UPDATE, false) -> startRoute = Route.Update
		}
	}

	companion object {
		private const val EXTRA_UPDATE_TAPPED = "updateTapped"
		private const val EXTRA_FILE_PATH = "filePath"
		private const val EXTRA_MIGRATE_PREFS = "migratePrefs"
		private const val EXTRA_NEW_UPDATE = "newUpdate"
		private const val KEY_VOLUME_STEPS = "volumeStps"
	}
}
