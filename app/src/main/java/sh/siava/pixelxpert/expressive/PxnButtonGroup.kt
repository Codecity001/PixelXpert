package sh.siava.pixelxpert.expressive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Where a button sits inside a [PxnButtonGroup]; the segment shape follows from it.
 */
enum class PxnButtonGroupPosition { Start, Middle, End, Only }

/** One action inside a [PxnButtonGroup]. */
data class PxnButtonGroupItem(
	val label: String,
	val onClick: () -> Unit,
	val enabled: Boolean = true,
	val selected: Boolean = false,
)

/**
 * Connected row of buttons - the local counterpart to `androidx.compose.material3
 * .ButtonGroup`, which does not exist on the stable material3 1.4.0 line.
 *
 * Segments share one outline: the outer ends are rounded, middles are square so
 * their borders line up into dividers.
 */
@Composable
fun PxnButtonGroup(
	modifier: Modifier = Modifier,
	items: List<PxnButtonGroupItem>,
	contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
	segmentShape: Dp = 16.dp,
) {
	if (items.isEmpty()) return

	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(0.dp),
	) {
		items.forEachIndexed { index, item ->
			PxnButtonGroupSegment(
				item = item,
				position = segmentPosition(index, items.size),
				contentPadding = contentPadding,
				segmentShape = segmentShape,
			)
		}
	}
}

private fun segmentPosition(index: Int, count: Int): PxnButtonGroupPosition = when {
	count == 1 -> PxnButtonGroupPosition.Only
	index == 0 -> PxnButtonGroupPosition.Start
	index == count - 1 -> PxnButtonGroupPosition.End
	else -> PxnButtonGroupPosition.Middle
}

fun segmentShapeFor(position: PxnButtonGroupPosition, radius: Dp): Shape = when (position) {
	PxnButtonGroupPosition.Only -> RoundedCornerShape(radius)
	PxnButtonGroupPosition.Start -> RoundedCornerShape(topStart = radius, bottomStart = radius)
	PxnButtonGroupPosition.End -> RoundedCornerShape(topEnd = radius, bottomEnd = radius)
	PxnButtonGroupPosition.Middle -> RectangleShape
}

@Composable
private fun RowScope.PxnButtonGroupSegment(
	item: PxnButtonGroupItem,
	position: PxnButtonGroupPosition,
	contentPadding: PaddingValues,
	segmentShape: Dp,
) {
	val colors = MaterialTheme.colorScheme

	OutlinedButton(
		onClick = item.onClick,
		enabled = item.enabled,
		shape = segmentShapeFor(position, segmentShape),
		border = BorderStroke(1.dp, colors.outlineVariant),
		contentPadding = contentPadding,
		modifier = Modifier.weight(1f, fill = false),
	) {
		Text(
			text = item.label,
			style = MaterialTheme.typography.labelLarge,
			color = if (item.selected) colors.primary else colors.onSurface,
		)
	}
}
