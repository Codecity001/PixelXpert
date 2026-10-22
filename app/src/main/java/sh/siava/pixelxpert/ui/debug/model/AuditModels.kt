package sh.siava.pixelxpert.ui.debug.model

import android.os.Build
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
enum class AuditStatus {
	@SerializedName("PRESENT")
	PRESENT,

	@SerializedName("ABSENT")
	ABSENT,

	@SerializedName("INLINED")
	INLINED,

	@SerializedName("ACTIVE")
	ACTIVE,

	@SerializedName("INACTIVE")
	INACTIVE,
}

@Keep
data class TargetAuditItem(
	@SerializedName(value = "name", alternate = ["a"])
	val name: String = "",

	@SerializedName(value = "target", alternate = ["b"])
	val target: String = "",

	@SerializedName(value = "status", alternate = ["c"])
	val status: AuditStatus = AuditStatus.ABSENT,

	@SerializedName(value = "detail", alternate = ["d"])
	val detail: String = "",

	@SerializedName(value = "utilized", alternate = ["e"])
	val utilized: Boolean? = null,
)

@Keep
data class ModuleAuditResult(
	@SerializedName(value = "moduleName", alternate = ["a"])
	val moduleName: String = "",

	@SerializedName(value = "packageName", alternate = ["b"])
	val packageName: String = "",

	@SerializedName(value = "pipelineSummary", alternate = ["c"])
	val pipelineSummary: String? = null,

	@SerializedName(value = "items", alternate = ["d"])
	val items: List<TargetAuditItem>? = emptyList(),
) {
	val safeItems: List<TargetAuditItem>
		get() = items ?: emptyList()
}

@Keep
data class ProcessAuditReport(
	@SerializedName(value = "packageName", alternate = ["a"])
	val packageName: String = "",

	@SerializedName(value = "processName", alternate = ["b"])
	val processName: String = "",

	@SerializedName(value = "timestamp", alternate = ["c"])
	val timestamp: Long = System.currentTimeMillis(),

	@SerializedName(value = "modules", alternate = ["d"])
	val modules: List<ModuleAuditResult>? = emptyList(),
) {
	val safeModules: List<ModuleAuditResult>
		get() = modules ?: emptyList()
}

@Keep
data class DeviceDetails(
	@SerializedName("buildDisplay")
	val buildDisplay: String = Build.DISPLAY,

	@SerializedName("buildId")
	val buildId: String = Build.ID,

	@SerializedName("model")
	val model: String = Build.MODEL,

	@SerializedName("device")
	val device: String = Build.DEVICE,

	@SerializedName("product")
	val product: String = Build.PRODUCT,

	@SerializedName("manufacturer")
	val manufacturer: String = Build.MANUFACTURER,

	@SerializedName("androidVersion")
	val androidVersion: String = Build.VERSION.RELEASE,

	@SerializedName("sdkInt")
	val sdkInt: Int = Build.VERSION.SDK_INT,

	@SerializedName("securityPatch")
	val securityPatch: String = Build.VERSION.SECURITY_PATCH,

	@SerializedName("fingerprint")
	val fingerprint: String = Build.FINGERPRINT,

	@SerializedName("supportedAbis")
	val supportedAbis: String = Build.SUPPORTED_ABIS.joinToString(),
)
