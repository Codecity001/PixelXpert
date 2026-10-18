package sh.siava.pixelxpert.xposed.utils.toolkit

import android.content.Context
import android.content.res.Resources
import android.util.DisplayMetrics

abstract class FakeIntegerResource(context: Context) :
    Resources(context.assets, DisplayMetrics(), context.resources.configuration) {
    abstract override fun getInteger(id: Int): Int
}
