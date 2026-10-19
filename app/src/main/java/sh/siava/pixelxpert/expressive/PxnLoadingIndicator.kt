package sh.siava.pixelxpert.expressive

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.siava.pixelxpert.theme.LocalPxnMotionScheme

/**
 * Indeterminate loading indicator.
 *
 * Stands in for `androidx.compose.material3.LoadingIndicator`, which does not
 * exist on the stable material3 1.4.0 line (only `LoadingIndicatorTokens` does).
 * A sweeping arc rotates continuously while its sweep length breathes using the
 * active [PxnMotionScheme]'s easings, so it reacts to the expressive/standard
 * toggle like the rest of the UI.
 */
@Composable
fun PxnLoadingIndicator(
	modifier: Modifier = Modifier,
	size: Dp = 32.dp,
	strokeWidth: Dp = 4.dp,
	color: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
) {
	val motion = LocalPxnMotionScheme.current
	val transition = rememberInfiniteTransition(label = "pxnLoadingIndicator")

	val rotation by transition.animateFloat(
		initialValue = 0f,
		targetValue = 360f,
		animationSpec = infiniteRepeatable(
			animation = tween(durationMillis = motion.spatialSlowMillis, easing = LinearEasing),
			repeatMode = RepeatMode.Restart,
		),
		label = "rotation",
	)

	val sweep by transition.animateFloat(
		initialValue = 0.3f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(
			animation = tween(
				durationMillis = motion.effectsSlowMillis * 2,
				easing = motion.effectsEasing,
			),
			repeatMode = RepeatMode.Reverse,
		),
		label = "sweep",
	)

	Canvas(
		modifier = modifier
			.size(size)
			.semantics { contentDescription = "Loading" },
	) {
		val stroke = strokeWidth.toPx()
		val arcSize = Size(this.size.width - stroke, this.size.height - stroke)

		drawArc(
			color = color,
			startAngle = rotation,
			sweepAngle = 360f * sweep,
			useCenter = false,
			topLeft = Offset(stroke / 2f, stroke / 2f),
			size = arcSize,
			style = Stroke(width = stroke, cap = StrokeCap.Round),
		)
	}
}
