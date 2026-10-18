package sh.siava.pixelxpert.service.tileServices

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.lang.ref.WeakReference
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.utils.PXPreferences

class SleepOnSurfaceTileService : TileService() {

    private var mLastEnabled = false

    companion object {
        private var instance: WeakReference<SleepOnSurfaceTileService>? = null

        @JvmStatic
        fun onPrefsChanged() {
            runCatching {
                instance?.get()?.updateTile()
            }
        }
    }

    init {
        instance = WeakReference(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        setTile(!mLastEnabled)
        Thread {
            PXPreferences.putBoolean("SleepOnFlatScreen", mLastEnabled)
        }.start()
    }

    private fun updateTile() {
        setTile(PXPreferences.getBoolean("SleepOnFlatScreen", false))
    }

    private fun setTile(enabled: Boolean) {
        if (mLastEnabled == enabled) return
        mLastEnabled = enabled

        val thisTile = qsTile ?: return
        thisTile.icon = Icon.createWithResource(
            applicationContext,
            if (enabled) R.drawable.ic_sleep_surface_on else R.drawable.ic_sleep_surface_off
        )
        thisTile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        thisTile.subtitle = getString(if (enabled) R.string.general_on else R.string.general_off)
        thisTile.label = getString(R.string.sleep_on_flat_screen_tile_title)
        thisTile.updateTile()
    }
}
