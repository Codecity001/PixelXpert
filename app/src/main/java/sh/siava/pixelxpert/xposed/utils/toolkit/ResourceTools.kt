package sh.siava.pixelxpert.xposed.utils.toolkit

import android.content.Context
import android.util.TypedValue

object ResourceTools {
    @JvmStatic
    fun getSpTextSizeFromStyle(context: Context, styleName: String): Float? {
        val attrsToQuery = intArrayOf(android.R.attr.textSize)
        val styleResId = getStyleID(context, styleName)

        context.obtainStyledAttributes(styleResId, attrsToQuery).use { typedArray ->
            if (typedArray.hasValue(0)) {
                val outValue = TypedValue()
                typedArray.getValue(0, outValue)
                if (outValue.type == TypedValue.TYPE_DIMENSION && outValue.complexUnit == TypedValue.COMPLEX_UNIT_SP) {
                    return TypedValue.complexToFloat(outValue.data)
                }
            }
        }
        return null
    }

    @JvmStatic
    fun getStyleID(context: Context, styleName: String): Int {
        return context.resources.getIdentifier(styleName, "style", context.packageName)
    }

    @JvmStatic
    fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
