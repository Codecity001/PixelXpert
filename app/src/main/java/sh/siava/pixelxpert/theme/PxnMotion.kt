package sh.siava.pixelxpert.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Local Material 3 Expressive motion scheme.
 *
 * material3 1.4.0 (the stable line pinned for this project) keeps
 * `androidx.compose.material3.MotionScheme` Kotlin-internal, so the expressive
 * motion model is implemented here instead. It mirrors the androidx shape: a set
 * of *spatial* specs for position/size changes and *effects* specs for effects
 * such as fades and colour transitions, each in fast / standard / slow tiers.
 */
class PxnMotionScheme(
	val spatialEasing: Easing,
	val effectsEasing: Easing,
	val spatialFastMillis: Int,
	val spatialMillis: Int,
	val spatialSlowMillis: Int,
	val effectsFastMillis: Int,
	val effectsMillis: Int,
	val effectsSlowMillis: Int,
) {

	fun <T> spatialFast(): FiniteAnimationSpec<T> = tween(spatialFastMillis, easing = spatialEasing)

	fun <T> spatial(): FiniteAnimationSpec<T> = tween(spatialMillis, easing = spatialEasing)

	fun <T> spatialSlow(): FiniteAnimationSpec<T> = tween(spatialSlowMillis, easing = spatialEasing)

	fun <T> effectsFast(): FiniteAnimationSpec<T> = tween(effectsFastMillis, easing = effectsEasing)

	fun <T> effects(): FiniteAnimationSpec<T> = tween(effectsMillis, easing = effectsEasing)

	fun <T> effectsSlow(): FiniteAnimationSpec<T> = tween(effectsSlowMillis, easing = effectsEasing)

	companion object {

		/**
		 * Emphasised easing curves from the Material 3 Expressive motion system:
		 * [emphasized], [emphasizedAccelerate] and [emphasizedDecelerate].
		 */
		val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
		val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
		val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

		/**
		 * Expressive scheme: bolder easing, longer durations for slow tiers so
		 * movement reads as deliberate rather than instant.
		 */
		val expressive: PxnMotionScheme = PxnMotionScheme(
			spatialEasing = emphasized,
			effectsEasing = emphasized,
			spatialFastMillis = 200,
			spatialMillis = 400,
			spatialSlowMillis = 600,
			effectsFastMillis = 150,
			effectsMillis = 350,
			effectsSlowMillis = 500,
		)

		val standard: PxnMotionScheme = PxnMotionScheme(
			spatialEasing = emphasizedDecelerate,
			effectsEasing = emphasized,
			spatialFastMillis = 150,
			spatialMillis = 300,
			spatialSlowMillis = 450,
			effectsFastMillis = 100,
			effectsMillis = 250,
			effectsSlowMillis = 400,
		)
	}
}

val LocalPxnMotionScheme: ProvidableCompositionLocal<PxnMotionScheme> =
	staticCompositionLocalOf { PxnMotionScheme.expressive }
