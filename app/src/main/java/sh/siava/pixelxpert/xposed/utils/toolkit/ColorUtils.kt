package sh.siava.pixelxpert.xposed.utils.toolkit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.annotation.ColorInt
import kotlin.math.min

/**
 * Resolves theme attributes to colours (or their state lists) so call sites
 * do not have to open a TypedArray by hand, plus two small colour maths
 * helpers used when styling tiles and bars. The attribute lookups swallow any
 * Throwable a missing or malformed attribute would otherwise throw and fall
 * back to the default they were given.
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
	fun getColorAttr(context: Context, attr: Int): ColorStateList? {
		context.obtainStyledAttributes(intArrayOf(attr)).use { ta ->
			//noinspection ReassignedVariable
			var stateList: ColorStateList? = null
			try {
				stateList = ta.getColorStateList(0)
			} catch (ignored: Throwable) {
			}
			return stateList
		}
	}

	//noinspection unused
	@JvmStatic
	fun compositeAlpha(foregroundAlpha: Int, backgroundAlpha: Int): Int {
		return 0xFF - ((0xFF - backgroundAlpha) * (0xFF - foregroundAlpha)) / 0xFF
	}

	@JvmStatic
	fun setColorBoldness(color: Int, factor: Float): Int {
		val hsv = FloatArray(3)
		Color.colorToHSV(color, hsv)
		hsv[2] = min(hsv[2] * factor, 1.0f) // Increase the V (value) component
		return Color.HSVToColor(hsv)
	}
}
