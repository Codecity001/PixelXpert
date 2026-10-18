package sh.siava.pixelxpert.utils

import sh.siava.pixelxpert.PixelXpert

object DisplayUtils {
    @JvmStatic
    fun isTablet(): Boolean {
        return PixelXpert.instance.resources.configuration.smallestScreenWidthDp >= 600
    }
}
