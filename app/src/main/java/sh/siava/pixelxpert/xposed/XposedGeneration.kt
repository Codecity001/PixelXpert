package sh.siava.pixelxpert.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything one loaded copy of the module owns.
 *
 * A hot reload never mutates a generation: it retires the old instance and lets
 * a fresh one take over. That makes ownership explicit and gives every hook,
 * thread, receiver and callback exactly one place to be released from.
 */
class XposedGeneration(
	val module: XposedModule,
	val api: XposedInterface,
	val processName: String,
	val isSystemServer: Boolean,
	val sequence: Long,
) {
	private val onceKeys = ConcurrentHashMap.newKeySet<String>()
	private val cleanups = CopyOnWriteArrayList<Cleanup>()
	private val threads = ConcurrentHashMap.newKeySet<Thread>()
	private val hooks = CopyOnWriteArrayList<HookHandle>()
	private val nextCleanupId = AtomicLong()

	@Volatile
	var isRetired: Boolean = false
		private set

	fun isActive(): Boolean = !isRetired

	/**
	 * Runs [action] once per generation for [key].
	 *
	 * A failed action releases the key again, so a transient failure does not
	 * permanently mark the work as done.
	 */
	fun installOnce(key: String, action: () -> Unit): Boolean {
		checkActive()
		if (!onceKeys.add(key)) return false

		try {
			action()
		} catch (t: Throwable) {
			onceKeys.remove(key)
			throw t
		}
		return true
	}

	fun registerCleanup(owner: Any? = null, action: () -> Unit): Long {
		val id = nextCleanupId.incrementAndGet()
		cleanups += Cleanup(id, owner, action)
		return id
	}

	fun unregisterCleanup(id: Long) {
		cleanups.removeAll { it.id == id }
	}

	/** Starts a tracked thread. Retiring the generation interrupts every tracked thread. */
	fun startThread(name: String, daemon: Boolean = true, block: () -> Unit): Thread {
		checkActive()

		val thread = Thread({
			try {
				block()
			} catch (_: InterruptedException) {
				// Retirement interrupts threads so blocking calls wake up.
			} catch (t: Throwable) {
				Log.e(TAG, "Uncaught error in module thread $name", t)
			} finally {
				threads.remove(Thread.currentThread())
			}
		}, name)
		thread.isDaemon = daemon
		threads += thread
		thread.start()
		return thread
	}

	/**
	 * Installs a hook through the API 102 builder.
	 *
	 * The id is what makes a hot reload seamless: the next generation registers
	 * the same id and the framework swaps the hooker atomically instead of
	 * stacking a second hook on the executable.
	 */
	fun hook(executable: Executable, options: HookOptions, hooker: Hooker): HookHandle {
		checkActive()

		val builder = api.hook(executable)
			.setPriority(options.priority)
			.setExceptionMode(options.exceptionMode)
			.setId(options.id)
		options.configure?.invoke(builder)

		val handle = builder.intercept(hooker)
		hooks += handle
		return handle
	}

	/**
	 * Stops all module-owned work while leaving hooks installed.
	 *
	 * Hooks are intentionally not removed here. The replacement generation
	 * re-registers the same ids, which lets the framework atomically replace
	 * them; whatever it does not replace is unhooked from
	 * `HotReloadedParam.oldHookHandles` once the new generation has finished.
	 */
	fun retire() {
		if (isRetired) return
		isRetired = true

		cleanups.asReversed().forEach { cleanup ->
			runCatching(cleanup.action)
				.onFailure { Log.e(TAG, "Generation cleanup failed", it) }
		}
		cleanups.clear()

		threads.forEach { it.interrupt() }
		threads.clear()
	}

	/** Removes every hook this generation owns. Used when the module itself detaches. */
	fun unhookAll() {
		hooks.forEach { handle -> runCatching(handle::unhook) }
		hooks.clear()
	}

	private fun checkActive() {
		check(!isRetired) { "This module generation has been retired" }
	}

	private class Cleanup(val id: Long, val owner: Any?, val action: () -> Unit)

	/** Immutable description of one hook registration. */
	data class HookOptions(
		val id: String?,
		val priority: Int = XposedInterface.PRIORITY_DEFAULT,
		val exceptionMode: ExceptionMode = ExceptionMode.DEFAULT,
		val configure: ((HookBuilder) -> Unit)? = null,
	)

	companion object {
		private const val TAG = "PixelXpert"
	}
}
