package sh.siava.pixelxpert.xposed.modpacks.android;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@FrameworkModPack
public class AdbWifiPortPin extends XposedModPack {

	private boolean pinWirelessAdb = false;
	private int pinnedAdbPort = 5555;

	private ServerSocket serverSocket = null;
	private int currentTargetPort = -1;

	public AdbWifiPortPin(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		boolean newPinWirelessAdb = Xprefs.getBoolean("pin_wireless_adb", false);
		int newPinnedAdbPort = 5555;
		try {
			newPinnedAdbPort = Integer.parseInt(Xprefs.getString("pinned_adb_port", "5555"));
		} catch (NumberFormatException e) {
			newPinnedAdbPort = 5555;
		}

		boolean changed = (newPinWirelessAdb != pinWirelessAdb) || (newPinnedAdbPort != pinnedAdbPort);
		pinWirelessAdb = newPinWirelessAdb;
		pinnedAdbPort = newPinnedAdbPort;

		if (changed && currentTargetPort != -1) {
			if (pinWirelessAdb) {
				startTcpForwarder(currentTargetPort, pinnedAdbPort);
			} else {
				stopTcpForwarder();
			}
		}
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass AdbDebuggingHandlerClass = ReflectedClass.of("com.android.server.adb.AdbDebuggingManager$AdbDebuggingHandler");

		AdbDebuggingHandlerClass
				.before("onAdbdWifiServerConnected")
				.run(param -> {
					currentTargetPort = (int) param.args[0];
					if (pinWirelessAdb) {
						startTcpForwarder(currentTargetPort, pinnedAdbPort);
					}
				});

		AdbDebuggingHandlerClass
				.before("onAdbdWifiServerDisconnected")
				.run(param -> {
					currentTargetPort = -1;
					stopTcpForwarder();
				});
	}

	private void startTcpForwarder(int targetPort, int listenPort) {
		stopTcpForwarder();
		new Thread(() -> {
			try {
				serverSocket = new ServerSocket(listenPort);
				while (!serverSocket.isClosed()) {
					Socket clientSocket = serverSocket.accept();
					new Thread(() -> forward(clientSocket, targetPort)).start();
				}
			} catch (Exception e) {
				// Ignored
			}
		}).start();
	}

	private void stopTcpForwarder() {
		if (serverSocket != null && !serverSocket.isClosed()) {
			try {
				serverSocket.close();
			} catch (Exception e) {
				// Ignored
			}
		}
	}

	private void forward(Socket clientSocket, int targetPort) {
		try {
			Socket targetSocket = new Socket("localhost", targetPort);
			
			Thread t1 = new Thread(() -> {
				try {
					InputStream in = clientSocket.getInputStream();
					OutputStream out = targetSocket.getOutputStream();
					byte[] buffer = new byte[4096];
					int read;
					while ((read = in.read(buffer)) != -1) {
						out.write(buffer, 0, read);
						out.flush();
					}
				} catch (Exception e) {
				} finally {
					closeSockets(clientSocket, targetSocket);
				}
			});
			
			Thread t2 = new Thread(() -> {
				try {
					InputStream in = targetSocket.getInputStream();
					OutputStream out = clientSocket.getOutputStream();
					byte[] buffer = new byte[4096];
					int read;
					while ((read = in.read(buffer)) != -1) {
						out.write(buffer, 0, read);
						out.flush();
					}
				} catch (Exception e) {
				} finally {
					closeSockets(clientSocket, targetSocket);
				}
			});
			
			t1.start();
			t2.start();
		} catch (Exception e) {
			closeSockets(clientSocket, null);
		}
	}

	private void closeSockets(Socket s1, Socket s2) {
		try { if (s1 != null) s1.close(); } catch (Exception e) {}
		try { if (s2 != null) s2.close(); } catch (Exception e) {}
	}
}
