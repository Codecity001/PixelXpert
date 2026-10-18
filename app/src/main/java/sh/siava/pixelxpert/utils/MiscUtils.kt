package sh.siava.pixelxpert.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.ColorInt
import sh.siava.pixelxpert.PixelXpert

object MiscUtils {

    const val REQUEST_IMPORT = 7
    const val REQUEST_EXPORT = 9

    @JvmStatic
    @ColorInt
    fun getColorFromAttribute(context: Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    @JvmStatic
    fun intToHex(colorValue: Int): String {
        return String.format("#%06X", 0xFFFFFF and colorValue)
    }

    @JvmStatic
    fun dpToPx(dp: Int): Int = dpToPx(dp.toFloat())

    @JvmStatic
    fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            PixelXpert.instance.resources.displayMetrics
        ).toInt()
    }

    @Suppress("DEPRECATION")
    @JvmStatic
    fun importExportSettings(activity: Activity, export: Boolean) {
        val fileIntent = Intent().apply {
            action = if (export) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_GET_CONTENT
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, "PixelXpert_Config.bin")
        }
        activity.startActivityForResult(fileIntent, if (export) REQUEST_EXPORT else REQUEST_IMPORT)
    }

    @JvmStatic
    fun weakVibrate(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
