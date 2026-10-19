package sh.siava.pixelxpert.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import com.topjohnwu.superuser.Shell
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.IPixelXpertProxy
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

/**
 * Binder endpoint that hands root to hooked processes which have no root of
 * their own - the status bar and launcher, for instance.
 *
 * The module app holds the root shell because it is the component allowed
 * to request one; `XPLauncher` binds this by
 * [sh.siava.pixelxpert.xposed.XPLauncher.forceConnectRootService]
 * whenever the hooked package appears in `R.array.root_requirement`.
 *
 * Two lines of defence before anything runs: the caller's UID must resolve to a
 * package listed in that same `root_requirement` array, and libsu must actually
 * hold a root shell. Everything the binder entry points do is wrapped so a
 * failure comes back as an empty result rather than a transaction crash in the
 * *hooked* process.
 *
 * Scope note: `extractSubject` is intentionally not implemented yet. It is only
 * reachable from the `DepthWallpaper` mod pack, and no mod pack is ported in
 * this slice; the segmenters it needs (ML Kit / PyTorch) are deferred with it.
 */
class PixelXpertProxy : Service() {

	override fun onBind(intent: Intent?): IBinder = PixelXpertProxyIPC(this)

	/**
	 * Because this is a bind-service, the binder object must not be static, or
	 * it will be GC'd and the process will die.
	 */
	private inner class PixelXpertProxyIPC(context: Context) : IPixelXpertProxy.Stub() {

		private val TAG = javaClass.simpleName

		private val rootAllowedPacks: List<String>
		private val rootGranted: Boolean

		init {
			try {
				Shell.setDefaultBuilder(
					Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER),
				)
			} catch (ignored: Throwable) {
				// Without master mount the shell still runs, it just cannot
				// remount - so this is worth trying and discarding, not failing.
			}
			rootGranted = try {
				Shell.getShell().isRoot
			} catch (ignored: Throwable) {
				false
			}
			rootAllowedPacks = context.resources.getStringArray(R.array.root_requirement).toList()

			if (!rootGranted) {
				// Fire and forget. If a KSU manager is present it will pick this
				// up and prompt for the grant.
				context.sendBroadcast(Intent(Constants.ACTION_KSU_ACQUIRE_ROOT))
			}
		}

		@Throws(RemoteException::class)
		override fun runRootCommand(command: String): Array<String> = try {
			ensureEnvironment()
			// libsu hands back a List; AIDL declared String[], so bridge here.
			Shell.cmd(command).exec().out.toTypedArray()
		} catch (t: Throwable) {
			Logger.log("Root request failed: $command", t)
			arrayOf<String>()
		}

		/**
		 * Subject segmentation, deferred with the `DepthWallpaper` mod pack.
		 *
		 * Throws rather than returning null so that the first caller - once that
		 * pack lands - fails loudly instead of silently rendering no cutout.
		 */
		@Throws(RemoteException::class)
		override fun extractSubject(input: Bitmap?, method: Int): Bitmap? {
			throw RemoteException(
				"PixelXpertProxy.extractSubject is not ported yet: " +
					"it is only reachable from the DepthWallpaper mod pack, " +
					"which is not part of this slice.",
			)
		}

		/**
		 * Throws unless libsu really is root and the caller is one of the
		 * packages we agreed to serve.
		 *
		 * This runs inside [runRootCommand], whose `catch` turns the throw into
		 * an empty result - the same outcome as a caller whose uid
		 * `getPackagesForUid` resolves to nothing.
		 */
		@Throws(RemoteException::class)
		private fun ensureEnvironment() {
			if (!rootGranted) {
				throw RemoteException("Root is not granted")
			}
			ensureSecurity(Binder.getCallingUid())
		}

		@Throws(RemoteException::class)
		private fun ensureSecurity(callingUid: Int) {
			val pkgs = packageManager.getPackagesForUid(callingUid)
			if (pkgs != null) {
				for (packageName in pkgs) {
					if (rootAllowedPacks.contains(packageName)) return
				}
			}
			throw RemoteException("You do know that this is a shady request, right?")
		}
	}
}
