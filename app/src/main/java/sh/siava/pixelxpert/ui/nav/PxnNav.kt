package sh.siava.pixelxpert.ui.nav

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.data.PrefStore
import sh.siava.pixelxpert.theme.LocalPxnMotionScheme
import sh.siava.pixelxpert.theme.PxnMotionScheme
import sh.siava.pixelxpert.ui.debug.HookAuditScreen
import sh.siava.pixelxpert.ui.prefs.PrefScreenContent
import sh.siava.pixelxpert.ui.prefs.PullDownIndicator
import sh.siava.pixelxpert.ui.screens.header_preferences
import sh.siava.pixelxpert.ui.screens.own_prefs_header
import sh.siava.pixelxpert.ui.screens.preferenceScreens
import sh.siava.pixelxpert.ui.screens.routeToScreenKey
import sh.siava.pixelxpert.ui.hooks.HooksScreen
import sh.siava.pixelxpert.ui.iconpack.IconPackScreen
import sh.siava.pixelxpert.ui.search.SearchScreen
import sh.siava.pixelxpert.ui.update.UpdateScreen
import sh.siava.pixelxpert.util.Restarter

/**
 * Navigation3 backstack keys.
 *
 * A preference screen is addressed by its preference-XML basename. When a row
 * navigates it carries both the route id and its own title, and that title
 * becomes the destination's app bar heading, so the row that opened a screen
 * also names it.
 */
sealed interface Route : NavKey {
	@Serializable
	data object Home : Route

	@Serializable
	data class Pref(val screenKey: String, val title: Int) : Route

	@Serializable
	data class Unported(val route: String, val title: Int) : Route

	@Serializable
	data object Search : Route

	/**
	 * A dedicated key rather than a [Pref] because the screen has no
	 * preference XML of its own: it is two tabs of list over `IconPackUtil`,
	 * written by hand as `ui/iconpack/IconPackScreen.kt`.
	 */
	@Serializable
	data object IconPack : Route

	/**
	 * Bottom-navigation tabs. Distinct keys rather than [Unported] so each can
	 * later be attached to its own screen without changing tab addressing.
	 *
	 * Each is a top-level destination: selecting a tab pops back to Home
	 * before pushing the tab's own key.
	 */
	@Serializable
	data object Update : Route // updateFragment

	@Serializable
	data object Hooks : Route // hooksFragment

	@Serializable
	data object Settings : Route // ownPrefsFragment

	@Serializable
	data object HookAudit : Route
}

/**
 * One bottom-navigation tab.
 *
 * Carries both icon vectors rather than one glyph plus a tint: the
 * `ic_nav_*` art already encodes selection through `fillAlpha` (0.77 selected,
 * 0.27 unselected), so checked and unchecked states each need their own
 * vector.
 */
private data class TabSpec(
	val key: Route,
	val title: Int,
	val selectedIcon: Int,
	val unselectedIcon: Int,
)

/**
 * Routes whose screen is hand-written rather than generated from a preference
 * XML, so `routeToScreenKey` cannot name them.
 */
private val bespokeRoutes: Map<String, NavKey> = mapOf(
	"icon_pack" to Route.IconPack,
	// Settings' "Check for updates" row points at the update screen the
	// bottom nav also reaches - and there is no `update_prefs` preference XML
	// for routeToScreenKey to map to, so without this entry it falls through
	// to Route.Unported and renders as a missing screen.
	"update" to Route.Update,
)

/** Bottom-bar tab order: Home, Update, Hooks, Settings. */
private val tabs = listOf(
	TabSpec(
		key = Route.Home,
		title = R.string.navigation_home,
		selectedIcon = R.drawable.ic_nav_home_checked,
		unselectedIcon = R.drawable.ic_nav_home_unchecked,
	),
	TabSpec(
		key = Route.Update,
		title = R.string.navigation_update,
		selectedIcon = R.drawable.ic_nav_update_checked,
		unselectedIcon = R.drawable.ic_nav_update_unchecked,
	),
	TabSpec(
		key = Route.Hooks,
		title = R.string.navigation_hooks,
		selectedIcon = R.drawable.ic_nav_hooks_checked,
		unselectedIcon = R.drawable.ic_nav_hooks_unchecked,
	),
	TabSpec(
		key = Route.Settings,
		title = R.string.navigation_settings,
		selectedIcon = R.drawable.ic_nav_settings_checked,
		unselectedIcon = R.drawable.ic_nav_settings_unchecked,
	),
)

/**
 * Export, import and reset defaults as a single flat list of three rows with
 * no nesting to hand-roll: the reboot group has its own [RestartMenu] action,
 * so there is nothing left to nest. That matters because material3 1.4.0
 * ships no submenu API at all (1367 classes in the AAR, none containing
 * "Submenu"), so a real nested panel would mean untested popup-inside-popup
 * layout.
 *
 * No group heading above the rows: with a single group left it repeats what
 * the menu already implies.
 */
@Composable
private fun OverflowMenu(store: PrefStore) {
	var expanded by remember { mutableStateOf(false) }

	val exportLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.CreateDocument("*/*"),
	) { uri ->
		if (uri != null) store.exportTo(uri)
	}

	val importLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		// SystemUI is restarted after an import so the hooks pick the new
		// values up from RemotePreferences.
		if (uri != null && store.importFrom(uri)) Restarter.restartSystemUI()
	}

	IconButton(onClick = { expanded = true }) {
		Icon(
			imageVector = Icons.Filled.MoreVert,
			contentDescription = stringResource(R.string.menu_settings_title),
		)
	}

	DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
		DropdownMenuItem(
			text = { Text(stringResource(R.string.menu_export_settings)) },
			onClick = {
				expanded = false
				exportLauncher.launch(EXPORT_FILE_NAME)
			},
		)
		DropdownMenuItem(
			text = { Text(stringResource(R.string.menu_import_settings)) },
			onClick = {
				expanded = false
				importLauncher.launch(OPEN_MIME_TYPES)
			},
		)
		DropdownMenuItem(
			text = { Text(stringResource(R.string.menu_reset_defaults)) },
			onClick = {
				expanded = false
				store.resetAll()
				Restarter.restartSystemUI()
			},
		)
	}
}

/**
 * The reboot group, promoted to its own top-bar action instead of sitting
 * inside the overflow.
 *
 * It is drawn with a refresh glyph: all three entries are "run it again"
 * operations, and a dedicated icon puts them one tap away rather than two.
 */
@Composable
private fun RestartMenu() {
	var expanded by remember { mutableStateOf(false) }

	IconButton(onClick = { expanded = true }) {
		Icon(
			imageVector = Icons.Filled.Refresh,
			contentDescription = stringResource(R.string.menu_restart),
		)
	}

	DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
		DropdownMenuItem(
			text = { Text(stringResource(R.string.menu_restartSysUI)) },
			onClick = {
				expanded = false
				Restarter.restartSystemUI()
			},
		)
		DropdownMenuItem(
			text = { Text(stringResource(R.string.menu_soft_restart)) },
			onClick = {
				expanded = false
				Restarter.softRestart()
			},
		)
		DropdownMenuItem(
			text = { Text(stringResource(R.string.menu_restart_system)) },
			onClick = {
				expanded = false
				Restarter.restartSystem()
			},
		)
	}
}

/**
 * Initial file name for the export picker, so new exports land beside the
 * ones already on device.
 */
private const val EXPORT_FILE_NAME = "PixelXpert_Config.bin"

/** The import picker accepts any MIME type - the type exports are created with. */
private val OPEN_MIME_TYPES = arrayOf("*/*")

/**
 * Navigation3 host for all 21 preference screens plus the four nav tabs.
 *
 * Uses `NavDisplay` + `rememberNavBackStack` from navigation3-runtime/ui
 * rather than Nav2's NavHost. Navigation is driven by
 * [PrefScreenContent]'s `onNavigate` callback, which resolves the route id
 * through the generated `routeToScreenKey` map.
 *
 * [startRoute] is a destination requested by an incoming intent - the update
 * notification, the Magisk install callback and the "new update" broadcast
 * all arrive at the Activity and have to reach the tab they are about. Every
 * one of them is a top-level tab, so it is applied as a tab selection: the
 * stack is cleared back to Home before the tab key is pushed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PxnNav(startRoute: Route? = null) {
	val store: PrefStore = koinInject()
	// rememberNavBackStack is NOT generic: it returns NavBackStack<NavKey>.
	val backStack = rememberNavBackStack(Route.Home)

	// Destination movement comes from the theme's motion scheme
	val motion = LocalPxnMotionScheme.current
	val slideDistance = with(LocalDensity.current) { SharedAxisSlideDistance.roundToPx() }

	fun pushTarget(target: NavKey) {
		if (backStack.lastOrNull() != target) backStack.add(target)
	}

	fun push(route: String, title: Int) {
		// Screens written by hand rather than generated from a preference XML
		// have no key in `routeToScreenKey`, so without this they would fall
		// through to Unported and render as missing.
		val bespoke = bespokeRoutes[route]
		if (bespoke != null) {
			pushTarget(bespoke)
			return
		}
		val screenKey = routeToScreenKey[route]
		pushTarget(
			if (screenKey != null) Route.Pref(screenKey, title)
			else Route.Unported(route, title)
		)
	}

	fun pop() {
		if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
	}

	fun selectTab(spec: TabSpec) {
		backStack.clear()
		backStack.add(Route.Home)
		if (spec.key != Route.Home) backStack.add(spec.key)
	}

	LaunchedEffect(startRoute) {
		val route = startRoute ?: return@LaunchedEffect
		backStack.clear()
		backStack.add(Route.Home)
		if (route != Route.Home) backStack.add(route)
	}

	fun reselectTab(spec: TabSpec) {
		val index = backStack.indexOf(spec.key)
		if (index < 0) return
		while (backStack.size > index + 1) backStack.removeAt(backStack.lastIndex)
	}

	val tabKeys = tabs.mapTo(mutableSetOf()) { it.key }
	// Most recently visited tab still on the stack; a tab's own child screens
	// are not tabs, so scanning from the top leaves the owning tab selected.
	val selectedTab = backStack.lastOrNull { it in tabKeys } ?: Route.Home

	// The Update tab is badged whenever a build newer than this one has been
	// advertised via `latestVersionCode`. The value is read through
	// `revision`, which bumps on every store write, so the badge recomputes
	// when that key changes.
	val revision by store.revision.collectAsState()
	val updateAvailable = remember(revision) {
		store.getInt("latestVersionCode", -1) > BuildConfig.VERSION_CODE
	}

	var settingsTapCount by remember { mutableIntStateOf(0) }
	var lastSettingsTapTime by remember { mutableLongStateOf(0L) }

	val heading = when (val current = backStack.lastOrNull()) {
		is Route.Pref -> current.title
		is Route.Unported -> current.title
		is Route.Search -> R.string.searchpreference_title
		is Route.IconPack -> R.string.icon_packs_title
		is Route.Update -> R.string.navigation_update
		is Route.Hooks -> R.string.navigation_hooks
		is Route.Settings -> R.string.navigation_settings
		is Route.HookAudit -> R.string.hook_audit_title
		else -> 0
	}

	Scaffold(
		topBar = {
			// The pull-down reach has to hang off the window rather than off
			// the list: drawing it inside the screen would put it over the
			// first row instead of over the edge the gesture actually starts
			// from.
			Box(Modifier.fillMaxWidth()) {
				TopAppBar(
					title = {
						Text(
							text = if (heading != 0) stringResource(heading)
							else stringResource(R.string.app_name),
						)
					},
					navigationIcon = {
						if (backStack.size > 1) {
							IconButton(onClick = ::pop) {
								Icon(
									imageVector = Icons.AutoMirrored.Filled.ArrowBack,
									contentDescription = "Back",
								)
							}
						}
					},
					actions = {
						// Reboot action first, overflow last: the trailing
						// edge is where "more options" belongs by convention.
						RestartMenu()
						OverflowMenu(store)
					},
					colors = TopAppBarDefaults.topAppBarColors(
						containerColor = MaterialTheme.colorScheme.surface,
					),
				)

				// The reach belongs to the quick settings screen only, so it
				// is drawn only while that screen is on top.
				val onQuickSettings =
					(backStack.lastOrNull() as? Route.Pref)?.screenKey == "quicksettings_prefs"
				if (onQuickSettings) PullDownIndicator(store)
			}
		},
		bottomBar = {
			NavigationBar {
				tabs.forEach { spec ->
					val isSelected = selectedTab == spec.key
					NavigationBarItem(
						selected = isSelected,
						onClick = {
							if (spec.key == Route.Settings) {
								val now = System.currentTimeMillis()
								if (now - lastSettingsTapTime > 3000L) {
									settingsTapCount = 1
								} else {
									settingsTapCount++
								}
								lastSettingsTapTime = now
								if (settingsTapCount >= 5) {
									settingsTapCount = 0
									pushTarget(Route.HookAudit)
								} else {
									if (isSelected) reselectTab(spec) else selectTab(spec)
								}
							} else {
								settingsTapCount = 0
								if (isSelected) reselectTab(spec) else selectTab(spec)
							}
						},
						icon = {
							val tabIcon = Icon(
								painter = painterResource(
									if (isSelected) spec.selectedIcon
									else spec.unselectedIcon
								),
								contentDescription = null,
							)
							if (spec.key == Route.Update && updateAvailable) {
								BadgedBox(badge = { Badge() }) { tabIcon }
							} else {
								tabIcon
							}
						},
						label = { Text(stringResource(spec.title)) },
					)
				}
			}
		},
	) { innerPadding ->
		NavDisplay(
			backStack = backStack,
			modifier = Modifier.padding(innerPadding),
			onBack = ::pop,
			transitionSpec = { motion.sharedAxisX(forward = true, slideDistance = slideDistance) },
			popTransitionSpec = { motion.sharedAxisX(forward = false, slideDistance = slideDistance) },
			// The gesture sets the same geometry as a settled back
			predictivePopTransitionSpec = {
				motion.sharedAxisX(forward = false, slideDistance = slideDistance)
			},
			// The builder lambda takes no explicit parameter - `this` is the
			// EntryProviderScope. Dispatch happens via the reified `entry<K>`,
			// which matches on the key's runtime class.
			entryProvider = entryProvider<NavKey> {
				entry<Route.Home> {
					NavScene {
						PrefScreenContent(
							screen = header_preferences,
							store = store,
							onNavigate = ::push,
							onSearch = { pushTarget(Route.Search) },
						)
					}
				}
				entry<Route.Pref> { key ->
					val screen = preferenceScreens[key.screenKey]
					NavScene {
						if (screen != null) {
							PrefScreenContent(
								screen = screen,
								store = store,
								onNavigate = ::push,
								onSearch = { pushTarget(Route.Search) },
							)
						} else {
							MissingScreen(key.screenKey)
						}
					}
				}
				entry<Route.Unported> { key -> NavScene { MissingScreen(key.route) } }
				entry<Route.IconPack> { NavScene { IconPackScreen() } }
				entry<Route.Search> {
					NavScene {
						SearchScreen(
							store = store,
							onOpenResult = { result ->
								pushTarget(Route.Pref(result.screenKey, result.screenTitle))
							},
						)
					}
				}
				entry<Route.Update> { NavScene { UpdateScreen(store = store) } }
				entry<Route.Hooks> { NavScene { HooksScreen(store = store) } }
				entry<Route.Settings> {
					NavScene {
						PrefScreenContent(
							screen = own_prefs_header,
							store = store,
							onNavigate = ::push,
							onSearch = { pushTarget(Route.Search) },
						)
					}
				}
				entry<Route.HookAudit> { NavScene { HookAuditScreen() } }
			},
		)
	}
}

@Composable
private fun MissingScreen(id: String) {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.padding(32.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = "Not ported yet:\n$id",
			style = MaterialTheme.typography.bodyLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
	}
}

// Paints the window colour behind a destination.
@Composable
private fun NavScene(content: @Composable () -> Unit) {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background),
	) {
		content()
	}
}

// Kinetic distance a destination travels during a transition
private val SharedAxisSlideDistance = 96.dp

// Share of the transition the outgoing destination fades out over
private const val OutgoingFadeFraction = 0.35f

// Shared X-axis transition
// the two destinations cross-fade while sliding a short distance along X
private fun PxnMotionScheme.sharedAxisX(
	forward: Boolean,
	slideDistance: Int,
): ContentTransform {
	// Fading the outgoing destination over the first [OutgoingFadeFraction]
	val outgoingMillis = (spatialMillis * OutgoingFadeFraction).toInt()
	val incomingMillis = spatialMillis - outgoingMillis

	return (
		slideInHorizontally(
			animationSpec = tween(spatialMillis, easing = spatialEasing),
			initialOffsetX = { if (forward) slideDistance else -slideDistance },
		) + fadeIn(
			animationSpec = tween(incomingMillis, delayMillis = outgoingMillis, easing = effectsEasing),
		)
	) togetherWith (
		slideOutHorizontally(
			animationSpec = tween(spatialMillis, easing = spatialEasing),
			targetOffsetX = { if (forward) -slideDistance else slideDistance },
		) + fadeOut(
			animationSpec = tween(outgoingMillis, easing = effectsEasing),
		)
	)
}
