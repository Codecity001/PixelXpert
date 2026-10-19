package sh.siava.pixelxpert.service.tileServices

import android.service.quicksettings.TileService

/**
 * Manifest entry point for the Caffeine Quick Settings tile. The class is
 * deliberately empty: SystemUI only needs a component to bind, because the
 * CaffeineTile modpack intercepts the tile's callbacks and handles them
 * entirely inside the hooked process.
 */
class CaffeineTileService : TileService()
