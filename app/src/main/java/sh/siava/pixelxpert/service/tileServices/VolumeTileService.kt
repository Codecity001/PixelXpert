package sh.siava.pixelxpert.service.tileServices

import android.service.quicksettings.TileService

/**
 * Manifest entry point for the Media Volume Quick Settings tile. Like the
 * other tile services it only exists so SystemUI has a component to bind:
 * the VolumeTile modpack intercepts every callback below, so each override
 * here simply forwards to the superclass.
 */
class VolumeTileService : TileService() {
	override fun onTileAdded() {
		super.onTileAdded()
	}

	override fun onStartListening() {
		super.onStartListening()
	}

	override fun onStopListening() {
		super.onStopListening()
	}

	override fun onClick() {
		super.onClick()
	}

	override fun onTileRemoved() {
		super.onTileRemoved()
	}
}
