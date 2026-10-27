package sh.siava.pixelxpert.ui.prefs

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.google.android.material.color.utilities.Hct
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.data.PrefStore
import sh.siava.pixelxpert.ui.prefs.node.ActionRow
import sh.siava.pixelxpert.ui.prefs.node.Category
import sh.siava.pixelxpert.ui.prefs.node.ColorPref
import sh.siava.pixelxpert.ui.prefs.node.Illustration
import sh.siava.pixelxpert.ui.prefs.node.ListPref
import sh.siava.pixelxpert.ui.prefs.node.MainTile
import sh.siava.pixelxpert.ui.prefs.node.NavRow
import sh.siava.pixelxpert.ui.prefs.node.PrefNode
import sh.siava.pixelxpert.ui.prefs.node.SearchBar
import sh.siava.pixelxpert.ui.prefs.node.SliderPref
import sh.siava.pixelxpert.ui.prefs.node.SwitchPref
import sh.siava.pixelxpert.ui.prefs.node.TextPref
import sh.siava.pixelxpert.ui.prefs.node.TimePref
import sh.siava.pixelxpert.ui.prefs.node.ValuePref
import sh.siava.pixelxpert.util.Restarter
import sh.siava.pixelxpert.util.TimeSyncScheduler
import sh.siava.pixelxpert.util.UpdateScheduler
import kotlin.math.roundToInt

/**
 * Screens are pure data, and every behaviour exists exactly once here: the
 * payoff of `settings_model = declarative_model_renderer` is that porting a
 * new screen costs a generated data file, never a new composable.
 *
 * @param onNavigate receives the route id plus the @StringRes title of
 *        the row that was tapped; that title becomes the destination heading.
 * @param onSearch invoked by the screen's `SearchPreference` widget; the host
 *        opens the search surface.
 */
@Composable
fun PrefScreenContent(
	screen: PrefScreen,
	store: PrefStore,
	onNavigate: (route: String, title: Int) -> Unit,
	onSearch: () -> Unit = {},
	modifier: Modifier = Modifier,
) {
	// Re-reads every value whenever any preference is written.
	val revision by store.revision.collectAsState()
	@Suppress("UNUSED_VARIABLE") val ignored = revision

	val flat = remember(screen) { screen.flatten() }

	// Visibility is store state, so unlike the flatten above it is deliberately
	// not remembered: any write bumps revision, which recomposes this body and
	// re-runs the filter for the whole screen. That is what makes a switch
	// hiding its own sub-rows take effect without the screen being reopened.
	val rows = flat.filter { visibleBy(store, it.node.nodeKey) }

	// Tiles that sit beside each other share a bubble
	val blocks = rows.toBlocks()

	// Whether the home screen uses Google design.
	val googleDesign = store.getBoolean("GoogleSettingsDesign", false)

	LazyColumn(
		modifier = modifier.fillMaxSize(),
		contentPadding = PaddingValues(bottom = 32.dp),
	) {
		// Deliberately no key lambda. Several generated <category> nodes carry no
		// android:title, so any title-derived key collides ("c:0" was already
		// used). The list is positional so rows appear and disappear correctly
		// when a dependency flips.
		items(blocks) { block ->
			when (block) {
				is PrefBlock.Row -> PrefNodeRow(
					node = block.item.node,
					store = store,
					onNavigate = onNavigate,
					onSearch = onSearch,
					googleDesign = googleDesign,
				)

				is PrefBlock.Tiles -> TileBubble(
					tiles = block.tiles,
					accent = block.accent,
					googleDesign = googleDesign,
					onNavigate = onNavigate,
				)
			}
		}
	}
}

// One entry of the rendered list (either a single node, or the run of tiles)
private sealed interface PrefBlock {
	data class Row(val item: FlattenedNode) : PrefBlock
	data class Tiles(val tiles: List<MainTile>, val accent: Long) : PrefBlock
}

// Each run of consecutive [MainTile]s into a [PrefBlock.Tiles].
private fun List<FlattenedNode>.toBlocks(): List<PrefBlock> {
	val blocks = ArrayList<PrefBlock>(size)
	// A tile's category carries the same accent
	var accent = 0L
	var i = 0
	while (i < size) {
		val node = this[i].node
		if (node is Category) accent = node.accent
		if (node !is MainTile) {
			blocks += PrefBlock.Row(this[i])
			i++
			continue
		}
		val tiles = ArrayList<MainTile>()
		while (i < size && this[i].node is MainTile) {
			tiles += this[i].node as MainTile
			i++
		}
		blocks += PrefBlock.Tiles(tiles, accent)
	}
	return blocks
}

/**
 * The quick settings pull-down reach.
 *
 * A 25dp strip: its width is the share of the screen a swipe has to cover
 * (`QSPulldownPercent`) and it clings to the edge the gesture starts from
 * (`QSPulldownSide`), so the setting reads as a shape rather than as a bare
 * number. It appears only while `QSPullodwnEnabled` holds - the same condition
 * `visibleBy` applies to the rows beneath it - and only over the quick
 * settings screen.
 *
 * The strip sits over the top bar rather than over the list: it is a mark on
 * the window's edge, not a row of the settings screen, and overlaying the
 * first row would hide content.
 */
@Composable
fun BoxScope.PullDownIndicator(store: PrefStore) {
	// Live across a write, like every other read on this renderer.
	val revision by store.revision.collectAsState()
	@Suppress("UNUSED_VARIABLE") val ignored = revision

	if (!store.getBoolean("QSPullodwnEnabled", false)) return

	// The percent is applied to the window's width in dp rather than in
	// pixels: the share of the display is the same either way, and screen
	// width in dp survives every display the app runs on.
	val percent = store.getSliderInt("QSPulldownPercent", 25).coerceIn(0, 100)
	val screenWidth = LocalConfiguration.current.screenWidthDp

	// Side "1" hangs right, anything else left.
	val hangsRight = store.getString("QSPulldownSide", "1") == "1"

	val context = LocalContext.current
	val accent = remember(context) { context.getColor(android.R.color.system_accent1_200) }

	Box(
		modifier = Modifier
			.align(if (hangsRight) Alignment.TopEnd else Alignment.TopStart)
			.width((screenWidth * percent / 100f).dp)
			.height(25.dp)
			// Alpha on the colour rather than on the call: `background` only
			// takes an alpha alongside a Brush.
			.background(Color(accent).copy(alpha = 0.7f)),
	)
}

@Composable
private fun PrefNodeRow(
	node: PrefNode,
	store: PrefStore,
	onNavigate: (route: String, title: Int) -> Unit,
	onSearch: () -> Unit,
	googleDesign: Boolean,
) {
	when (node) {
		is Illustration -> IllustrationHeader(node)
		is Category -> CategoryHeader(node)
		is SearchBar -> SearchHeader(node, onSearch, googleDesign)
		is NavRow -> NavRowItem(node, onNavigate)
		is ActionRow -> ActionRowItem(node)
		// Unreachable from the home screen, a lone tile renders as a bubble of one.
		is MainTile -> TileBubble(listOf(node), 0L, googleDesign, onNavigate)
		is SwitchPref -> SwitchItem(node, store)
		is SliderPref -> SliderItem(node, store)
		is ListPref -> ListChoiceItem(node, store)
		is TextPref -> TextItem(node, store)
		is ColorPref -> ColorItem(node, store)
		is TimePref -> TimeItem(node, store)
	}
}

/**
 * `android:dependency` semantics: the row is enabled while the referenced
 * preference - always a switch here - reads true. The untouched value comes
 * from the generated [preferenceDefaults] map so a fresh install resolves to
 * the default the XML declared.
 *
 * Every caller must also read `store.revision` in *its own* scope, the way
 * [SwitchItem] does. This reads plain `SharedPreferences`, which is not Compose
 * state, so the row subscribes to nothing by itself; and because `node` and
 * `store` are both reference-stable across a write, Compose skips the row's
 * scope and it goes on rendering - and gating on - the value it first
 * computed. That is how `pinned_adb_port` stayed greyed out, and stayed
 * untappable, after `pin_wireless_adb` was switched on, with nothing but a
 * cold start able to clear it. The read cannot be factored into a helper
 * composable either: a separate scope with stable parameters can itself be
 * skipped, dropping the read before it ever registers.
 */
private fun enabledBy(store: PrefStore, node: ValuePref): Boolean {
	val dep = node.dependency ?: return true
	val def = preferenceDefaults[dep] == "true"
	return if (store.contains(dep)) store.getBoolean(dep, def) else def
}

/**
 * Fades a row whose dependency is not satisfied.
 *
 * The row is already inert - its clickable is passed `enabled = false` - but
 * nothing was faded, so the row kept reading as live while its taps were
 * swallowed without a word. That is how `pinned_adb_port` came to look broken:
 * `Pin Wireless ADB Port` was off, so the port field could not be opened and
 * gave no sign why. 0.38 is M3's own disabled level, matching the widgets
 * these rows sit beside when they are disabled.
 */
private fun Modifier.dimUnless(enabled: Boolean): Modifier =
	if (enabled) this else this.alpha(0.38f)

/**
 * The node's preference key, or null for the one node type that has none.
 *
 * An untitled [Category] has no key to ask for. Returning null here and
 * reading null as visible below gives it the default of visible without
 * feeding a null to the `when` below, where it would throw only to be caught
 * back to visible.
 */
private val PrefNode.nodeKey: String?
	get() = when (this) {
		is ValuePref -> key
		is NavRow -> key
		is ActionRow -> key
		is MainTile -> key
		is Illustration -> key
		is SearchBar -> key
		is Category -> null
	}

/**
 * `SharedPreferences.getString` read as non-null: a stored null is an error,
 * so this throws rather than returning it.
 *
 * Every call site below passes a non-null default, so a null can only come
 * from an out-of-band write; if one does, the throw reaches [visibleBy],
 * which reads any throw as "visible".
 */
private fun PrefStore.string(key: String, default: String): String =
	getString(key, default) ?: error("$key stored as null")

/**
 * Row visibility: the third of this renderer's preference rules, beside
 * [enabledBy] (enablement) and the runtime summaries (summary text).
 *
 * A row is hidden until the switch that owns it reads true, so 51 keys
 * across 13 of the 21 screens do not exist until their feature is enabled -
 * the three NTP rows and `displayOverride` on Misc, the traffic rows on
 * Status bar, the colour rows on Battery bar, the three-button rows on
 * Navigation, and so on. [PrefScreenContent] drops the node when this returns
 * false, so the row never enters the LazyColumn rather than being composed
 * and not drawn.
 *
 * The reads are allowed to throw and must not. A `taskBarMode` that is not a
 * number, a switch stored where a String was expected, or a range that turns
 * out to have one handle all reach the catch, which returns true and leaves
 * the row at its default of visible; propagating would take the whole screen
 * down instead.
 */
private fun visibleBy(store: PrefStore, key: String?): Boolean {
	if (key == null) return true
	return try {
		when (key) {
			"ForceThemedLauncherIcons" -> !store.getBoolean("DisableThemedIconsPref", false)

			"TaskbarAsRecents", "taskbarHeightOverride", "TaskbarRadiusOverride" ->
				store.string("taskBarMode", "0").toInt() == 1

			"EnableGoogleRecents" -> !store.getBoolean("TaskbarAsRecents", false)
			"TaskbarHideAllAppsIcon" -> store.getBoolean("TaskbarAsRecents", false)

			"displayOverride" -> store.getBoolean("displayOverrideEnabled", false)
			"carrierTextValue" -> store.getBoolean("carrierTextMod", false)

			// The five battery colour rows only make sense while their level is
			// actually drawn. `batteryWarningRange` goes through sliderValues,
			// whose first step is `getString(key, "")`: an absent key therefore
			// parses to the empty list and neither level reads as zero, while a
			// Float or Int stored here throws out of getString. The `[1]` is
			// unguarded on purpose - a single handle throws and the row stays
			// visible. A bare `lo,hi` is not the `{"":[lo,hi]}` JSON those
			// values were written in, but the regex takes it anyway, so its
			// levels are computed rather than left to the throw.
			"batteryFastChargingColor", "batteryPowerSaveColor", "batteryChargingColor",
			"batteryWarningColor", "batteryCriticalColor" -> {
				val levels = sliderValues(store.getString("batteryWarningRange", ""))
				val critZero = levels.isNotEmpty() && levels[0] == 0f
				val warnZero = levels.isNotEmpty() && levels[1] == 0f
				val bBarEnabled = store.getBoolean("BBarEnabled", false)
				val transitColors = store.getBoolean("BBarTransitColors", false)
				when (key) {
					"batteryFastChargingColor" ->
						store.getBoolean("indicateFastCharging", false) && bBarEnabled
					"batteryChargingColor" ->
						store.getBoolean("indicateCharging", false) && bBarEnabled
					"batteryPowerSaveColor" ->
						store.getBoolean("indicatePowerSave", false) && bBarEnabled
					"batteryWarningColor" -> !warnZero && bBarEnabled
					// batteryCriticalColor
					else -> (!critZero || transitColors) && bBarEnabled && !warnZero
				}
			}

			"networkTrafficRXTop" ->
				store.getBoolean("networkOnSBEnabled", false) &&
					store.string("networkTrafficMode", "0") == "0"

			"networkTrafficColorful" ->
				store.getBoolean("networkOnSBEnabled", false) &&
					store.string("networkTrafficMode", "0") != "3"

			"networkTrafficDLColor", "networkTrafficULColor", "networkTrafficShadow" ->
				store.getBoolean("networkOnSBEnabled", false) &&
					store.string("networkTrafficMode", "0") != "3" &&
					store.getBoolean("networkTrafficColorful", false)

			"SBCBeforeClockColor", "SBCClockColor", "SBCAfterClockColor", "SBCClockTextEffect" ->
				store.getBoolean("SBCClockColorful", false)

			"ThreeButtonLeft", "ThreeButtonCenter", "ThreeButtonRight" ->
				store.getBoolean("ThreeButtonLayoutMod", false)

			"network_settings_header", "networkTrafficPosition", "networkTrafficMode",
			"networkTrafficShowIcons", "networkTrafficInterval", "networkTrafficThreshold",
			"networkTrafficOpacity" -> store.getBoolean("networkOnSBEnabled", false)

			"SyncNTPTimeNow", "TimeSyncInterval", "NTPServers" ->
				store.getBoolean("SyncNTPTime", false)

			"systemIconSortPlan" -> store.getBoolean("systemIconsMultiRow", false)

			// Both of these declare `dependency`, which only ever dims a row -
			// it never hides one, so without an arm here they stayed on screen
			// greyed out. "Move clock to bottom row" is meaningless until the
			// status bar is actually multi-row, and the pinned port is a
			// nothing until wireless ADB pinning is on.
			"multiRowClockBottom" -> store.getBoolean("notificationAreaMultiRow", false)
			"pinned_adb_port" -> store.getBoolean("pin_wireless_adb", false)

			"networkStatDLColor", "networkStatULColor" ->
				store.getBoolean("networkStatsColorful", false)

			"NetworkStatsStartTime" -> store.string("NetworkStatsStartBase", "0") == "0"
			"NetworkStatsWeekStart" -> store.string("NetworkStatsStartBase", "0") == "1"
			"NetworkStatsMonthStart" -> store.string("NetworkStatsStartBase", "0") == "2"

			"QSPulldownPercent", "QSPulldownSide", "oneFingerPullupEnabled" ->
				store.getBoolean("QSPullodwnEnabled", false)

			"BackLeftHeight" -> store.getBoolean("BackFromLeft", true)
			"BackRightHeight" -> store.getBoolean("BackFromRight", true)

			"leftSwipeUpPercentage" -> store.string("leftSwipeUpAction", "-1") != "-1"
			"rightSwipeUpPercentage" -> store.string("rightSwipeUpAction", "-1") != "-1"

			// Reads `AutoUpdate` with a fallback of `true`, deliberately not
			// the row's XML default of false - the same asymmetry
			// UpdateScheduler already reproduces for AutoUpdate.
			"UpdateWifiOnly" -> store.getBoolean("AutoUpdate", true)

			"SegmentorAI", "DWOpacity", "DWonAOD" ->
				store.getBoolean("DWallpaperEnabled", false)

			else -> true
		}
	} catch (ignored: Throwable) {
		true
	}
}

/**
 * Returns null when the row declares no icon, so `ListItem` reserves no
 * leading space at all. Size and tint: 20dp, tinted `?attr/colorOnSurface`.
 *
 * The tint is resolved now and captured by the returned lambda, because
 * [MaterialTheme.colorScheme] may only be read during composition.
 */
@Composable
private fun leadingIcon(icon: Int): (@Composable () -> Unit)? {
	if (icon == 0) return null
	val tint = MaterialTheme.colorScheme.onSurface
	return {
		Icon(
			painter = painterResource(icon),
			contentDescription = null,
			tint = tint,
			modifier = Modifier.size(20.dp),
		)
	}
}

// ------------------------------------------------------------- structural

@Composable
private fun IllustrationHeader(node: Illustration) {
	if (node.lottieRawRes == 0) return
	val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(node.lottieRawRes))
	val progress by animateLottieCompositionAsState(
		composition = composition,
		iterations = LottieConstants.IterateForever,
	)
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 8.dp, bottom = 4.dp),
		contentAlignment = Alignment.Center,
	) {
		LottieAnimation(
			composition = composition,
			progress = { progress },
			modifier = Modifier
				.fillMaxWidth()
				.height(150.dp),
		)
	}
}

@Composable
private fun CategoryHeader(node: Category) {
	if (node.title == 0) {
		Box(modifier = Modifier.height(8.dp))
		return
	}
	Text(
		text = titleText(node),
		style = MaterialTheme.typography.titleSmall,
		color = MaterialTheme.colorScheme.primary,
		fontWeight = FontWeight.Medium,
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
	)
}

@Composable
private fun SearchHeader(node: SearchBar, onSearch: () -> Unit, googleDesign: Boolean) {
	if (googleDesign) {
		GoogleSearchHeader(onSearch)
		return
	}
	// Tapping the widget opens the search screen rather than filtering this
	// one. The field itself is disabled so it cannot take focus or consume the
	// gesture, and the click lives on the wrapper; colours are overridden so
	// it still reads as enabled.
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp)
			.clickable(onClick = onSearch),
	) {
		OutlinedTextField(
			value = "",
			onValueChange = {},
			enabled = false,
			readOnly = true,
			label = { Text(stringResource(R.string.searchpreference_search)) },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
			colors = OutlinedTextFieldDefaults.colors(
				disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
				disabledBorderColor = MaterialTheme.colorScheme.outline,
				disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
				disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
				disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
			),
		)
	}
}

// The rounded search widget of the Google design
@Composable
private fun GoogleSearchHeader(onSearch: () -> Unit) {
	val shape = RoundedCornerShape(percent = 50)

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp)
			.height(56.dp)
			.clip(shape)
			.background(MaterialTheme.colorScheme.surfaceContainerLowest)
			.border(1.dp, MaterialTheme.colorScheme.outline, shape)
			.clickable(onClick = onSearch)
			.padding(horizontal = 20.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(
			imageVector = Icons.Filled.Search,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Spacer(modifier = Modifier.width(16.dp))
		Text(
			text = stringResource(R.string.searchpreference_search),
			style = MaterialTheme.typography.bodyLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

// ------------------------------------------------------------ navigation

@Composable
private fun NavRowItem(node: NavRow, onNavigate: (route: String, title: Int) -> Unit) {
	ListItem(
		leadingContent = leadingIcon(node.icon),
		headlineContent = { Text(titleText(node)) },
		supportingContent = summaryOrNull(node.key, node.summary),
		trailingContent = {
			IconChevron()
		},
		modifier = Modifier
			.fillMaxWidth()
			.clickable { onNavigate(node.route, node.title) },
	)
}

@Composable
private fun ActionRowItem(node: ActionRow) {
	val context = LocalContext.current
	val link = externalLinks[node.key]

	// Two dispatches, both keyed on the preference: the four URL rows come
	// from `externalLinks`, and `SyncNTPTimeNow` runs the NTP sync. Every
	// other ActionRow stays inert.
	val syncNow = node.key == "SyncNTPTimeNow"

	ListItem(
		leadingContent = leadingIcon(node.icon),
		headlineContent = { Text(titleText(node)) },
		supportingContent = summaryOrNull(node.key, node.summary),
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = syncNow || link != null) {
				when {
					syncNow -> syncNTP(context)
					link != null -> openExternalLink(context, link)
				}
			},
	)
}

// One rounded bubble holding all home tiles with a divider
@Composable
private fun TileBubble(
	tiles: List<MainTile>,
	accent: Long,
	googleDesign: Boolean,
	onNavigate: (route: String, title: Int) -> Unit,
) {
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 6.dp),
		shape = RoundedCornerShape(20.dp),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainer,
		),
	) {
		tiles.forEachIndexed { index, tile ->
			if (index > 0) {
				// the window background show through (used as a divider)
				HorizontalDivider(
					thickness = 2.dp,
					color = MaterialTheme.colorScheme.background,
				)
			}
			TileRow(tile, accent, googleDesign, onNavigate)
		}
	}
}

/**
 * A single home tile. The bubble supplies the card, this is only the
 * row: circle, title, summary, and the click opens [MainTile.route].
 *
 * Google design - the circle takes the category's [accent] and the glyph is tinted with the card colour
 */
@Composable
private fun TileRow(
	node: MainTile,
	accent: Long,
	googleDesign: Boolean,
	onNavigate: (route: String, title: Int) -> Unit,
) {
	val painted = googleDesign && accent != 0L
	val circle = if (painted) Color(accent.toInt()) else MaterialTheme.colorScheme.primaryContainer
	val glyph = when {
		painted -> glyphOn(Color(accent.toInt()))
		// The app's own design keeps the theme's glyph in the light theme and
		// takes the background colour in the dark one
		isSystemInDarkTheme() -> MaterialTheme.colorScheme.background
		else -> MaterialTheme.colorScheme.onSurface
	}

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = node.route != null) {
				node.route?.let { r -> onNavigate(r, node.title) }
			}
			.padding(16.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (node.icon != 0) {
			Box(
				modifier = Modifier
					.size(40.dp)
					.background(
						color = circle,
						shape = CircleShape,
					),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = painterResource(node.icon),
					contentDescription = null,
					tint = glyph,
				)
			}
			Spacer(modifier = Modifier.width(16.dp))
		}

		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = titleText(node),
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
			)
			if (node.summary != 0) {
				Text(
					text = stringResource(node.summary),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 4.dp),
				)
			}
		}
	}
}

/**
 * The glyph the system Settings app draws inside one of its circles.
 *
 * The circles are tones of the M3 reference palette the system carries, so
 * reading the accent's hue and chroma back out and asking for tone 30 regardless of accent.
 */
private fun glyphOn(accent: Color): Color {
	val hct = Hct.fromInt(accent.toArgb())
	return Color(Hct.from(hct.hue, hct.chroma, GlyphTone).toInt())
}

/** The dark tone the system pairs its pale circle tones with. */
private const val GlyphTone = 30.0

@Composable
private fun summaryOrNull(res: Int, modifier: Modifier = Modifier): (@Composable () -> Unit)? =
	if (res == 0) null else {{ Text(stringResource(res), modifier = modifier) }}

/**
 * A row's title, or "" when it has none.
 *
 * `android:title` may be a plain literal rather than a `@string/` reference -
 * `misc_prefs.xml` declares exactly that once, on `SystemUI Tuner` - and a
 * resource id cannot express it, so the converter keeps such text on the node
 * as [PrefNode.titleText], which wins here. The `title == 0` branch still
 * guards `stringResource(0)`, which throws `Resources$NotFoundException`.
 */
@Composable
private fun titleText(node: PrefNode): String =
	node.titleText ?: if (node.title == 0) "" else stringResource(node.title)

/**
 * [summaryOrNull] for the rows whose text is only known at runtime.
 *
 * Two summaries are formatted after inflation and overwrite the
 * `android:summary` they came from: "CheckForUpdate" needs this build's
 * version name (`current_version`) and "CrowdinProject" the app name
 * (`crowdin_summary`), and neither is expressible as a resource reference.
 * Every other key falls through to the plain resource.
 */
@Composable
private fun summaryOrNull(key: String, res: Int): (@Composable () -> Unit)? =
	when (key) {
		"CheckForUpdate" -> {
			{ Text(stringResource(R.string.current_version, BuildConfig.VERSION_NAME)) }
		}
		"CrowdinProject" -> {
			{ Text(stringResource(R.string.crowdin_summary, stringResource(R.string.app_name))) }
		}
		else -> summaryOrNull(res)
	}

@Composable
private fun IconChevron() {
	androidx.compose.material3.Icon(
		imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
		contentDescription = null,
		tint = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

// ---------------------------------------------------------------- switch

@Composable
private fun SwitchItem(node: SwitchPref, store: PrefStore) {
	val context = LocalContext.current

	// Subscribe to the store *inside* this composable, not only in the list
	// container above it. `node` and `store` are both unchanged by a write,
	// so Compose skips this scope and `checked` would go on rendering the
	// value it first rendered - after which the row writes `!checked` as the
	// same value again and the switch appears dead.
	val revision by store.revision.collectAsState()
	@Suppress("UNUSED_VARIABLE") val ignored = revision

	val enabled = enabledBy(store, node)
	val checked = (store.read(node) ?: node.default) == "true"

	val summaryRes = when {
		node.summaryOn != 0 && checked -> node.summaryOn
		node.summaryOff != 0 && !checked -> node.summaryOff
		else -> node.summary
	}

	// Side effects run after the value is already persisted, so the write
	// carries out whatever that key does: arming the icon notice below,
	// re-registering the update check now that AutoUpdate has flipped, or
	// re-arming the periodic NTP query.
	var iconFlip by remember { mutableStateOf<Boolean?>(null) }

	val write = { value: String ->
		store.write(node, value)
		when (node.key) {
			"AlternativeThemedAppIcon" -> iconFlip = value == "true"
			"AutoUpdate" -> UpdateScheduler.scheduleUpdates(context)
			"SyncNTPTime" -> TimeSyncScheduler.scheduleTimeSync(context)
		}
	}

	ListItem(
		leadingContent = leadingIcon(node.icon),
		// Text only: the Switch already takes M3's disabled colours, and
		// dimming the whole row as well would take it past unreadable.
		headlineContent = { Text(titleText(node), modifier = Modifier.dimUnless(enabled)) },
		supportingContent = summaryOrNull(summaryRes, Modifier.dimUnless(enabled)),
		trailingContent = {
			Switch(
				checked = checked,
				onCheckedChange = { write(it.toString()) },
				enabled = enabled,
			)
		},
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = enabled) { write((!checked).toString()) },
	)

	if (iconFlip != null) {
		// setCancelable(false): only OK ends this, so the app is never left
		// in a half-changed state with the notice dismissed.
		AlertDialog(
			onDismissRequest = {},
			title = { Text(stringResource(R.string.app_kill_alert_title)) },
			text = { Text(stringResource(R.string.app_kill_alert_body)) },
			confirmButton = {
				TextButton(onClick = {
					val themed = iconFlip ?: false
					iconFlip = null
					applyAlternativeAppIcon(context, themed)
				}) {
					Text(stringResource(R.string.app_kill_ok_btn))
				}
			},
		)
	}
}

// ---------------------------------------------------------------- slider

private val TipGap = 8.dp

/**
 * The number currently selected, sitting under the summary.
 *
 * Without this the value simply is not on screen anywhere - the ListItem shows
 * title and summary, and the slider below it only shows a thumb position. The
 * line is bodyMedium in `colorOnSurface`, start-aligned rather than hung off
 * the right edge.
 */
@Composable
private fun SliderValueLine(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.bodyMedium,
		color = MaterialTheme.colorScheme.onSurface,
		modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
	)
}

/**
 * Clearance reserved above the track for the default marker, and - because M3
 * centres the track box against the thumb - an equal share below it.
 *
 * The symmetry is what keeps this safe: the track box stays centred on the
 * thumb no matter how tall it gets, and at 12dp it still measures under the
 * thumb's own 48dp, so `SliderImpl`'s `sliderHeight` does not move either.
 * Going past 16dp would start to grow the row.
 */
private val DefaultMarkerSpace = 12.dp

private val DefaultArrowWidth = 10.dp
private val DefaultArrowHeight = 6.dp

/**
 * The width M3 reserves for the thumb when it decides where to place it.
 *
 * This is not the drawn handle - that is a 4dp pill - but the box M3 insets the
 * value range by, so the thumb's centre lands on
 * `thumb / 2 + fraction * (track - thumb)`.
 *
 * Derived from pixels rather than from a token, because the token read off
 * androidx-main disagreed with what the app actually ships. On device at
 * 480dpi with material3 1.4.0, the gesture pill height slider (fraction 0.2,
 * track x = 54..1227) put the arrow on 288 and the thumb's pill on 302.5.
 * Plain `fraction * track` gives 288, and M3's expression gives
 * `288 + thumb * (0.5 - 0.2)`, so `thumb = 14.5 / 0.3 = 48px = 16.dp`. Drawing
 * the arrow with that number lands it on 302.4 - a tenth of a pixel from the
 * thumb it has to point at.
 *
 * It is a constant rather than a measurement because the marker's Canvas is
 * laid out before the thumb it has to line up with: reading it back at runtime
 * would cost a second layout pass and a frame of the arrow sitting visibly off
 * centre. If material3 ever moves it, the symptom is a constant few-px offset,
 * and the arithmetic above is how to re-derive it.
 */
private val DefaultThumbWidth = 16.dp

/**
 * Lays a slider's track out and points a down arrow at the position the
 * preference declares as its default, so drift away from stock is visible
 * without having to read the value line.
 *
 * M3 does not seat the thumb at a plain `fraction * width`. It takes the
 * thumb's own measured width out of the track first, lays the value range over
 * what is left, and starts that run half a thumb in - so the thumb's centre
 * lands on `thumb / 2 + fraction * (width - thumb)`. The arrow was drawn at
 * `fraction * width`, which puts it left of the thumb by exactly
 * `thumb * (0.5 - fraction)`: 14.5px at f = 0.2. Over the 1173px track of the
 * gesture pill height slider that is 14.5 / 1173 * 250 = 3.1 units - so a
 * default of 100 pointed at 97, which is precisely what it looked like. See
 * [DefaultThumbWidth] for where that number came from.
 *
 * The arrow sits above the track rather than cut into it, so it is drawn
 * against the row's background and only has to read against that. A preference
 * that declares no default passes an empty list and lays down nothing extra.
 */
@Composable
private fun SliderTrackWithDefaultMarker(
	fractions: List<Float>,
	enabled: Boolean,
	content: @Composable () -> Unit,
) {
	val base = MaterialTheme.colorScheme.onSurfaceVariant
	val marker = if (enabled) base else base.copy(alpha = 0.38f)
	Column(Modifier.fillMaxWidth()) {
		Box(Modifier.fillMaxWidth().height(DefaultMarkerSpace)) {
			Canvas(Modifier.matchParentSize()) {
				// DrawScope is a Density, so the tokens can be resolved here.
				val half = DefaultArrowWidth.toPx() / 2f
				val rise = DefaultArrowHeight.toPx()
				val thumb = DefaultThumbWidth.toPx()
				fractions.forEach { raw ->
					val fraction = raw.coerceIn(0f, 1f)
					// Same expression M3 uses for the thumb, so the two cannot
					// drift apart; the coerce only bites at the extremes.
					val x = (thumb / 2f + fraction * (size.width - thumb))
						.coerceIn(half, size.width - half)
					val tip = size.height
					val arrow = Path().apply {
						moveTo(x, tip)
						lineTo(x - half, tip - rise)
						lineTo(x + half, tip - rise)
						close()
					}
					drawPath(arrow, color = marker)
				}
			}
		}
		content()
		// Equal share below, so the track's centre stays put.
		Spacer(Modifier.fillMaxWidth().height(DefaultMarkerSpace))
	}
}

/**
 * True while [source] still has an unresolved press or drag, i.e. the user is
 * holding this thumb.
 *
 * A plain tap counts as well as a drag, so a thumb that is tapped without
 * being moved still reports its value - which is the case a bubble is most
 * useful for, since nothing else on the row changes.
 */
@Composable
private fun InteractionSource.isActiveThumb(): Boolean =
	collectIsPressedAsState().value || collectIsDraggedAsState().value

/**
 * The bubble itself - the plain-tooltip treatment, inverted surface, so it
 * reads as an overlay rather than as part of the row.
 */
@Composable
private fun ValueBubble(text: String) {
	Surface(
		color = MaterialTheme.colorScheme.inverseSurface,
		contentColor = MaterialTheme.colorScheme.inverseOnSurface,
		shape = MaterialTheme.shapes.small,
	) {
		Text(
			text = text,
			style = MaterialTheme.typography.labelSmall,
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
		)
	}
}

/**
 * The slider thumb, plus a value bubble floating above it while the user holds
 * that thumb.
 *
 * The bubble deliberately takes no part in measurement. `SliderImpl` derives
 * the track's width from the thumb's measured width and then places the thumb's
 * left edge at `fraction * trackWidth`, so anything that made the thumb measure
 * wider would drag the thumb off its own track. Measuring the children here and
 * reporting only the thumb's own size keeps the default geometry exactly as it
 * was while letting the bubble overhang - `Layout` does not clip its children,
 * and `SliderImpl` puts no clipping modifier in the chain above this.
 */
@Composable
private fun SliderThumbWithTip(
	interactionSource: MutableInteractionSource,
	colors: SliderColors,
	enabled: Boolean,
	label: String,
	showTip: Boolean,
) {
	Layout(
		content = {
			SliderDefaults.Thumb(
				interactionSource = interactionSource,
				colors = colors,
				enabled = enabled,
			)
			if (showTip) ValueBubble(label)
		},
	) { measurables, constraints ->
		val thumb = measurables[0].measure(constraints)
		val tip = measurables.getOrNull(1)
			?.measure(Constraints(maxWidth = Constraints.Infinity, maxHeight = Constraints.Infinity))

		layout(thumb.width, thumb.height) {
			thumb.placeRelative(0, 0)
			if (tip != null) {
				tip.placeRelative(
					x = (thumb.width - tip.width) / 2,
					y = -tip.height - TipGap.roundToPx(),
				)
			}
		}
	}
}

@Composable
private fun SliderItem(node: SliderPref, store: PrefStore) {
	// Keep `enabled` live across a write - see enabledBy.
	val revision by store.revision.collectAsState()
	@Suppress("UNUSED_VARIABLE") val ignored = revision
	val enabled = enabledBy(store, node)
	val steps = if (node.step > 0f) {
		((node.max - node.min) / node.step).roundToInt().coerceAtLeast(1) - 1
	} else 0
	val range = node.min..node.max
	val isRange = node.valueCount >= 2

	fun frac(v: Float) = if (node.max > node.min) (v - node.min) / (node.max - node.min) else 0f
	val defaultFractions = sliderValues(node.default).map { frac(it) }

	val context = LocalContext.current

	// MiscFragment.updateScreen(key) re-armed the periodic NTP query whenever
	// `TimeSyncInterval` moved, after the value was already persisted - so the
	// hook sits in the two release handlers, which are the only places this
	// generic widget persists anything. It keys on the preference key rather
	// than on the widget type, so which branch the node's data takes does not
	// change what happens to it.
	fun onCommitted() {
		if (node.key == "TimeSyncInterval") TimeSyncScheduler.scheduleTimeSync(context)
	}

	// Non-continuous sliders show the thumb moving but commit on release.
	var pending by remember(node.key) { mutableStateOf<Float?>(null) }
	var pendingLo by remember(node.key) { mutableStateOf<Float?>(null) }
	var pendingHi by remember(node.key) { mutableStateOf<Float?>(null) }

	// Only these two rows carry a reset icon, and it means "stop overriding"
	// rather than "write the default back": clearing the key is the only reset
	// either hook recognises, because both fall through to the system's own
	// values only when there is nothing stored left to parse.
	val resettable = node.key in RESETTABLE_RANGES

	ListItem(
		leadingContent = leadingIcon(node.icon),
		headlineContent = { Text(titleText(node)) },
		supportingContent = summaryOrNull(node.summary),
		trailingContent = if (resettable) {
			{
				IconButton(onClick = {
					pending = null
					pendingLo = null
					pendingHi = null
					store.putString(node.key, null)
					if (node.key in RESET_RESTARTS_SYSTEMUI) Restarter.restartSystemUI()
				}) {
					Icon(
						imageVector = Icons.Filled.Refresh,
						contentDescription = stringResource(R.string.reset_to_default),
					)
				}
			}
		} else null,
		modifier = Modifier.fillMaxWidth(),
	)

	if (isRange) {
		val parts = sliderValues(store.read(node) ?: node.default)
		val lo = pendingLo ?: parts.getOrNull(0) ?: node.min
		val hi = pendingHi ?: parts.getOrNull(1) ?: node.max
		val current = lo.coerceAtMost(hi)..hi.coerceAtLeast(lo)

		// Status bar padding's two default handles are track travel, not a
		// value anyone picked: -0.5 and 100.5 are precisely what
		// `PADDING_DEFAULT` decodes to on the hooked side, meaning "apply
		// nothing". Saying that is better than printing the plumbing.
		val defParts = sliderValues(node.default)
		val atDefault = current.start == defParts.getOrNull(0) &&
			current.endInclusive == defParts.getOrNull(1)
		SliderValueLine(
			if (atDefault && node.key == "statusbarPaddings") {
				val d = context.getString(R.string.word_default)
				"$d - $d"
			} else {
				"${trimFloat(current.start)} – ${trimFloat(current.endInclusive)}"
			},
		)

		val colors = SliderDefaults.colors()
		val startInteraction = remember(node.key) { MutableInteractionSource() }
		val endInteraction = remember(node.key) { MutableInteractionSource() }
		val startActive = startInteraction.isActiveThumb()
		val endActive = endInteraction.isActiveThumb()

		RangeSlider(
			value = current,
			onValueChange = { new ->
				// Snap here as well as in M3. The track quantises before it
				// hands the value over, but a range written by an older build
				// can still be off-grid on the way back in, and a read-out of
				// 6.9653473 on a 0.5 track is exactly that stale value showing
				// through - snapping on every move retires it.
				val l = if (node.step > 0f) snap(new.start, node) else new.start
				val h = if (node.step > 0f) snap(new.endInclusive, node) else new.endInclusive
				pendingLo = l
				pendingHi = h
				if (node.continuous) writeRange(node, store, l, h)
			},
			onValueChangeFinished = {
				if (!node.continuous) {
					pendingLo?.let { l -> pendingHi?.let { h -> writeRange(node, store, l, h) } }
				}
				pendingLo = null
				pendingHi = null
				onCommitted()
			},
			enabled = enabled,
			valueRange = range,
			steps = steps,
			colors = colors,
			// Own sources rather than the overload's defaults, so each thumb
			// knows whether it is the one being held and shows only its own
			// value bubble. (The value-based overload names these
			// start/endInteractionSource; the state-based one uses
			// start/endThumbInteractionSource.)
			startInteractionSource = startInteraction,
			endInteractionSource = endInteraction,
			modifier = Modifier.padding(horizontal = 16.dp),
			startThumb = {
				SliderThumbWithTip(
					interactionSource = startInteraction,
					colors = colors,
					enabled = enabled,
					label = trimFloat(current.start),
					showTip = startActive,
				)
			},
			endThumb = {
				SliderThumbWithTip(
					interactionSource = endInteraction,
					colors = colors,
					enabled = enabled,
					label = trimFloat(current.endInclusive),
					showTip = endActive,
				)
			},
			track = { state ->
				SliderTrackWithDefaultMarker(defaultFractions, enabled) {
					SliderDefaults.Track(
						colors = colors,
						enabled = enabled,
						rangeSliderState = state,
					)
				}
			},
		)
	} else {
		val stored = sliderValues(store.read(node)).firstOrNull()
		val current = pending ?: stored ?: node.min

		SliderValueLine(valueLabel(context, node.key, current))

		val colors = SliderDefaults.colors(
			thumbColor = MaterialTheme.colorScheme.primary,
		)
		val interaction = remember(node.key) { MutableInteractionSource() }
		val active = interaction.isActiveThumb()

		LaunchedEffect(active) {
			if (active) {
				store.setActiveSliderKey(node.key)
			} else if (store.activeSliderKey.value == node.key) {
				kotlinx.coroutines.delay(1200)
				if (store.activeSliderKey.value == node.key) {
					store.setActiveSliderKey(null)
				}
			}
		}

		Slider(
			value = current.coerceIn(node.min, node.max),
			onValueChange = { v ->
				val snapped = if (node.step > 0f) snap(v, node) else v
				pending = snapped
				store.setActiveSliderKey(node.key)
				if (node.continuous) store.putFloat(node.key, snapped)
			},
			onValueChangeFinished = {
				if (!node.continuous) pending?.let { store.putFloat(node.key, it) }
				pending = null
				onCommitted()
			},
			enabled = enabled,
			valueRange = range,
			steps = steps,
			colors = colors,
			interactionSource = interaction,
			modifier = Modifier.padding(horizontal = 16.dp),
			thumb = {
				SliderThumbWithTip(
					interactionSource = interaction,
					colors = colors,
					enabled = enabled,
					label = trimFloat(current),
					showTip = active,
				)
			},
			track = { state ->
				SliderTrackWithDefaultMarker(defaultFractions, enabled) {
					SliderDefaults.Track(colors = colors, enabled = enabled, sliderState = state)
				}
			},
		)
	}
}

/**
 * Writes a range in exactly the shape `RangeSliderPreference.setValues`
 * produces - `{"":[lo,hi]}` - rather than the bare `lo,hi` written before.
 *
 * The hooks read these keys through `Xprefs.getSliderValues`, which parses
 * that JSON first and, failing that, falls back to `getFloat`, then
 * `getInt`, then the declared default. A bare `lo,hi` fails all four
 * steps, so the value was stored correctly and then silently ignored on the
 * hooked side - `BrightnessRange`, `batteryWarningRange` and
 * `statusbarPaddings` were all affected.
 */
private fun writeRange(node: SliderPref, store: PrefStore, lo: Float, hi: Float) {
	store.putString(node.key, "{\"\":[${trimFloat(lo)},${trimFloat(hi)}]}")
}

private fun trimFloat(v: Float): String =
	if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else v.toString()

/**
 * Ranges whose trailing reset icon means "stop overriding this".
 *
 * The distinction matters because these two hooks do not read a stored zero as
 * "no opinion": `StatusbarMods` only skips its padding when both handles sit on
 * `PADDING_DEFAULT`, and `BrightnessRange` only skips the clamp while it holds
 * its 0..1 pair. Clearing the key is therefore the only form of reset either of
 * them honours - writing the default back would leave a value the hook still
 * parses and still enforces.
 */
private val RESETTABLE_RANGES = setOf("statusbarPaddings", "BrightnessRange")

/**
 * Resettable ranges whose reset must also cycle SystemUI.
 *
 * Clearing the key is what the hook actually honours, but landing the cleared
 * value on the status bar that is already drawn goes through
 * `updateStatusbarHeight()` reaching `mPhoneStatusbarView` at the right moment,
 * inside a `try`/`catch` that swallows the miss. The overflow menu's "Reset to
 * defaults" never depended on that path - it clears every key and restarts -
 * so this row now does the same rather than leaving the reset timing to the
 * live-update path. `BrightnessRange` is deliberately absent: it lives in the
 * `android` process, where killing SystemUI reaches nothing.
 */
private val RESET_RESTARTS_SYSTEMUI = setOf("statusbarPaddings")

/**
 * The text a stored slider value reads as on screen.
 *
 * For a handful of keys the raw number is not what the row should say: an
 * untouched `volumeStps` reads "Default", not 10. Keys not named here fall
 * through to the bare number.
 *
 * Deliberately absent: the battery-bar, pill-size and generic percentage
 * labels. Nothing asked for them, and a label nobody noticed is one worth
 * leaving alone.
 */
private fun valueLabel(context: Context, key: String, v: Float): String {
	val wordDefault = context.getString(R.string.word_default)
	return when (key) {
		// `(QSLabelScaleFactor + 100) + "%"`. The track runs -50..50,
		// so the untouched centre - already the correct position - reads
		// 100%, not 0.
		"QSLabelScaleFactor", "QSSecondaryLabelScaleFactor" ->
			"${trimFloat(v + 100f)}%"

		// `KeyGuardDimAmount < 0 ? Default : KeyGuardDimAmount + "%"`. -1 is
		// both the XML default and the sentinel for "let the system decide",
		// so printing it raw would show the reader the plumbing.
		"KeyGuardDimAmount" ->
			if (v < 0f) wordDefault else "${trimFloat(v)}%"

		// `value <= 5 ? Default : "N Watts"`. 5 is the XML default and the
		// floor of the track, so the default can never be told apart from the
		// lowest reachable value without this.
		"FastChargingWattage" ->
			if (v <= 5f) wordDefault
			else "${trimFloat(v)} ${context.getString(R.string.watts)}"

		// `volumeStps == 10 ? Default : volumeStps`, always carrying the
		// reboot warning the number on its own cannot.
		"volumeStps" ->
			"${if (v == 10f) wordDefault else trimFloat(v)} - " +
				"(${context.getString(R.string.restart_needed)})"

		"QSPulldownPercent" -> "${trimFloat(v)}%"

		else -> trimFloat(v)
	}
}

private val SLIDER_NUMBER = Regex("""-?\d*\.?\d+""")

/**
 * Reads a slider's value back out of the three shapes that reach here: the
 * `{"":[lo,hi]}` JSON the key may already be stored in, the bare `lo,hi`
 * string every generated default still uses (`"0,100"`), and a plain number
 * for single-handle sliders.
 *
 * Pulling the numbers out by regex covers all three - including values
 * already stored under the old format - without giving the renderer a JSON
 * dependency.
 */
private fun sliderValues(raw: String?): List<Float> =
	SLIDER_NUMBER.findAll(raw ?: "").mapNotNull { it.value.toFloatOrNull() }.toList()

private fun snap(v: Float, node: SliderPref): Float {
	if (node.step <= 0f) return v
	val n = ((v - node.min) / node.step).roundToInt()
	return (node.min + n * node.step).coerceIn(node.min, node.max)
}

// ------------------------------------------------------------------ list

@Composable
private fun ListChoiceItem(node: ListPref, store: PrefStore) {
	val context = LocalContext.current
	// Keep `enabled` live across a write - see enabledBy.
	val revision by store.revision.collectAsState()
	@Suppress("UNUSED_VARIABLE") val ignored = revision
	val enabled = enabledBy(store, node)
	var open by remember { mutableStateOf(false) }

	val labels = remember(node, context) {
		if (node.entries == 0) emptyList()
		else runCatching { context.resources.getStringArray(node.entries).toList() }
			.getOrElse { emptyList() }
	}
	val values = remember(node, context) {
		if (node.entryValues == 0) emptyList()
		else runCatching { context.resources.getStringArray(node.entryValues).toList() }
			.getOrElse { emptyList() }
	}

	val current = store.read(node) ?: node.default ?: ""
	val selected: Set<String> =
		if (node.multiSelect) {
			current.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
		} else setOf(current)

	val shown = values.indices
		.filter { values[it] in selected }
		.joinToString(", ") { labels.getOrElse(it) { values[it] } }

	ListItem(
		leadingContent = leadingIcon(node.icon),
		headlineContent = { Text(titleText(node)) },
		supportingContent = {
			Column {
				if (node.summary != 0) Text(stringResource(node.summary))
				if (shown.isNotEmpty()) {
					Text(
						text = shown,
						color = MaterialTheme.colorScheme.primary,
						style = MaterialTheme.typography.bodySmall,
					)
				}
			}
		},
		modifier = Modifier
			.fillMaxWidth()
			.dimUnless(enabled)
			.clickable(enabled = enabled) { open = true },
	)

	if (open) {
		// The dialog only persists on OK: a pick is committed there, and
		// Cancel - or a tap outside - throws it away. Toggling must therefore
		// edit a draft, with the stored value written once, on OK.
		var draft by remember(node.key) { mutableStateOf(selected) }

		AlertDialog(
			onDismissRequest = { open = false },
			title = { Text(titleText(node)) },
			text = {
				Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
					values.forEachIndexed { i, v ->
						val toggle = { draft = if (v in draft) draft - v else draft + v }
						Row(
							verticalAlignment = Alignment.CenterVertically,
							modifier = Modifier
								.fillMaxWidth()
								.clickable {
									if (node.multiSelect) toggle()
									else {
										store.write(node, v)
										open = false
									}
								}
								.padding(vertical = 4.dp),
						) {
							if (node.multiSelect) {
								// A real Checkbox rather than a drawn box. The box
								// was transparent while clear, so the row showed no
								// control at all, and being a bare Box it reported
								// no checked state to accessibility or to a
								// uiautomator dump - which is how the missing
								// checkbox went unnoticed.
								Checkbox(
									checked = v in draft,
									onCheckedChange = { toggle() },
								)
							} else {
								RadioButton(selected = v in selected, onClick = null)
							}
							Text(
								text = labels.getOrElse(i) { v },
								modifier = Modifier.padding(start = 12.dp),
							)
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = {
					if (node.multiSelect) store.write(node, draft.joinToString(","))
					open = false
				}) {
					Text(stringResource(android.R.string.ok))
				}
			},
			dismissButton = {
				// Only multi-select earns a Cancel: single-select commits on the
				// tap itself in this renderer, so there would be nothing to undo.
				if (node.multiSelect) {
					TextButton(onClick = { open = false }) {
						Text(stringResource(android.R.string.cancel))
					}
				}
			},
		)
	}
}

// ------------------------------------------------------------------ text

@Composable
private fun TextItem(node: TextPref, store: PrefStore) {
	// Keep `enabled` live across a write - see enabledBy.
	val revision by store.revision.collectAsState()
	@Suppress("UNUSED_VARIABLE") val ignored = revision
	val enabled = enabledBy(store, node)
	var open by remember { mutableStateOf(false) }
	var draft by remember(node.key) { mutableStateOf(store.read(node) ?: node.default ?: "") }

	ListItem(
		leadingContent = leadingIcon(node.icon),
		headlineContent = { Text(titleText(node)) },
		supportingContent = {
			Column {
				if (node.summary != 0) Text(stringResource(node.summary))
				val v = store.read(node)
				if (!v.isNullOrEmpty()) {
					Text(
						text = v,
						color = MaterialTheme.colorScheme.primary,
						style = MaterialTheme.typography.bodySmall,
					)
				}
			}
		},
		modifier = Modifier
			.fillMaxWidth()
			.dimUnless(enabled)
			.clickable(enabled = enabled) {
				draft = store.read(node) ?: node.default ?: ""
				open = true
			},
	)

	if (open) {
		AlertDialog(
			onDismissRequest = { open = false },
			title = { Text(titleText(node)) },
			text = {
				Column {
					if (node.manual) {
						// A scrollable format reference above the input. Cap the
						// height so the field and the dialog buttons below it
						// stay reachable - the body runs to ~880 characters.
						Box(
							modifier = Modifier
								.heightIn(max = 260.dp)
								.verticalScroll(rememberScrollState()),
						) {
							Text(
								text = stringResource(R.string.string_formatter_manual_body_v3),
								color = MaterialTheme.colorScheme.onSurface,
								style = MaterialTheme.typography.bodySmall,
							)
						}
					}
					OutlinedTextField(
						value = draft,
						onValueChange = { draft = it },
						singleLine = true,
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = if (node.manual) 24.dp else 0.dp),
					)
				}
			},
			confirmButton = {
				TextButton(onClick = {
					store.write(node, draft)
					open = false
				}) { Text(stringResource(android.R.string.ok)) }
			},
			dismissButton = {
				TextButton(onClick = { open = false }) {
					Text(stringResource(android.R.string.cancel))
				}
			},
		)
	}
}

// ----------------------------------------------------------------- color

@Composable
private fun ColorItem(node: ColorPref, store: PrefStore) {
	// Keep `enabled` live across a write - see enabledBy.
	val revision by store.revision.collectAsState()
	@Suppress("UNUSED_VARIABLE") val ignored = revision
	val enabled = enabledBy(store, node)
	var open by remember { mutableStateOf(false) }
	val current = store.read(node) ?: node.default ?: "#FFFFFF"

	ListItem(
		leadingContent = leadingIcon(node.icon),
		headlineContent = { Text(titleText(node)) },
		supportingContent = {
			Column {
				if (node.summary != 0) Text(stringResource(node.summary))
				Text(
					text = formatColorHex(current),
					color = MaterialTheme.colorScheme.primary,
					style = MaterialTheme.typography.bodySmall,
				)
			}
		},
		trailingContent = {
			Box(
				modifier = Modifier
					.size(28.dp)
					.background(parseColor(current), CircleShape),
			)
		},
		modifier = Modifier
			.fillMaxWidth()
			.dimUnless(enabled)
			.clickable(enabled = enabled) { open = true },
	)

	if (open) {
		var draft by remember { mutableStateOf(formatColorHex(current)) }
		AlertDialog(
			onDismissRequest = { open = false },
			title = { Text(titleText(node)) },
			text = {
				Column(
					modifier = Modifier.verticalScroll(rememberScrollState()),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = 4.dp),
					) {
						Box(
							modifier = Modifier
								.size(36.dp)
								.background(parseColor(draft), CircleShape),
						)
						Spacer(Modifier.width(12.dp))
						OutlinedTextField(
							value = draft,
							onValueChange = { draft = it },
							singleLine = true,
							modifier = Modifier.fillMaxWidth(),
							label = { Text("Hex Color") },
						)
					}
					Text(
						text = "Presets",
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					PRESET_COLORS.forEach { hex ->
						Row(
							verticalAlignment = Alignment.CenterVertically,
							modifier = Modifier
								.fillMaxWidth()
								.clickable {
									draft = hex
								}
								.padding(vertical = 4.dp),
						) {
							Box(
								modifier = Modifier
									.size(24.dp)
									.background(parseColor(hex), CircleShape),
							)
							Text(
								text = hex,
								modifier = Modifier.padding(start = 12.dp),
								style = MaterialTheme.typography.bodyMedium,
							)
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = {
					store.write(node, draft)
					open = false
				}) {
					Text(stringResource(android.R.string.ok))
				}
			},
			dismissButton = {
				TextButton(onClick = { open = false }) {
					Text(stringResource(android.R.string.cancel))
				}
			},
		)
	}
}

private val PRESET_COLORS = listOf(
	"#8BC34A", "#FAA19B", "#FF0000", "#FF9800",
	"#FFEB3B", "#4CAF50", "#00BCD4", "#2196F3",
	"#3F51B5", "#9C27B0", "#E91E63", "#795548",
	"#607D8B", "#000000", "#FFFFFF",
)

private fun formatColorHex(value: String): String {
	val intVal = value.toIntOrNull()
	if (intVal != null) {
		return String.format("#%06X", 0xFFFFFF and intVal)
	}
	val trimmed = value.trim()
	if (trimmed.startsWith("#")) return trimmed.uppercase()
	if (trimmed.startsWith("0x", ignoreCase = true)) return "#${trimmed.substring(2).uppercase()}"
	return if (trimmed.isNotEmpty()) "#${trimmed.uppercase()}" else "#FFFFFF"
}

/** Pure - must not touch MaterialTheme, as it is called outside composition. */
private fun parseColor(value: String): Color {
	return try {
		val intVal = value.toIntOrNull()
		if (intVal != null) {
			Color(intVal)
		} else {
			val v = value.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
			when (v.length) {
				6 -> Color(0xFF000000 or v.toLong(16))
				8 -> Color(v.toLong(16))
				else -> Color.Gray
			}
		}
	} catch (_: Throwable) {
		Color.Gray
	}
}

// ------------------------------------------------------------------ time

@Composable
private fun TimeItem(node: TimePref, store: PrefStore) {
	// Keep `enabled` live across a write - see enabledBy.
	val revision by store.revision.collectAsState()
	@Suppress("UNUSED_VARIABLE") val ignored = revision
	val enabled = enabledBy(store, node)
	var open by remember { mutableStateOf(false) }
	val minutes = (store.read(node) ?: node.default ?: "0").toIntOrNull() ?: 0

	ListItem(
		leadingContent = leadingIcon(node.icon),
		headlineContent = { Text(titleText(node)) },
		supportingContent = {
			Column {
				if (node.summary != 0) Text(stringResource(node.summary))
				Text(
					text = "%02d:%02d".format(minutes / 60, minutes % 60),
					color = MaterialTheme.colorScheme.primary,
					style = MaterialTheme.typography.bodySmall,
				)
			}
		},
		modifier = Modifier
			.fillMaxWidth()
			.dimUnless(enabled)
			.clickable(enabled = enabled) { open = true },
	)

	if (open) {
		val state = rememberTimePickerState(
			initialHour = minutes / 60,
			initialMinute = minutes % 60,
			is24Hour = true,
		)
		AlertDialog(
			onDismissRequest = { open = false },
			title = { Text(titleText(node)) },
			text = { TimePicker(state = state) },
			confirmButton = {
				TextButton(onClick = {
					store.write(node, (state.hour * 60 + state.minute).toString())
					open = false
				}) { Text(stringResource(android.R.string.ok)) }
			},
			dismissButton = {
				TextButton(onClick = { open = false }) {
					Text(stringResource(android.R.string.cancel))
				}
			},
		)
	}
}
