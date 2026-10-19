package sh.siava.pixelxpert.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.data.PrefStore
import sh.siava.pixelxpert.ui.debug.model.AuditStatus
import sh.siava.pixelxpert.ui.debug.model.DeviceDetails
import sh.siava.pixelxpert.ui.debug.model.ProcessAuditReport
import sh.siava.pixelxpert.ui.debug.model.TargetAuditItem

@Composable
fun HookAuditScreen() {
	val context = LocalContext.current
	val device = remember { DeviceDetails() }
	val reports = remember { mutableStateMapOf<String, ProcessAuditReport>() }

	fun sendAuditRequest() {
		reports.clear()
		Thread {
			runCatching {
				context.sendBroadcast(Intent(Constants.ACTION_REQUEST_HOOK_AUDIT))
			}
		}.start()
	}

	fun preferenceSnapshot(): Map<String, Any?> =
		runCatching { PrefStore.open(context).all.toMap() }.getOrDefault(emptyMap())

	DisposableEffect(context) {
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(ctx: Context?, intent: Intent?) {
				if (intent?.action != Constants.ACTION_HOOK_AUDIT_RESULT) return
				val pkg = intent.getStringExtra("packageName") ?: return
				val json = intent.getStringExtra("auditJson") ?: return
				runCatching {
					val type = object : com.google.gson.reflect.TypeToken<ProcessAuditReport>() {}.type
					val report = Gson().fromJson<ProcessAuditReport>(json, type)
					if (report != null) {
						reports[pkg] = report
					}
				}
			}
		}

		context.registerReceiver(
			receiver,
			IntentFilter(Constants.ACTION_HOOK_AUDIT_RESULT),
			Context.RECEIVER_EXPORTED,
		)

		onDispose {
			runCatching { context.unregisterReceiver(receiver) }
		}
	}

	LaunchedEffect(Unit) {
		sendAuditRequest()
	}

	fun copyReport() {
		val markdown = MarkdownGenerator.generate(device, reports.values.toList(), preferenceSnapshot())
		val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
		clipboard.setPrimaryClip(ClipData.newPlainText("PixelXpert Audit", markdown))
		Toast.makeText(context, context.getString(R.string.hook_audit_copied), Toast.LENGTH_SHORT).show()
	}

	fun exportReport() {
		val markdown = MarkdownGenerator.generate(device, reports.values.toList(), preferenceSnapshot())
		val rawBuild = device.buildId.ifBlank { device.buildDisplay }
		val buildPrefix = rawBuild.substringBefore('.').filter { it.isLetterOrDigit() }
		val buildTag = (if (buildPrefix.length >= 4) buildPrefix.take(4) else rawBuild.filter { it.isLetterOrDigit() }.take(4))
			.lowercase(Locale.ROOT)
			.ifBlank { "unknown" }
		val timestamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
		val fileName = "PXN-hooks-$buildTag-$timestamp.md"

		try {
			val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
			val reportFile = File(reportsDir, fileName)
			reportFile.writeText(markdown)

			val uri = FileProvider.getUriForFile(
				context,
				"${context.packageName}.fileprovider",
				reportFile,
			)

			val sendIntent = Intent(Intent.ACTION_SEND).apply {
				type = "text/markdown"
				putExtra(Intent.EXTRA_STREAM, uri)
				putExtra(Intent.EXTRA_SUBJECT, fileName)
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				clipData = ClipData.newRawUri(fileName, uri)
			}

			val chooser = Intent.createChooser(sendIntent, context.getString(R.string.hook_audit_export)).apply {
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
			context.startActivity(chooser)
		} catch (e: Throwable) {
			Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
		}
	}

	val allModules = reports.values.flatMap { it.safeModules }

	LazyColumn(
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = 16.dp, vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		// Top Actions Bar
		item {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Button(
					onClick = ::exportReport,
					modifier = Modifier.weight(1f),
				) {
					Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
					Spacer(Modifier.width(8.dp))
					Text(stringResource(R.string.hook_audit_export))
				}
				FilledTonalButton(
					onClick = ::copyReport,
					modifier = Modifier.weight(1f),
				) {
					Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
					Spacer(Modifier.width(8.dp))
					Text(stringResource(R.string.hook_audit_copy))
				}
				OutlinedIconButton(onClick = ::sendAuditRequest) {
					Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.hook_audit_refresh))
				}
			}
		}

		// Device Details Card
		item {
			DeviceCard(device)
		}

		// Architecture Overview Card
		item {
			ArchitectureCard(allModules)
		}

		if (allModules.isEmpty()) {
			item {
				Card(
					modifier = Modifier.fillMaxWidth(),
					colors = CardDefaults.cardColors(
						containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
					),
				) {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(24.dp),
						horizontalAlignment = Alignment.CenterHorizontally,
					) {
						CircularProgressIndicator(modifier = Modifier.size(36.dp))
						Spacer(modifier = Modifier.height(16.dp))
						Text(
							text = stringResource(R.string.hook_audit_waiting),
							style = MaterialTheme.typography.titleSmall,
							fontWeight = FontWeight.Medium,
						)
						Spacer(modifier = Modifier.height(8.dp))
						Text(
							text = stringResource(R.string.hook_audit_waiting_desc),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}
		} else {
			items(allModules) { module ->
				ModuleCard(module)
			}
		}

		item {
			Spacer(modifier = Modifier.height(24.dp))
		}
	}
}

@Composable
private fun DeviceCard(device: DeviceDetails) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
		),
	) {
		Column(modifier = Modifier.padding(16.dp)) {
			Text(
				text = "Device Information",
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.primary,
			)
			Spacer(modifier = Modifier.height(12.dp))
			InfoRow("Model", "${device.model} (${device.device})")
			InfoRow("Build Version", device.buildDisplay)
			InfoRow("Android Version", "Android ${device.androidVersion} (API ${device.sdkInt})")
			InfoRow("Security Patch", device.securityPatch)
			InfoRow("Fingerprint", device.fingerprint)
		}
	}
}

@Composable
private fun ArchitectureCard(modules: List<sh.siava.pixelxpert.ui.debug.model.ModuleAuditResult>) {
	val statusbarMod = modules.firstOrNull { it.moduleName == "Status Bar" }
	val qsMod = modules.firstOrNull { it.moduleName == "Quick Settings" }

	val isLegacyStatusbar = statusbarMod?.safeItems?.any {
		it.name.contains("HomeStatusBarIconBlockListBinder") && it.status == AuditStatus.PRESENT
	} ?: false

	val isQqsInlined = qsMod?.safeItems?.any {
		it.name.contains("QuickQuickSettingsRowRepository") && it.status == AuditStatus.INLINED
	} ?: false

	Card(
		modifier = Modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
		),
	) {
		Column(modifier = Modifier.padding(16.dp)) {
			Text(
				text = "Subsystem Architecture",
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSecondaryContainer,
			)
			Spacer(modifier = Modifier.height(10.dp))

			ArchitectureBadge(
				title = "Status Bar Pipeline",
				status = if (statusbarMod == null) "Awaiting Reply" else if (isLegacyStatusbar) "Legacy Views Pipeline" else "Compose Statusbar Pipeline",
				details = if (statusbarMod == null) "Waiting for com.android.systemui..." else if (isLegacyStatusbar) "StatusBarIconControllerImpl -> PhoneStatusBarView active" else "ExternalSystemStatusIconRepository active; custom status bar icons must dispatch via CommandQueue.setIcon()",
				isWarning = !isLegacyStatusbar && statusbarMod != null,
			)

			Spacer(modifier = Modifier.height(8.dp))

			ArchitectureBadge(
				title = "Quick QS Rows Pipeline",
				status = if (qsMod == null) "Awaiting Reply" else if (isQqsInlined) "Repository Constructor Inlined" else "Standard Constructor Intact",
				details = if (qsMod == null) "Waiting for com.android.systemui..." else if (isQqsInlined) "QuickQuickSettingsRowRepository.<init> inlined by R8; hook QuickQuickSettingsRowInteractor.<init>" else "QuickQuickSettingsRowRepository constructor hook active",
				isWarning = isQqsInlined,
			)
		}
	}
}

@Composable
private fun ArchitectureBadge(
	title: String,
	status: String,
	details: String,
	isWarning: Boolean,
) {
	Surface(
		shape = RoundedCornerShape(8.dp),
		color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(modifier = Modifier.padding(12.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = title,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.SemiBold,
					modifier = Modifier.weight(1f),
				)
				Spacer(modifier = Modifier.width(8.dp))
				StatusChip(
					text = status,
					color = if (isWarning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
				)
			}
			Spacer(modifier = Modifier.height(4.dp))
			Text(
				text = details,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun ModuleCard(module: sh.siava.pixelxpert.ui.debug.model.ModuleAuditResult) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainer,
		),
	) {
		Column(modifier = Modifier.padding(16.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = module.moduleName,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
					modifier = Modifier.weight(1f),
				)
				Spacer(modifier = Modifier.width(8.dp))
				Text(
					text = module.packageName,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.outline,
					fontFamily = FontFamily.Monospace,
				)
			}

			if (!module.pipelineSummary.isNullOrBlank()) {
				Spacer(modifier = Modifier.height(6.dp))
				Text(
					text = module.pipelineSummary,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.primary,
				)
			}

			Spacer(modifier = Modifier.height(12.dp))

			module.safeItems.forEach { item ->
				TargetItemRow(item)
				Spacer(modifier = Modifier.height(8.dp))
			}
		}
	}
}

@Composable
private fun TargetItemRow(item: TargetAuditItem) {
	val (badgeText, badgeColor) = when (item.status) {
		AuditStatus.PRESENT -> "PRESENT" to Color(0xFF2E7D32)
		AuditStatus.ABSENT -> "ABSENT" to MaterialTheme.colorScheme.error
		AuditStatus.INLINED -> "INLINED" to Color(0xFFEF6C00)
		AuditStatus.ACTIVE -> "ACTIVE" to MaterialTheme.colorScheme.primary
		AuditStatus.INACTIVE -> "INACTIVE" to MaterialTheme.colorScheme.outline
	}

	Surface(
		shape = RoundedCornerShape(8.dp),
		color = MaterialTheme.colorScheme.surfaceContainerLowest,
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(modifier = Modifier.padding(10.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = item.name,
					style = MaterialTheme.typography.bodySmall,
					fontWeight = FontWeight.SemiBold,
					modifier = Modifier.weight(1f),
				)
				Spacer(modifier = Modifier.width(8.dp))
				StatusChip(text = badgeText, color = badgeColor)
			}
			Spacer(modifier = Modifier.height(4.dp))
			Text(
				text = item.target,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.outline,
				fontFamily = FontFamily.Monospace,
			)
			if (item.detail.isNotBlank() || item.utilized != null) {
				Spacer(modifier = Modifier.height(4.dp))
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = item.detail,
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.weight(1f, fill = false),
					)
					if (item.utilized != null) {
						Spacer(modifier = Modifier.width(8.dp))
						StatusChip(
							text = if (item.utilized) "Utilized by OS" else "Not utilized",
							color = if (item.utilized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun StatusChip(text: String, color: Color) {
	Box(
		modifier = Modifier
			.clip(RoundedCornerShape(4.dp))
			.background(color.copy(alpha = 0.15f))
			.padding(horizontal = 6.dp, vertical = 2.dp),
	) {
		Text(
			text = text,
			color = color,
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.Bold,
			fontSize = 10.sp,
			maxLines = 1,
		)
	}
}

@Composable
private fun InfoRow(label: String, value: String) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 2.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Spacer(modifier = Modifier.width(16.dp))
		Text(
			text = value,
			style = MaterialTheme.typography.bodySmall,
			fontWeight = FontWeight.Medium,
			textAlign = TextAlign.End,
			modifier = Modifier.weight(1f, fill = false),
		)
	}
}
