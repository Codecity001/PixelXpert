package sh.siava.pixelxpert.xposed.utils

import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PathEffect
import sh.siava.pixelxpert.xposed.utils.toolkit.ColorUtils.setColorBoldness

/**
 * When setting a paint's color, alpha gets reset... naturally. So this is
 * kind of paint that remembers its alpha and keeps it intact: every
 * [setAlpha] / [setColor] is recorded here and the two are recombined into
 * the colour actually handed to [Paint], with the dashed path effect getting
 * a slight boldness boost while it is active.
 */
class AlphaAndColorBalancedPaint(flag: Int) : Paint(flag) {
	companion object {
		@JvmStatic
		val DASH_PATH_EFFECT: PathEffect = DashPathEffect(floatArrayOf(3f, 2f), 0f)
		const val DASH_PATH_EFFECT_BOLDNESS_FACTOR = 1.1f
	}

	internal var mAlpha: Int = super.getAlpha()
	internal var mColor: Int = super.getColor()

	override fun setAlpha(alpha: Int) {
		mAlpha = alpha
		refreshColor()
	}

	override fun setColor(color: Int) {
		mColor = color
		refreshColor()
	}

	private fun refreshColor() {
		val color = if (DASH_PATH_EFFECT == getPathEffect()) {
			setColorBoldness(mColor, DASH_PATH_EFFECT_BOLDNESS_FACTOR)
		} else {
			mColor
		}

		super.setColor(
			Color.argb(
				combinedAlpha(mAlpha, Color.alpha(color)),
				Color.red(color),
				Color.green(color),
				Color.blue(color),
			),
		)
	}

	private fun combinedAlpha(alpha1: Int, alpha2: Int): Int = Math.round((alpha1 / 255f) * (alpha2 / 255f) * 255f)
}
