package sh.siava.pixelxpert.utils

import com.crossbowffs.remotepreferences.RemotePreferenceFile
import com.crossbowffs.remotepreferences.RemotePreferenceProvider
import sh.siava.pixelxpert.BuildConfig

class RemotePrefProvider : RemotePreferenceProvider(
    BuildConfig.APPLICATION_ID,
    arrayOf(RemotePreferenceFile(BuildConfig.APPLICATION_ID + "_preferences", true))
)
