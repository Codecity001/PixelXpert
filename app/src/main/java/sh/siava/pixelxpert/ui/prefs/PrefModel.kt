package sh.siava.pixelxpert.ui.prefs

import sh.siava.pixelxpert.ui.prefs.node.PrefNode

/**
 * A single settings screen, expressed declaratively. The declarations below are
 * the source of truth for the screen; edit them directly.
 *
 * Rendering is handled by a single generic renderer rather than a bespoke
 * composable per screen.
 *
 * @property key   stable identifier, also used as the Navigation3 route id.
 * @property title optional @StringRes heading; 0 means "no heading".
 * @property nodes ordered top-to-bottom as they appear on the screen.
 */
data class PrefScreen(
	val key: String,
	val nodes: List<PrefNode>,
	val title: Int = 0,
) {
	/**
	 * Flattens the tree depth-first so the renderer can drive a single
	 * LazyColumn while keeping each node attached to its category.
	 */
	fun flatten(): List<FlattenedNode> {
		val out = ArrayList<FlattenedNode>(nodes.size * 2)
		fun walk(list: List<PrefNode>, heading: Int) {
			for (node in list) {
				if (node is sh.siava.pixelxpert.ui.prefs.node.Category) {
					out += FlattenedNode(node, heading)
					walk(node.children, node.title)
				} else {
					out += FlattenedNode(node, heading)
				}
			}
		}
		walk(nodes, 0)
		return out
	}
}

/**
 * One node together with the @StringRes of the category header it belongs to
 * (0 when the node sits outside any category).
 */
data class FlattenedNode(
	val node: PrefNode,
	val heading: Int,
)
