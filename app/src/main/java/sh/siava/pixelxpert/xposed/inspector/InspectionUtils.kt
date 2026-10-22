package sh.siava.pixelxpert.xposed.inspector

import de.robv.android.xposed.XposedHelpers
import sh.siava.pixelxpert.ui.debug.model.AuditStatus
import sh.siava.pixelxpert.ui.debug.model.TargetAuditItem
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Single-pass reflection auditing utilities.
 *
 * CRITICAL CONSTRAINT: Exactly one attempt per target. Once a check fails or
 * throws, it immediately records the failure and returns. No loops, no retries,
 * and no polling.
 */
object InspectionUtils {

	fun resolveClass(loader: ClassLoader?, className: String): Class<*>? {
		if (loader != null) {
			XposedHelpers.findClassIfExists(className, loader)?.let { return it }
		}
		val fwLoader = ReflectedClass.getFrameworkClassloader()
		if (fwLoader != null && fwLoader != loader) {
			XposedHelpers.findClassIfExists(className, fwLoader)?.let { return it }
		}
		try {
			val threadLoader = Thread.currentThread().contextClassLoader
			if (threadLoader != null && threadLoader != loader && threadLoader != fwLoader) {
				return XposedHelpers.findClassIfExists(className, threadLoader)
			}
		} catch (_: Throwable) {
		}
		return null
	}

	fun checkClass(
		loader: ClassLoader?,
		className: String,
		name: String,
		utilized: Boolean? = null,
	): TargetAuditItem {
		val clazz = resolveClass(loader, className)
		return if (clazz != null) {
			TargetAuditItem(
				name = name,
				target = className,
				status = AuditStatus.PRESENT,
				detail = "Class loaded",
				utilized = utilized ?: true,
			)
		} else {
			TargetAuditItem(
				name = name,
				target = className,
				status = AuditStatus.ABSENT,
				detail = "Absent (ClassNotFoundException)",
				utilized = false,
			)
		}
	}

	fun checkConstructor(
		loader: ClassLoader?,
		className: String,
		name: String,
		paramTypes: Array<Class<*>>? = null,
		checkInlined: Boolean = false,
		utilized: Boolean? = null,
	): TargetAuditItem {
		val clazz = resolveClass(loader, className)
		if (clazz == null) {
			return TargetAuditItem(
				name = name,
				target = "$className.<init>",
				status = AuditStatus.ABSENT,
				detail = "Class absent (ClassNotFoundException)",
				utilized = false,
			)
		}
		return try {
			if (checkInlined && clazz.declaredConstructors.isEmpty()) {
				return TargetAuditItem(
					name = name,
					target = "$className.<init>",
					status = AuditStatus.INLINED,
					detail = "Inlined by R8 compiler (0 declared constructors)",
					utilized = false,
				)
			}

			if (paramTypes == null) {
				if (clazz.declaredConstructors.isNotEmpty()) {
					TargetAuditItem(
						name = name,
						target = "$className.<init>",
						status = AuditStatus.PRESENT,
						detail = "Constructors found (${clazz.declaredConstructors.size})",
						utilized = utilized ?: true,
					)
				} else {
					TargetAuditItem(
						name = name,
						target = "$className.<init>",
						status = AuditStatus.ABSENT,
						detail = "No declared constructors found",
						utilized = false,
					)
				}
			} else {
				clazz.getDeclaredConstructor(*paramTypes)
				TargetAuditItem(
					name = name,
					target = "$className.<init>(${paramTypes.joinToString { it.simpleName }})",
					status = AuditStatus.PRESENT,
					detail = "Matching constructor found",
					utilized = utilized ?: true,
				)
			}
		} catch (e: NoSuchMethodException) {
			TargetAuditItem(
				name = name,
				target = "$className.<init>",
				status = AuditStatus.ABSENT,
				detail = "Constructor not found (${e.javaClass.simpleName})",
				utilized = false,
			)
		} catch (e: Throwable) {
			TargetAuditItem(
				name = name,
				target = "$className.<init>",
				status = AuditStatus.ABSENT,
				detail = "Failed to inspect (${e.javaClass.simpleName})",
				utilized = false,
			)
		}
	}

	fun checkMethod(
		loader: ClassLoader?,
		className: String,
		methodName: String,
		name: String,
		paramTypes: Array<Class<*>>? = null,
		utilized: Boolean? = null,
	): TargetAuditItem {
		val clazz = resolveClass(loader, className)
		if (clazz == null) {
			return TargetAuditItem(
				name = name,
				target = "$className#$methodName",
				status = AuditStatus.ABSENT,
				detail = "Class absent (ClassNotFoundException)",
				utilized = false,
			)
		}
		return try {
			val found = if (paramTypes == null) {
				clazz.declaredMethods.any { it.name == methodName }
			} else {
				try {
					clazz.getDeclaredMethod(methodName, *paramTypes)
					true
				} catch (_: NoSuchMethodException) {
					false
				}
			}

			if (found) {
				TargetAuditItem(
					name = name,
					target = "$className#$methodName",
					status = AuditStatus.PRESENT,
					detail = "Method found",
					utilized = utilized ?: true,
				)
			} else {
				TargetAuditItem(
					name = name,
					target = "$className#$methodName",
					status = AuditStatus.ABSENT,
					detail = "Method not found in class",
					utilized = false,
				)
			}
		} catch (e: Throwable) {
			TargetAuditItem(
				name = name,
				target = "$className#$methodName",
				status = AuditStatus.ABSENT,
				detail = "Class absent or failed to load (${e.javaClass.simpleName})",
				utilized = false,
			)
		}
	}

	fun checkField(
		loader: ClassLoader?,
		className: String,
		fieldName: String,
		name: String,
		utilized: Boolean? = null,
	): TargetAuditItem {
		val clazz = resolveClass(loader, className)
		if (clazz == null) {
			return TargetAuditItem(
				name = name,
				target = "$className.$fieldName",
				status = AuditStatus.ABSENT,
				detail = "Class absent (ClassNotFoundException)",
				utilized = false,
			)
		}
		return try {
			val found = clazz.declaredFields.any { it.name == fieldName }
			if (found) {
				TargetAuditItem(
					name = name,
					target = "$className.$fieldName",
					status = AuditStatus.PRESENT,
					detail = "Field found",
					utilized = utilized ?: true,
				)
			} else {
				TargetAuditItem(
					name = name,
					target = "$className.$fieldName",
					status = AuditStatus.ABSENT,
					detail = "Field not found in class",
					utilized = false,
				)
			}
		} catch (e: Throwable) {
			TargetAuditItem(
				name = name,
				target = "$className.$fieldName",
				status = AuditStatus.ABSENT,
				detail = "Class absent or failed to load (${e.javaClass.simpleName})",
				utilized = false,
			)
		}
	}
}
