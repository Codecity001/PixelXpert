package sh.siava.pixelxpert.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedInterface.Invoker
import io.github.libxposed.api.XposedModule
import sh.siava.pixelxpert.xposed.XposedGeneration.HookOptions
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide bridge between the module code and the libxposed API.
 *
 * Every access to the framework goes through here. That is what keeps the
 * move from API 101 to 102 in one place: there is no second hook engine, no
 * legacy `XposedBridge` compatibility layer, and no static field outside a
 * generation that a hot reload could leave behind.
 */
object XposedRuntime {
	private const val TAG = "PixelXpert"

	private val sequence = AtomicLong()
	private val additionalInstanceFields =
		Collections.synchronizedMap(WeakHashMap<Any, MutableMap<String, Any?>>())

	/** The generation currently servicing this process. */
	@Volatile
	var generation: XposedGeneration? = null
		private set

	val api: XposedInterface?
		get() = generation?.api

	val processName: String
		get() = generation?.processName.orEmpty()

	val isSystemServer: Boolean
		get() = generation?.isSystemServer == true

	/** True while the current generation may still run module work. */
	fun isActive(): Boolean = generation?.isActive() ?: true

	fun attach(module: XposedModule, processName: String, isSystemServer: Boolean): XposedGeneration {
		val previous = generation
		if (previous != null && !previous.isRetired) {
			// A new entry without a hot reload means the framework loaded the
			// module twice. Retire the old generation cleanly rather than let
			// two copies fight over the same hooks.
			previous.retire()
		}

		val next =
			XposedGeneration(
				module = module,
				api = module,
				processName = processName,
				isSystemServer = isSystemServer,
				sequence = sequence.incrementAndGet(),
			)
		generation = next
		log(
			Log.INFO,
			"Loaded generation ${next.sequence} in $processName (systemServer=$isSystemServer)",
		)
		return next
	}

	/** Re-binds the current generation after the framework attached a new entry. */
	fun rebind(module: XposedModule) {
		val current = generation ?: return
		generation =
			XposedGeneration(
				module = module,
				api = module,
				processName = current.processName,
				isSystemServer = current.isSystemServer,
				sequence = sequence.incrementAndGet(),
			)
	}

	/**
	 * Retires the current generation and removes every hook it owns.
	 *
	 * Called when the module explicitly detaches, not on hot reload: there the
	 * successor owns the replacement step.
	 */
	fun detach() {
		val current = generation ?: return
		current.retire()
		current.unhookAll()
		generation = null
	}

	fun requireApi(): XposedInterface = api ?: throw IllegalStateException("Xposed API is not attached in this process")

	/**
	 * Runs the original implementation, bypassing the hook chain.
	 *
	 * Uses the API 102 invoker with [Invoker.Type.ORIGIN] rather than a legacy
	 * bridge call. Invocation targets are unwrapped so callers keep seeing the
	 * exception the hooked method actually threw.
	 */
	@Throws(Throwable::class)
	fun invokeOriginal(method: Method, thisObject: Any?, vararg args: Any?): Any? {
		val invoker = requireApi().getInvoker(method).setType(Invoker.Type.ORIGIN)
		return try {
			invoker.invoke(thisObject, *args)
		} catch (t: InvocationTargetException) {
			throw t.targetException ?: t
		}
	}

	/** Installs a hook, tracking it when it belongs to the current generation. */
	fun hook(api: XposedInterface, executable: Executable, options: HookOptions, hooker: Hooker): HookHandle {
		val current = generation
		return if (current != null && current.api === api) {
			current.hook(executable, options, hooker)
		} else {
			// An explicit interface was supplied (tests or a future second
			// entry). Still use the 102 builder, just without generation
			// bookkeeping.
			api.hook(executable)
				.setPriority(options.priority)
				.setExceptionMode(options.exceptionMode)
				.setId(options.id)
				.also { options.configure?.invoke(it) }
				.intercept(hooker)
		}
	}

	fun log(priority: Int, text: String, throwable: Throwable? = null) {
		val currentApi = api
		if (currentApi != null) {
			try {
				currentApi.log(priority, TAG, text, throwable)
				return
			} catch (_: Throwable) {
				// The framework may already have removed this generation while
				// it is being retired. Fall through to logcat.
			}
		}

		if (throwable == null) {
			Log.println(priority, TAG, text)
		} else {
			Log.println(priority, TAG, "$text\n${Log.getStackTraceString(throwable)}")
		}
	}

	/** Legacy-compatible additional instance fields, scoped to this generation. */
	fun getAdditionalInstanceField(instance: Any?, key: String): Any? {
		val target = instance ?: return null
		return synchronized(additionalInstanceFields) { additionalInstanceFields[target]?.get(key) }
	}

	fun setAdditionalInstanceField(instance: Any?, key: String, value: Any?): Any? {
		val target = instance ?: return null
		return synchronized(additionalInstanceFields) {
			val fields = additionalInstanceFields.getOrPut(target) { HashMap() }
			fields.put(key, value)
		}
	}

	fun removeAdditionalInstanceField(instance: Any?, key: String): Any? {
		val target = instance ?: return null
		return synchronized(additionalInstanceFields) {
			val fields = additionalInstanceFields[target] ?: return null
			val removed = fields.remove(key)
			if (fields.isEmpty()) additionalInstanceFields.remove(target)
			removed
		}
	}
}
