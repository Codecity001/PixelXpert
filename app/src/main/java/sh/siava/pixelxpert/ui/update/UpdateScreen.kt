package sh.siava.pixelxpert.ui.update

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.gson.JsonParser
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.MainActivity
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.data.PrefStore
import sh.siava.pixelxpert.util.ModuleInstaller
import sh.siava.pixelxpert.util.ModuleVersion
import sh.siava.pixelxpert.util.Restarter
import java.io.File
import java.net.URI
import java.net.URL

/** Release channel manifests, one per channel. */
private const val STABLE_UPDATES_URL =
	"https://raw.githubusercontent.com/Codecity001/PixelXpert/stable/latestStable.json"
private const val CANARY_UPDATES_URL =
	"https://raw.githubusercontent.com/Codecity001/PixelXpert/canary/latestCanary.json"

/**
 * Taps on the installed-version line that open the hidden update-source box.
 *
 * The count is cumulative rather than time-boxed: the line carried no click
 * handler at all before this, so nothing but a deliberate run of taps can
 * reach it, and a window would only make the control harder to hit on a slow
 * device.
 */
private const val SOURCE_TAP_LIMIT: Int = 15

private const val TAG = "UpdateScreen"

/**
 * What the channel manifest advertised.
 *
 * Internal rather than private so the scheduled AutoUpdate worker can run the
 * same check the Update button does, instead of growing a second copy of it.
 */
internal data class UpdateInfo(
	val version: String?,
	val versionCode: Int?,
	val zipUrl: String?,
	val changelog: String?,
)

/**
 * The updates page.
 *
 * It answers three questions in one place: what is installed, what is
 * available, and whether the button between them should install it, reinstall
 * it, switch channel, or simply reboot - the whole of the button state
 * machine, in one screen.
 */
@Composable
fun UpdateScreen(store: PrefStore) {
	val context = LocalContext.current
	val configuration = LocalConfiguration.current
	val scope = rememberCoroutineScope()

	// The layout is picked per orientation, but only for phones: a tablet
	// keeps the stacked layout whatever its orientation, which is what
	// smallestScreenWidthDp >= 600 gates.
	val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
	val twoColumn = isLandscape && configuration.smallestScreenWidthDp < 600

	// Seeded with the app's own version so the header is never blank while
	// the root read is in flight; the seeded value is replaced the moment
	// that read lands.
	var current by remember {
		mutableStateOf(ModuleVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, false))
	}
	var versionLoaded by remember { mutableStateOf(false) }

	// The channel starts from the build type and is re-derived from the
	// module version once that read has landed.
	var canary by remember {
		mutableStateOf(BuildConfig.VERSION_NAME.lowercase().contains("canary"))
	}

	var latest by remember { mutableStateOf<UpdateInfo?>(null) }
	var latestText by remember { mutableStateOf("") }
	var buttonText by remember { mutableStateOf(R.string.update_word) }
	var buttonEnabled by remember { mutableStateOf(false) }
	var changelog by remember { mutableStateOf("") }
	var changelogLoading by remember { mutableStateOf(true) }
	var downloadID by remember { mutableStateOf(-1L) }

	/**
	 * One channel check: ask the manifest, then ask for the changelog it
	 * names.
	 *
	 * The button is decided from the pair: a stable release is always
	 * flashable (so it can repair or downgrade an install), a canary only
	 * when it is genuinely newer, and a staged update overrides both with
	 * "Reboot".
	 */
	fun runCheck() {
		latestText = context.getString(R.string.update_checking)
		buttonEnabled = current.pending
		changelogLoading = true

		scope.launch {
			val info = withContext(Dispatchers.IO) {
				fetchUpdateInfo(canary, store.getString(Constants.KEY_UPDATE_JSON_URL))
			}
			latest = info

			latestText = "${info.version} (${info.versionCode})"
			buttonText = R.string.update_word
			buttonEnabled = false
			try {
				val latestCode = info.versionCode ?: error("manifest has no versionCode")
				when {
					current.pending -> {
						buttonText = R.string.reboot_word
						buttonEnabled = true
					}

					!canary -> {
						// A canary install is identified by a dash in its name
						// ("v1.0-123"); switching back is why a stable release
						// is flashable unconditionally.
						if (current.name.contains("-")) {
							buttonText = R.string.switch_branches
						} else if (latestCode == current.code) {
							buttonText = R.string.reinstall_word
						}
						buttonEnabled = true
					}

					latestCode > current.code -> {
						buttonEnabled = true
						// Writing this key is what makes the Update tab badge
						// itself; the nav bar re-reads it through
						// PrefStore.revision, so no broadcast is needed.
						store.putInt("latestVersionCode", latestCode)
					}
				}
			} catch (ignored: Throwable) {
				Log.e(TAG, "update check failed", ignored)
			}

			changelog = withContext(Dispatchers.IO) { fetchChangelog(info.changelog) }
			changelogLoading = false
		}
	}

	// Read the installed module version through root first, then let the
	// first check run: nothing is fetched until that read has landed, so no
	// request ever went out against an unknown version.
	LaunchedEffect(Unit) {
		// Android 13 needs this or the completion notification is dropped.
		// The grant is asked for through root rather than as a dialog, which
		// also doubles as the app's root request on a fresh install.
		val read = withContext(Dispatchers.IO) {
			runCatching {
				Shell
					.cmd("pm grant ${BuildConfig.APPLICATION_ID} android.permission.POST_NOTIFICATIONS")
					.exec()
			}
			if (ModuleInstaller.hasRoot()) {
				ModuleInstaller.currentVersion()
			} else {
				ModuleVersion(context.getString(R.string.root_not_here), 9999, false)
			}
		}
		current = read
		canary = read.name.lowercase().contains("canary")
		versionLoaded = true
	}

	LaunchedEffect(canary, versionLoaded) {
		if (versionLoaded) runCheck()
	}

	// Completion of a download queued by this screen. One-shot: it
	// unregisters itself the moment it fires.
	DisposableEffect(Unit) {
		val downloadManager = context.getSystemService(DownloadManager::class.java)
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(ctx: Context, intent: Intent) {
				runCatching { context.unregisterReceiver(this) }

				var successful = false
				if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == intent.action &&
					intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == downloadID
				) {
					try {
						downloadManager
							.query(DownloadManager.Query().setFilterById(downloadID))
							?.use { cursor ->
								if (cursor.moveToFirst()) {
									val uriColumn =
										cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
									val file = File(URI.create(cursor.getString(uriColumn)))
									if (file.exists()) {
										notifyInstall(ctx, file.absolutePath)
										successful = true
									}
								}
							}
					} catch (ignored: Throwable) {
						Log.e(TAG, "resolving download failed", ignored)
					}
				}

				if (!successful) notifyDownloadFailed(ctx)
			}
		}
		ContextCompat.registerReceiver(
			context,
			receiver,
			IntentFilter().apply {
				addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
				addAction(DownloadManager.ACTION_NOTIFICATION_CLICKED)
			},
			ContextCompat.RECEIVER_EXPORTED,
		)
		onDispose { runCatching { context.unregisterReceiver(receiver) } }
	}

	// The single action. It reads the same result the label came from, so a
	// check that has completed since the label changed cannot start a
	// download of something no longer current.
	val onAction: () -> Unit = {
		if (current.pending) {
			Restarter.restartSystem()
		} else {
			latest?.let { info ->
				val zipUrl = info.zipUrl
				val versionCode = info.versionCode
				if (zipUrl != null && versionCode != null) {
					runCatching {
						downloadID = startDownload(context, zipUrl, versionCode)
					}
					buttonText = R.string.update_download_started
					buttonEnabled = false
				}
			}
		}
	}

	// ---- page -------------------------------------------------------------

	val onSurface = MaterialTheme.colorScheme.onSurface
	val versionLine =
		"${current.name} (${current.code})" +
			if (current.pending) " - ${stringResource(R.string.reboot_pending)}" else ""

	Column(
		modifier = Modifier
			.fillMaxSize()
			.then(
				if (twoColumn) {
					Modifier.padding(24.dp)
				} else {
					Modifier.padding(24.dp).verticalScroll(rememberScrollState())
				},
			),
	) {
		if (twoColumn) {
			Row(Modifier.fillMaxWidth().weight(1f)) {
				Column(
					modifier = Modifier
						.weight(1f)
						.fillMaxHeight()
						.padding(end = 24.dp)
						.verticalScroll(rememberScrollState()),
				) {
					VersionAndControls(
						store = store,
						versionLine = versionLine,
						onSurface = onSurface,
						canary = canary,
						onChannelChange = { canary = it },
						latestText = latestText,
						buttonText = buttonText,
						buttonEnabled = buttonEnabled,
						onAction = onAction,
						onSourceSaved = { runCheck() },
					)
				}
				ChangelogCard(
					modifier = Modifier.weight(1f).fillMaxHeight(),
					fillHeight = true,
					loading = changelogLoading,
					markdown = changelog,
				)
			}
		} else {
			VersionAndControls(
				store = store,
				versionLine = versionLine,
				onSurface = onSurface,
				canary = canary,
				onChannelChange = { canary = it },
				latestText = latestText,
				buttonText = buttonText,
				buttonEnabled = buttonEnabled,
				onAction = onAction,
				onSourceSaved = { runCheck() },
			)
			ChangelogCard(
				modifier = Modifier.fillMaxWidth(),
				fillHeight = false,
				loading = changelogLoading,
				markdown = changelog,
			)
		}
	}
}

/**
 * Installed version, channel picker, latest version and the one action
 * button whose label changes.
 */
@Composable
private fun VersionAndControls(
	store: PrefStore,
	versionLine: String,
	onSurface: Color,
	canary: Boolean,
	onChannelChange: (Boolean) -> Unit,
	latestText: String,
	buttonText: Int,
	buttonEnabled: Boolean,
	onAction: () -> Unit,
	onSourceSaved: () -> Unit,
) {
	// Taps are counted in composition-local state, so the tally is discarded
	// whenever the screen leaves composition and never survives a tab switch.
	var taps by remember { mutableStateOf(0) }
	var sourceOpen by remember { mutableStateOf(false) }

	Row(verticalAlignment = Alignment.Top) {
		Text(
			text = stringResource(R.string.currentVersionTitle),
			color = onSurface,
			fontWeight = FontWeight.Bold,
		)
		Text(
			text = versionLine,
			color = onSurface,
			fontWeight = FontWeight.Bold,
			modifier = Modifier
				.padding(start = 8.dp)
				.clickable(
					// Neither a ripple nor a countdown: the control is meant
					// to read as plain text right up until it opens.
					interactionSource = remember { MutableInteractionSource() },
					indication = null,
				) {
					taps += 1
					if (taps >= SOURCE_TAP_LIMIT) {
						taps = 0
						sourceOpen = true
					}
				},
		)
	}

	Text(
		text = stringResource(R.string.update_flavor_title),
		color = onSurface,
		fontWeight = FontWeight.Bold,
		modifier = Modifier.padding(top = 16.dp),
	)

	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
	) {
		ChannelOption(
			label = stringResource(R.string.stable_flavor),
			selected = !canary,
			modifier = Modifier.weight(1f),
		) { onChannelChange(false) }
		ChannelOption(
			label = stringResource(R.string.canary_flavor),
			selected = canary,
			modifier = Modifier.weight(1f),
		) { onChannelChange(true) }
	}

	Row(
		modifier = Modifier.padding(top = 8.dp),
		verticalAlignment = Alignment.Top,
	) {
		Text(
			text = stringResource(R.string.lastestVersionTitle),
			color = onSurface,
			fontWeight = FontWeight.Bold,
		)
		Text(
			text = latestText,
			color = onSurface,
			fontWeight = FontWeight.Bold,
			modifier = Modifier.padding(start = 8.dp),
		)
	}

	Box(
		modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
		contentAlignment = Alignment.Center,
	) {
		Button(onClick = onAction, enabled = buttonEnabled) {
			Text(stringResource(buttonText))
		}
	}

	if (sourceOpen) {
		UpdateSourceDialog(
			store = store,
			onDismiss = { sourceOpen = false },
			onSaved = onSourceSaved,
		)
	}
}

/**
 * The text box behind the fifteen taps.
 *
 * OK stores the trimmed URL - or removes the key entirely when the field is
 * left empty, which is how the override is switched back off - and then
 * re-runs the check so the effect is visible immediately instead of waiting
 * for the next time the page loads. Cancel and a tap outside discard the
 * draft without touching what is stored.
 */
@Composable
private fun UpdateSourceDialog(store: PrefStore, onDismiss: () -> Unit, onSaved: () -> Unit) {
	var draft by remember {
		mutableStateOf(store.getString(Constants.KEY_UPDATE_JSON_URL).orEmpty())
	}

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.update_source_title)) },
		text = {
			OutlinedTextField(
				value = draft,
				onValueChange = { draft = it },
				singleLine = true,
				label = { Text(stringResource(R.string.update_source_hint)) },
				modifier = Modifier.fillMaxWidth(),
			)
		},
		confirmButton = {
			TextButton(
				onClick = {
					// A null value is a removal in SharedPreferences, so an
					// emptied field restores the shipped endpoint rather than
					// storing an empty string that would never match a host.
					store.putString(
						Constants.KEY_UPDATE_JSON_URL,
						draft.trim().ifEmpty { null },
					)
					onDismiss()
					onSaved()
				},
			) {
				Text(stringResource(android.R.string.ok))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(android.R.string.cancel))
			}
		},
	)
}

/** One half of the channel picker: a RadioButton with its label set beside it. */
@Composable
private fun ChannelOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
	// The label sits outside the RadioButton widget itself, so the row takes
	// the click as well: tapping the text must select the channel too, not
	// sit there as a dead label.
	Row(
		modifier = modifier.clickable(onClick = onClick),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(selected = selected, onClick = onClick)
		Text(label, color = MaterialTheme.colorScheme.onSurface)
	}
}

/**
 * The changelog card.
 *
 * In portrait the caller wraps this card in a scrolling column, so the body
 * simply flows and the page scrolls it. [fillHeight] marks the two-column
 * layout, where the page does not scroll: the card takes the height left over
 * and the body scrolls inside it. One of the two is a scroll container at any
 * time, never both, which is what keeps a drag from being split between them.
 */
@Composable
private fun ChangelogCard(modifier: Modifier, fillHeight: Boolean, loading: Boolean, markdown: String) {
	Card(
		modifier = modifier.heightIn(min = 400.dp).padding(top = 16.dp),
		shape = RoundedCornerShape(dimensionResource(R.dimen.ui_container_radius)),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
		),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
				.background(colorResource(R.color.changelog_bg)),
		) {
			Text(
				text = stringResource(R.string.changelog_title),
				color = MaterialTheme.colorScheme.onSurface,
				fontSize = 16.sp,
				fontWeight = FontWeight.Bold,
				textAlign = TextAlign.Center,
				modifier = Modifier
					.fillMaxWidth()
					.background(MaterialTheme.colorScheme.surfaceBright)
					.padding(12.dp),
			)

			if (loading) {
				LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
			}

			MarkdownText(
				markdown = markdown,
				modifier = Modifier
					.fillMaxWidth()
					.then(
						if (fillHeight) {
							Modifier.weight(1f).verticalScroll(rememberScrollState())
						} else {
							Modifier
						},
					)
					.padding(
						start = dimensionResource(R.dimen.ui_container_margin_side),
						end = dimensionResource(R.dimen.ui_container_margin_side),
						top = 8.dp,
						bottom = 12.dp,
					),
			)
		}
	}
}

// --------------------------------------------------------------- network

/**
 * Reads the manifest for [canary], falling back to a canned error payload so
 * the caller parses both outcomes identically.
 *
 * [customUrl] is the endpoint entered through the hidden field on the
 * Updates page: anything non-blank in it replaces the channel's own URL for
 * this check, and blank (or null) means the shipped one is used.
 */
internal suspend fun fetchUpdateInfo(canary: Boolean, customUrl: String? = null): UpdateInfo =
	withContext(Dispatchers.IO) {
		Thread.sleep(200)
		val endpoints = if (!customUrl.isNullOrBlank()) {
			listOf(customUrl.trim())
		} else if (canary) {
			listOf(CANARY_UPDATES_URL)
		} else {
			listOf(
				STABLE_UPDATES_URL,
				"https://raw.githubusercontent.com/Codecity001/PixelXpert/main/latestStable.json",
				"https://raw.githubusercontent.com/Codecity001/PixelXpert/master/latestStable.json",
			)
		}
		var lastError: Throwable? = null
		for (endpoint in endpoints) {
			try {
				val body = URL(endpoint).openStream().use { it.reader().readText() }
				val json = JsonParser.parseString(body).asJsonObject
				fun text(key: String) = json.get(key)?.takeIf { !it.isJsonNull }?.asString

				return@withContext UpdateInfo(
					version = text("version"),
					versionCode = json.get("versionCode")?.takeIf { !it.isJsonNull }?.asInt,
					zipUrl = text("zipUrl"),
					changelog = text("changelog"),
				)
			} catch (t: Throwable) {
				lastError = t
			}
		}
		Log.e(TAG, "update check failed for all endpoints", lastError)
		UpdateInfo("Connection Error", -1, null, null)
	}

/** Downloads the changelog markdown, or the hardcoded failure notice. */
private suspend fun fetchChangelog(url: String?): String = withContext(Dispatchers.IO) {
	if (url.isNullOrBlank()) return@withContext "Error loading changelog."
	val candidates = listOfNotNull(
		url,
		if (url.contains("/main/")) url.replace("/main/", "/stable/") else null,
		if (url.contains("/stable/")) url.replace("/stable/", "/main/") else null,
	).distinct()
	for (target in candidates) {
		try {
			return@withContext URL(target).openStream().use { it.reader().readText() }
		} catch (ignored: Throwable) {
		}
	}
	Log.e(TAG, "Error fetching changelog from $url")
	"Error loading changelog."
}

// -------------------------------------------------------------- download

/** Queues the module zip and returns its DownloadManager id. */
private fun startDownload(context: Context, zipUrl: String, versionCode: Int): Long {
	val downloadManager = context.getSystemService(DownloadManager::class.java)
	return downloadManager.enqueue(
		DownloadManager.Request(Uri.parse(zipUrl))
			.setTitle("PixelXpert Update Package")
			.setDestinationInExternalPublicDir(
				Environment.DIRECTORY_DOWNLOADS,
				"PixelXpert-$versionCode.zip",
			)
			.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE),
	)
}

// ---------------------------------------------------------- notifications

/** "Tap here to install": reopens this app with the path to install. */
private fun notifyInstall(context: Context, filePath: String) {
	val notificationIntent = Intent(context, MainActivity::class.java).apply {
		action = Intent.ACTION_RUN
		addCategory(Intent.CATEGORY_DEFAULT)
		putExtra("updateTapped", true)
		putExtra("filePath", filePath)
	}
	val pendingIntent = PendingIntent.getActivity(
		context,
		0,
		notificationIntent,
		PendingIntent.FLAG_IMMUTABLE,
	)
	try {
		val notification = NotificationCompat.Builder(context, Constants.UPDATES_CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification_foreground)
			.setContentTitle(context.getString(R.string.update_notification_title))
			.setContentText(context.getString(R.string.update_notification_text))
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setContentIntent(pendingIntent)
			.setAutoCancel(true)
			.build()
		NotificationManagerCompat.from(context)
			.notify(Constants.UPDATE_DOWNLOADED_ID, notification)
	} catch (ignored: SecurityException) {
		// POST_NOTIFICATIONS can still be denied; the download is unaffected,
		// the user simply has to reopen the app to install.
		Log.w(TAG, "could not post install notification", ignored)
	}
}

private fun notifyDownloadFailed(context: Context) {
	try {
		val notification = NotificationCompat.Builder(context, Constants.UPDATES_CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification_foreground)
			.setContentTitle(context.getString(R.string.download_failed))
			.setContentText(context.getString(R.string.try_again_later))
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.build()
		NotificationManagerCompat.from(context)
			.notify(Constants.UPDATE_DOWNLOAD_FAILED_ID, notification)
	} catch (ignored: SecurityException) {
		Log.w(TAG, "could not post failure notification", ignored)
	}
}
