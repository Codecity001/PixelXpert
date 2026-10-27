package sh.siava.pixelxpert.xposed.inspector

import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.ui.debug.model.ModuleAuditResult
import sh.siava.pixelxpert.ui.debug.model.ProcessAuditReport
import sh.siava.pixelxpert.ui.debug.model.TargetAuditItem
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Single-pass reflection inspector for Android Framework / system_server (`android`).
 *
 * CRITICAL CONSTRAINT: Exactly one attempt per target. Once a check fails,
 * it records the failure and moves on. Never loops, retries, or polls.
 */
object FrameworkInspector {

	fun inspect(classLoader: ClassLoader?): ProcessAuditReport {
		val loader = ReflectedClass.getFrameworkClassloader() ?: classLoader
		val frameworkItems = mutableListOf<TargetAuditItem>()

		frameworkItems.add(
			InspectionUtils.checkClass(
				loader,
				"com.android.server.policy.PhoneWindowManager",
				"PhoneWindowManager (Key & Power Gestures)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.policy.PhoneWindowManager",
				"interceptKeyBeforeQueueing",
				"PhoneWindowManager#interceptKeyBeforeQueueing",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.policy.PhoneWindowManager",
				"showGlobalActions",
				"PhoneWindowManager#showGlobalActions (Lockscreen Power Menu Blocker)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkClass(
				loader,
				"com.android.server.policy.PhoneWindowManager\$PowerKeyRule",
				"PhoneWindowManager\$PowerKeyRule (ScreenOffKeys)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.GestureLauncherService",
				"handleCameraGesture",
				"GestureLauncherService#handleCameraGesture (ScreenOffKeys)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.adb.AdbDebuggingManager\$AdbDebuggingHandler",
				"onAdbdWifiServerConnected",
				"AdbDebuggingHandler#onAdbdWifiServerConnected (Wireless ADB Pin)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.display.DisplayPowerController",
				"clampScreenBrightness",
				"DisplayPowerController#clampScreenBrightness (Brightness Range)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkClass(
				loader,
				"android.hardware.display.BrightnessInfo",
				"BrightnessInfo (Hardware Brightness Info)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.pm.InstallPackageHelper",
				"doesSignatureMatchForPermissions",
				"InstallPackageHelper#doesSignatureMatchForPermissions (Package Installer)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.pm.PackageManagerServiceUtils",
				"checkDowngrade",
				"PackageManagerServiceUtils#checkDowngrade (Package Downgrade)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.pm.PackageManagerServiceUtils",
				"verifySignatures",
				"PackageManagerServiceUtils#verifySignatures (Package Signature)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"android.content.pm.SigningDetails",
				"checkCapability",
				"SigningDetails#checkCapability (Signature Bypass)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.wm.DisplayRotation",
				"rotationForOrientation",
				"DisplayRotation#rotationForOrientation (Screen Rotation 180°)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.internal.policy.SystemBarUtils",
				"getStatusBarHeight",
				"SystemBarUtils#getStatusBarHeight (Statusbar Size/Height)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.wm.utils.WmDisplayCutout",
				"getDisplayCutout",
				"WmDisplayCutout#getDisplayCutout (Cutout Size Override)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.power.PowerManagerService",
				"updatePowerStateLocked",
				"PowerManagerService#updatePowerStateLocked (Face Up Sleep)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkClass(
				loader,
				"com.android.server.power.FaceDownDetector",
				"FaceDownDetector (Face Up Sleep Sensor)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkClass(
				loader,
				"android.net.wifi.SoftApConfiguration",
				"SoftApConfiguration (HotSpot Controller)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.display.DisplayManagerService",
				"canProjectSecureVideo",
				"DisplayManagerService#canProjectSecureVideo (System Screen Record)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.am.ActivityManagerService",
				"checkCallingPermission",
				"ActivityManagerService#checkCallingPermission (Launcher Force Stop)",
				utilized = true,
			),
		)

		frameworkItems.add(
			InspectionUtils.checkMethod(
				loader,
				"com.android.server.inputmethod.InputMethodManagerService",
				"getInputMethodNavButtonFlagsLocked",
				"InputMethodManagerService#getInputMethodNavButtonFlagsLocked (Keyboard Space)",
				utilized = true,
			),
		)

		val modules = listOf(
			ModuleAuditResult(
				moduleName = "Framework & system_server",
				packageName = Constants.SYSTEM_FRAMEWORK_PACKAGE,
				pipelineSummary = "Android System Server Core Services & Policy Hooks",
				items = frameworkItems,
			),
		)

		return ProcessAuditReport(
			packageName = Constants.SYSTEM_FRAMEWORK_PACKAGE,
			processName = "system_server",
			modules = modules,
		)
	}
}
