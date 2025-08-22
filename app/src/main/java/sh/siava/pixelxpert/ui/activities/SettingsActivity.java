package sh.siava.pixelxpert.ui.activities;

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static sh.siava.pixelxpert.R.string.update_channel_name;
import static sh.siava.pixelxpert.ui.Constants.UPDATES_CHANNEL_ID;
import static sh.siava.pixelxpert.utils.AppUtils.isLikelyPixelBuild;
import static sh.siava.pixelxpert.utils.MiscUtils.REQUEST_EXPORT;
import static sh.siava.pixelxpert.utils.MiscUtils.REQUEST_IMPORT;
import static sh.siava.pixelxpert.utils.MiscUtils.weakVibrate;
import static sh.siava.pixelxpert.utils.NavigationExtensionKt.navigateTo;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Outline;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.navigation.NavController;
import androidx.navigation.NavGraph;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.PixelXpert;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.databinding.SettingsActivityBinding;
import sh.siava.pixelxpert.service.tileServices.SleepOnSurfaceTileService;
import sh.siava.pixelxpert.ui.fragments.HeaderFragment;
import sh.siava.pixelxpert.ui.fragments.UpdateFragment;
import sh.siava.pixelxpert.ui.misc.StateManager;
import sh.siava.pixelxpert.ui.preferences.preferencesearch.SearchPreferenceResult;
import sh.siava.pixelxpert.ui.preferences.preferencesearch.SearchPreferenceResultListener;
import sh.siava.pixelxpert.utils.AppUtils;
import sh.siava.pixelxpert.utils.DisplayUtils;
import sh.siava.pixelxpert.utils.PXPreferences;
import sh.siava.pixelxpert.utils.PrefManager;
import sh.siava.pixelxpert.utils.PreferenceHelper;
import sh.siava.pixelxpert.xposed.modpacks.android.TargetOptimizer;

@AndroidEntryPoint
public class SettingsActivity extends BaseActivity implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback, SearchPreferenceResultListener {

	private SettingsActivityBinding binding;
	private HeaderFragment headerFragment;
	private NavController navControllerMain;
	private NavController navControllerDetails;
	private final boolean isTabletDevice = DisplayUtils.isTablet();

	@Inject
	StateManager stateManager;

	private final BroadcastReceiver updateCheckReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			int latestVersionCode = intent.getIntExtra("latestVersionCode", BuildConfig.VERSION_CODE);
			handleUpdateBadge(latestVersionCode);
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		binding = SettingsActivityBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());

		createNotificationChannel();
		setupNavigation(savedInstanceState);

		PreferenceHelper.init();

		if (getIntent() != null) {
			if (getIntent().getBooleanExtra("updateTapped", false)) {
				Intent intent = getIntent();
				Bundle bundle = new Bundle();
				bundle.putBoolean("updateTapped", intent.getBooleanExtra("updateTapped", false));
				bundle.putString("filePath", intent.getStringExtra("filePath"));
				UpdateFragment updateFragment = new UpdateFragment();
				updateFragment.setArguments(bundle);
				navigateTo(navControllerMain, R.id.updateFragment, bundle);
			} else if ("true".equals(getIntent().getStringExtra("migratePrefs"))) {
				Intent intent = getIntent();
				Bundle bundle = new Bundle();
				bundle.putString("migratePrefs", intent.getStringExtra("migratePrefs"));
				UpdateFragment updateFragment = new UpdateFragment();
				updateFragment.setArguments(bundle);
				navigateTo(navControllerMain, R.id.updateFragment, bundle);
			} else if (getIntent().getBooleanExtra("newUpdate", false)) {
				navigateTo(navControllerMain, R.id.updateFragment);
			} else if (getIntent().hasExtra(Intent.EXTRA_COMPONENT_NAME)) {
				ComponentName callerComponentName = getIntent().getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName.class);
				if(callerComponentName != null) {
					String callerClassName = callerComponentName.getClassName();
					if (SleepOnSurfaceTileService.class.getName().equals(callerClassName)) {
						NavController navController = isTabletDevice ? navControllerDetails : navControllerMain;
						navigateTo(navController, R.id.sleepOnFlatFragment);
					}
				}
			}
		}

		if (PXPreferences.getBoolean(TargetOptimizer.SYSTEM_RESTART_PENDING_KEY, false)) {
			new MaterialAlertDialogBuilder(this, R.style.MaterialComponents_MaterialAlertDialog)
					.setTitle(R.string.optimization_restart_needed_title)
					.setMessage(R.string.optimization_restart_needed_message)
					.setPositiveButton(R.string.restart_now, (dialog, which) -> AppUtils.restart("system"))
					.setNegativeButton(R.string.restart_postpone, (dialog, which) -> dialog.dismiss())
					.show();
		}

		//noinspection ConstantValue
		if (!isLikelyPixelBuild() && !BuildConfig.VERSION_NAME.contains("canary")) {
			new MaterialAlertDialogBuilder(this, R.style.MaterialComponents_MaterialAlertDialog)
					.setTitle(R.string.incompatible_alert_title)
					.setMessage(R.string.incompatible_alert_body)
					.setPositiveButton(R.string.incompatible_alert_ok_btn, (dialog, which) -> dialog.dismiss())
					.show();
		}

		observeRestartFlag();
		setupFloatingActionButtons();
	}

	private void observeRestartFlag() {
		AtomicBoolean requiresSystemUiRestart = new AtomicBoolean(Boolean.TRUE.equals(stateManager.getRequiresSystemUIRestart().getValue()));
		AtomicBoolean requiresDeviceRestart = new AtomicBoolean(Boolean.TRUE.equals(stateManager.getRequiresDeviceRestart().getValue()));

		stateManager.getRequiresSystemUIRestart().observe(this, isRequired -> {
			requiresSystemUiRestart.set(isRequired);
			showOrHidePendingActionButton(binding, requiresSystemUiRestart.get(), requiresDeviceRestart.get());
		});

		stateManager.getRequiresDeviceRestart().observe(this, isRequired -> {
			requiresDeviceRestart.set(isRequired);
			showOrHidePendingActionButton(binding, requiresSystemUiRestart.get(), requiresDeviceRestart.get());
		});
	}

	private void setupFloatingActionButtons() {
		// Initially hide all FABs
		binding.hideAll.hide();
		binding.hideAll.shrink();
		binding.restartSystemui.hide();
		binding.restartSystemui.shrink();
		binding.restartDevice.hide();
		binding.restartDevice.shrink();
		binding.pendingActions.shrink();

		// Show or hide the main pending actions FAB based on Dynamic flags
		showOrHidePendingActionButton(
				binding,
				Boolean.TRUE.equals(stateManager.getRequiresSystemUIRestart().getValue()),
				Boolean.TRUE.equals(stateManager.getRequiresDeviceRestart().getValue())
		);

		// Pending Action FAB clicked
		binding.pendingActions.setOnClickListener(v -> {
			weakVibrate(binding.pendingActions);
			showOrHideFabButtons();
		});

		// Hide All FAB clicked
		binding.hideAll.setOnClickListener(v -> {
			weakVibrate(binding.hideAll);
			stateManager.setRequiresSystemUIRestart(false);
			stateManager.setRequiresDeviceRestart(false);
		});

		// Restart System UI FAB clicked
		binding.restartSystemui.setOnClickListener(v -> {
			weakVibrate(binding.restartSystemui);
			stateManager.setRequiresSystemUIRestart(false);

			new Handler(Looper.getMainLooper()).postDelayed(() -> AppUtils.restart("systemui"), 500);
		});

		// Restart Device FAB clicked
		binding.restartDevice.setOnClickListener(v -> {
			weakVibrate(binding.restartDevice);
			stateManager.setRequiresDeviceRestart(false);

			new Handler(Looper.getMainLooper()).postDelayed(() -> AppUtils.restart("system"), 500);
		});
	}

	private void showOrHideFabButtons() {
		try {
			Handler handler = new Handler(Looper.getMainLooper());
			boolean requiresSystemUIRestart = Boolean.TRUE.equals(stateManager.getRequiresSystemUIRestart().getValue());
			boolean requiresDeviceRestart = Boolean.TRUE.equals(stateManager.getRequiresDeviceRestart().getValue());
			boolean isPendingExtended = binding.pendingActions.isExtended();

			// Delay between each FAB animation
			int delay = 40;
			int[] currentDelay = {0};

			if (!isPendingExtended) {
				// Extend pending FAB
				binding.pendingActions.extend();
				updateFabCornerRadius(binding);

				// Show restartSystemui if required
				if (requiresSystemUIRestart) {
					handler.postDelayed(() -> binding.restartSystemui.show(), currentDelay[0] += delay);
					handler.postDelayed(() -> binding.restartSystemui.extend(), currentDelay[0] += delay);
				}

				// Show restartDevice if required
				if (requiresDeviceRestart) {
					handler.postDelayed(() -> binding.restartDevice.show(), currentDelay[0] += delay);
					handler.postDelayed(() -> binding.restartDevice.extend(), currentDelay[0] += delay);
				}

				// Show hideAll always
				handler.postDelayed(() -> binding.hideAll.show(), currentDelay[0] += delay);
				handler.postDelayed(() -> binding.hideAll.extend(), currentDelay[0] += delay);
			} else {
				// Shrink and hide in reverse order
				handler.postDelayed(() -> binding.hideAll.shrink(), currentDelay[0] += delay);
				handler.postDelayed(() -> binding.hideAll.hide(), currentDelay[0] += delay);

				if (requiresDeviceRestart) {
					handler.postDelayed(() -> binding.restartDevice.shrink(), currentDelay[0] += delay);
					handler.postDelayed(() -> binding.restartDevice.hide(), currentDelay[0] += delay);
				}

				if (requiresSystemUIRestart) {
					handler.postDelayed(() -> binding.restartSystemui.shrink(), currentDelay[0] += delay);
					handler.postDelayed(() -> binding.restartSystemui.hide(), currentDelay[0] += delay);
				}

				// Finally shrink pending FAB
				handler.postDelayed(() -> {
					binding.pendingActions.shrink();
					updateFabCornerRadius(binding);
				}, currentDelay[0] += delay);
			}
		} catch (Exception ignored) {
		}
	}

	public static void showOrHidePendingActionButton(
			SettingsActivityBinding binding,
			boolean requiresSystemUiRestart,
			boolean requiresDeviceRestart
	) {
		try {
			if (!requiresSystemUiRestart && !requiresDeviceRestart) {
				binding.hideAll.hide();
				binding.restartSystemui.hide();
				binding.restartDevice.hide();
				binding.pendingActions.hide();
				binding.pendingActions.shrink();
				updateFabCornerRadius(binding);
			} else {
				// Restart System UI button visibility logic
				if (binding.hideAll.isShown() && requiresSystemUiRestart && !binding.restartSystemui.isShown()) {
					binding.restartSystemui.show();
				} else if (!requiresSystemUiRestart && binding.restartSystemui.isShown()) {
					binding.restartSystemui.hide();
				}

				// Restart Device button visibility logic
				if (binding.hideAll.isShown() && requiresDeviceRestart && !binding.restartDevice.isShown()) {
					binding.restartDevice.show();
				} else if (!requiresDeviceRestart && binding.restartDevice.isShown()) {
					binding.restartDevice.hide();
				}

				// Shrink or extend main FAB
				if (!binding.hideAll.isShown()) {
					binding.pendingActions.shrink();
				} else {
					binding.pendingActions.extend();
				}

				binding.pendingActions.show();
			}
		} catch (Exception ignored) {
		}
	}

	private static void updateFabCornerRadius(SettingsActivityBinding binding) {
		float expandedRadius = binding.getRoot().getContext().getResources().getDimension(R.dimen.fab_expanded_corner_radius);
		float collapsedRadius = binding.getRoot().getContext().getResources().getDimension(R.dimen.fab_collapsed_corner_radius);

		if (binding.pendingActions.isExtended()) {
			animateCornerRadius(binding.pendingActions, collapsedRadius, expandedRadius);
		} else {
			animateCornerRadius(binding.pendingActions, expandedRadius, collapsedRadius);
		}
	}

	private static void animateCornerRadius(View fab, float from, float to) {
		ValueAnimator animator = ValueAnimator.ofFloat(from, to);
		animator.setDuration(300);
		animator.addUpdateListener(animation -> {
			float radius = (float) animation.getAnimatedValue();
			fab.setClipToOutline(true);
			fab.setOutlineProvider(new ViewOutlineProvider() {
				@Override
				public void getOutline(View view, Outline outline) {
					outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
				}
			});
			fab.invalidateOutline();
		});
		animator.start();
	}

	@SuppressLint({"RestrictedApi", "NonConstantResourceId"})
	private void setupNavigation(Bundle savedInstanceState) {
		NavHostFragment navHostFragmentMain = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.mainFragmentContainerView);
		navControllerMain = Objects.requireNonNull(navHostFragmentMain).getNavController();

		NavHostFragment navHostFragmentDetails = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.detailFragmentContainerView);
		navControllerDetails = Objects.requireNonNull(navHostFragmentDetails).getNavController();

		NavGraph navGraphMain = navControllerMain.getNavInflater().inflate(isTabletDevice ? R.navigation.nav_graph_tablet_main : R.navigation.nav_graph_phone);
		navControllerMain.setGraph(navGraphMain, savedInstanceState);

		binding.detailFragmentContainerView.setVisibility(isTabletDevice ? View.VISIBLE : View.GONE);

		if (isTabletDevice) {
			binding.bottomNavigationView.setVisibility(View.GONE);
			binding.navigationRailView.setVisibility(View.VISIBLE);
			binding.navigationRailView.setOnItemSelectedListener(this::setupOnItemSelectedListener);
			binding.navigationRailView.setOnItemReselectedListener(this::setupOnItemReselectedListener);
			NavigationUI.setupWithNavController(binding.navigationRailView, navControllerMain);
		} else {
			binding.navigationRailView.setVisibility(View.GONE);
			binding.bottomNavigationView.setVisibility(View.VISIBLE);
			binding.bottomNavigationView.setOnItemSelectedListener(this::setupOnItemSelectedListener);
			binding.bottomNavigationView.setOnItemReselectedListener(this::setupOnItemReselectedListener);
			NavigationUI.setupWithNavController(binding.bottomNavigationView, navControllerMain);
		}

		ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, windowInsets) -> {
			Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
			boolean isRtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

			if (insets.left > 0 || insets.right > 0) {
				int startInset = isRtl ? insets.right : insets.left;

				((ViewGroup) binding.navigationRailView.getParent()).setPaddingRelative(
						startInset + binding.navigationRailView.getPaddingStart(),
						0, 0, 0
				);
			}

			return windowInsets;
		});
	}

	private boolean setupOnItemSelectedListener(MenuItem item) {
		if (item.getItemId() == R.id.headerFragment) {
			return navControllerMain.popBackStack(R.id.headerFragment, false);
		} else if (item.getItemId() == R.id.updateFragment) {
			navControllerMain.popBackStack(R.id.headerFragment, false);
			return navigateTo(navControllerMain, R.id.updateFragment);
		} else if (item.getItemId() == R.id.hooksFragment) {
			navControllerMain.popBackStack(R.id.headerFragment, false);
			return navigateTo(navControllerMain, R.id.hooksFragment);
		} else if (item.getItemId() == R.id.ownPrefsFragment) {
			navControllerMain.popBackStack(R.id.headerFragment, false);
			return navigateTo(navControllerMain, R.id.ownPrefsFragment);
		}
		return false;
	}

	private void setupOnItemReselectedListener(MenuItem item) {
		if (item.getItemId() == R.id.headerFragment) {
			navControllerMain.popBackStack(R.id.headerFragment, false);
		} else if (item.getItemId() == R.id.updateFragment) {
			navControllerMain.popBackStack(R.id.updateFragment, false);
		} else if (item.getItemId() == R.id.hooksFragment) {
			navControllerMain.popBackStack(R.id.hooksFragment, false);
		} else if (item.getItemId() == R.id.ownPrefsFragment) {
			navControllerMain.popBackStack(R.id.ownPrefsFragment, false);
		}
	}

	@Override
	public void onSearchResultClicked(@NonNull final SearchPreferenceResult result, NavController navController) {
		headerFragment = new HeaderFragment();
		NavController myNavController = isTabletDevice ? navControllerDetails : navController;
		new Handler(getMainLooper()).post(() -> headerFragment.onSearchResultClicked(result, myNavController, this));
	}

	private void createNotificationChannel() {
		NotificationManager notificationManager = getSystemService(NotificationManager.class);

		notificationManager.createNotificationChannel(new NotificationChannel(UPDATES_CHANNEL_ID, getString(update_channel_name), IMPORTANCE_DEFAULT));
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		if (data == null) return; //user hit cancel. Nothing to do

		SharedPreferences prefs = PixelXpert.get().getDefaultPreferences();
		switch (requestCode) {
			case REQUEST_IMPORT:
				try {
					PixelXpert.get().setPrefsValidity(false);
					PrefManager.importPath(prefs, getContentResolver().openInputStream(data.getData()));
					PixelXpert.get().initiatePreferences(false);

					AppUtils.restart("systemui");
					recreate();
				} catch (Exception ignored) {
				}
				break;
			case REQUEST_EXPORT:
				try {
					//noinspection DataFlowIssue
					PrefManager.exportPrefs(prefs, getContentResolver().openOutputStream(data.getData()));
				} catch (Exception ignored) {
				}
				break;
		}
	}

	@Override
	public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller, @NonNull Preference pref) {
		String key = pref.getKey();
		if (key == null) return false;

		NavController navController = isTabletDevice ? navControllerDetails : navControllerMain;

		return switch (key) {
			case "quicksettings_header" -> {
				if (isTabletDevice) navController.popBackStack(R.id.headerFragment, false);
				yield navigateTo(navController, R.id.action_headerFragment_to_quickSettingsFragment);
			}
			case "lockscreen_header" -> {
				if (isTabletDevice) navController.popBackStack(R.id.headerFragment, false);
				yield navigateTo(navController, R.id.action_headerFragment_to_lockScreenFragment);
			}
			case "theming_header" -> {
				if (isTabletDevice) navController.popBackStack(R.id.headerFragment, false);
				yield navigateTo(navController, R.id.action_headerFragment_to_themingFragment);
			}
			case "statusbar_header" -> {
				if (isTabletDevice) navController.popBackStack(R.id.headerFragment, false);
				yield navigateTo(navController, R.id.action_headerFragment_to_statusbarFragment);
			}
			case "nav_header" -> {
				if (isTabletDevice) navController.popBackStack(R.id.headerFragment, false);
				yield navigateTo(navController, R.id.action_headerFragment_to_navFragment);
			}
			case "dialer_header" -> {
				if (isTabletDevice) navController.popBackStack(R.id.headerFragment, false);
				yield navigateTo(navController, R.id.action_headerFragment_to_dialerFragment);
			}
			case "hotspot_header" -> {
				if (isTabletDevice) navController.popBackStack(R.id.headerFragment, false);
				yield navigateTo(navController, R.id.action_headerFragment_to_hotSpotFragment);
			}
			case "pm_header" -> {
				if (isTabletDevice) navController.popBackStack(R.id.headerFragment, false);
				yield navigateTo(navController, R.id.action_headerFragment_to_packageManagerFragment);
			}
			case "misc_header" -> {
				if (isTabletDevice) navController.popBackStack(R.id.headerFragment, false);
				yield navigateTo(navController, R.id.action_headerFragment_to_miscFragment);
			}
			case "CheckForUpdate" -> {
				if (isTabletDevice) {
					binding.navigationRailView.setSelectedItemId(R.id.updateFragment);
				} else {
					binding.bottomNavigationView.setSelectedItemId(R.id.updateFragment);
				}
				yield true;
			}
			case "qs_tile_qty" ->
					navigateTo(navController, R.id.action_quickSettingsFragment_to_QSTileQtyFragment);
			case "network_settings_header_qs" ->
					navigateTo(navController, R.id.action_quickSettingsFragment_to_networkFragment);
			case "sbc_header" ->
					navigateTo(navController, R.id.action_statusbarFragment_to_SBCFragment);
			case "BBarEnabled" ->
					navigateTo(navController, R.id.action_statusbarFragment_to_SBBBFragment);
			case "network_settings_header" ->
					navigateTo(navController, R.id.action_statusbarFragment_to_networkFragment);
			case "threebutton_header" ->
					navigateTo(navController, R.id.action_navFragment_to_threeButtonNavFragment);
			case "taskbar_header" ->
					navigateTo(navController, R.id.action_navFragment_to_taskbarNavFragment);
			case "gesturenav_header" ->
					navigateTo(navController, R.id.action_navFragment_to_gestureNavFragment);
			case "remap_physical_buttons" ->
					navigateTo(navController, R.id.action_miscFragment_to_physicalButtonRemapFragment);
			case "netstat_header" ->
					navigateTo(navController, R.id.action_miscFragment_to_networkStatFragment);
			case "SleepOnFlatScreen" ->
					navigateTo(navController, R.id.action_miscFragment_to_sleepOnFlatFragment);
			case "icon_packs" ->
					navigateTo(navController, R.id.action_themingFragment_to_iconPackFragment);
			default -> false;
		};
	}

	private void handleUpdateBadge(int latestVersionCode) {
		if (latestVersionCode > BuildConfig.VERSION_CODE) {
			BadgeDrawable badge;
			if (isTabletDevice) {
				badge = binding.navigationRailView.getOrCreateBadge(R.id.updateFragment);
			} else {
				badge = binding.bottomNavigationView.getOrCreateBadge(R.id.updateFragment);
			}
			badge.setVisible(true);
		} else {
			if (isTabletDevice) {
				binding.navigationRailView.removeBadge(R.id.updateFragment);
			} else {
				binding.bottomNavigationView.removeBadge(R.id.updateFragment);
			}
		}
	}

	@Override
	protected void onNewIntent(@NonNull Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
	}

	@SuppressWarnings("deprecation")
	@Override
	protected void onResume() {
		super.onResume();
		LocalBroadcastManager.getInstance(this).registerReceiver(
				updateCheckReceiver,
				new IntentFilter(BuildConfig.APPLICATION_ID + ".UPDATE_CHECK")
		);
		handleUpdateBadge(PXPreferences.getInt("latestVersionCode", -1));
	}

	@SuppressWarnings("deprecation")
	@Override
	protected void onPause() {
		super.onPause();
		LocalBroadcastManager.getInstance(this).unregisterReceiver(updateCheckReceiver);
	}
}
