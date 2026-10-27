package sh.siava.pixelxpert.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive theme.
 *
 * Two colour sources with a runtime toggle - dynamic wallpaper colour and a
 * static brand palette - plus a user-selectable [PxnMotionScheme]
 * (expressive vs standard).
 *
 * This wraps the stable `MaterialTheme` because material3 1.4.0 keeps the
 * androidx `MotionScheme`/`MaterialExpressiveTheme` API Kotlin-internal; the
 * expressive motion layer is provided through [LocalPxnMotionScheme] instead.
 */
@Composable
fun PxnTheme(
	darkTheme: Boolean = isSystemInDarkTheme(),
	dynamicColor: Boolean = true,
	expressiveMotion: Boolean = true,
	content: @Composable () -> Unit,
) {
	val context = LocalContext.current

	val colorScheme = resolveColorScheme(context, darkTheme, dynamicColor)

	val motionScheme = if (expressiveMotion) {
		PxnMotionScheme.expressive
	} else {
		PxnMotionScheme.standard
	}

	CompositionLocalProvider(LocalPxnMotionScheme provides motionScheme) {
		MaterialTheme(
			colorScheme = colorScheme,
			content = content,
		)
	}
}

/**
 * Resolves the colour scheme outside composition.
 *
 * Kept non-composable so the same single source of truth can be read from any
 * context, not just from a composable; [PxnTheme] is its only caller today.
 *
 * `dynamicColor` selects the system palette (wallpaper-derived, or a custom
 * colour profile if the user picked one) versus a fixed brand palette. The
 * window/splash cannot use this - they are resolved before any code runs - and
 * instead read the same system background role directly through
 * `pxn_window_background`.
 */
fun resolveColorScheme(context: Context, darkTheme: Boolean, dynamicColor: Boolean = true): ColorScheme = when {
	dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
	dynamicColor && !darkTheme -> dynamicLightColorScheme(context)
	darkTheme -> darkColorScheme()
	else -> lightColorScheme()
}
