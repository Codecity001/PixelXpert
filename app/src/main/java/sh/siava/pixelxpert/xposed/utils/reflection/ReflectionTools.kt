package sh.siava.pixelxpert.xposed.utils.reflection

import android.view.View
import android.view.ViewGroup
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

/**
 * Small view-tree helpers the UI mod packs share.
 *
 * Logging goes through [Logger] rather than `XposedBridge.log` because it
 * is guaranteed to reach the framework under an API-102 load, which is
 * where this module runs.
 */
@Suppress("unused", "RedundantThrows")
object ReflectionTools {

	/** Posts [runnable] after [delay], from a thread so the caller never blocks. */
	@JvmStatic
	fun runDelayedOnMainThread(viewObject: View, delay: Long, runnable: Runnable) {
		Thread {
			try {
				Thread.sleep(delay)
				viewObject.post(runnable)
			} catch (ignored: Throwable) {
			}
		}.start()
	}

	/**
	 * Detaches [childView] from wherever it sits (if anywhere) and appends it
	 * to [parentView].
	 */
	@JvmStatic
	fun reAddView(parentView: ViewGroup, childView: View?) {
		if (childView == null) return

		try {
			(childView.parent as? ViewGroup)?.removeView(childView)
		} catch (ignored: Throwable) {
		}
		parentView.addView(childView)
	}

	/**
	 * Detaches [childView] from wherever it sits and inserts it at [index] in
	 * [parentView].
	 */
	@JvmStatic
	fun reAddView(parentView: ViewGroup, childView: View?, index: Int) {
		if (childView == null) return

		try {
			(childView.parent as? ViewGroup)?.removeView(childView)
		} catch (ignored: Throwable) {
		}
		parentView.addView(childView, index)
	}

	@JvmStatic
	fun dumpParentIDs(v: View) = dumpParentIDs(v, 0)

	@JvmStatic
	fun dumpIDs(v: View) = dumpIDs(v, 0)

	private fun dumpParentIDs(v: View, level: Int) {
		dumpID(v, level)
		try {
			if (v.parent is View) {
				dumpParentIDs(v.parent as View, level + 1)
			}
		} catch (ignored: Throwable) {
		}
	}

	private fun dumpIDs(v: View, level: Int) {
		dumpID(v, level)
		if (v is ViewGroup) {
			for (i in 0 until v.childCount) {
				dumpIDs(v.getChildAt(i), level + 1)
			}
		}
	}

	private fun dumpID(v: View, level: Int) {
		var name = "**"
		val indent = "\t".repeat(level)

		try {
			name = v.context.resources.getResourceName(v.id)
		} catch (ignored: Throwable) {
		}

		Logger.log("$indent id $name type ${v.javaClass.name}")
	}
}
