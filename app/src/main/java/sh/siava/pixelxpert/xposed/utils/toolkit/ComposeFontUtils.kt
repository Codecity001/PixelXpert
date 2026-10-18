package sh.siava.pixelxpert.xposed.utils.toolkit

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.sp
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper

class ComposeFontUtils {
    companion object {
        var TILE_LABEL_STYLE = "TextAppearance.QS.TileLabel"
        var TILE_SUBTITLE_STYLE = "TextAppearance.TileDetailsEntrySubTitle"

        @SuppressLint("DiscouragedApi")
        fun scaleTileFont(
            context: Context,
            currentSize: Long,
            labelScale: Float,
            secondaryLabelScale: Float
        ): Long {
            return try {
                val originalSpanStyle = SpanStyle(fontSize = 1.sp)
                HookHelper.setObjectField(originalSpanStyle, "fontSize", currentSize)

                val originalFontSizeSP = originalSpanStyle.fontSize.value
                var scaledFontSizeSP = originalFontSizeSP

                if (originalFontSizeSP == ResourceTools.getSpTextSizeFromStyle(context, TILE_LABEL_STYLE)) {
                    scaledFontSizeSP = originalFontSizeSP * labelScale
                } else if (originalFontSizeSP == ResourceTools.getSpTextSizeFromStyle(context, TILE_SUBTITLE_STYLE)) {
                    scaledFontSizeSP = originalFontSizeSP * secondaryLabelScale
                }

                val scaledSpanStyle = SpanStyle(fontSize = scaledFontSizeSP.sp)
                HookHelper.getObjectField<Long>(scaledSpanStyle.fontSize, "packedValue") ?: currentSize
            } catch (_: Throwable) {
                currentSize
            }
        }
    }
}