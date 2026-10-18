package sh.siava.pixelxpert.service.tileServices

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import sh.siava.pixelxpert.Constants

class AppProfileSwitchTileService : TileService() {

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()

        val tile = qsTile ?: return
        tile.state = Tile.STATE_UNAVAILABLE
        tile.updateTile()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startActivityAndCollapse(pendingIntent)
        sendBroadcastAsync()
    }

    private fun sendBroadcastAsync() {
        Thread {
            sendBroadcast(Constants.getAppProfileSwitchIntent())
            val tile = qsTile ?: return@Thread
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }.start()
    }
}
