package sh.siava.pixelxpert.xposed.utils.reflection

import android.view.View
import android.view.ViewGroup
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

object ReflectionTools {
    @JvmStatic
    fun runDelayedOnMainThread(viewObject: View?, delay: Long, runnable: Runnable) {
        if (viewObject == null) return
        Thread {
            runCatching {
                Thread.sleep(delay)
                viewObject.post(runnable)
            }
        }.start()
    }

    @JvmStatic
    fun reAddView(parentView: ViewGroup?, childView: View?) {
        if (childView == null || parentView == null) return
        runCatching {
            (childView.parent as? ViewGroup)?.removeView(childView)
        }
        parentView.addView(childView)
    }

    @JvmStatic
    fun reAddView(parentView: ViewGroup?, childView: View?, index: Int) {
        if (childView == null || parentView == null) return
        runCatching {
            (childView.parent as? ViewGroup)?.removeView(childView)
        }
        parentView.addView(childView, index)
    }

    @JvmStatic
    fun dumpParentIDs(v: View?) {
        dumpParentIDs(v, 0)
    }

    private fun dumpParentIDs(v: View?, level: Int) {
        if (v == null) return
        dumpID(v, level)
        runCatching {
            val p = v.parent
            if (p is View) {
                dumpParentIDs(p, level + 1)
            }
        }
    }

    @JvmStatic
    fun dumpIDs(v: View?) {
        dumpIDs(v, 0)
    }

    private fun dumpIDs(v: View?, level: Int) {
        if (v == null) return
        dumpID(v, level)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                dumpIDs(v.getChildAt(i), level + 1)
            }
        }
    }

    private fun dumpID(v: View, level: Int) {
        var name = "**"
        val str = "\t".repeat(level)
        runCatching {
            name = v.context.resources.getResourceName(v.id)
        }
        Logger.log("$str id $name type ${v.javaClass.name}")
    }
}
