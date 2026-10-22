package sh.siava.pixelxpert

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.theme.PxnTheme
import sh.siava.pixelxpert.util.AppUtils
import sh.siava.pixelxpert.util.ModuleInstaller
import sh.siava.pixelxpert.util.PixelGate

private enum class CheckState { RUNNING, DONE, FAILED }

/**
 * Launcher entry point: verify root, connect the root service, then admit.
 *
 * The first two checks are gated in sequence:
 *
 *  1. **Root permission** - [ModuleInstaller.hasRoot] off the main thread. A
 *     denial does not merely report itself: it fires [AppUtils.runKSURootActivity]
 *     (unless we were launched by KernelSU, which is already granting it) and
 *     raises a dialog that can only be dismissed by exiting the process. The
 *     latch never counts down in that case, so the second check never starts.
 *
 *  2. **Root service** - waits for check 1, then binds libsu's root service
 *     through [PxnApp]. Only once it has settled do we proceed, after holding
 *     the screen for a second so it does not flash past.
 *
 *  3. **Pixel device** - [PixelGate], gated behind check 1 as well, and
 *     awaited by the handover so a refused device never reaches
 *     `MainActivity`. Asking here, not only at module install time, is what
 *     lets the app refuse on its own rather than depending on the installer
 *     having done so.
 *
 * Each row shows a spinner while running and swap to a tick or cross on
 * completion.
 *
 * The wait in step 2 is deliberately unbounded: a timeout would turn
 * "still connecting" into "failed".
 */
class SplashActivity : ComponentActivity() {

	/** Counts down only when root is granted; stays at 1 on denial. */
	private val rootCheckPassed = CountDownLatch(1)

	private var rootState by mutableStateOf(CheckState.RUNNING)
	private var serviceState by mutableStateOf(CheckState.RUNNING)
	private var pixelState by mutableStateOf(CheckState.RUNNING)

	/**
	 * Counts down once check 3 has settled, pass or fail.
	 *
	 * Check 3 cannot start before check 1 has granted root, because its one
	 * escape hatch is only readable through a root shell, and it must settle
	 * before the screen hands over - refusing the device is the whole point -
	 * so the handover awaits this alongside the root service.
	 */
	private val pixelCheckDone = CountDownLatch(1)

	/** Check 3's verdict; a non-null token means the device was refused. */
	@Volatile private var pixelReason: String? = null

	/** Non-null once a check has failed; drives the blocking exit dialog. */
	private var failure by mutableStateOf<CharSequence?>(null)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()

		// Captured before the background threads read it, and forwarded to
		// MainActivity so the KSU and updater handoffs still arrive.
		val received = intent

		thread(name = "pxn-splash-root") {
			val granted = ModuleInstaller.hasRoot()

			if (granted) {
				rootCheckPassed.countDown()
			} else {
				// KernelSU reached us itself; asking it for root again would
				// loop.
				if (!received.hasExtra(FROM_KSU)) {
					AppUtils.runKSURootActivity(this, true)
				}
				failure = getString(R.string.root_access_denied)
			}

			rootState = if (granted) CheckState.DONE else CheckState.FAILED
		}

		thread(name = "pxn-splash-gate") {
			try {
				// Never satisfied when check 1 failed, so nothing beyond the
				// dialog happens - waiting here forever is deliberate.
				rootCheckPassed.await()

				val app = PxnApp.get()
				if (!app.rootServiceBound) {
					app.tryConnectRootService()
				}

				// Unbounded on purpose; see the class doc.
				app.rootServiceConnected.await()

				val connected = app.rootServiceConnected.count == 0L
				serviceState = if (connected) CheckState.DONE else CheckState.FAILED

				// Check 3 runs alongside this one, but the screen must not
				// hand over until it has answered: refusing the device is
				// the whole point of asking.
				pixelCheckDone.await()

				// Held for a second on purpose: it should not feel like it
				// flashed past.
				Thread.sleep(1000)

				val reason = pixelReason
				when {
					!connected -> failure = getString(R.string.root_service_failed)

					reason == null -> {
						startActivity(
							Intent(this@SplashActivity, MainActivity::class.java).apply {
								received.extras?.let(::putExtras)
							},
						)
						finish()
					}

					else -> failure = if (BuildConfig.DEBUG) {
						getString(R.string.pixel_not_supported_debug, reason)
					} else {
						getString(R.string.pixel_not_supported)
					}
				}
			} catch (ignored: InterruptedException) {
			}
		}

		thread(name = "pxn-splash-pixel") {
			// The escape hatch sits somewhere only a root shell reaches, so
			// nothing here can be attempted until root has been granted.
			rootCheckPassed.await()

			val reason = PixelGate.evaluate()
			pixelReason = reason
			pixelState = if (reason == null) CheckState.DONE else CheckState.FAILED
			pixelCheckDone.countDown()
		}

		setContent {
			PxnTheme {
				SplashContent(
					rootState = rootState,
					serviceState = serviceState,
					pixelState = pixelState,
					failure = failure,
					onExit = { System.exit(0) },
				)
			}
		}
	}

	companion object {
		/** Extra `KSUInjector` sets when it is the one launching us. */
		private const val FROM_KSU = "FromKSU"
	}
}

/**
 * Rasterises the launcher icon for display.
 *
 * `R.mipmap.ic_launcher` resolves to an `<adaptive-icon>` XML on API 31+, and
 * `painterResource` refuses those with "Only VectorDrawables and rasterized
 * asset types are supported". There is no raster fallback in this project, so
 * the drawable is drawn into a bitmap once and reused.
 */
@Composable
private fun rememberLauncherIcon(): ImageBitmap {
	val context = LocalContext.current
	return remember(context) {
		val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
			?: error("launcher icon missing")

		// Adaptive icons report the size of their layers; fall back rather
		// than let a negative intrinsic size reach Bitmap.createBitmap.
		drawable.toBitmap(
			drawable.intrinsicWidth.takeIf { it > 0 } ?: 256,
			drawable.intrinsicHeight.takeIf { it > 0 } ?: 256,
		).asImageBitmap()
	}
}

@Composable
private fun SplashContent(
	rootState: CheckState,
	serviceState: CheckState,
	pixelState: CheckState,
	failure: CharSequence?,
	onExit: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(16.dp),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Image(
			bitmap = rememberLauncherIcon(),
			contentDescription = null,
			modifier = Modifier.size(120.dp),
		)

		Text(
			text = stringResource(R.string.app_name),
			fontSize = 34.sp,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.padding(bottom = 32.dp),
		)

		// IntrinsicSize.Max hugs the widest row, so the block is centred as
		// a whole, the rows share one width, and their icons stay aligned.
		Column(modifier = Modifier.width(IntrinsicSize.Max)) {
			CheckRow(
				label = stringResource(R.string.checking_root_permissions),
				state = rootState,
			)
			CheckRow(
				label = stringResource(R.string.connecting_root_service),
				state = serviceState,
			)
			CheckRow(
				label = stringResource(R.string.checking_pixel_device),
				state = pixelState,
			)
		}
	}

	if (failure != null) {
		// Non-cancelable: an empty onDismissRequest is what makes the back
		// gesture and outside taps do nothing. The only way out is the Exit
		// button.
		AlertDialog(
			onDismissRequest = {},
			confirmButton = {
				TextButton(onClick = onExit) {
					Text(stringResource(R.string.exit))
				}
			},
			text = { Text(failure.toString()) },
		)
	}
}

@Composable
private fun CheckRow(
	label: String,
	state: CheckState,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		when (state) {
			CheckState.RUNNING -> CircularProgressIndicator(
				modifier = Modifier.size(32.dp),
				color = colorResource(R.color.launcher_accent),
			)

			// ic_success fills with launcher_accent itself, so no tint.
			CheckState.DONE -> Icon(
				painter = painterResource(R.drawable.ic_success),
				contentDescription = null,
				modifier = Modifier.size(32.dp),
				tint = Color.Unspecified,
			)

			// ic_fail is a white glyph the XML tinted red; painterResource
			// does not carry that tint over, so apply it here.
			CheckState.FAILED -> Icon(
				painter = painterResource(R.drawable.ic_fail),
				contentDescription = null,
				modifier = Modifier.size(32.dp),
				tint = Color(0xFFF44336),
			)
		}

		Text(
			text = label,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.padding(start = 8.dp),
		)
	}
}
