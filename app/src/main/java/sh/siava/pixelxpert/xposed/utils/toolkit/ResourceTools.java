package sh.siava.pixelxpert.xposed.utils.toolkit;

import static de.robv.android.xposed.XposedBridge.log;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.TypedValue;

public class ResourceTools {
	public static Float getSpTextSizeFromStyle(Context context, String styleName) {
		// Define the attribute we want to retrieve
		int[] attrsToQuery = new int[]{android.R.attr.textSize};

		int styleResId = getStyleID(context, styleName);

		// Obtain the styled attributes for the given style resource
		try (TypedArray typedArray = context.obtainStyledAttributes(styleResId, attrsToQuery)){
			// Check if the attribute was found in the style
			if (typedArray.hasValue(0)) {
				TypedValue outValue = new TypedValue();
				// Get the raw TypedValue for the attribute (index 0 because we only queried one attr)
				typedArray.getValue(0, outValue);

				if (outValue.type == TypedValue.TYPE_DIMENSION) {
					// Check if the unit is SP
					if (outValue.getComplexUnit() == TypedValue.COMPLEX_UNIT_SP) {
						return TypedValue.complexToFloat(outValue.data);
					}
				}
			}
		}
		return null;
	}

	public static int getStyleID(Context context, String styleName)
	{
		return context.getResources().getIdentifier(styleName, "style", context.getPackageName());
	}

	public static int dpToPx(Context context, int dp) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
	}

}
