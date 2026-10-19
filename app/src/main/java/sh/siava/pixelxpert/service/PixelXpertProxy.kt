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
import sh.siava.pixelxpert.Constants.AI_METHOD_MLKIT
import sh.siava.pixelxpert.IPixelXpertProxy
import sh.siava.pixelxpert.PixelXpert
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.utils.MLKitSegmentor

class PixelXpertProxy : Service() {

    override fun onBind(intent: Intent?): IBinder {
        return PixelXpertProxyIPC(this)
    }

    private inner class PixelXpertProxyIPC(context: Context) : IPixelXpertProxy.Stub() {
        val TAG: String = javaClass.simpleName

        private val rootAllowedPacks: List<String>
        private val rootGranted: Boolean

        init {
            runCatching {
                Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER))
            }
            rootGranted = Shell.getShell().isRoot

            if (!rootGranted) {
                context.sendBroadcast(Intent(Constants.ACTION_KSU_ACQUIRE_ROOT))
            }

            rootAllowedPacks = context.resources.getStringArray(R.array.root_requirement).toList()
        }

        override fun runRootCommand(command: String?): Array<String> {
            return try {
                ensureEnvironment()
                if (command == null) return emptyArray()
                val result = Shell.cmd(command).exec().out
                result.toTypedArray()
            } catch (_: Throwable) {
                emptyArray()
            }
        }

        override fun extractSubject(input: Bitmap?, method: Int): Bitmap? {
            ensureEnvironment()

            val app = PixelXpert.get()
            if (!app.isCoreRootServiceBound) {
                app.tryConnectRootService()
            }

            if (input == null) return null

            return when (method) {
                AI_METHOD_MLKIT -> MLKitSegmentor.extractSubject(app, input)
                else -> null
            }
        }

        private fun ensureEnvironment() {
            if (!rootGranted) {
                throw RemoteException("Root permission denied")
            }
            ensureSecurity(Binder.getCallingUid())
        }

        private fun ensureSecurity(uid: Int) {
            val packages = packageManager.getPackagesForUid(uid) ?: emptyArray()
            for (pkg in packages) {
                if (rootAllowedPacks.contains(pkg)) return
            }
            throw RemoteException("You do know you're not supposed to use this service. So...")
        }
    }
}
