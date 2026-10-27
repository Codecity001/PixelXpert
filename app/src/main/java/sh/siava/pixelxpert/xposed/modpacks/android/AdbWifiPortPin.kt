package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * Pins the wireless-debugging adb port to a fixed number so `adb connect`
 * targets survive adbd picking a fresh ephemeral port each time.
 *
 * `AdbDebuggingManager$AdbDebuggingHandler` is told when the adbd wifi server
 * comes up and goes down; the connected hook records the port adbd actually
 * chose, and - when `pin_wireless_adb` is on - starts a TCP forwarder that
 * listens on `pinned_adb_port` (default 5555) and relays both directions of
 * every accepted connection to that real port. The disconnected hook tears the
 * listener down again, and a preference change restarts the forwarder only if
 * something was already connected, so flipping settings while idle just
 * updates the cached values.
 *
 * The forwarder is plain blocking sockets: one accept loop thread, one thread
 * per direction per client, and both directions closing both sockets when
 * either side ends - no async, no selectors.
 */
@FrameworkModPack
class AdbWifiPortPin(context: Context) : XposedModPack(context) {

	private var pinWirelessAdb = false
	private var pinnedAdbPort = 5555

	private var serverSocket: ServerSocket? = null
	private var currentTargetPort = -1
	private val clientSockets = Collections.synchronizedSet(HashSet<Socket>())

	@Suppress("SENSELESS_COMPARISON")
	override fun onPreferenceUpdated(vararg key: String?) {
		val newPinWirelessAdb = Xprefs.getBoolean("pin_wireless_adb", false)
		var newPinnedAdbPort = 5555
		try {
			newPinnedAdbPort = Integer.parseInt(Xprefs.getString("pinned_adb_port", "5555") ?: "5555")
		} catch (e: NumberFormatException) {
			newPinnedAdbPort = 5555
		}

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

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val AdbDebuggingHandlerClass = ReflectedClass.of("com.android.server.adb.AdbDebuggingManager\$AdbDebuggingHandler")

		AdbDebuggingHandlerClass
			.before("onAdbdWifiServerConnected")
			.run { param ->
				currentTargetPort = param.args[0] as Int
				if (pinWirelessAdb) {
					startTcpForwarder(currentTargetPort, pinnedAdbPort)
				}
			}

		AdbDebuggingHandlerClass
			.before("onAdbdWifiServerDisconnected")
			.run { param ->
				currentTargetPort = -1
				stopTcpForwarder()
			}
	}

	override fun onUnloaded() {
		stopTcpForwarder()
	}

	private fun startTcpForwarder(targetPort: Int, listenPort: Int) {
		stopTcpForwarder()
		Thread {
			try {
				serverSocket = ServerSocket(listenPort)
				while (!serverSocket!!.isClosed()) {
					val clientSocket = serverSocket!!.accept()
					clientSockets += clientSocket
					Thread { forward(clientSocket, targetPort) }.start()
				}
			} catch (e: Exception) {
				// Ignored
			}
		}.start()
	}

	private fun stopTcpForwarder() {
		if (serverSocket != null && !serverSocket!!.isClosed()) {
			try {
				serverSocket!!.close()
			} catch (e: Exception) {
				// Ignored
			}
		}
		val activeClients = synchronized(clientSockets) { clientSockets.toList() }
		clientSockets.clear()
		activeClients.forEach { closeSockets(it, null) }
	}

	private fun forward(clientSocket: Socket, targetPort: Int) {
		try {
			val targetSocket = Socket("localhost", targetPort)

			val t1 = Thread {
				try {
					val input: InputStream = clientSocket.getInputStream()
					val out: OutputStream = targetSocket.getOutputStream()
					val buffer = ByteArray(4096)
					var read = input.read(buffer)
					while (read != -1) {
						out.write(buffer, 0, read)
						out.flush()
						read = input.read(buffer)
					}
				} catch (e: Exception) {
				} finally {
					closeSockets(clientSocket, targetSocket)
				}
			}

			val t2 = Thread {
				try {
					val input: InputStream = targetSocket.getInputStream()
					val out: OutputStream = clientSocket.getOutputStream()
					val buffer = ByteArray(4096)
					var read = input.read(buffer)
					while (read != -1) {
						out.write(buffer, 0, read)
						out.flush()
						read = input.read(buffer)
					}
				} catch (e: Exception) {
				} finally {
					closeSockets(clientSocket, targetSocket)
				}
			}

			t1.start()
			t2.start()
		} catch (e: Exception) {
			closeSockets(clientSocket, null)
		}
	}

	private fun closeSockets(s1: Socket?, s2: Socket?) {
		if (s1 != null) clientSockets.remove(s1)
		try {
			if (s1 != null) s1.close()
		} catch (e: Exception) {
		}
		try {
			if (s2 != null) s2.close()
		} catch (e: Exception) {
		}
	}
}
