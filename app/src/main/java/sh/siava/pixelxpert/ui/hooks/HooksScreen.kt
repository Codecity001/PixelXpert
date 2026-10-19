package sh.siava.pixelxpert.ui.hooks

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.IBinder
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.delay
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.PxnApp
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.data.PrefStore
import sh.siava.pixelxpert.service.RootProvider
import sh.siava.pixelxpert.util.Restarter
import sh.siava.pixelxpert.IRootProviderService

private enum class RowStatus {
	CHECKING,

	HOOKED,

	NOT_FOUND,

	NOT_ENABLED,

	BOOTLOOPED,

	NO_RESPONSE,
}

private data class HookRow(
	val packageName: String,
	val installed: Boolean,
	val status: RowStatus,
	/** Extra explanation shown behind the "why?" chip, when non-null. */
	val reason: String? = null,
)

/**
 * The page exists to answer one question: which of the scoped packages is
 * this module actually inside? It asks by broadcasting
 * [Constants.ACTION_CHECK_XPOSED_ENABLED], gives the hooks five seconds to
 * answer with [Constants.ACTION_XPOSED_CONFIRMED], and then derives each
 * row's state from what it did not hear as much as what it did - a package
 * that is installed, in scope, and silent is reported as unresponsive
 * rather than left as "checking" forever.
 */
@Composable
fun HooksScreen(store: PrefStore) {
	val context = LocalContext.current
	val app = PxnApp.get()

	var rows by remember { mutableStateOf<List<HookRow>>(emptyList()) }
	var dots by remember { mutableIntStateOf(0) }
	var checking by remember { mutableStateOf(false) }
	var refreshing by remember { mutableStateOf(false) }
	var rebootPending by remember { mutableStateOf(false) }
	var showEnableDialog by remember { mutableStateOf(false) }
	var xposedService by remember { mutableStateOf<XposedService?>(null) }

	val hooked = remember { mutableStateOf<Set<String>>(emptySet()) }

	var rootIpc by remember { mutableStateOf<IRootProviderService?>(null) }
	var rootBound by remember { mutableStateOf(false) }

	val monitorScope = remember {
		context.resources.getStringArray(R.array.module_scope).toList()
	}

	/**
	 * Whether this package is installed, answered through the root service -
	 * the PackageManager lookups run as root because system packages are not
	 * always visible to the app user.
	 */
	fun isInstalled(pkg: String): Boolean =
		runCatching { rootIpc?.isPackageInstalled(pkg) }.getOrNull() ?: true

	/**
	 * Rebuilds every row's state from the evidence gathered so far.
	 *
	 * Order matters: hooked first (the broadcast is the
	 * strongest signal), then not-installed, then out-of-scope, then
	 * bootlooped, and finally "no response". Each branch decides the row's
	 * reason text at the same time, so the chip and the dialog agree.
	 */
	fun refreshRows() {
		val svc = xposedService
		val activeScope = svc?.scope?.toSet() ?: emptySet()

		rows = rows.map { row ->
			val confirmed = hooked.value.contains(row.packageName)
			val strikes = store.getInt("${Constants.PACKAGE_STRIKE_KEY_KEY}${row.packageName}", 0)

			when {
				confirmed -> row.copy(status = RowStatus.HOOKED, reason = null)
				!row.installed -> row.copy(status = RowStatus.NOT_FOUND, reason = null)
				svc == null || row.packageName !in activeScope ->
					row.copy(
						status = RowStatus.NOT_ENABLED,
						reason = context.getString(
							R.string.package_not_hook_enabled_info,
							context.getString(R.string.activate_in_lsposed),
						),
					)

				strikes >= 3 -> row.copy(
					status = RowStatus.BOOTLOOPED,
					reason = context.getString(R.string.package_hook_bootlooped_info),
				)

				else -> row.copy(
					status = RowStatus.NO_RESPONSE,
					reason = context.getString(R.string.package_hook_no_response_info),
				)
			}
		}

		// Shown once the check settles rather than on entry: a device without
		// the framework should still list its packages.
		if (svc == null && checking.not()) showEnableDialog = true
	}

	fun checkHookedPackages() {
		hooked.value = emptySet()
		dots = 0

		val scopeList = monitorScope.filter { pkg ->
			isInstalled(pkg) || (pkg != Constants.KSU_PACKAGE && pkg != Constants.KSU_NEXT_PACKAGE)
		}

		rows = scopeList.map { pkg ->
			HookRow(
				packageName = pkg,
				installed = isInstalled(pkg),
				status = RowStatus.CHECKING,
			)
		}

		checking = true

		// Sent off the main thread so the check cannot stall the UI.
		Thread {
			runCatching {
				context.sendBroadcast(Intent(Constants.ACTION_CHECK_XPOSED_ENABLED))
			}
		}.start()
	}

	val rootConnection = remember {
		object : ServiceConnection {
			override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
				rootIpc = IRootProviderService.Stub.asInterface(service)
				rootBound = true
			}

			override fun onServiceDisconnected(name: ComponentName?) {
				rootIpc = null
				rootBound = false
			}
		}
	}

	DisposableEffect(Unit) {
		RootService.bind(Intent(context, RootProvider::class.java), rootConnection)
		onDispose {
			runCatching { context.unbindService(rootConnection) }
		}
	}

	LaunchedEffect(Unit) {
		app.getXposedService { xposedService = it }
	}

	val receiver = remember {
		object : BroadcastReceiver() {
			override fun onReceive(ctx: Context?, intent: Intent?) {
				if (intent?.action != Constants.ACTION_XPOSED_CONFIRMED) return
				val pkg = intent.getStringExtra("packageName") ?: return

				hooked.value = hooked.value + pkg
				rows = rows.map { row ->
					if (row.packageName == pkg) row.copy(status = RowStatus.HOOKED, reason = null)
					else row
				}
			}
		}
	}

	DisposableEffect(Unit) {
		ContextCompat.registerReceiver(
			context,
			receiver,
			IntentFilter(Constants.ACTION_XPOSED_CONFIRMED),
			ContextCompat.RECEIVER_EXPORTED,
		)
		onDispose {
			runCatching { context.unregisterReceiver(receiver) }
		}
	}

	LaunchedEffect(checking) {
		if (!checking) return@LaunchedEffect
		repeat(WAIT_TICKS) {
			delay(TICK_MS)
			dots = (dots + 1) % 4
		}
		checking = false
		refreshRows()
		if (xposedService == null) showEnableDialog = true
	}

	LaunchedEffect(rootBound) {
		if (rootBound && rows.isEmpty()) checkHookedPackages()
	}

	if (showEnableDialog) {
		androidx.compose.material3.AlertDialog(
			onDismissRequest = { showEnableDialog = false },
			text = { Text(stringResource(R.string.enable_px_manually)) },
			confirmButton = {
				TextButton(onClick = { showEnableDialog = false }) {
					Text(stringResource(R.string.okay))
				}
			},
		)
	}

	Box(modifier = Modifier.fillMaxSize()) {
		PullToRefreshBox(
			isRefreshing = refreshing,
			onRefresh = {
				refreshing = true
				checkHookedPackages()
				refreshing = false
			},
			modifier = Modifier.fillMaxSize(),
		) {
			if (rows.isEmpty() && !rootBound) {
				CircularProgressIndicator(
					modifier = Modifier
						.align(Alignment.Center)
						.size(48.dp),
				)
			} else {
				LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = androidx.compose.foundation.layout.PaddingValues(
						vertical = 8.dp,
					),
					verticalArrangement = Arrangement.spacedBy(2.dp),
				) {
					itemsIndexed(rows) { index, row ->
						HookRowItem(
							row = row,
							dots = dots,
							isFirst = index == 0,
							isLast = index == rows.lastIndex,
							onActivate = { activate(context, xposedService, row.packageName) { rebootPending = true } },
							onLaunch = { launch(context, row.packageName) },
							onRestart = { restart(context, row.packageName) },
						)
					}
				}
			}
		}

		AnimatedVisibility(
			visible = rebootPending,
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.padding(end = 16.dp, bottom = 48.dp),
		) {
			ExtendedFloatingActionButton(
				onClick = { Restarter.restartSystem() },
				icon = {
					Icon(
						painter = painterResource(R.drawable.ic_reboot),
						contentDescription = stringResource(R.string.reboot_pending),
					)
				},
				text = { Text(stringResource(R.string.reboot_pending)) },
				containerColor = FloatingActionButtonDefaults.containerColor,
				shape = RoundedCornerShape(16.dp),
			)
		}
	}
}

/**
 * One monitored package: a 42dp icon, the package name with
 * the "why?" chip beside it, the state line, and - only when the package is
 * in scope but unhooked - the tonal activation button. Rows sit in one
 * grouped container, so the first and last carry the outer corner radius.
 */
@Composable
private fun HookRowItem(
	row: HookRow,
	dots: Int,
	isFirst: Boolean,
	isLast: Boolean,
	onActivate: () -> Unit,
	onLaunch: () -> Unit,
	onRestart: () -> Unit,
) {
	val context = LocalContext.current
	var menuOpen by remember { mutableStateOf(false) }
	// The "why?" chip explains itself in a dialog, which has to be emitted
	// from composition rather than from the click lambda.
	var showReason by remember { mutableStateOf(false) }

	val dimmed = !row.installed
	val descText = when (row.status) {
		RowStatus.CHECKING -> context.getString(R.string.package_checking, ".".repeat(dots))
		RowStatus.HOOKED -> context.getString(R.string.package_hooked_successful)
		RowStatus.NOT_FOUND -> context.getString(R.string.package_not_found)
		RowStatus.NOT_ENABLED -> context.getString(R.string.package_not_hook_enabled)
		RowStatus.BOOTLOOPED -> context.getString(R.string.package_hook_bootlooped)
		RowStatus.NO_RESPONSE -> context.getString(R.string.package_hook_no_response)
	}

	val descColor = when {
		row.status == RowStatus.HOOKED -> colorResource(R.color.success)
		row.status == RowStatus.CHECKING -> MaterialTheme.colorScheme.onSurface
		else -> colorResource(R.color.error)
	}

	val reason = row.reason
	val activateVisible = row.status == RowStatus.NOT_ENABLED

	// The target app's own icon, falling back to ic_android. The row is dimmed
	// rather than tinted - only its alpha changes.
	val fallbackIcon = remember {
		drawableBitmap(
			ContextCompat.getDrawable(context, R.drawable.ic_android)
				?: error("ic_android missing"),
		)
	}
	val iconBitmap = remember(row.packageName) {
		runCatching { context.packageManager.getApplicationIcon(row.packageName) }
			.getOrNull()
			?.let(::drawableBitmap)
			?: fallbackIcon
	}

	if (showReason && reason != null) {
		androidx.compose.material3.AlertDialog(
			onDismissRequest = { showReason = false },
			title = { Text(stringResource(R.string.whats_wrong)) },
			text = { Text(reason) },
			confirmButton = {
				TextButton(onClick = { showReason = false }) {
					Text(stringResource(R.string.okay))
				}
			},
		)
	}

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 1.dp)
			.clip(
				RoundedCornerShape(
					topStart = if (isFirst) 18.dp else 4.dp,
					topEnd = if (isFirst) 18.dp else 4.dp,
					bottomStart = if (isLast) 18.dp else 4.dp,
					bottomEnd = if (isLast) 18.dp else 4.dp,
				),
			)
			// Solid + corner radii + ripple: the radii come from clip
			// and the highlight from the click, while the fill is the theme's
			// surfaceBright so dark mode needs no second drawable.
			.background(MaterialTheme.colorScheme.surfaceBright)
			.clickable { }
			.alpha(if (dimmed) 0.4f else 1f)
			.padding(top = 18.dp, bottom = 18.dp, end = 23.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Image(
			bitmap = iconBitmap,
			contentDescription = null,
			contentScale = ContentScale.Inside,
			// Padding before size, never after: `size().padding()` insets the
			// *content* of an already-sized box, and 42dp minus 46dp of
			// horizontal padding clamps the drawable to zero width - which is
			// why the system icons were being fetched correctly and then drawn
			// at nothing. Padding first spends the 23dp as margin (mirroring the
			// row's own end inset) and leaves the icon a full 42dp.
			modifier = Modifier
				.padding(horizontal = 23.dp)
				.size(42.dp),
		)

		Column(modifier = Modifier.weight(1f)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = row.packageName,
					style = MaterialTheme.typography.titleMedium,
					maxLines = 1,
					fontWeight = FontWeight.Medium,
					modifier = Modifier.weight(1f, fill = false),
				)

				if (reason != null) {
					Spacer(modifier = Modifier.width(6.dp))
					Text(
						text = stringResource(R.string.why),
						color = MaterialTheme.colorScheme.onPrimaryContainer,
						fontWeight = FontWeight.Bold,
						fontSize = 12.sp,
						modifier = Modifier
							.clip(RoundedCornerShape(4.dp))
							.background(MaterialTheme.colorScheme.primaryContainer)
							.padding(horizontal = 4.dp, vertical = 2.dp)
							.clickable { showReason = true },
					)
				}
			}

			Text(
				text = descText,
				color = descColor,
				style = MaterialTheme.typography.bodySmall,
				modifier = Modifier.padding(top = 2.dp),
			)

			AnimatedVisibility(visible = activateVisible) {
				TextButton(
					onClick = onActivate,
					modifier = Modifier.padding(top = 6.dp),
				) {
					Text(stringResource(R.string.activate_in_lsposed))
				}
			}
		}

		Box {
			Icon(
				imageVector = Icons.Filled.MoreVert,
				contentDescription = null,
				modifier = Modifier
					.clickable(enabled = row.installed) { menuOpen = true }
					.alpha(0.6f),
			)

			DropdownMenu(
				expanded = menuOpen,
				onDismissRequest = { menuOpen = false },
			) {
				DropdownMenuItem(
					text = { Text(stringResource(R.string.menu_launch_app)) },
					onClick = {
						menuOpen = false
						onLaunch()
					},
				)
				DropdownMenuItem(
					text = { Text(stringResource(R.string.menu_restart_app)) },
					onClick = {
						menuOpen = false
						onRestart()
					},
				)
			}
		}
	}
}

/**
 * Asks LSPosed to add this package to the module's scope.
 *
 * The button is disabled for the round trip and animated away on approval
 * before the reboot action appears, because scope changes only take effect
 * after the target restarts - hence the pending-reboot flag rather than an
 * immediate restart.
 */
private fun activate(
	context: Context,
	service: XposedService?,
	pkg: String,
	onApproved: () -> Unit,
) {
	if (service == null) {
		Toast.makeText(context, R.string.lsposed_not_found, Toast.LENGTH_SHORT).show()
		return
	}

	service.requestScope(
		listOf(pkg),
		object : XposedService.OnScopeEventListener {
			override fun onScopeRequestApproved(approved: List<String>) {
				Toast.makeText(context, R.string.package_activated, Toast.LENGTH_SHORT).show()
				onApproved()
			}

			override fun onScopeRequestFailed(message: String) {
				Toast.makeText(context, R.string.package_activation_failed, Toast.LENGTH_SHORT).show()
			}
		},
	)
}

private fun launch(context: Context, pkg: String) {
	val intent = context.packageManager.getLaunchIntentForPackage(pkg)
	if (intent != null) {
		context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
	} else {
		Toast.makeText(context, R.string.package_not_launchable, Toast.LENGTH_SHORT).show()
	}
}

/**
 * Long-press action: kill and relaunch the package, with the two system
 * packages routed to the restart utilities because `killall` on them would
 * take the device down with it.
 */
private fun restart(context: Context, pkg: String) {
	when (pkg) {
		Constants.SYSTEM_FRAMEWORK_PACKAGE -> Restarter.restartSystem()
		Constants.SYSTEM_UI_PACKAGE -> Restarter.restartSystemUI()
		else -> {
			Shell.cmd("killall $pkg", "am force-stop $pkg").submit()
			val intent = context.packageManager.getLaunchIntentForPackage(pkg)
			if (intent != null) {
				context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
			}
		}
	}
}

private const val WAIT_TICKS = 10
private const val TICK_MS = 500L

/**
 * Rasterises a Drawable so a row can show the target app's own icon.
 *
 * `painterResource` only accepts a resource id, so a Drawable handed over by
 * the PackageManager has to become an ImageBitmap before `Image` can draw
 * it. Degenerate intrinsic sizes are coerced to 1px rather than throwing,
 * because a broken icon must not take the page down.
 */
private fun drawableBitmap(drawable: Drawable): ImageBitmap =
	drawable.toBitmap(
		drawable.intrinsicWidth.coerceAtLeast(1),
		drawable.intrinsicHeight.coerceAtLeast(1),
	).asImageBitmap()
