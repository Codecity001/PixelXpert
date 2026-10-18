package sh.siava.pixelxpert.xposed.utils.toolkit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.annotation.ColorInt

object ColorUtils {
    @ColorInt
    @JvmStatic
    fun getColorAttrDefaultColor(context: Context, attr: Int): Int {
        return getColorAttrDefaultColor(context, attr, 0)
    }

    @ColorInt
    @JvmStatic
    fun getColorAttrDefaultColor(context: Context, attr: Int, @ColorInt defValue: Int): Int {
        context.obtainStyledAttributes(intArrayOf(attr)).use { ta ->
            return ta.getColor(0, defValue)
        }
    }

    @JvmStatic
    fun getColorAttr(context: Context, attr: Int): ColorStateList? {
        context.obtainStyledAttributes(intArrayOf(attr)).use { ta ->
            return runCatching { ta.getColorStateList(0) }.getOrNull()
        }
    }

    @JvmStatic
    fun compositeAlpha(foregroundAlpha: Int, backgroundAlpha: Int): Int {
        return 0xFF - (((0xFF - backgroundAlpha) * (0xFF - foregroundAlpha)) / 0xFF)
    }

    @JvmStatic
    fun setColorBoldness(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = minOf(hsv[2] * factor, 1.0f)
        return Color.HSVToColor(hsv)
    }
}
