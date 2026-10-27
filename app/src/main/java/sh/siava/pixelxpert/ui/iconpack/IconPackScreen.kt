package sh.siava.pixelxpert.ui.iconpack

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.openExternalLink
import sh.siava.pixelxpert.util.IconPackUtil

/** Where the disclaimer points pack authors. */
private const val PX_ICON_PACK_REPO = "https://github.com/siavash79/PXIconPackTemplate"

/**
 * The icon pack screen: pick a pack, then fine-tune it per icon.
 *
 * Everything the screen changes lands in [IconPackUtil], which flattens it into
 * the JSON under `drawableMapping`. Nothing here substitutes a drawable: the
 * Xposed side is what reads that map and swaps icons, so this screen's job is
 * only to decide and record what should be swapped.
 *
 * [IconPackUtil] mutates its mappings in place and notifies from its own
 * executor thread, so `revision` is bumped after every mutation to make the rows
 * re-read state Compose cannot observe on its own.
 */
@Composable
fun IconPackScreen() {
	val context = LocalContext.current
	val util = remember { IconPackUtil.getInstance(context) }
	// The util calls back from a background pool, so Compose state is only
	// touched once the hop to the main looper has run.
	val handler = remember { Handler(Looper.getMainLooper()) }

	var loading by remember { mutableStateOf(true) }
	// Bumped whenever the mapping changes, so every derived value below
	// recomputes.
	var revision by remember { mutableIntStateOf(0) }
	var selectedTab by remember { mutableIntStateOf(0) }
	var query by remember { mutableStateOf("") }
	var showDisclaimer by remember { mutableStateOf(false) }

	DisposableEffect(util) {
		val listener = object : IconPackUtil.IconPackQueryListener {
			override fun onIconPacksLoaded(mapping: IconPackUtil.ResourceMapping, packMapping: IconPackUtil.IconPackMapping) {
				handler.post {
					loading = false
					revision++
				}
			}
		}
		util.addListener(listener)
		// onResume's force-refresh: the list can change while the screen is
		// behind another one.
		util.queryIconPacks(force = true)
		onDispose { util.removeListener(listener) }
	}

	val packs = remember(revision) {
		util.iconPackMapping.getIconPacks().sortedWith(
			compareBy({ it.mName }, { it.mPackageName }),
		)
	}
	val anythingEnabled = remember(revision) { util.isAnythingEnabled() }

	fun togglePack(pack: IconPackUtil.IconPack) {
		when (util.getEnabledState(pack)) {
			// Either way round, tapping leaves the pack off: FULL and PARTIAL
			// both disable, and only a disabled pack is ever switched on.
			IconPackUtil.ENABLED_FULL, IconPackUtil.ENABLED_PARTIAL -> util.disable(pack)

			else -> util.enable(pack)
		}
		revision++
	}

	fun resetIcons() {
		util.iconPackMapping.getIconPacks().forEach { util.disable(it) }
		util.queryIconPacks(force = false)
		revision++
	}

	Box(modifier = Modifier.fillMaxSize()) {
		Column(modifier = Modifier.fillMaxSize()) {
			ToolbarRow(
				query = query,
				onQueryChange = { query = it },
				onInfo = { showDisclaimer = true },
			)

			when {
				loading -> PanePlaceholder(modifier = Modifier.weight(1f)) {
					// The M3 LoadingIndicator widget has no Compose equivalent
					// on the material3 line this app is pinned to, so a circular
					// progress indicator at the same size carries the same
					// reading.
					CircularProgressIndicator(modifier = Modifier.size(48.dp))
				}

				packs.isEmpty() -> PanePlaceholder(modifier = Modifier.weight(1f)) {
					Text(text = stringResource(R.string.shrug), fontSize = 42.sp)
					Spacer(modifier = Modifier.height(4.dp))
					Text(
						text = stringResource(R.string.no_icon_pack_installed),
						fontSize = 16.sp,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}

				else -> {
					IconPackTabs(selectedTab = selectedTab, onSelect = { selectedTab = it })
					if (selectedTab == 0) {
						PackList(
							modifier = Modifier.weight(1f),
							packs = packs,
							query = query,
							util = util,
							revision = revision,
							onToggle = ::togglePack,
						)
					} else {
						CustomizationList(
							modifier = Modifier.weight(1f),
							util = util,
							query = query,
							revision = revision,
							onChanged = { revision++ },
						)
					}
				}
			}
		}

		if (anythingEnabled) {
			ExtendedFloatingActionButton(
				onClick = ::resetIcons,
				icon = {
					Icon(painter = painterResource(R.drawable.ic_reset), contentDescription = null)
				},
				text = { Text(stringResource(R.string.icon_pack_pack_reset)) },
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(16.dp),
			)
		}
	}

	if (showDisclaimer) DisclaimerDialog(onDismiss = { showDisclaimer = false })
}

/**
 * Search and "Icon pack info" sit in a row at the head of the screen, because
 * the nav bar owns the toolbar slot - still above the tabs, still always
 * visible.
 */
@Composable
private fun ToolbarRow(query: String, onQueryChange: (String) -> Unit, onInfo: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		OutlinedTextField(
			value = query,
			onValueChange = onQueryChange,
			modifier = Modifier.weight(1f),
			singleLine = true,
			leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
			placeholder = { Text(stringResource(R.string.searchpreference_title)) },
			textStyle = MaterialTheme.typography.bodyLarge,
		)
		IconButton(onClick = onInfo) {
			Icon(
				imageVector = Icons.Default.Info,
				contentDescription = stringResource(R.string.icon_pack_info),
			)
		}
	}
}

@Composable
private fun IconPackTabs(selectedTab: Int, onSelect: (Int) -> Unit) {
	// Inset by the container margin, so the tab strip never runs edge to edge.
	PrimaryTabRow(
		selectedTabIndex = selectedTab,
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
	) {
		Tab(
			selected = selectedTab == 0,
			onClick = { onSelect(0) },
			text = { Text(stringResource(R.string.icon_pack_selection_title)) },
		)
		Tab(
			selected = selectedTab == 1,
			onClick = { onSelect(1) },
			text = { Text(stringResource(R.string.icon_pack_customization_title)) },
		)
	}
}

/**
 * Both the loading indicator and the empty state float well above the bottom
 * edge. The weight comes from the caller, because this has to sit where the
 * other panes sit in the screen's column.
 */
@Composable
private fun PanePlaceholder(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
	Box(
		modifier = modifier.fillMaxWidth(),
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier.padding(bottom = 160.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) { content() }
	}
}

@Composable
private fun PackList(
	modifier: Modifier = Modifier,
	packs: List<IconPackUtil.IconPack>,
	query: String,
	util: IconPackUtil,
	revision: Int,
	onToggle: (IconPackUtil.IconPack) -> Unit,
) {
	val needle = query.lowercase()
	val visible = packs.filter {
		it.mName.lowercase().contains(needle) || it.mAuthor.lowercase().contains(needle)
	}

	LazyColumn(modifier = modifier.fillMaxWidth()) {
		items(visible, key = { it.mPackageName }) { pack ->
			PackRow(
				pack = pack,
				util = util,
				revision = revision,
				onToggle = { onToggle(pack) },
			)
		}
	}
}

@Composable
private fun PackRow(pack: IconPackUtil.IconPack, util: IconPackUtil, revision: Int, onToggle: () -> Unit) {
	val context = LocalContext.current
	var menuOpen by remember { mutableStateOf(false) }
	var showIcons by remember { mutableStateOf(false) }

	// Read on every recomposition rather than remembered: the util mutates its
	// mapping in place, so the value is only as fresh as `revision`.
	val enabledState = util.getEnabledState(pack)
	val nightMode =
		(LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
			Configuration.UI_MODE_NIGHT_YES

	val containerColor = when (enabledState) {
		IconPackUtil.ENABLED_FULL -> MaterialTheme.colorScheme.primary

		IconPackUtil.ENABLED_PARTIAL -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)

		// In dark mode the card takes colorSurfaceBright so it still separates
		// from the page behind it.
		else -> if (nightMode) {
			MaterialTheme.colorScheme.surfaceBright
		} else {
			MaterialTheme.colorScheme.surface
		}
	}

	Box {
		Card(
			colors = CardDefaults.cardColors(containerColor = containerColor),
			shape = RoundedCornerShape(20.dp),
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 2.dp)
				.pointerInput(pack) {
					detectTapGestures(
						onTap = { onToggle() },
						onLongPress = { menuOpen = true },
					)
				},
		) {
			Column(modifier = Modifier.padding(horizontal = 23.dp, vertical = 18.dp)) {
				PreviewStrip(pack = pack, revision = revision)

				val name = if (pack.mName.isEmpty()) {
					stringResource(R.string.icon_pack_unknown_name)
				} else {
					pack.mName
				}
				val author = if (pack.mAuthor.isEmpty()) {
					stringResource(R.string.icon_pack_unknown_author)
				} else {
					stringResource(R.string.icon_pack_author, pack.mAuthor)
				}

				Text(
					text = packName(pack, name),
					style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)

				Spacer(modifier = Modifier.height(2.dp))
				Text(
					text = author,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.alpha(0.7f),
				)
			}
		}

		DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
			DropdownMenuItem(
				text = { Text(stringResource(R.string.menu_more_info)) },
				onClick = {
					menuOpen = false
					context.startActivity(
						Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
							data = Uri.fromParts("package", pack.mPackageName, null)
						},
					)
				},
			)
			DropdownMenuItem(
				text = { Text(stringResource(R.string.menu_show_icons)) },
				onClick = {
					menuOpen = false
					showIcons = true
				},
			)
		}
	}

	if (showIcons) PackIconsDialog(pack = pack, util = util, onDismiss = { showIcons = false })
}

@Composable
private fun packName(pack: IconPackUtil.IconPack, name: String): androidx.compose.ui.text.AnnotatedString {
	val enabledState = IconPackUtil.getInstance(LocalContext.current).getEnabledState(pack)
	if (enabledState != IconPackUtil.ENABLED_PARTIAL) {
		return buildAnnotatedString { append(name) }
	}

	val hint = stringResource(R.string.partially_enabled)
	val full = "$name $hint"
	val at = full.indexOf(hint)
	return buildAnnotatedString {
		append(full)
		if (at >= 0) {
			addStyle(SpanStyle(color = MaterialTheme.colorScheme.primary), at, at + hint.length)
		}
	}
}

@Composable
private fun PreviewStrip(pack: IconPackUtil.IconPack, revision: Int) {
	val context = LocalContext.current
	// Keyed on the pack and the revision: the previews are cached on the pack
	// itself, so this only re-runs when the mapping actually changed.
	val previews = remember(pack, revision) {
		pack.getPackDrawables(context).mapNotNull { it?.toImageBitmapOrNull() }
	}
	if (previews.isEmpty()) return

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(bottom = 16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		previews.chunked(4).forEach { row ->
			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				modifier = Modifier.padding(vertical = 4.dp),
			) {
				row.forEach { bitmap ->
					Image(
						bitmap = bitmap,
						contentDescription = null,
						modifier = Modifier.size(24.dp),
						colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
					)
				}
			}
		}
	}
}

@Composable
private fun CustomizationList(
	modifier: Modifier = Modifier,
	util: IconPackUtil,
	query: String,
	revision: Int,
	onChanged: () -> Unit,
) {
	val all = remember(revision) { util.resourceMapping.getOriginalResList().sorted() }
	val needle = query.lowercase()
	val visible = all.filter { it.lowercase().contains(needle) }

	LazyColumn(modifier = modifier.fillMaxWidth()) {
		items(visible, key = { it }) { resName ->
			CustomizationRow(
				resName = resName,
				util = util,
				revision = revision,
				onChanged = onChanged,
			)
		}
	}
}

@Composable
private fun CustomizationRow(resName: String, util: IconPackUtil, revision: Int, onChanged: () -> Unit) {
	val enabledIcon = remember(resName, revision) { util.getEnabled(resName) }
	val isEnabled = enabledIcon != null
	val bitmap = remember(enabledIcon) { enabledIcon?.getDrawable()?.toImageBitmapOrNull() }
	var pickerOpen by remember { mutableStateOf(false) }

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.pointerInput(resName) {
				detectTapGestures(onTap = { pickerOpen = true })
			}
			.padding(top = 8.dp, bottom = 8.dp, end = 23.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier
				.padding(horizontal = 23.dp)
				.size(24.dp),
			contentAlignment = Alignment.Center,
		) {
			if (bitmap != null) {
				Image(
					bitmap = bitmap,
					contentDescription = null,
					modifier = Modifier
						.size(24.dp)
						.alpha(if (isEnabled) 1f else 0.5f),
				)
			} else {
				Icon(
					painter = painterResource(R.drawable.ic_icon_disabled),
					contentDescription = null,
					modifier = Modifier
						.width(16.dp)
						.alpha(if (isEnabled) 1f else 0.5f),
					tint = MaterialTheme.colorScheme.onSurface,
				)
			}
		}

		Column(modifier = Modifier.weight(1f)) {
			Text(
				// Packs name resources as "type/name"; the bare name is the part
				// worth reading.
				text = resName.substringAfter(':', resName),
				style = MaterialTheme.typography.bodyLarge,
				modifier = Modifier.alpha(if (isEnabled) 1f else 0.5f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = enabledIcon?.iconPack?.mName
					?: stringResource(R.string.icon_pack_icon_disabled),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.alpha(if (isEnabled) 0.7f else 0.4f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}

		if (isEnabled) {
			IconButton(onClick = {
				util.disable(resName)
				onChanged()
			}) {
				Icon(
					painter = painterResource(R.drawable.ic_reset),
					contentDescription = stringResource(R.string.icon_pack_pack_reset),
				)
			}
		}
	}

	if (pickerOpen) {
		ReplacementPickerDialog(
			resName = resName,
			util = util,
			onDismiss = { pickerOpen = false },
			onPicked = onChanged,
		)
	}
}

/**
 * "Select replacement icon": every pack's offer for this one resource.
 *
 * The choice is cleared first and then the picked option is enabled, so the
 * picker is what decides - never an accumulation.
 */
@Composable
private fun ReplacementPickerDialog(resName: String, util: IconPackUtil, onDismiss: () -> Unit, onPicked: () -> Unit) {
	val options = remember(resName) {
		util.resourceMapping.getReplacementIcons(resName).orEmpty()
	}

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.select_replacement_icon)) },
		text = {
			LazyColumn {
				items(options) { option ->
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.pointerInput(option) {
								detectTapGestures {
									util.disable(resName)
									val picked = util.resourceMapping
										.getReplacementIcons(resName)
										.orEmpty()
										.firstOrNull {
											it.mReplacementRes == option.mReplacementRes
										}
									picked?.let { util.setEnabled(resName, it) }
									onPicked()
									onDismiss()
								}
							}
							.padding(vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						val thumbnail = remember(option) {
							option.getDrawable()?.toImageBitmapOrNull()
						}
						if (thumbnail != null) {
							Image(
								bitmap = thumbnail,
								contentDescription = null,
								modifier = Modifier.size(24.dp),
							)
						} else {
							Box(modifier = Modifier.size(24.dp))
						}
						Spacer(modifier = Modifier.width(16.dp))
						Text(
							text = option.iconPack.mName,
							style = MaterialTheme.typography.bodyLarge,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.okay)) }
		},
	)
}

/**
 * "Show icons": every drawable the pack offers, in a four column grid.
 *
 * Names are collected but not rendered - they are debugging only.
 */
@Composable
private fun PackIconsDialog(pack: IconPackUtil.IconPack, util: IconPackUtil, onDismiss: () -> Unit) {
	val icons = remember(pack) {
		util.iconPackMapping[pack]
			.orEmpty()
			.values
			.flatten()
			.mapNotNull { icon ->
				icon.getDrawable()?.toImageBitmapOrNull()
					?.let { icon.mReplacementRes to it }
			}
			.sortedBy { it.first }
	}

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(pack.mName.ifEmpty { stringResource(R.string.icon_pack_unknown_name) }) },
		text = {
			LazyVerticalGrid(
				columns = GridCells.Fixed(4),
				contentPadding = PaddingValues(8.dp),
			) {
				items(icons, key = { it.first }) { (_, bitmap) ->
					Image(
						bitmap = bitmap,
						contentDescription = null,
						modifier = Modifier
							.size(48.dp)
							.padding(8.dp),
					)
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.okay)) }
		},
	)
}

@Composable
private fun DisclaimerDialog(onDismiss: () -> Unit) {
	val context = LocalContext.current
	val message = stringResource(R.string.icon_pack_disclaimer_desc, PX_ICON_PACK_REPO)

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.icon_pack_disclaimer_title)) },
		text = {
			val body = buildAnnotatedString {
				append(message)
				val at = message.indexOf(PX_ICON_PACK_REPO)
				if (at >= 0) {
					addLink(
						LinkAnnotation.Url(
							url = PX_ICON_PACK_REPO,
							linkInteractionListener = LinkInteractionListener {
								openExternalLink(context, PX_ICON_PACK_REPO)
							},
						),
						at,
						at + PX_ICON_PACK_REPO.length,
					)
				}
			}
			Text(body, style = MaterialTheme.typography.bodyMedium)
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.okay)) }
		},
	)
}

private fun Drawable.toImageBitmapOrNull(): ImageBitmap? = runCatching {
	val width = if (bounds.isEmpty) intrinsicWidth else bounds.width()
	val height = if (bounds.isEmpty) intrinsicHeight else bounds.height()
	if (width <= 0 || height <= 0) return null
	toBitmap(width, height).asImageBitmap()
}.getOrNull()
