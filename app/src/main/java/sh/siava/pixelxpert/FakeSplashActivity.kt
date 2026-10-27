package sh.siava.pixelxpert

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * Holds the system splash screen while [SplashActivity] starts.
 *
 * Its only job is to own the
 * `Theme.SplashScreen` window and get out of the way: the splash is pinned on
 * screen unconditionally, the check activity is launched underneath it, and
 * this one finishes.
 *
 * Splitting the two is what makes the checklist legible. When a single activity
 * both installed the splash and rendered the checks, the platform splash stayed
 * up until the first frame was drawn, and because root and the root service
 * settle well inside that window, the checks were already ticked by the time
 * the overlay cleared - the spinners never appeared. [SplashActivity] keeps a
 * plain theme for exactly this reason, so its check screen draws the instant
 * it starts instead of sitting under a splash overlay.
 *
 * Extras and [Intent.EXTRA_COMPONENT_NAME] are forwarded: the updater reaches
 * the app through this path.
 */
class FakeSplashActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		val splashScreen = installSplashScreen()
		super.onCreate(savedInstanceState)

		// Kept on screen unconditionally. The activity finishes immediately
		// afterwards, and that is what dismisses it.
		splashScreen.setKeepOnScreenCondition { true }

		val received = intent
		val forward = Intent(this, SplashActivity::class.java)

		received.extras?.let(forward::putExtras)
		received.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
			?.let { forward.putExtra(Intent.EXTRA_COMPONENT_NAME, it) }

		startActivity(forward)
		finish()
	}
}
