package sh.siava.pixelxpert.xposed.utils;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.os.Environment;
import android.util.AtomicFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Calendar;

public class BootLoopProtector {
	public static final String LOAD_TIME_KEY_KEY = "packageLastLoad_";
	public static final String PACKAGE_STRIKE_KEY_KEY = "packageStrike_";
	private static final int COUNTER_RESET_PERIOD = 60; //seconds
	private static final int MAX_STRIKES = 3;
	private static final int EARLY_BOOT_STATE_VERSION = 1;
	private static final String EARLY_SYSTEM_SERVER_STATE_FILE = "pixelxpert-system-server-boot-state";

	public enum BootLoopState {
		ALLOWED,
		BLOCKED,
		UNAVAILABLE
	}

	@SuppressLint("ApplySharedPref")
	public static boolean isBootLooped(String packageName)
	{
		String loadTimeKey = String.format("%s%s", LOAD_TIME_KEY_KEY, packageName);
		String strikeKey = String.format("%s%s", PACKAGE_STRIKE_KEY_KEY, packageName);
		long currentTime = Calendar.getInstance().getTime().getTime();
		long lastLoadTime = Xprefs.getLong(loadTimeKey, 0);
		int strikeCount = Xprefs.getInt(strikeKey, 0);

		if (currentTime - lastLoadTime > COUNTER_RESET_PERIOD * 1000)
		{
			resetCounter(packageName);
		}
		else if(strikeCount >= MAX_STRIKES)
		{
			return true;
		}
		else
		{
			Xprefs.edit().putInt(strikeKey, ++strikeCount).commit();
		}
		return false;
	}

	public static BootLoopState checkAndRecordEarlySystemServerStart()
	{
		AtomicFile stateFile = new AtomicFile(new File(
				new File(Environment.getDataDirectory(), "system"),
				EARLY_SYSTEM_SERVER_STATE_FILE));
		long currentTime = Calendar.getInstance().getTime().getTime();
		long lastLoadTime = 0;
		int strikeCount = 0;

		try {
			if (stateFile.getBaseFile().exists()) {
				try (DataInputStream input = new DataInputStream(
						new BufferedInputStream(stateFile.openRead()))) {
					if (input.readInt() != EARLY_BOOT_STATE_VERSION) {
						return BootLoopState.UNAVAILABLE;
					}
					lastLoadTime = input.readLong();
					strikeCount = input.readInt();
				}
			}
		} catch (IOException | RuntimeException ignored) {
			return BootLoopState.UNAVAILABLE;
		}

		if (currentTime - lastLoadTime > COUNTER_RESET_PERIOD * 1000L) {
			lastLoadTime = currentTime;
			strikeCount = 0;
		} else if (strikeCount >= MAX_STRIKES) {
			return BootLoopState.BLOCKED;
		} else {
			strikeCount++;
		}

		FileOutputStream output = null;
		try {
			output = stateFile.startWrite();
			DataOutputStream dataOutput = new DataOutputStream(new BufferedOutputStream(output));
			dataOutput.writeInt(EARLY_BOOT_STATE_VERSION);
			dataOutput.writeLong(lastLoadTime);
			dataOutput.writeInt(strikeCount);
			dataOutput.flush();
			stateFile.finishWrite(output);
			return BootLoopState.ALLOWED;
		} catch (IOException | RuntimeException ignored) {
			if (output != null) {
				try {
					stateFile.failWrite(output);
				} catch (RuntimeException ignoredFailure) {
				}
			}
			return BootLoopState.UNAVAILABLE;
		}
	}

	@SuppressLint("ApplySharedPref")
	public static void resetCounter(String packageName)
	{
		try
		{
			String loadTimeKey = String.format("%s%s", LOAD_TIME_KEY_KEY, packageName);
			String strikeKey = String.format("%s%s", PACKAGE_STRIKE_KEY_KEY, packageName);
			long currentTime = Calendar.getInstance().getTime().getTime();

			Xprefs.edit()
					.putLong(loadTimeKey, currentTime)
					.putInt(strikeKey, 0)
					.commit();
		}
		catch (Throwable ignored){}
	}
}
