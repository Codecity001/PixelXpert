package sh.siava.pixelxpert.ui.prefs

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.util.NTPTimeSyncer

/**
 * Preference keys that open a browser, keyed by preference key.
 *
 * `SyncNTPTimeNow` is deliberately absent: it is also an `ActionRow`, but it
 * runs an NTP sync rather than opening a link - see [syncNTP] below, which
 * the renderer dispatches to by key.
 */
internal val externalLinks: Map<String, String> = mapOf(
	"GitHubRepo" to "https://github.com/Codecity001/PixelXpert",
	"TelegramGroup" to "https://t.me/PixelXpert_Github",
	"CrowdinProject" to "https://pixelxpert.siava.sh/translate",
	"UsageWiki" to "https://pixelxpert.siava.sh/wiki",
)

/**
 * `ACTION_VIEW`. Every failure - no browser installed, a chooser nobody
 * picks, an unresolvable host - falls through to the `browser_not_found`
 * toast instead of taking the app down.
 */
internal fun openExternalLink(context: Context, url: String) {
	try {
		context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
	} catch (ignored: Exception) {
		Toast.makeText(
			context,
			context.getString(R.string.browser_not_found),
			Toast.LENGTH_SHORT,
		).show()
	}
}

/**
 * Runs a one-shot NTP query and toasts the outcome.
 *
 * The call blocks. [NTPTimeSyncer.syncTimeNow] waits on a monitor for up to
 * ten seconds, and Kronos takes six to give up, so the main thread stops for
 * the length of the query and only then does the toast land. Moving the sync
 * off the main thread would change what the user sees, because the toast
 * would start certifying a freeze that had already ended.
 *
 * Both outcomes toast: `sync_ntp_successful` when the root `date -s` ran,
 * `sync_ntp_failed` for a timeout, a refused NTP packet, or a shell with no
 * root behind it. Failure is a normal result here, not an error to report.
 */
internal fun syncNTP(context: Context) {
	val successful = NTPTimeSyncer(context).syncTimeNow()

	val toastResource = if (successful) R.string.sync_ntp_successful else R.string.sync_ntp_failed

	Toast.makeText(context, toastResource, Toast.LENGTH_SHORT).show()
}

/**
 * Swaps the launcher icon and closes - what the notice's *"App will close
 * now. You can open it again by tapping on the new icon"* promises the user.
 *
 * The two aliases are toggled against each other and the target activity is
 * left alone, so exactly one launcher entry is ever enabled: two enabled
 * aliases declaring identical intent-filters would leave the entry Android
 * resolves up to whichever it returns first. The outcome is the one the
 * dialog describes, without the ambiguity.
 *
 * The activity finishes last, and only when one can be found: a Context that
 * is not an Activity can still perform the flip, it just has nothing to close.
 */
internal fun applyAlternativeAppIcon(context: Context, themed: Boolean) {
	val pkg = context.packageName
	val pm = context.packageManager

	fun set(alias: String, state: Int) =
		pm.setComponentEnabledSetting(ComponentName(pkg, "$pkg.$alias"), state, PackageManager.DONT_KILL_APP)

	set(
		"SplashActivityAlternateIcon",
		if (themed) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
		else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
	)
	set(
		"SplashActivityNormalIcon",
		if (themed) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
		else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
	)

	context.findActivity()?.finish()
}

private fun Context.findActivity(): Activity? = when (this) {
	is Activity -> this
	is ContextWrapper -> baseContext.findActivity()
	else -> null
}
