package sh.siava.pixelxpert.ui.prefs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import sh.siava.pixelxpert.data.PrefStore

/**
 * Visual indicators overlaid along the screen edges while configuring gesture navigation.
 *
 * Implements Option 2:
 * - Sleek vertical bars along the left and right edges for back gestures.
 * - Solid initiation bars along the bottom navbar showing swipe trigger width.
 * - Floating dashed threshold lines with percentage chips indicating the swipe-up release height.
 * - Center area remains 100% open and transparent to preserve full UI legibility.
 *
 * All shapes update live as preferences change and do not intercept touch input.
 */
@Composable
fun BoxScope.GestureNavIndicators(store: PrefStore) {
	val revision by store.revision.collectAsState()
	@Suppress("UNUSED_VARIABLE") val ignored = revision

	val configuration = LocalConfiguration.current
	val screenWidth = configuration.screenWidthDp.dp
	val screenHeight = configuration.screenHeightDp.dp

	val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

	val context = LocalContext.current
	val navBarHeight = remember(context, navBarBottom) {
		var resHeight = 0.dp
		try {
			val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
			if (resourceId > 0) {
				resHeight = (context.resources.getDimensionPixelSize(resourceId) / context.resources.displayMetrics.density).dp
			}
		} catch (_: Throwable) {
		}
		maxOf(navBarBottom, resHeight, 24.dp)
	}

	val accentInt = remember(context) {
		try {
			context.getColor(android.R.color.system_accent1_300)
		} catch (_: Throwable) {
			0
		}
	}
	val baseColor = if (accentInt != 0) Color(accentInt) else MaterialTheme.colorScheme.primary

	val fillColor = baseColor.copy(alpha = 0.45f)
	val borderColor = baseColor.copy(alpha = 0.85f)
	val edgeBarWidth = 22.dp

	// Back gestures
	val backLeftEnabled = store.getBoolean("BackFromLeft", true)
	val backLeftPercent = store.getSliderInt("BackLeftHeight", 100).coerceIn(10, 100)
	val backLeftTargetHeight = (screenHeight - navBarHeight) * (backLeftPercent / 100f)
	val animatedBackLeftHeight by animateDpAsState(
		targetValue = backLeftTargetHeight,
		animationSpec = tween(150),
		label = "backLeftHeight",
	)

	val backRightEnabled = store.getBoolean("BackFromRight", true)
	val backRightPercent = store.getSliderInt("BackRightHeight", 100).coerceIn(10, 100)
	val backRightTargetHeight = (screenHeight - navBarHeight) * (backRightPercent / 100f)
	val animatedBackRightHeight by animateDpAsState(
		targetValue = backRightTargetHeight,
		animationSpec = tween(150),
		label = "backRightHeight",
	)

	// Swipe gestures
	val leftSwipeAction = store.getString("leftSwipeUpAction", "-1")
	val leftSwipeEnabled = leftSwipeAction != "-1"
	val leftSwipePercent = store.getSliderFloat("leftSwipeUpPercentage", 25f).coerceIn(5f, 50f)
	val leftSwipeTargetWidth = screenWidth * (leftSwipePercent / 100f)
	val animatedLeftSwipeWidth by animateDpAsState(
		targetValue = leftSwipeTargetWidth,
		animationSpec = tween(150),
		label = "leftSwipeWidth",
	)

	val rightSwipeAction = store.getString("rightSwipeUpAction", "-1")
	val rightSwipeEnabled = rightSwipeAction != "-1"
	val rightSwipePercent = store.getSliderFloat("rightSwipeUpPercentage", 25f).coerceIn(5f, 50f)
	val rightSwipeTargetWidth = screenWidth * (rightSwipePercent / 100f)
	val animatedRightSwipeWidth by animateDpAsState(
		targetValue = rightSwipeTargetWidth,
		animationSpec = tween(150),
		label = "rightSwipeWidth",
	)

	val swipeUpPercent = store.getSliderFloat("swipeUpPercentage", 20f).coerceIn(5f, 50f)
	val swipeUpTargetHeight = (screenHeight * (swipeUpPercent / 100f)).coerceAtLeast(navBarHeight + 12.dp)
	val animatedSwipeHeight by animateDpAsState(
		targetValue = swipeUpTargetHeight,
		animationSpec = tween(150),
		label = "swipeHeight",
	)

	val activeSlider by store.activeSliderKey.collectAsState()
	val isAdjustingSwipeHeight = activeSlider == "swipeUpPercentage"

	// Overlay container: passive and non-blocking
	Box(modifier = Modifier.fillMaxSize()) {
		// Left Back Indicator
		AnimatedVisibility(
			visible = backLeftEnabled,
			enter = fadeIn(tween(200)),
			exit = fadeOut(tween(200)),
			modifier = Modifier
				.align(Alignment.BottomStart)
				.padding(bottom = navBarHeight),
		) {
			val shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
			Box(
				modifier = Modifier
					.width(edgeBarWidth)
					.height(animatedBackLeftHeight)
					.background(color = fillColor, shape = shape)
					.border(width = 1.5.dp, color = borderColor, shape = shape),
			)
		}

		// Right Back Indicator
		AnimatedVisibility(
			visible = backRightEnabled,
			enter = fadeIn(tween(200)),
			exit = fadeOut(tween(200)),
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.padding(bottom = navBarHeight),
		) {
			val shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
			Box(
				modifier = Modifier
					.width(edgeBarWidth)
					.height(animatedBackRightHeight)
					.background(color = fillColor, shape = shape)
					.border(width = 1.5.dp, color = borderColor, shape = shape),
			)
		}

		// Left Swipe Up Indicator (Option 2: Bottom bar + Floating dashed threshold line)
		AnimatedVisibility(
			visible = leftSwipeEnabled,
			enter = fadeIn(tween(200)),
			exit = fadeOut(tween(200)),
			modifier = Modifier.align(Alignment.BottomStart),
		) {
			Box(
				modifier = Modifier
					.width(animatedLeftSwipeWidth)
					.height(animatedSwipeHeight),
			) {
				// Floating dashed threshold line at trigger height (visible only while adjusting swipe-up height)
				AnimatedVisibility(
					visible = isAdjustingSwipeHeight,
					enter = fadeIn(tween(200)),
					exit = fadeOut(tween(350)),
					modifier = Modifier.align(Alignment.TopStart),
				) {
					Box(modifier = Modifier.fillMaxWidth()) {
						Canvas(
							modifier = Modifier
								.fillMaxWidth()
								.height(2.dp)
								.align(Alignment.CenterStart),
						) {
						drawLine(
							color = borderColor,
							start = Offset(0f, size.height / 2),
							end = Offset(size.width, size.height / 2),
							strokeWidth = 2.dp.toPx(),
							pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
						)
					}

					// Chip badge showing the threshold percentage
					Box(
						modifier = Modifier
							.align(Alignment.CenterEnd)
							.padding(end = 4.dp)
							.background(
								color = borderColor,
								shape = RoundedCornerShape(4.dp),
							)
							.padding(horizontal = 4.dp, vertical = 1.5.dp),
					) {
						Text(
							text = "▲ ${swipeUpPercent.roundToInt()}%",
							color = Color.White,
							fontSize = 9.sp,
							fontWeight = FontWeight.Bold,
							fontFamily = FontFamily.Monospace,
						)
					}
					}
				}

				// Bottom touch initiation bar across navbar width
				val baseShape = RoundedCornerShape(topEnd = 8.dp)
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(navBarHeight)
						.align(Alignment.BottomStart)
						.background(color = fillColor, shape = baseShape)
						.border(width = 1.5.dp, color = borderColor, shape = baseShape),
				)
			}
		}

		// Right Swipe Up Indicator (Option 2: Bottom bar + Floating dashed threshold line)
		AnimatedVisibility(
			visible = rightSwipeEnabled,
			enter = fadeIn(tween(200)),
			exit = fadeOut(tween(200)),
			modifier = Modifier.align(Alignment.BottomEnd),
		) {
			Box(
				modifier = Modifier
					.width(animatedRightSwipeWidth)
					.height(animatedSwipeHeight),
			) {
				// Floating dashed threshold line at trigger height (visible only while adjusting swipe-up height)
				AnimatedVisibility(
					visible = isAdjustingSwipeHeight,
					enter = fadeIn(tween(200)),
					exit = fadeOut(tween(350)),
					modifier = Modifier.align(Alignment.TopEnd),
				) {
					Box(modifier = Modifier.fillMaxWidth()) {
						Canvas(
							modifier = Modifier
								.fillMaxWidth()
								.height(2.dp)
								.align(Alignment.CenterEnd),
						) {
						drawLine(
							color = borderColor,
							start = Offset(0f, size.height / 2),
							end = Offset(size.width, size.height / 2),
							strokeWidth = 2.dp.toPx(),
							pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
						)
					}

					// Chip badge showing the threshold percentage
					Box(
						modifier = Modifier
							.align(Alignment.CenterStart)
							.padding(start = 4.dp)
							.background(
								color = borderColor,
								shape = RoundedCornerShape(4.dp),
							)
							.padding(horizontal = 4.dp, vertical = 1.5.dp),
					) {
						Text(
							text = "▲ ${swipeUpPercent.roundToInt()}%",
							color = Color.White,
							fontSize = 9.sp,
							fontWeight = FontWeight.Bold,
							fontFamily = FontFamily.Monospace,
						)
					}
					}
				}

				// Bottom touch initiation bar across navbar width
				val baseShape = RoundedCornerShape(topStart = 8.dp)
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(navBarHeight)
						.align(Alignment.BottomEnd)
						.background(color = fillColor, shape = baseShape)
						.border(width = 1.5.dp, color = borderColor, shape = baseShape),
				)
			}
		}
	}
}
