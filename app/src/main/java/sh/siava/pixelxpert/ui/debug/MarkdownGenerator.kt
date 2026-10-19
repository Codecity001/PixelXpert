package sh.siava.pixelxpert.ui.debug

import sh.siava.pixelxpert.ui.debug.model.AuditStatus
import sh.siava.pixelxpert.ui.debug.model.DeviceDetails
import sh.siava.pixelxpert.ui.debug.model.ProcessAuditReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MarkdownGenerator {

	fun generate(
		device: DeviceDetails,
		reports: List<ProcessAuditReport>,
		preferences: Map<String, Any?>,
	): String {
		val sb = StringBuilder()
		val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())

		sb.appendLine("# PixelXpert Hook & System Architecture Audit")
		sb.appendLine()
		sb.appendLine("Generated: $timestamp")
		sb.appendLine()

		// 1. Device Information
		sb.appendLine("## Device Information")
		sb.appendLine("- **Model**: ${device.model} (`${device.device}` / `${device.product}`)")
		sb.appendLine("- **Manufacturer**: ${device.manufacturer}")
		sb.appendLine("- **Build Version (Display)**: `${device.buildDisplay}`")
		sb.appendLine("- **Build ID**: `${device.buildId}`")
		sb.appendLine("- **Android Version**: Android ${device.androidVersion} (API ${device.sdkInt})")
		sb.appendLine("- **Security Patch Level**: ${device.securityPatch}")
		sb.appendLine("- **Supported ABIs**: `${device.supportedAbis}`")
		sb.appendLine("- **Build Fingerprint**: `${device.fingerprint}`")
		sb.appendLine()

		// 2. High-Level Architecture Assessment
		sb.appendLine("## System Architecture Diagnostics")
		val allModules = reports.flatMap { it.safeModules }
		val statusbarMod = allModules.firstOrNull { it.moduleName == "Status Bar" }
		val qsMod = allModules.firstOrNull { it.moduleName == "Quick Settings" }

		if (statusbarMod != null) {
			val hasLegacy = statusbarMod.safeItems.any { it.name.contains("HomeStatusBarIconBlockListBinder") && it.status == AuditStatus.PRESENT }
			if (hasLegacy) {
				sb.appendLine("- **Status Bar Subsystem**: **Legacy Views Pipeline** (`HomeStatusBarIconBlockListBinder` is present). `StatusBarIconControllerImpl` controls icon slots.")
			} else {
				sb.appendLine("- **Status Bar Subsystem**: **Compose Statusbar Pipeline** (`HomeStatusBarIconBlockListBinder` is absent; `@id/system_icons` is hidden). Custom status bar icons must dispatch via `CommandQueue.setIcon()`.")
			}
		}

		if (qsMod != null) {
			val inlinedRepo = qsMod.safeItems.any { it.name.contains("QuickQuickSettingsRowRepository") && it.status == AuditStatus.INLINED }
			if (inlinedRepo) {
				sb.appendLine("- **Quick Settings Subsystem**: **Inlined QQS Repository (R8)**. `QuickQuickSettingsRowRepository.<init>` has 0 declared constructors. Hooking repository constructor fails; must hook `QuickQuickSettingsRowInteractor.<init>` instead.")
			} else {
				sb.appendLine("- **Quick Settings Subsystem**: **Standard QQS Repository**. `QuickQuickSettingsRowRepository` constructor is intact.")
			}
		}
		sb.appendLine()

		// 3. Module Details
		if (allModules.isEmpty()) {
			sb.appendLine("## Hook Targets")
			sb.appendLine("*No process reports received yet. Ensure Xposed/LSPosed is active and hooked processes have replied.*")
			sb.appendLine()
		} else {
			allModules.forEach { module ->
				sb.appendLine("## Module: ${module.moduleName}")
				sb.appendLine("- **Target Process / Package**: `${module.packageName}`")
				if (!module.pipelineSummary.isNullOrBlank()) {
					sb.appendLine("- **Architecture Summary**: ${module.pipelineSummary}")
				}
				sb.appendLine()

				sb.appendLine("| Hook Target | Status | Utilized by System | Details |")
				sb.appendLine("|---|---|---|---|")
				module.safeItems.forEach { item ->
					val statusBadge = when (item.status) {
						AuditStatus.PRESENT -> "✅ PRESENT"
						AuditStatus.ABSENT -> "❌ ABSENT"
						AuditStatus.INLINED -> "⚠️ INLINED"
						AuditStatus.ACTIVE -> "🚀 ACTIVE"
						AuditStatus.INACTIVE -> "⏸️ INACTIVE"
					}
					val utilizedStr = when (item.utilized) {
						true -> "Yes"
						false -> "No"
						null -> "—"
					}
					sb.appendLine("| `${item.name}` | $statusBadge | $utilizedStr | ${item.detail} |")
				}
				sb.appendLine()
			}
		}

		// 4. Preferences as the hooks see them
		sb.appendLine("## PixelXpert Preferences")
		if (preferences.isEmpty()) {
			sb.appendLine("*Preference store is empty.*")
			sb.appendLine()
		} else {
			sb.appendLine("| Preference | Value |")
			sb.appendLine("|---|---|")
			preferences.entries
				.sortedBy { it.key }
				.forEach { (key, value) ->
					sb.appendLine("| `${cell(key)}` | ${cell(preferenceValue(value))} |")
				}
			sb.appendLine()
		}

		return sb.toString()
	}

	/** Values are stored under a type, so keep the type visible: `"true"` vs `true`. */
	private fun preferenceValue(value: Any?): String = when (value) {
		null -> "—"
		is String -> if (value.isEmpty()) "(empty)" else "`$value`"
		is Set<*> -> value.joinToString(", ")
		else -> value.toString()
	}

	/** Keeps a key or value from breaking out of its table cell. */
	private fun cell(raw: String): String =
		raw.replace("\n", " ").replace("|", "\\|")
}
