package sh.siava.pixelxpert.xposed.inspector

import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.ui.debug.model.AuditStatus
import sh.siava.pixelxpert.ui.debug.model.ModuleAuditResult
import sh.siava.pixelxpert.ui.debug.model.ProcessAuditReport
import sh.siava.pixelxpert.ui.debug.model.TargetAuditItem

/**
 * Single-pass reflection inspector for SystemUI (`com.android.systemui`).
 *
 * CRITICAL CONSTRAINT: Exactly one attempt per target. Once a check fails,
 * it records the failure and moves on. Never loops, retries, or polls.
 */
object SystemUIInspector {

	fun inspect(classLoader: ClassLoader?): ProcessAuditReport {
		val statusbarItems = mutableListOf<TargetAuditItem>()
		val qsItems = mutableListOf<TargetAuditItem>()
		val lockscreenItems = mutableListOf<TargetAuditItem>()
		val gesturesItems = mutableListOf<TargetAuditItem>()
		val powerVolumeItems = mutableListOf<TargetAuditItem>()

		// ==========================================
		// 1. STATUS BAR MODULE
		// ==========================================
		val homeBlockListBinder = InspectionUtils.checkClass(
			classLoader,
			"com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarIconBlockListBinder",
			"HomeStatusBarIconBlockListBinder (Legacy View Binder)",
		)
		statusbarItems.add(homeBlockListBinder)

		val isLegacyStatusBar = homeBlockListBinder.status == AuditStatus.PRESENT

		// ExternalSystemStatusIconRepository (Compose status bar icon repo)
		var composeIconRepo = InspectionUtils.checkClass(
			classLoader,
			"com.android.systemui.statusbar.systemstatusicons.data.repository.ExternalSystemStatusIconRepository",
			"ExternalSystemStatusIconRepository (Compose)",
			utilized = !isLegacyStatusBar,
		)
		if (composeIconRepo.status == AuditStatus.ABSENT) {
			// Older compose package path fallback
			val fallback = InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.statusbar.pipeline.icons.data.repository.ExternalSystemStatusIconRepository",
				"ExternalSystemStatusIconRepository (Pipeline path)",
				utilized = !isLegacyStatusBar,
			)
			if (fallback.status == AuditStatus.PRESENT) composeIconRepo = fallback
		}
		statusbarItems.add(composeIconRepo)

		val composeContainer = InspectionUtils.checkClass(
			classLoader,
			"com.android.systemui.statusbar.systemstatusicons.ui.compose.SystemStatusIconsKt",
			"SystemStatusIconsKt (Compose Icon Container)",
			utilized = !isLegacyStatusBar,
		)
		statusbarItems.add(composeContainer)

		statusbarItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.statusbar.CommandQueue",
				"CommandQueue (Statusbar IPC & Icon Callbacks)",
				utilized = !isLegacyStatusBar,
			),
		)

		statusbarItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.statusbar.CommandQueue",
				"setIcon",
				"CommandQueue#setIcon(int, StatusBarIcon)",
				utilized = !isLegacyStatusBar,
			),
		)

		statusbarItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.internal.statusbar.StatusBarIcon",
				"StatusBarIcon (Internal Parcelable Icon)",
				utilized = true,
			),
		)

		statusbarItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.statusbar.phone.StatusBarIconHolder",
				"StatusBarIconHolder (SystemUI Holder)",
				utilized = isLegacyStatusBar,
			),
		)

		statusbarItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.statusbar.phone.PhoneStatusBarView",
				"PhoneStatusBarView",
				utilized = true,
			),
		)

		// StatusBarIconControllerImpl (either in .phone.ui or .phone)
		var iconController = InspectionUtils.checkClass(
			classLoader,
			"com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl",
			"StatusBarIconControllerImpl (New path)",
			utilized = isLegacyStatusBar,
		)
		if (iconController.status == AuditStatus.ABSENT) {
			val legacyController = InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.statusbar.phone.StatusBarIconControllerImpl",
				"StatusBarIconControllerImpl (Legacy path)",
				utilized = isLegacyStatusBar,
			)
			if (legacyController.status == AuditStatus.PRESENT) iconController = legacyController
		}
		statusbarItems.add(iconController)

		val composeClock = InspectionUtils.checkClass(
			classLoader,
			"com.android.systemui.clock.ui.composable.ClockKt",
			"ClockKt (Compose Statusbar Clock)",
			utilized = false,
		)
		val isComposeClockPresent = composeClock.status == AuditStatus.PRESENT

		statusbarItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.statusbar.policy.Clock",
				"Clock (Statusbar Clock Policy)",
				utilized = !isComposeClockPresent,
			),
		)

		statusbarItems.add(
			if (isComposeClockPresent) {
				composeClock.copy(utilized = true)
			} else {
				composeClock
			},
		)

		statusbarItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.clock.domain.interactor.ClockInteractor",
				"getClockTextFormatString",
				"ClockInteractor#getClockTextFormatString (Clock Formatter)",
				utilized = isComposeClockPresent,
			),
		)

		// Shade header clock/date/battery tap
		val isComposeShadeHeader = InspectionUtils.resolveClass(
			classLoader,
			"com.android.systemui.shade.ui.composable.ShadeHeaderKt",
		) != null
		val isBatteryWithEstimatePresent = InspectionUtils.resolveClass(
			classLoader,
			"com.android.systemui.statusbar.pipeline.battery.ui.composable.BatteryWithEstimateKt",
		) != null

		statusbarItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.shade.ShadeHeaderController",
				"onInit",
				"ShadeHeaderController#onInit (Legacy Shade Header Clock/Date Tap)",
				utilized = !isComposeShadeHeader,
			),
		)

		statusbarItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"androidx.compose.foundation.ClickableKt",
				"ClickableKt (Compose Shade Header Tap)",
				utilized = isComposeShadeHeader || isBatteryWithEstimatePresent,
			),
		)

		statusbarItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.shade.ui.composable.VariableDayDateKt",
				"VariableDayDateKt (Compose Shade Header Date)",
				utilized = isComposeShadeHeader,
			),
		)

		val composeBatteryInfo = InspectionUtils.checkMethod(
			classLoader,
			"com.android.systemui.shade.ui.composable.ShadeHeaderKt",
			"BatteryInfo",
			"ShadeHeaderKt#BatteryInfo (Compose Shade Header Battery)",
			utilized = isComposeShadeHeader,
			matchPrefix = true,
		)
		statusbarItems.add(composeBatteryInfo)

		statusbarItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.statusbar.pipeline.battery.ui.composable.BatteryWithEstimateKt",
				"BatteryWithEstimate",
				"BatteryWithEstimateKt#BatteryWithEstimate (Legacy Shade Header Battery)",
				utilized = composeBatteryInfo.status != AuditStatus.PRESENT,
				matchPrefix = true,
			),
		)

		statusbarItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModelImpl\$isClockVisible\$1",
				"invoke",
				"HomeStatusBarViewModelImpl\$isClockVisible\$1#invoke (Clock Visibility Mirror)",
				utilized = isComposeClockPresent,
			),
		)

		statusbarItems.add(
			InspectionUtils.checkField(
				classLoader,
				"com.android.systemui.statusbar.pipeline.shared.domain.model.StatusBarDisableFlagsVisibilityModel",
				"isClockAllowed",
				"StatusBarDisableFlagsVisibilityModel.isClockAllowed (Clock Visibility Input)",
				utilized = isComposeClockPresent,
			),
		)

		statusbarItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"androidx.compose.ui.platform.AbstractComposeView",
				"disposeComposition",
				"AbstractComposeView#disposeComposition (Clock Re-parent Guard)",
				utilized = isComposeClockPresent,
			),
		)

		val sbNicVmConstructor = InspectionUtils.checkConstructor(
			classLoader,
			"com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel",
			"NotificationIconContainerStatusBarViewModel.<init>",
			checkInlined = true,
			utilized = false,
		)
		statusbarItems.add(
			if (sbNicVmConstructor.status == AuditStatus.PRESENT) {
				sbNicVmConstructor.copy(utilized = true)
			} else {
				sbNicVmConstructor
			},
		)

		statusbarItems.add(
			InspectionUtils.checkConstructor(
				classLoader,
				"com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder\$bind\$2",
				"NotificationIconContainerViewBinder\$bind\$2.<init> (Status Bar Icon Limit)",
				checkInlined = true,
				utilized = true,
			),
		)

		statusbarItems.add(
			InspectionUtils.checkConstructor(
				classLoader,
				"com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel",
				"NotificationIconContainerAlwaysOnDisplayViewModel.<init> (AOD Icon Limit)",
				checkInlined = true,
				utilized = true,
			),
		)

		statusbarItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.tuner.TunerServiceImpl",
				"TunerServiceImpl",
				utilized = true,
			),
		)

		val clockSubsystem = if (isComposeClockPresent) {
			"Compose ClockKt pipeline (Android 17+)"
		} else {
			"Legacy View Clock pipeline (@id/clock)"
		}

		val statusbarSummary = buildString {
			if (!isLegacyStatusBar) {
				append(
					"SYSTEM UTILIZING: COMPOSE STATUSBAR PIPELINE. Legacy HomeStatusBarIconBlockListBinder is ABSENT and @id/system_icons is hidden. Custom status bar icons must be dispatched via CommandQueue.setIcon().",
				)
			} else {
				append(
					"SYSTEM UTILIZING: LEGACY VIEW STATUSBAR PIPELINE. HomeStatusBarIconBlockListBinder is PRESENT; StatusBarIconControllerImpl drives PhoneStatusBarView.",
				)
			}
			append(" Clock Subsystem: $clockSubsystem.")
		}

		// ==========================================
		// 2. QUICK SETTINGS MODULE
		// ==========================================
		val qqsRowRepo = InspectionUtils.checkConstructor(
			classLoader,
			"com.android.systemui.qs.panels.data.repository.QuickQuickSettingsRowRepository",
			"QuickQuickSettingsRowRepository.<init>",
			checkInlined = true,
			utilized = false,
		)
		val isQqsInlined = qqsRowRepo.status == AuditStatus.INLINED
		qsItems.add(
			if (!isQqsInlined && qqsRowRepo.status == AuditStatus.PRESENT) {
				qqsRowRepo.copy(utilized = true)
			} else {
				qqsRowRepo
			},
		)

		val qqsRowInteractor = InspectionUtils.checkConstructor(
			classLoader,
			"com.android.systemui.qs.panels.domain.interactor.QuickQuickSettingsRowInteractor",
			"QuickQuickSettingsRowInteractor.<init>",
			checkInlined = false,
			utilized = isQqsInlined,
		)
		qsItems.add(qqsRowInteractor)

		qsItems.add(
			InspectionUtils.checkConstructor(
				classLoader,
				"com.android.systemui.qs.panels.data.repository.QSColumnsRepository",
				"QSColumnsRepository.<init>",
				checkInlined = true,
				utilized = true,
			),
		)

		qsItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.qs.panels.ui.compose.PaginatedGridLayout",
				"TileGrid",
				"PaginatedGridLayout#TileGrid",
				utilized = true,
			),
		)

		qsItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileKt",
				"TileLabel",
				"CommonTileKt#TileLabel",
				utilized = true,
			),
		)

		// BrightnessSlider (Compose pipeline with legacy fallback)
		var brightnessSlider = InspectionUtils.checkMethod(
			classLoader,
			"com.android.systemui.brightness.ui.compose.BrightnessSliderKt",
			"BrightnessSliderContainer",
			"BrightnessSliderKt#BrightnessSliderContainer (Compose)",
			utilized = true,
		)
		if (brightnessSlider.status == AuditStatus.ABSENT) {
			val legacySlider = InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.settings.brightness.BrightnessSliderController",
				"BrightnessSliderController (Legacy View)",
				utilized = true,
			)
			if (legacySlider.status == AuditStatus.PRESENT) brightnessSlider = legacySlider
		}
		qsItems.add(brightnessSlider)

		qsItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel",
				"BrightnessSliderViewModel",
				utilized = true,
			),
		)

		qsItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.brightness.ui.compose.BrightnessSliderKt",
				"BrightnessSlider",
				"BrightnessSliderKt#BrightnessSlider (Adaptive Brightness Toggle)",
				utilized = true,
			),
		)

		qsItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.compose.ui.graphics.ContainerElement",
				"ContainerElement (Adaptive Brightness Toggle Mirror)",
				utilized = true,
			),
		)

		qsItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.qs.composefragment.QSFragmentCompose",
				"QuickQuickSettingsElement",
				"QSFragmentCompose#QuickQuickSettingsElement",
				utilized = true,
			),
		)

		qsItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.qs.tileimpl.QSFactoryImpl",
				"createTile",
				"QSFactoryImpl#createTile (Custom Tiles)",
				utilized = true,
			),
		)

		qsItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"android.hardware.camera2.CameraManager",
				"setTorchMode",
				"CameraManager#setTorchMode (AnimateFlashlight)",
				utilized = true,
			),
		)

		qsItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"android.hardware.camera2.CameraManager",
				"turnOnTorchWithStrengthLevel",
				"CameraManager#turnOnTorchWithStrengthLevel (AnimateFlashlight)",
				utilized = true,
			),
		)

		qsItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"android.hardware.camera2.CameraManager",
				"getTorchStrengthLevel",
				"CameraManager#getTorchStrengthLevel (Flashlight Level Tracking)",
				utilized = true,
			),
		)

		val leveledFlashlightRepo = InspectionUtils.checkClass(
			classLoader,
			"com.android.systemui.flashlight.data.repository.FlashlightRepositoryImpl",
			"FlashlightRepositoryImpl (Leveled Pipeline)",
		)
		val isLeveledFlashlight = leveledFlashlightRepo.status == AuditStatus.PRESENT
		qsItems.add(leveledFlashlightRepo.copy(utilized = isLeveledFlashlight))

		qsItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.statusbar.policy.FlashlightControllerImpl",
				"FlashlightControllerImpl (Legacy Pipeline)",
				utilized = !isLeveledFlashlight,
			),
		)

		qsItems.add(
			InspectionUtils.checkConstructor(
				classLoader,
				"com.android.systemui.development.domain.interactor.BuildNumberInteractor",
				"BuildNumberInteractor.<init> (QS Footer Build Number)",
				utilized = true,
			),
		)

		qsItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.development.ui.viewmodel.BuildNumberViewModel",
				"onActivated",
				"BuildNumberViewModel#onActivated (QS Footer Build Number)",
				utilized = true,
			),
		)

		val qsSummary = if (isQqsInlined) {
			"SYSTEM UTILIZING: INLINED QQS REPOSITORY. QuickQuickSettingsRowRepository constructor is inlined by R8 (0 constructors). System instantiates QuickQuickSettingsRowInteractor; hook must target QuickQuickSettingsRowInteractor.<init> to inject fake Resources."
		} else {
			"SYSTEM UTILIZING: STANDARD QQS REPOSITORY. QuickQuickSettingsRowRepository constructor is declared and directly hookable."
		}

		// ==========================================
		// 3. LOCKSCREEN MODULE
		// ==========================================
		lockscreenItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.statusbar.phone.KeyguardBottomAreaView",
				"KeyguardBottomAreaView",
				utilized = true,
			),
		)
		lockscreenItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.keyguard.KeyguardUpdateMonitor",
				"shouldListenForFingerprint",
				"KeyguardUpdateMonitor#shouldListenForFingerprint",
				utilized = true,
			),
		)

		// PIN Bouncer (Compose PinBouncerViewModel with legacy View fallback)
		var pinBouncer = InspectionUtils.checkClass(
			classLoader,
			"com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel",
			"PinBouncerViewModel (Compose)",
			utilized = true,
		)
		if (pinBouncer.status == AuditStatus.ABSENT) {
			val legacyPin = InspectionUtils.checkClass(
				classLoader,
				"com.android.keyguard.KeyguardPinBasedInputView",
				"KeyguardPinBasedInputView (Legacy View)",
				utilized = true,
			)
			if (legacyPin.status == AuditStatus.PRESENT) pinBouncer = legacyPin
		}
		lockscreenItems.add(pinBouncer)

		lockscreenItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.keyguard.ui.view.DeviceEntryIconView",
				"DeviceEntryIconView (UDFPS View)",
				utilized = true,
			),
		)
		lockscreenItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel",
				"DeviceEntryIconViewModel (UDFPS ViewModel)",
				utilized = true,
			),
		)
		lockscreenItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.wallpapers.ImageWallpaper",
				"ImageWallpaper (Depth Wallpaper)",
				utilized = true,
			),
		)

		// UDFPS Controller (PhoneUdfpsController in modern builds, UdfpsController in earlier)
		var udfpsController = InspectionUtils.checkClass(
			classLoader,
			"com.android.systemui.auth.biometrics.fingerprint.udfps.controller.PhoneUdfpsController",
			"PhoneUdfpsController (Modern Biometrics)",
			utilized = true,
		)
		if (udfpsController.status == AuditStatus.ABSENT) {
			val legacyUdfps = InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.biometrics.UdfpsController",
				"UdfpsController (Legacy Biometrics)",
				utilized = true,
			)
			if (legacyUdfps.status == AuditStatus.PRESENT) udfpsController = legacyUdfps
		}
		lockscreenItems.add(udfpsController)

		lockscreenItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.qs.tileimpl.QSTileImpl",
				"click",
				"QSTileImpl#click (Lockscreen Sensitive QS Tile Blocker)",
				utilized = true,
			),
		)

		lockscreenItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.qs.tiles.dialog.InternetDialogManager",
				"create",
				"InternetDialogManager#create (Lockscreen Internet Dialog Blocker)",
				utilized = true,
			),
		)

		lockscreenItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.bluetooth.qsdialog.BluetoothTileDialogDelegate",
				"createDialog",
				"BluetoothTileDialogDelegate#createDialog (Lockscreen BT Dialog Blocker)",
				utilized = true,
			),
		)

		lockscreenItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.globalactions.GlobalActionsDialogLite",
				"showOrHideDialog",
				"GlobalActionsDialogLite#showOrHideDialog (Lockscreen Power Menu Blocker)",
				utilized = true,
			),
		)

		// ==========================================
		// 4. GESTURES & NAVIGATION MODULE
		// ==========================================
		var navBarView = InspectionUtils.checkClass(
			classLoader,
			"com.android.systemui.navigationbar.views.NavigationBarView",
			"NavigationBarView (views package)",
			utilized = true,
		)
		if (navBarView.status == AuditStatus.ABSENT) {
			val fallback = InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.navigationbar.NavigationBarView",
				"NavigationBarView (legacy package)",
				utilized = true,
			)
			if (fallback.status == AuditStatus.PRESENT) navBarView = fallback
		}
		gesturesItems.add(navBarView)

		gesturesItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.statusbar.CommandQueue",
				"setImeWindowStatus",
				"CommandQueue#setImeWindowStatus (Cursor Control Keys)",
				utilized = true,
			),
		)

		var edgeBack = InspectionUtils.checkMethod(
			classLoader,
			"com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",
			"updateIsEnabled",
			"EdgeBackGestureHandler#updateIsEnabled (gestural package)",
			utilized = true,
		)
		if (edgeBack.status == AuditStatus.ABSENT) {
			val fallback = InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.navigationbar.EdgeBackGestureHandler",
				"EdgeBackGestureHandler (legacy package)",
				utilized = true,
			)
			if (fallback.status == AuditStatus.PRESENT) edgeBack = fallback
		}
		gesturesItems.add(edgeBack)

		gesturesItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.navigationbar.gestural.BackPanelController",
				"onMotionEvent",
				"BackPanelController#onMotionEvent",
				utilized = true,
			),
		)

		gesturesItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.shade.NotificationPanelViewController",
				"NotificationPanelViewController (Screen Gestures)",
				utilized = true,
			),
		)

		gesturesItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.shade.NotificationShadeWindowViewController",
				"NotificationShadeWindowViewController",
				utilized = true,
			),
		)

		gesturesItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.doze.DozeTriggers",
				"onSensor",
				"DozeTriggers#onSensor (Screen Gestures)",
				utilized = true,
			),
		)

		gesturesItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.doze.DozeSensors\$TriggerSensor",
				"DozeSensors\$TriggerSensor",
				utilized = true,
			),
		)

		// ==========================================
		// 5. POWER & VOLUME MODULE
		// ==========================================
		powerVolumeItems.add(
			InspectionUtils.checkMethod(
				classLoader,
				"com.android.systemui.globalactions.GlobalActionsDialogLite",
				"createActionItems",
				"GlobalActionsDialogLite#createActionItems (Power Menu)",
				utilized = true,
			),
		)
		powerVolumeItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.globalactions.GlobalActionsDialogLite\$PowerOptionsAction",
				"GlobalActionsDialogLite\$PowerOptionsAction",
				utilized = true,
			),
		)
		powerVolumeItems.add(
			InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.globalactions.GlobalActionsDialogLite\$LongPressAction",
				"GlobalActionsDialogLite\$LongPressAction",
				utilized = true,
			),
		)

		var volumeDialog = InspectionUtils.checkClass(
			classLoader,
			"com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel",
			"VolumeDialogViewModel (Compose Volume Dialog)",
			utilized = true,
		)
		if (volumeDialog.status == AuditStatus.ABSENT) {
			val legacyVolume = InspectionUtils.checkClass(
				classLoader,
				"com.android.systemui.volume.VolumeDialogImpl",
				"VolumeDialogImpl (Legacy View)",
				utilized = true,
			)
			if (legacyVolume.status == AuditStatus.PRESENT) volumeDialog = legacyVolume
		}
		powerVolumeItems.add(volumeDialog)

		val modules = listOf(
			ModuleAuditResult(
				moduleName = "Status Bar",
				packageName = Constants.SYSTEM_UI_PACKAGE,
				pipelineSummary = statusbarSummary,
				items = statusbarItems,
			),
			ModuleAuditResult(
				moduleName = "Quick Settings",
				packageName = Constants.SYSTEM_UI_PACKAGE,
				pipelineSummary = qsSummary,
				items = qsItems,
			),
			ModuleAuditResult(
				moduleName = "Lockscreen",
				packageName = Constants.SYSTEM_UI_PACKAGE,
				pipelineSummary = "Standard SystemUI Lockscreen & Keyguard Architecture",
				items = lockscreenItems,
			),
			ModuleAuditResult(
				moduleName = "Gestures & Navigation",
				packageName = Constants.SYSTEM_UI_PACKAGE,
				pipelineSummary = "SystemUI Navigation Bar & Edge Back Pipeline",
				items = gesturesItems,
			),
			ModuleAuditResult(
				moduleName = "Power & Volume",
				packageName = Constants.SYSTEM_UI_PACKAGE,
				pipelineSummary = "Global Actions & Volume Dialog Pipeline",
				items = powerVolumeItems,
			),
		)

		return ProcessAuditReport(
			packageName = Constants.SYSTEM_UI_PACKAGE,
			processName = "com.android.systemui",
			modules = modules,
		)
	}
}
