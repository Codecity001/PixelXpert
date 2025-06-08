package sh.siava.pixelxpert.utils;

import sh.siava.pixelxpert.PixelXpert;

public class DisplayUtils {
	public static boolean isTablet() {
		return PixelXpert.get().getResources().getConfiguration().smallestScreenWidthDp >= 600;
	}
}