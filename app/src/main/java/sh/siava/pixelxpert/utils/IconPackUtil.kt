package sh.siava.pixelxpert.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import java.io.Serializable
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.utils.ThemePackMapping.TYPE_DRAWABLE

class IconPackUtil(context: Context) : Serializable {

    companion object {
        private const val TAG = "IconPackUtil"

        @JvmField
        var instance: IconPackUtil? = null

        const val ENABLED_FULL = 0
        const val ENABLED_PARTIAL = 1
        const val DISABLED = 2

        @Synchronized
        @JvmStatic
        fun getInstance(context: Context): IconPackUtil {
            return instance ?: IconPackUtil(context).also { instance = it }
        }
    }

    private val mPackageManager: PackageManager = context.packageManager
    @JvmField
    var mResourceMapping: ResourceMapping? = null
    @JvmField
    var mIconPackMapping: IconPackMapping? = null
    private val listeners = ConcurrentHashMap.newKeySet<IconPackQueryListener>()
    private val executorService = Executors.newCachedThreadPool()
    private var hasLoaded = false
    private var isQuerying = false

    fun interface IconPackQueryListener {
        fun onIconPacksLoaded(resourceMapping: ResourceMapping?, iconPackMapping: IconPackMapping?)
    }

    fun addListener(listener: IconPackQueryListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: IconPackQueryListener) {
        listeners.remove(listener)
    }

    fun isAnythingEnabled(): Boolean {
        if (isQuerying || mResourceMapping == null) return false
        return mResourceMapping?.values?.asSequence()
            ?.flatMap { it.asSequence() }
            ?.any { it.isEnabled } ?: false
    }

    fun queryIconPacks(force: Boolean) {
        if (force || !hasLoaded) {
            executorService.submit { queryMappingInternal() }
            return
        }
        listeners.forEach { it.onIconPacksLoaded(mResourceMapping, mIconPackMapping) }
    }

    private fun queryMappingInternal() {
        isQuerying = true
        hasLoaded = false
        val resourceMapping = ResourceMapping()
        val iconPackMapping = IconPackMapping()
        mResourceMapping = resourceMapping
        mIconPackMapping = iconPackMapping

        val prefMapping = ThemePackMapping.Mapping.loadMapping(PXPreferences.getPrefs(), TYPE_DRAWABLE)
        val activities = mPackageManager.queryIntentActivities(Intent("sh.siava.pixelxpert.iconpack"), 0)

        for (activity in activities) {
            try {
                val packageName = activity.activityInfo.packageName
                val packName = activity.activityInfo.name.replaceFirst("^$packageName\\.".toRegex(), "")
                val packLabel = activity.activityInfo.loadLabel(mPackageManager).toString()

                val packageInfo = mPackageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
                val author = runCatching {
                    packageInfo.applicationInfo?.metaData?.getString("packauthor") ?: "Unknown"
                }.getOrDefault("Unknown")

                val packResources = mPackageManager.getResourcesForApplication(packageName)

                @SuppressLint("DiscouragedApi")
                val packDataId = packResources.getIdentifier(packName, "array", packageName)
                val packData = packResources.getStringArray(packDataId)

                val resNameArrayID = getStringArrayID(packData[0], packResources, packageName)
                val replacementNameArrayID = getStringArrayID(packData[1], packResources, packageName)

                val resNames = packResources.getStringArray(resNameArrayID)
                val replacements = packResources.getStringArray(replacementNameArrayID)

                val iconPack = IconPack(packageName, packLabel, author)

                for (i in resNames.indices) {
                    val rep = ReplacementIcon(iconPack, replacements[i]).apply {
                        mEnabled = prefMapping.isEnabled(resNames[i], iconPack.mPackageName, replacements[i])
                    }
                    resourceMapping.add(resNames[i], rep)
                    iconPackMapping.add(iconPack, resNames[i], rep)
                }
            } catch (ignored: Exception) {}
        }

        listeners.forEach { it.onIconPacksLoaded(mResourceMapping, mIconPackMapping) }
        hasLoaded = true
        isQuerying = false
    }

    fun enable(iconPack: IconPack) {
        setEnabled(iconPack, true)
    }

    fun disable(iconPack: IconPack) {
        setEnabled(iconPack, false)
    }

    private fun setEnabled(iconPack: IconPack, enabled: Boolean) {
        val mapping = mIconPackMapping ?: return
        val resMapping = mResourceMapping ?: return
        val thisPack = mapping[iconPack] ?: return
        thisPack.forEach { (resName, replacementIcons) ->
            if (replacementIcons.isNotEmpty()) {
                resMapping.setEnabled(resName, replacementIcons[0], enabled)
            }
        }
        savePrefs()
    }

    private fun savePrefs() {
        val resMapping = mResourceMapping ?: return
        val mapping = ThemePackMapping.Mapping()
        resMapping.forEach { (resName, replacementIcons) ->
            replacementIcons.firstOrNull { it.isEnabled }?.let { rep ->
                mapping.add(resName, rep.mIconPack.mPackageName, rep.mReplacementRes)
            }
        }
        ThemePackMapping.Mapping.saveMapping(PXPreferences.getPrefs(), TYPE_DRAWABLE, mapping)
    }

    fun disable(resName: String) {
        mResourceMapping?.reset(resName)
        savePrefs()
    }

    fun setEnabled(resName: String, replacementIcon: ReplacementIcon) {
        mResourceMapping?.setEnabled(resName, replacementIcon, true)
        savePrefs()
    }

    fun getEnabledState(iconPack: IconPack): Int {
        val mapping = mIconPackMapping ?: return DISABLED
        val thisPack = mapping[iconPack] ?: return DISABLED
        val fullyEnabled = AtomicBoolean(true)
        val partialEnabled = AtomicBoolean(false)

        thisPack.forEach { (resName, _) ->
            val replacementIcon = getEnabled(resName)
            if (replacementIcon != null && replacementIcon.mIconPack == iconPack) {
                partialEnabled.set(true)
            } else {
                fullyEnabled.set(false)
            }
        }

        if (fullyEnabled.get()) return ENABLED_FULL
        if (partialEnabled.get()) return ENABLED_PARTIAL
        return DISABLED
    }

    fun getEnabled(resName: String): ReplacementIcon? {
        val icons = mResourceMapping?.getReplacementIcons(resName) ?: return null
        return icons.firstOrNull { it.isEnabled }
    }

    @SuppressLint("DiscouragedApi")
    private fun getStringArrayID(resName: String, packResources: Resources, packageName: String): Int {
        return packResources.getIdentifier(resName, "array", packageName)
    }

    class IconPack(
        @JvmField var mPackageName: String,
        @JvmField var mName: String,
        @JvmField var mAuthor: String
    ) : Serializable {
        @Transient
        var packDrawables: MutableList<Drawable> = ArrayList()

        fun getPackDrawables(context: Context): List<Drawable> {
            val util = instance ?: return emptyList()
            val packMapping = util.mIconPackMapping?.get(this) ?: return emptyList()
            if (packDrawables.isEmpty()) {
                val previewResNames = context.resources.getStringArray(R.array.iconpack_preview_icons)
                for (previewName in previewResNames) {
                    var resName = packMapping.keys.firstOrNull { it.contains(previewName) }
                    if (resName == null) {
                        val keyList = ArrayList(packMapping.keys)
                        if (keyList.isNotEmpty()) {
                            resName = keyList[ThreadLocalRandom.current().nextInt(0, keyList.size)]
                        }
                    }
                    if (resName != null) {
                        val icons = packMapping[resName]
                        if (!icons.isNullOrEmpty()) {
                            icons[0].getDrawable()?.let { packDrawables.add(it) }
                        }
                    }
                }
            }
            return packDrawables
        }

        override fun toString(): String {
            return "IconPack{mName='$mName', mAuthor='$mAuthor', mPackageName='$mPackageName'}"
        }

        override fun equals(other: Any?): Boolean {
            if (other is IconPack) {
                return mName == other.mName && mPackageName == other.mPackageName
            }
            return false
        }

        override fun hashCode(): Int {
            return 31 * mPackageName.hashCode() + mName.hashCode()
        }
    }

    inner class ReplacementIcon(
        @JvmField var mIconPack: IconPack,
        @JvmField var mReplacementRes: String
    ) {
        @JvmField
        var mEnabled: Boolean = false

        val isEnabled: Boolean
            get() = mEnabled

        fun getDrawable(): Drawable? {
            return try {
                val packResources = mPackageManager.getResourcesForApplication(mIconPack.mPackageName)
                @SuppressLint("DiscouragedApi")
                val resID = packResources.getIdentifier(mReplacementRes, "drawable", mIconPack.mPackageName)
                if (resID == 0) return null
                val parser: XmlPullParser = packResources.getXml(resID)
                Drawable.createFromXml(packResources, parser)
            } catch (ignored: Throwable) {
                null
            }
        }

        override fun equals(other: Any?): Boolean {
            if (other is ReplacementIcon) {
                return other.mIconPack == mIconPack && other.mReplacementRes == mReplacementRes
            }
            return false
        }

        override fun hashCode(): Int {
            return 31 * mIconPack.hashCode() + mReplacementRes.hashCode()
        }

        override fun toString(): String {
            return "ReplacementIcon{mIconPack=$mIconPack, mReplacementRes='$mReplacementRes', mEnabled=$mEnabled}"
        }
    }

    inner class ResourceMapping : HashMap<String, ArrayList<ReplacementIcon>>() {
        fun add(originalRes: String, replacementIcon: ReplacementIcon) {
            if (!containsKey(originalRes)) {
                put(originalRes, ArrayList())
            }
            this[originalRes]?.add(replacementIcon)
        }

        fun getOriginalResList(): List<String> = ArrayList(keys)

        fun getReplacementIcons(originalRes: String): List<ReplacementIcon>? = this[originalRes]

        fun reset(resName: String) {
            this[resName]?.forEach { it.mEnabled = false }
        }

        fun setEnabled(resName: String, replacementIcon: ReplacementIcon, enabled: Boolean) {
            reset(resName)
            this[resName]?.forEach { rep ->
                if (rep == replacementIcon) rep.mEnabled = enabled
            }
        }
    }

    inner class IconPackMapping : HashMap<IconPack, HashMap<String, ArrayList<ReplacementIcon>>>() {
        fun add(iconPack: IconPack, resName: String, replacementIcon: ReplacementIcon) {
            val thisPack = getOrPut(iconPack) { HashMap() }
            val list = thisPack.getOrPut(resName) { ArrayList() }
            list.add(replacementIcon)
        }

        fun getIconPacks(): List<IconPack> = ArrayList(keys)

        fun getResNames(iconPack: IconPack): List<String> {
            val pack = this[iconPack] ?: return emptyList()
            return ArrayList(pack.keys)
        }

        fun getReplacementIcons(iconPack: IconPack, resName: String): List<ReplacementIcon>? {
            return this[iconPack]?.get(resName)
        }
    }
}
