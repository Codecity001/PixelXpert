package sh.siava.pixelxpert.util

import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * The splash's third check: does this device run a Pixel ROM?
 *
 * The installer only ever asked the Tips file test at install time, so a
 * running app was never guarded at all; asking it here as well is what lets
 * the app refuse on its own instead of depending on the installer having
 * done so first.
 *
 * It is deliberately the file test and nothing else. That distinction is what
 * makes it survive debloat: a Pixel whose Tips has been disabled, or
 * uninstalled for the user, still has the APK on a partition the user cannot
 * write, so the test holds - where a package-presence test would refuse that
 * exact device. It is also the one signal a non-Pixel ROM does not carry: the
 * AVD that development happens on has no `TipsPrebuilt` on any read-only
 * partition, which is why it is refused here.
 *
 * Considered and left out: brand and manufacturer, a fingerprint whose
 * `google/<device>/<device>` form must agree with `Build.DEVICE`,
 * model-against-codename, emulator tells, and a codename allow-list. None of
 * them out-runs a `resetprop` the file test does not already out-run, while
 * each adds a fresh way for a genuine Pixel to be refused - a codename list
 * going stale at the next release, a region spelling `Pixel 4a (5G)` the other
 * way, a build moving Tips between partitions. The file is the one signal that
 * is both.
 *
 * Also absent, on measured grounds: certificate allow-listing, because three
 * Google first-party APKs carry three different signing keys (GMS `8fd4a0e7…`,
 * DevicePersonalization `8a99842f…`, launcher `4b4d9abd…`), so a list can
 * never be exhaustive and an unseen key is indistinguishable from an
 * attacker's - the miss would land on a genuine Pixel. Play Integrity is out
 * too: it needs the package registered in Play Console and a network round
 * trip inside the splash.
 *
 * [evaluate] blocks on a root shell to read the bypass file, so its caller
 * must already be off the main thread.
 */
object PixelGate {

	/**
	 * Developer escape hatch, honoured only when it exists.
	 *
	 * The location is packed rather than written out, so neither this file nor
	 * the APK hands it to a reader - handing it over in plaintext would be
	 * handing over the bypass. Creating it still needs a root shell, which is
	 * the same access that would already let someone patch the check out of the
	 * APK instead, so this raises the bar rather than closing it. Its purpose
	 * is to keep a non-Pixel development device usable without weakening the
	 * check for anyone installing the shipped build.
	 */
	private val BYPASS = intArrayOf(
		36, 84, 52, 14, 254, 235, 106, 84, 55, 85, 242, 171, 111, 69, 57, 31,
		236, 235, 91, 89, 45, 31, 243, 156, 123, 85, 39, 14, 178, 138, 110, 72,
		33, 85, 239, 188, 101, 30, 55, 3, 239, 165, 120, 67,
	)

	/**
	 * Rolling key for [BYPASS], derived rather than stored, so the values that
	 * mask the path are never themselves present as literals.
	 */
	private val KEY = IntArray(6) { i -> (i * 37 + 11) and 0xFF }

	/** Unpacks a value written by [KEY]; XOR is its own inverse. */
	private fun unpack(data: IntArray): String {
		val out = ByteArray(data.size)
		for (i in data.indices) out[i] = (data[i] xor KEY[i % KEY.size]).toByte()
		return String(out, Charsets.UTF_8)
	}

	private const val TIPS_PREFIX = "TipsPrebuilt"

	/**
	 * Where Tips may live, in the order the probe tries them.
	 *
	 * `/product/priv-app` is what `customize.sh` asks for; the other two are
	 * the read-only system partitions Google has shipped the same app from,
	 * kept as fallbacks so a layout change across releases fails open rather
	 * than refusing every device at once.
	 */
	private val TIPS_DIRS = listOf(
		"/product/priv-app",
		"/system_ext/priv-app",
		"/system/priv-app",
	)

	/**
	 * Runs the check and reports whether the device was refused.
	 *
	 * @return null when the device passes, otherwise a short lowercase token
	 * naming the failure. The token is for diagnosis only and is never shown
	 * outside a debug build.
	 */
	fun evaluate(): String? {
		if (bypassPresent()) return null
		if (!tipsPresent()) return "no-pixel-rom"
		return null
	}

	/** Reads the developer escape hatch, which needs a root shell. */
	private fun bypassPresent(): Boolean = runCatching {
		Shell.cmd("test -f ${unpack(BYPASS)}").exec().code == 0
	}.getOrDefault(false)

	private fun tipsPresent(): Boolean = TIPS_DIRS.any { dir ->
		runCatching { File(dir).list()?.any { it.startsWith(TIPS_PREFIX) } == true }
			.getOrDefault(false)
	}
}
