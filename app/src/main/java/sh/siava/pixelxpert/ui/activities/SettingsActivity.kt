package sh.siava.pixelxpert.ui.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.android.ext.android.inject
import sh.siava.pixelxpert.PixelXpert
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.data.PrefStore
import sh.siava.pixelxpert.ui.misc.StateManager
import sh.siava.pixelxpert.ui.nav.Screen
import sh.siava.pixelxpert.ui.prefs.node.PrefNode
import sh.siava.pixelxpert.ui.prefs.renderer.PrefNodeItem
import sh.siava.pixelxpert.ui.prefs.screens.*
import sh.siava.pixelxpert.utils.AppUtils
import sh.siava.pixelxpert.utils.PXPreferences
import sh.siava.pixelxpert.utils.PrefManager
import sh.siava.pixelxpert.xposed.modpacks.android.TargetOptimizer

class SettingsActivity : BaseActivity() {

    private val prefStore: PrefStore by inject()
    private val stateManager: StateManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PixelXpert.get().getXposedService({}, false)

        if (PXPreferences.getBoolean(TargetOptimizer.SYSTEM_RESTART_PENDING_KEY, false)) {
            MaterialAlertDialogBuilder(this, R.style.MaterialComponents_MaterialAlertDialog)
                .setTitle(R.string.optimization_restart_needed_title)
                .setMessage(R.string.optimization_restart_needed_message)
                .setPositiveButton(R.string.restart_now) { _, _ ->
                    PXPreferences.putBoolean(TargetOptimizer.SYSTEM_RESTART_PENDING_KEY, false)
                    AppUtils.restart("system")
                }
                .setNegativeButton(R.string.restart_postpone) { dialog, _ ->
                    PXPreferences.putBoolean(TargetOptimizer.SYSTEM_RESTART_PENDING_KEY, false)
                    dialog.dismiss()
                }
                .show()
        }

        setContent {
            val isDark = isSystemInDarkTheme()
            val colorScheme = if (isDark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)

            MaterialTheme(colorScheme = colorScheme) {
                PixelXpertApp(prefStore = prefStore, stateManager = stateManager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PixelXpertApp(prefStore: PrefStore, stateManager: StateManager) {
    var currentScreen by remember { mutableStateOf(Screen.Dashboard) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var googleDesign by remember {
        mutableStateOf(prefStore.prefs.getBoolean("GoogleSettingsDesign", true))
    }

    val sysUIRestartNeeded by stateManager.systemUIRestartFlow.collectAsState()
    val deviceRestartNeeded by stateManager.deviceRestartFlow.collectAsState()

    BackHandler(enabled = currentScreen != Screen.Dashboard || isSearching) {
        if (isSearching) {
            isSearching = false
            searchQuery = ""
        } else {
            currentScreen = Screen.Dashboard
        }
    }

    val allScreenNodes: Map<Screen, List<PrefNode>> = remember {
        mapOf(
            Screen.Statusbar to StatusbarPrefs.getNodes(),
            Screen.QuickSettings to QuickSettingsPrefs.getNodes(),
            Screen.LockScreen to LockScreenPrefs.getNodes(),
            Screen.Theming to ThemingPrefs.getNodes(),
            Screen.Navigation to NavigationPrefs.getNodes(),
            Screen.Misc to MiscPrefs.getNodes(),
            Screen.Network to NetworkPrefs.getNodes(),
            Screen.Dialer to DialerPrefs.getNodes(),
            Screen.About to AboutPrefs.getNodes()
        )
    }

    val context = LocalContext.current
    var overflowExpanded by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            val ok = context.contentResolver.openOutputStream(uri)?.use { out ->
                PrefManager.exportPrefs(prefStore.prefs, out)
            } ?: false
            Toast.makeText(context, if (ok) "Settings exported successfully" else "Failed to export settings", Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val ok = context.contentResolver.openInputStream(uri)?.use { stream ->
                PrefManager.importPath(prefStore.prefs, stream)
            } ?: false
            if (ok) {
                stateManager.setRequiresSystemUIRestart(true)
                Toast.makeText(context, "Settings imported! Restart SystemUI to apply.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Failed to import settings file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search settings...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = if (currentScreen == Screen.Dashboard) "PixelXpert" else currentScreen.title,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    if (currentScreen != Screen.Dashboard || isSearching) {
                        IconButton(onClick = {
                            if (isSearching) {
                                isSearching = false
                                searchQuery = ""
                            } else {
                                currentScreen = Screen.Dashboard
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (!isSearching) {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export Settings") },
                                onClick = {
                                    overflowExpanded = false
                                    exportLauncher.launch("PixelXpert_Settings_Backup")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import Settings") },
                                onClick = {
                                    overflowExpanded = false
                                    importLauncher.launch(arrayOf("*/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reset to Defaults") },
                                onClick = {
                                    overflowExpanded = false
                                    prefStore.prefs.edit().clear().commit()
                                    stateManager.setRequiresSystemUIRestart(true)
                                    Toast.makeText(context, "Settings reset to defaults!", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (googleDesign) "Standard Settings Style" else "Google Settings Style") },
                                onClick = {
                                    overflowExpanded = false
                                    val newDesign = !googleDesign
                                    googleDesign = newDesign
                                    prefStore.prefs.edit().putBoolean("GoogleSettingsDesign", newDesign).apply()
                                }
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            isSearching = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = sysUIRestartNeeded || deviceRestartNeeded,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    tonalElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (deviceRestartNeeded) "Device reboot required" else "SystemUI restart required",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Button(
                            onClick = {
                                if (deviceRestartNeeded) {
                                    AppUtils.restart("system")
                                } else {
                                    AppUtils.restart("systemui")
                                    stateManager.setRequiresSystemUIRestart(false)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text(if (deviceRestartNeeded) "Reboot" else "Restart")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isSearching) {
                val flatNodes = remember(searchQuery) {
                    allScreenNodes.values.flatten().filter {
                        it !is PrefNode.Category && (
                            it.title.contains(searchQuery, ignoreCase = true) ||
                            (it.summary?.contains(searchQuery, ignoreCase = true) == true)
                        )
                    }
                }

                if (flatNodes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (searchQuery.isBlank()) "Type to search settings" else "No matching settings found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(flatNodes, key = { it.key }) { node ->
                            PrefNodeItem(node = node, prefStore = prefStore, stateManager = stateManager)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            } else {
                AnimatedContent(
                    targetState = currentScreen,
                    transitionSpec = {
                        if (targetState != Screen.Dashboard && initialState == Screen.Dashboard) {
                            (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> -width / 4 } + fadeOut()
                            )
                        } else if (targetState == Screen.Dashboard && initialState != Screen.Dashboard) {
                            (slideInHorizontally { width -> -width / 4 } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> width } + fadeOut()
                            )
                        } else {
                            fadeIn().togetherWith(fadeOut())
                        }
                    },
                    label = "ScreenTransition"
                ) { screen ->
                    if (screen == Screen.Dashboard) {
                        DashboardScreen(
                            googleDesign = googleDesign,
                            onNavigate = { currentScreen = it }
                        )
                    } else {
                        val nodes = allScreenNodes[screen] ?: emptyList()
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(nodes, key = { it.key }) { node ->
                                PrefNodeItem(node = node, prefStore = prefStore, stateManager = stateManager)
                            }
                        }
                    }
                }
            }
        }
    }
}

data class DashboardCategory(
    val title: String? = null,
    val accent: Long,
    val items: List<DashboardItem>
)

data class DashboardItem(
    val screen: Screen,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

@Composable
fun DashboardScreen(
    googleDesign: Boolean,
    onNavigate: (Screen) -> Unit
) {
    val categories = remember {
        listOf(
            DashboardCategory(
                accent = 0xFFA1C9FF, // Soft Sky Blue
                items = listOf(
                    DashboardItem(Screen.Statusbar, "Status Bar", "Clock, battery styles, padding & gestures", Icons.Default.SignalCellularAlt),
                    DashboardItem(Screen.QuickSettings, "Quick Settings", "Tile grid layout, text sizing & brightness slider", Icons.Default.Widgets)
                )
            ),
            DashboardCategory(
                accent = 0xFFFFAEE4, // Soft Orchid Pink
                items = listOf(
                    DashboardItem(Screen.LockScreen, "Lock Screen", "Depth wallpaper, shortcuts & biometric mods", Icons.Default.Lock),
                    DashboardItem(Screen.Theming, "Theming & Icons", "Themed icons, fonts & pitch black surfaces", Icons.Default.Palette)
                )
            ),
            DashboardCategory(
                accent = 0xFF81DA88, // Soft Mint Green
                items = listOf(
                    DashboardItem(Screen.Navigation, "Navigation", "Gesture bar pill, back triggers & 3-button mods", Icons.Default.Navigation),
                    DashboardItem(Screen.Network, "Network & Hotspot", "Hotspot auto-off timeout & fixed ADB port", Icons.Default.Wifi),
                    DashboardItem(Screen.Dialer, "Dialer & Calls", "Mute recording announcement & auto recording", Icons.Default.Call)
                )
            ),
            DashboardCategory(
                accent = 0xFFFFB683, // Soft Sunset Orange
                items = listOf(
                    DashboardItem(Screen.Misc, "Miscellaneous", "App clone enabler, high brightness torch & audio", Icons.Default.Tune),
                    DashboardItem(Screen.About, "About & System", "Version telemetry, changelog & system restart", Icons.Default.Info)
                )
            )
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(categories) { category ->
            DashboardBubbleCard(
                category = category,
                googleDesign = googleDesign,
                onNavigate = onNavigate
            )
        }
    }
}

@Composable
fun DashboardBubbleCard(
    category: DashboardCategory,
    googleDesign: Boolean,
    onNavigate: (Screen) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            category.items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 2.dp,
                        color = MaterialTheme.colorScheme.background
                    )
                }
                DashboardRow(
                    item = item,
                    accent = category.accent,
                    googleDesign = googleDesign,
                    onClick = { onNavigate(item.screen) }
                )
            }
        }
    }
}

@Composable
fun DashboardRow(
    item: DashboardItem,
    accent: Long,
    googleDesign: Boolean,
    onClick: () -> Unit
) {
    val painted = googleDesign && accent != 0L
    val circleColor = if (painted) androidx.compose.ui.graphics.Color(accent.toInt()) else MaterialTheme.colorScheme.primaryContainer
    val glyphColor = if (painted) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.onPrimaryContainer

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(circleColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = glyphColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}
