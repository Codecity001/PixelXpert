package sh.siava.pixelxpert.xposed.utils.toolkit;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;

public abstract class FakeIntegerResource extends Resources {
	public FakeIntegerResource(Context context) {
		super(context.getAssets(), new DisplayMetrics(), context.getResources().getConfiguration());
	}

	@Override
	public abstract int getInteger(int id);
}
