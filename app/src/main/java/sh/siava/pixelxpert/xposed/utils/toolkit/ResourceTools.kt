package sh.siava.pixelxpert.xposed.utils.toolkit

import android.content.Context
import android.content.res.TypedArray
import android.util.TypedValue

/**
 * Small helpers around the resource system: resolving a style resource id by
 * name, reading the `textSize` a style declares (only when it is expressed in
 * sp, which is what the font scaling hooks key off), and converting dp to px.
 * They exist because callers need the styled value behind a theme style, not
 * the resolved pixel size a theme would hand back.
 */
object ResourceTools {
	/**
	 * The sp `textSize` declared by style [styleName], or null when the style
	 * has no such attribute or its unit is not sp.
	 */
	@JvmStatic
	fun getSpTextSizeFromStyle(context: Context, styleName: String): Float? {
		// Define the attribute we want to retrieve
		val attrsToQuery = intArrayOf(android.R.attr.textSize)

		val styleResId = getStyleID(context, styleName)

		// Obtain the styled attributes for the given style resource
		context.obtainStyledAttributes(styleResId, attrsToQuery).use { typedArray ->
			// Check if the attribute was found in the style
			if (typedArray.hasValue(0)) {
				val outValue = TypedValue()
				// Get the raw TypedValue for the attribute (index 0 because we only queried one attr)
				typedArray.getValue(0, outValue)

				if (outValue.type == TypedValue.TYPE_DIMENSION) {
					// Check if the unit is SP
					if (outValue.complexUnit == TypedValue.COMPLEX_UNIT_SP) {
						return TypedValue.complexToFloat(outValue.data)
					}
				}
			}
		}
		return null
	}

	@JvmStatic
	fun getStyleID(context: Context, styleName: String): Int =
		context.resources.getIdentifier(styleName, "style", context.packageName)

	@JvmStatic
	fun dpToPx(context: Context, dp: Int): Int =
		TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics).toInt()
}
