package sh.siava.pixelxpert

import androidx.compose.runtime.Composable
import sh.siava.pixelxpert.ui.nav.PxnNav
import sh.siava.pixelxpert.ui.nav.Route

/**
 * Root of the Compose UI.
 *
 * Hosts the Navigation3 backstack: the module home plus all 21 preference
 * screens are rendered through [PxnNav].
 *
 * [startRoute] is the destination an incoming intent asked for. It is passed
 * in rather than read from a process-wide holder because every activity
 * instance composes its own tree: with a shared holder, an instance left
 * behind by a second notification tap could consume the request before the
 * instance that was actually launched for it.
 */
@Composable
fun PxnRoot(startRoute: Route? = null) {
	PxnNav(startRoute = startRoute)
}
