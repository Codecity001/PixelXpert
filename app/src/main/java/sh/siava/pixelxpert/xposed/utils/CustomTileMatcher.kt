package sh.siava.pixelxpert.xposed.utils

import android.content.ComponentName
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField

/**
 * Identifies the live `CustomTile` that belongs to one of the module's tile
 * services.
 *
 * A tile is created once by `QSFactoryImpl.createTile` and then survives for
 * the life of the SystemUI process. Hot reload creates new mod pack instances,
 * so remembering the tile only in `createTile` would leave those new packs
 * unable to match the already-created tile. The component field is stable
 * framework state, which lets the new generation adopt the same live tile on
 * its first callback instead.
 */
object CustomTileMatcher {

	@JvmStatic
	fun matches(tile: Any?, serviceClass: Class<*>): Boolean {
		if (tile == null) return false

		val component = runCatching {
			getObjectField(tile, "mComponent") as? ComponentName
		}.getOrNull()

		return component?.className == serviceClass.name ||
			component?.className?.endsWith(".${serviceClass.simpleName}") == true
	}
}
