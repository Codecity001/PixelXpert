package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import sh.siava.pixelxpert.xposed.XPrefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@FrameworkModPack
class AdbWifiPortPin(context: Context) : XposedModPack(context) {

    private var pinWirelessAdb = false
    private var pinnedAdbPort = 5555
    private var serverSocket: ServerSocket? = null
    private var currentTargetPort = -1

    override fun onPreferenceUpdated(vararg keys: String) {
        val newPinWirelessAdb = XPrefs.getBoolean("pin_wireless_adb", false)
        val newPinnedAdbPort = XPrefs.getString("pinned_adb_port", "5555").toIntOrNull() ?: 5555

        val changed = (newPinWirelessAdb != pinWirelessAdb) || (newPinnedAdbPort != pinnedAdbPort)
        pinWirelessAdb = newPinWirelessAdb
        pinnedAdbPort = newPinnedAdbPort

        if (changed && currentTargetPort != -1) {
            if (pinWirelessAdb) {
                startTcpForwarder(currentTargetPort, pinnedAdbPort)
            } else {
                stopTcpForwarder()
            }
        }
    }

    override fun onPackageLoaded(param: PackageReadyParam) {
        val adbDebuggingHandlerClass = ReflectedClass.of(
            "com.android.server.adb.AdbDebuggingManager\$AdbDebuggingHandler",
            param.classLoader
        )

        adbDebuggingHandlerClass
            .before("onAdbdWifiServerConnected")
            .run { hookParam ->
                currentTargetPort = (hookParam.args[0] as? Number)?.toInt() ?: -1
                if (pinWirelessAdb && currentTargetPort != -1) {
                    startTcpForwarder(currentTargetPort, pinnedAdbPort)
                }
            }

        adbDebuggingHandlerClass
            .before("onAdbdWifiServerDisconnected")
            .run {
                currentTargetPort = -1
                stopTcpForwarder()
            }
    }

    private fun startTcpForwarder(targetPort: Int, listenPort: Int) {
        stopTcpForwarder()
        Thread {
            runCatching {
                val ss = ServerSocket(listenPort)
                serverSocket = ss
                while (!ss.isClosed) {
                    val clientSocket = ss.accept()
                    Thread { forward(clientSocket, targetPort) }.start()
                }
            }
        }.start()
    }

    private fun stopTcpForwarder() {
        val ss = serverSocket
        if (ss != null && !ss.isClosed) {
            runCatching { ss.close() }
        }
    }

    private fun forward(clientSocket: Socket, targetPort: Int) {
        runCatching {
            val targetSocket = Socket("localhost", targetPort)

            val t1 = Thread {
                runCatching {
                    val `in`: InputStream = clientSocket.getInputStream()
                    val out: OutputStream = targetSocket.getOutputStream()
                    val buffer = ByteArray(4096)
                    var read: Int
                    while (`in`.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        out.flush()
                    }
                }
                closeSockets(clientSocket, targetSocket)
            }

            val t2 = Thread {
                runCatching {
                    val `in`: InputStream = targetSocket.getInputStream()
                    val out: OutputStream = clientSocket.getOutputStream()
                    val buffer = ByteArray(4096)
                    var read: Int
                    while (`in`.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        out.flush()
                    }
                }
                closeSockets(clientSocket, targetSocket)
            }

            t1.start()
            t2.start()
        }.onFailure {
            closeSockets(clientSocket, null)
        }
    }

    private fun closeSockets(s1: Socket?, s2: Socket?) {
        runCatching { s1?.close() }
        runCatching { s2?.close() }
    }
}
