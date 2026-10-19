package sh.siava.pixelxpert.xposed.utils.toolkit

import android.content.Context
import android.graphics.Color
import androidx.annotation.ColorInt
import kotlin.math.min

/**
 * The theme attribute lookup SystemUI uses for its on-wallpaper colours, plus
 * the colour maths helpers used when styling tiles and bars.
 */
object ColorUtils {
	@JvmStatic
	@ColorInt
	fun getColorAttrDefaultColor(context: Context, attr: Int): Int {
		return getColorAttrDefaultColor(context, attr, 0)
	}

	@JvmStatic
	@ColorInt
	fun getColorAttrDefaultColor(context: Context, attr: Int, @ColorInt defValue: Int): Int {
		context.obtainStyledAttributes(intArrayOf(attr)).use { ta ->
			@ColorInt val colorAccent = ta.getColor(0, defValue)
			return colorAccent
		}
	}

	@JvmStatic
	fun setColorBoldness(color: Int, factor: Float): Int {
		val hsv = FloatArray(3)
		Color.colorToHSV(color, hsv)
		hsv[2] = min(hsv[2] * factor, 1.0f) // Increase the V (value) component
		return Color.HSVToColor(hsv)
	}
}
