package sh.siava.pixelxpert.xposed.utils.toolkit

import android.content.Context
import android.content.res.Resources
import android.util.DisplayMetrics

/**
 * A [Resources] that only exists so that subclasses can answer [getInteger]
 * from code rather than from a real resource table. The super constructor
 * still wants the usual trio (assets, fresh metrics, the context's
 * configuration), so the primary constructor wires those up and leaves the
 * integer lookup abstract for each subclass to provide.
 */
abstract class FakeIntegerResource(context: Context) :
	Resources(
		context.assets,
		DisplayMetrics(),
		context.resources.configuration,
	) {
	abstract override fun getInteger(id: Int): Int
}
