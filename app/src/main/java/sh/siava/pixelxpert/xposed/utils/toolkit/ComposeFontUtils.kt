package sh.siava.pixelxpert.xposed.utils.toolkit

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.sp
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection

/**
 * Scales the Quick Settings tile typography for the font-size preference: it
 * reads the size baked into the tile label / subtitle styles, works out which
 * of the two scale factors applies, and returns the scaled Compose font size
 * packed the way `SpanStyle.fontSize` stores it. The packing is done
 * reflectively because that value is internal to Compose.
 */
class ComposeFontUtils {
	companion object {
		var TILE_LABEL_STYLE = "TextAppearance.QS.TileLabel"
		var TILE_SUBTITLE_STYLE = "TextAppearance.TileDetailsEntrySubTitle"

		@SuppressLint("DiscouragedApi")
		fun scaleTileFont(context: Context, currentSize: Long, labelScale: Float, secondaryLabelScale: Float): Long {
			try {
				var originalSpanStyle = SpanStyle(fontSize = 1.sp)
				Reflection.setObjectField(originalSpanStyle, "fontSize", currentSize)

				var originalFontSizeSP = originalSpanStyle.fontSize.value

				var scaledFontSizeSP = originalFontSizeSP

				if (originalFontSizeSP == ResourceTools.getSpTextSizeFromStyle(context, TILE_LABEL_STYLE)) {
					scaledFontSizeSP = originalFontSizeSP * labelScale
				} else if (originalFontSizeSP == ResourceTools.getSpTextSizeFromStyle(context, TILE_SUBTITLE_STYLE)) {
					scaledFontSizeSP = originalFontSizeSP * secondaryLabelScale
				}

				var scaledSpanStyle = SpanStyle(fontSize = scaledFontSizeSP.sp)

				return Reflection.getObjectField(scaledSpanStyle.fontSize, "packedValue") as Long
			} catch (_: Throwable) {
				return currentSize
			}
		}
	}
}
