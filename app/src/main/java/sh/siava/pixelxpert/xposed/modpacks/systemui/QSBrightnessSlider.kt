package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Pair
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.res.ResourcesCompat
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.findClassIfExists
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getStaticObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.toolkit.ColorUtils
import sh.siava.pixelxpert.xposed.utils.toolkit.ResourceTools
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

@SystemUIModPack
class QSBrightnessSlider(context: Context) : XposedModPack(context) {

    private var brightnessBelowTiles = false
    private var brightnessInQqs = false
    private var autoBrightnessToggle = false
    private var mLastAutoToggleClick: Long = 0
    private val activeToggleViews = WeakHashMap<View, Boolean>()
    private val activeSliders: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val mMainHandler = Handler(Looper.getMainLooper())
    private var cachedPaddedModifier: Any? = null
    @Volatile
    private var isBrightnessMirrorShowing = false
    private var composeToggleModifier: Any? = null
    private var composeToggleFactory: Any? = null

    private var function0Class: Class<*>? = null
    private var function1Class: Class<*>? = null
    private var function2Class: Class<*>? = null
    private var function3Class: Class<*>? = null
    private var modifierClass: Class<*>? = null
    private var composerClass: Class<*>? = null
    private var androidViewMethod: Method? = null
    private var paddingMethod: Method? = null
    private var sizeMethod: Method? = null
    private var alignMethod: Method? = null
    private var startReplaceGroupMethod: Method? = null
    private var endReplaceGroupMethod: Method? = null
    private var lastBrightnessViewModel: Any? = null
    private var sliderModifierIndex = -1
    private var sliderComposerIndex = -1
    private var androidViewArgCount = 0
    private var boxScopeInstance: Any? = null
    private var centerEndAlignment: Any? = null

    private var kotlinUnit: Any? = null
    private var modifierCompanion: Any? = null
    private var sharedElementKey: Any? = null
    private var brightnessContainerMethod: Method? = null
    private var containerColorsConstructor: Constructor<*>? = null
    private var rememberViewModelMethod: Method? = null
    private var elementMethod: Method? = null
    private var sceneBrightnessElementKey: Any? = null

    private var qsFragment: Any? = null
    private var qqsScope: Any? = null
    private var qsScope: Any? = null

    private val qsBrightnessSlots = WeakHashMap<Any, Any>()
    private val qqsTilesSlots = WeakHashMap<Any, Any>()
    private val mediaRowSlots = WeakHashMap<Any, Pair<Any, Any>>()
    private val sceneMediaRowSlots = WeakHashMap<Any, Pair<Any, Any>>()
    private val arrangedFirstSlots: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())
    private var emptyComposableSlot: Any? = null
    private var emptyScopedComposableSlot: Any? = null

    private var shadeSceneViewModel: Any? = null
    private var shadeScope: Any? = null
    private val shadeQqsSlots = WeakHashMap<Any, Any>()

    private fun className(pkg: String, cls: String): String = "$pkg.$cls"

    override fun onPreferenceUpdated(vararg key: String) {
        brightnessBelowTiles = Xprefs.getBoolean("qs_brightness_slider_bottom", false)
        brightnessInQqs = Xprefs.getBoolean("qqs_brightness_slider", false)
        autoBrightnessToggle = Xprefs.getBoolean("QSAutoBrightnessToggle", false)

        qsBrightnessSlots.clear()
        qqsTilesSlots.clear()
        shadeQqsSlots.clear()
        updateAllToggleViews()
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        resolveComposeApis()
        registerBrightnessModeObserver()
        hookQsFragmentCompose()
        hookSceneContainer()
        hookComposeBrightnessSlider()
        hookBrightnessSliderView()
    }

    private fun hookQsFragmentCompose() {
        val qsFragmentClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.composefragment.QSFragmentCompose")
        val qsLayoutClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.composefragment.QSFragmentComposeKt")

        if (qsFragmentClass?.clazz == null || qsLayoutClass?.clazz == null) {
            return
        }

        qsFragmentClass.before("QuickQuickSettingsElement").run { param ->
            qsFragment = param.thisObject
            qqsScope = param.args.getOrNull(0)
        }

        qsFragmentClass.before("QuickSettingsElement").run { param ->
            qsFragment = param.thisObject
            qsScope = param.args.getOrNull(0)
        }

        qsLayoutClass.before("QuickSettingsLayout").run { param ->
            if (!brightnessBelowTiles && !brightnessInQqs) return@run
            if (param.args.size < 3) return@run

            val brightness = param.args[0] ?: return@run
            if (arrangedFirstSlots.contains(brightness)) return@run

            val tiles = param.args[1] ?: return@run

            var brightnessSlot = if (brightnessInQqs) sharedQsBrightnessSlot(brightness) else brightness
            if (brightnessSlot == null) brightnessSlot = brightness

            if (!brightnessBelowTiles) {
                param.args[0] = brightnessSlot
                return@run
            }

            var mediaInRowIndex = -1
            for (i in param.args.indices) {
                if (param.args[i] is Boolean) {
                    mediaInRowIndex = i
                    break
                }
            }
            val mediaInRow = if (mediaInRowIndex != -1) param.args[mediaInRowIndex] as? Boolean else null
            val media = param.args[2]
            val emptySlot = emptySlot()

            if (mediaInRow == null || !mediaInRow || media == null || emptySlot == null) {
                arrangedFirstSlots.add(tiles)
                param.args[0] = tiles
                param.args[1] = brightnessSlot
                return@run
            }

            val method = param.method as? Method ?: return@run
            var composerIndex = -1
            val pts = method.parameterTypes
            for (i in pts.indices) {
                if (className("androidx.compose.runtime", "Composer") == pts[i].name) {
                    composerIndex = i
                    break
                }
            }
            val composer = if (composerIndex != -1) param.args[composerIndex] else return@run

            val cached = mediaRowSlots[tiles]
            val tilesAndMediaRow: Any?
            if (cached != null && cached.first == media) {
                tilesAndMediaRow = cached.second
            } else {
                tilesAndMediaRow = composableSlot { rowComposer, _ ->
                    composeOriginalLayout(method, emptySlot, tiles, media, true, rowComposer)
                }
                if (tilesAndMediaRow != null) {
                    mediaRowSlots[tiles] = Pair(media, tilesAndMediaRow)
                    arrangedFirstSlots.add(tilesAndMediaRow)
                } else {
                    return@run
                }
            }

            startGroup(composer, 0x1C0B5A1D)
            try {
                composeOriginalLayout(method, tilesAndMediaRow, brightnessSlot, emptySlot, false, composer)
            } finally {
                endGroup(composer)
            }
            param.result = null
        }

        qsLayoutClass.before("QuickQuickSettingsLayout").run { param ->
            if (!brightnessInQqs) return@run

            var mediaInRow: Boolean? = null
            for (arg in param.args) {
                if (arg is Boolean) {
                    mediaInRow = arg
                    break
                }
            }
            if (mediaInRow == null || mediaInRow) return@run

            val tiles = param.args.getOrNull(0) ?: return@run
            if (qqsTilesSlots.containsValue(tiles)) return@run

            val existingSlot = qqsTilesSlots[tiles]
            if (existingSlot != null) {
                param.args[0] = existingSlot
                return@run
            }

            val slot = composableSlot { composer, changed ->
                if (brightnessBelowTiles) {
                    callMethod(tiles, "invoke", composer, changed)
                    composeQqsBrightness(composer)
                } else {
                    composeQqsBrightness(composer)
                    callMethod(tiles, "invoke", composer, changed)
                }
            } ?: return@run

            qqsTilesSlots[tiles] = slot
            param.args[0] = slot
        }
    }

    private fun hookSceneContainer() {
        val qsContentKtClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.ui.composable.QuickSettingsContentKt")
        if (qsContentKtClass?.clazz != null) {
            for (m in qsContentKtClass.clazz.declaredMethods) {
                if (m.name.startsWith("QuickSettingsPanelLayout")) {
                    qsContentKtClass.before(m.name).run { param ->
                        if (!brightnessBelowTiles || param.args.size < 2) return@run
                        val brightness = param.args[0] ?: return@run
                        if (arrangedFirstSlots.contains(brightness)) return@run
                        val tiles = param.args[1] ?: return@run

                        val method = param.method as? Method ?: return@run
                        var mediaInRowIndex = -1
                        val pts = method.parameterTypes
                        for (i in pts.indices) {
                            if (pts[i] == Boolean::class.javaPrimitiveType) {
                                mediaInRowIndex = i
                                break
                            }
                        }
                        val mediaInRow = if (mediaInRowIndex != -1) param.args[mediaInRowIndex] as? Boolean else null
                        val media = param.args.getOrNull(2)
                        val emptySlot = emptyScopedSlot()

                        var composerIndex = -1
                        for (i in pts.indices) {
                            if (className("androidx.compose.runtime", "Composer") == pts[i].name) {
                                composerIndex = i
                                break
                            }
                        }
                        val composer = if (composerIndex != -1) param.args[composerIndex] else null

                        if (mediaInRow == null || !mediaInRow || media == null || emptySlot == null || composer == null) {
                            arrangedFirstSlots.add(tiles)
                            param.args[0] = tiles
                            param.args[1] = brightness
                            return@run
                        }

                        val originalArgs = param.args.clone()
                        val cached = sceneMediaRowSlots[tiles]
                        val tilesAndMediaRow: Any?
                        val finalMediaInRowIndex = mediaInRowIndex
                        if (cached != null && cached.first == media) {
                            tilesAndMediaRow = cached.second
                        } else {
                            tilesAndMediaRow = composableScopedSlot { _, rowComposer, _ ->
                                invokeScenePanelLayout(method, originalArgs, arrayOf(emptySlot, tiles, media), finalMediaInRowIndex, true, modifierCompanion, rowComposer)
                            }
                            if (tilesAndMediaRow != null) {
                                sceneMediaRowSlots[tiles] = Pair(media, tilesAndMediaRow)
                                arrangedFirstSlots.add(tilesAndMediaRow)
                            } else {
                                return@run
                            }
                        }

                        startGroup(composer, 0x1C0B5A1E)
                        try {
                            invokeScenePanelLayout(method, originalArgs, arrayOf(tilesAndMediaRow, brightness, emptySlot), mediaInRowIndex, false, null, composer)
                        } finally {
                            endGroup(composer)
                        }
                        param.result = null
                    }
                }
            }
        }

        val shadeSceneClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.ui.composable.ShadeSceneKt")
        if (shadeSceneClass?.clazz == null) return

        shadeSceneClass.before("SingleShade").run { param ->
            for (arg in param.args) {
                if (arg == null) continue
                val name = arg.javaClass.name
                if (className("com.android.systemui.shade.ui.viewmodel", "ShadeSceneContentViewModel") == name) {
                    shadeSceneViewModel = arg
                }
                if (isContentScope(arg)) {
                    shadeScope = arg
                }
            }
        }

        for (m in shadeSceneClass.clazz.declaredMethods) {
            if (m.name.startsWith("MediaAndQqsLayout")) {
                shadeSceneClass.before(m.name).run { param ->
                    val split = isSplitShade()

                    var mediaInRow: Boolean? = null
                    for (arg in param.args) {
                        if (arg is Boolean) {
                            mediaInRow = arg
                            break
                        }
                    }

                    if (!brightnessInQqs) return@run
                    if (split) return@run
                    if (mediaInRow == null || mediaInRow) return@run

                    val qqs = param.args.getOrNull(0) ?: return@run
                    if (shadeQqsSlots.containsValue(qqs)) return@run

                    val existingSlot = shadeQqsSlots[qqs]
                    if (existingSlot != null) {
                        param.args[0] = existingSlot
                        return@run
                    }

                    val slot = composableSlot { composer, changed ->
                        if (brightnessBelowTiles) {
                            callMethod(qqs, "invoke", composer, changed)
                            composeShadeBrightness(composer)
                        } else {
                            composeShadeBrightness(composer)
                            callMethod(qqs, "invoke", composer, changed)
                        }
                    } ?: return@run

                    shadeQqsSlots[qqs] = slot
                    param.args[0] = slot
                }
            }
        }
    }

    private fun composeShadeBrightness(composer: Any?) {
        val containerViewModel = rememberShadeContainerViewModel(composer) ?: return
        val brightnessViewModel = getObjectFieldSilently(containerViewModel, "brightnessSliderViewModel") ?: return

        val scope = shadeScope
        val key = sceneBrightnessElementKey ?: sharedElementKey

        if (scope != null && key != null) {
            composeElement(scope, key, composer) { innerComposer ->
                composeBrightnessContainer(innerComposer, brightnessViewModel)
            }
        } else {
            composeBrightnessContainer(composer, brightnessViewModel)
        }
    }

    private fun rememberShadeContainerViewModel(composer: Any?): Any? {
        val method = rememberViewModelMethod ?: return null
        val factory = getObjectFieldSilently(shadeSceneViewModel, "qsContainerViewModelFactory") ?: return null

        val provider = function0Proxy {
            try {
                callMethod(factory, "create", false)
            } catch (t: Throwable) {
                null
            }
        } ?: return null

        val parameterTypes = method.parameterTypes
        var composerIndex = -1
        for (i in parameterTypes.indices) {
            if (className("androidx.compose.runtime", "Composer") == parameterTypes[i].name) {
                composerIndex = i
                break
            }
        }
        if (composerIndex == -1) return null

        var defaultMask = 0
        var stringFilled = false
        val args = arrayOfNulls<Any>(parameterTypes.size)

        for (index in parameterTypes.indices) {
            val type = parameterTypes[index]
            if (index == composerIndex) {
                args[index] = composer
            } else if (index > composerIndex) {
                args[index] = 0
            } else if (type == String::class.java && !stringFilled) {
                stringFilled = true
                args[index] = "iconify_qqs_brightness"
            } else if (className("kotlin.jvm.functions", "Function0") == type.name) {
                args[index] = provider
            } else {
                defaultMask = defaultMask or (1 shl index)
                args[index] = null
            }
        }

        if (parameterTypes.size - composerIndex - 1 == 2) {
            args[parameterTypes.size - 1] = defaultMask
        }

        return try {
            method.invoke(null, *args)
        } catch (t: Throwable) {
            null
        }
    }

    private fun isContentScope(arg: Any?): Boolean {
        if (arg == null) return false
        for (iface in arg.javaClass.interfaces) {
            if (className("com.android.compose.animation.scene", "ContentScope") == iface.name) {
                return true
            }
        }
        for (m in arg.javaClass.methods) {
            if ("Element" == m.name && m.parameterTypes.size == 5) {
                return true
            }
        }
        return false
    }

    private fun isSplitShade(): Boolean {
        val id = getResId(mContext, "config_use_split_notification_shade", "bool")
        return id != 0 && mContext.resources.getBoolean(id)
    }

    private fun sysUiClassLoader(): ClassLoader {
        val anchor = ReflectedClass.ofIfPossible("com.android.systemui.shade.ui.composable.ShadeSceneKt")
        return anchor?.clazz?.classLoader ?: mContext.classLoader
    }

    private fun resolveComposeApis() {
        val cl = sysUiClassLoader()
        try {
            function0Class = findClassIfExists(className("kotlin.jvm.functions", "Function0"), cl)
            function1Class = findClassIfExists(className("kotlin.jvm.functions", "Function1"), cl)
            function2Class = findClassIfExists(className("kotlin.jvm.functions", "Function2"), cl)
            function3Class = findClassIfExists(className("kotlin.jvm.functions", "Function3"), cl)
            modifierClass = findClassIfExists(className("androidx.compose.ui", "Modifier"), cl)
            composerClass = findClassIfExists(className("androidx.compose.runtime", "Composer"), cl)
            if (composerClass != null) {
                try {
                    startReplaceGroupMethod = composerClass?.getMethod("startReplaceGroup", Int::class.javaPrimitiveType)
                    startReplaceGroupMethod?.isAccessible = true
                    endReplaceGroupMethod = composerClass?.getMethod("endReplaceGroup")
                    endReplaceGroupMethod?.isAccessible = true
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }

        try {
            val paddingKtClass = findClassIfExists(className("androidx.compose.foundation.layout", "PaddingKt"), cl)
            if (paddingKtClass != null) {
                for (m in paddingKtClass.declaredMethods) {
                    if (m.name.startsWith("padding-qDBjuR0") && m.parameterTypes.size == 5) {
                        paddingMethod = m
                        paddingMethod?.isAccessible = true
                        break
                    }
                }
            }
        } catch (ignored: Throwable) {
        }

        try {
            val sizeKtClass = findClassIfExists(className("androidx.compose.foundation.layout", "SizeKt"), cl)
            if (sizeKtClass != null) {
                for (m in sizeKtClass.declaredMethods) {
                    if (m.name.startsWith("size-3ABfNKs")) {
                        sizeMethod = m
                        sizeMethod?.isAccessible = true
                        break
                    }
                }
            }
        } catch (ignored: Throwable) {
        }

        try {
            val boxScopeInstanceClass = findClassIfExists(className("androidx.compose.foundation.layout", "BoxScopeInstance"), cl)
            if (boxScopeInstanceClass != null) {
                val instanceField = boxScopeInstanceClass.getDeclaredField("INSTANCE")
                instanceField.isAccessible = true
                boxScopeInstance = instanceField.get(null)
                for (m in boxScopeInstanceClass.declaredMethods) {
                    if ("align" == m.name && m.parameterTypes.size == 2) {
                        alignMethod = m
                        alignMethod?.isAccessible = true
                        break
                    }
                }
            }
        } catch (ignored: Throwable) {
        }

        try {
            val alignmentCompanionClass = findClassIfExists(className("androidx.compose.ui", "Alignment\$Companion"), cl)
            if (alignmentCompanionClass != null) {
                val centerEndField = alignmentCompanionClass.getDeclaredField("CenterEnd")
                centerEndField.isAccessible = true
                centerEndAlignment = centerEndField.get(null)
            }
        } catch (ignored: Throwable) {
        }

        try {
            val androidViewKtClass = findClassIfExists(className("androidx.compose.ui.viewinterop", "AndroidView_androidKt"), cl)
            if (androidViewKtClass != null) {
                for (m in androidViewKtClass.declaredMethods) {
                    if ("AndroidView" == m.name && m.parameterTypes.size == 6) {
                        androidViewMethod = m
                        androidViewMethod?.isAccessible = true
                        androidViewArgCount = 6
                        break
                    }
                }
                if (androidViewMethod == null) {
                    for (m in androidViewKtClass.declaredMethods) {
                        if ("AndroidView" == m.name && m.parameterTypes.size == 8) {
                            androidViewMethod = m
                            androidViewMethod?.isAccessible = true
                            androidViewArgCount = 8
                            break
                        }
                    }
                }
            }
        } catch (ignored: Throwable) {
        }

        try {
            val sysUiViewModelKt = findClassIfExists(className("com.android.systemui.lifecycle", "SysUiViewModelKt"), cl)
            if (sysUiViewModelKt != null) {
                for (m in sysUiViewModelKt.declaredMethods) {
                    if ("rememberViewModel" == m.name) {
                        rememberViewModelMethod = m
                        break
                    }
                }
            }
        } catch (ignored: Throwable) {
        }

        try {
            val qsElementsClass = findClassIfExists(className("com.android.systemui.qs.shared.ui", "QuickSettings\$Elements"), cl)
            if (qsElementsClass != null) {
                val brightnessSliderField = qsElementsClass.getDeclaredField("BrightnessSlider")
                brightnessSliderField.isAccessible = true
                sceneBrightnessElementKey = brightnessSliderField.get(null)
            }
        } catch (ignored: Throwable) {
        }

        try {
            val unitClass = findClassIfExists(className("kotlin", "Unit"), cl)
            if (unitClass != null) {
                kotlinUnit = getStaticObjectField(unitClass, "INSTANCE")
            }
            if (modifierClass != null) {
                modifierCompanion = getStaticObjectField(modifierClass, "Companion")
            }
        } catch (ignored: Throwable) {
        }

        try {
            val elementKeyClass = findClassIfExists(className("com.android.compose.animation.scene", "ElementKey"), cl)
            if (elementKeyClass != null) {
                for (c in elementKeyClass.declaredConstructors) {
                    if (c.parameterTypes.size == 6) {
                        c.isAccessible = true
                        sharedElementKey = c.newInstance("PXBrightnessSlider", null, null, false, 14, null)
                        break
                    }
                }
            }
        } catch (ignored: Throwable) {
        }

        try {
            val brightnessSliderKtClass = findClassIfExists(className("com.android.systemui.brightness.ui.compose", "BrightnessSliderKt"), cl)
            if (brightnessSliderKtClass != null) {
                for (m in brightnessSliderKtClass.declaredMethods) {
                    if ("BrightnessSliderContainer" == m.name) {
                        brightnessContainerMethod = m
                        brightnessContainerMethod?.isAccessible = true
                        break
                    }
                }
            }
        } catch (ignored: Throwable) {
        }

        try {
            val containerColorsClass = findClassIfExists(className("com.android.systemui.brightness.ui.compose", "ContainerColors"), cl)
            if (containerColorsClass != null) {
                for (c in containerColorsClass.declaredConstructors) {
                    if (c.parameterTypes.size == 2 &&
                        c.parameterTypes[0] == Long::class.javaPrimitiveType &&
                        c.parameterTypes[1] == Long::class.javaPrimitiveType
                    ) {
                        containerColorsConstructor = c
                        containerColorsConstructor?.isAccessible = true
                        break
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun sharedQsBrightnessSlot(brightness: Any?): Any? {
        if (brightness == null) return null
        if (qsBrightnessSlots.containsValue(brightness)) return brightness
        val existingSlot = qsBrightnessSlots[brightness]
        if (existingSlot != null) return existingSlot

        val scope = qsScope ?: return null
        val key = sharedElementKey ?: return null

        val slot = composableSlot { composer, changed ->
            composeElement(scope, key, composer) { innerComposer ->
                callMethod(brightness, "invoke", innerComposer, changed)
            }
        } ?: return null

        qsBrightnessSlots[brightness] = slot
        return slot
    }

    private fun composeQqsBrightness(composer: Any?) {
        val scope = qqsScope
        val key = sharedElementKey
        if (scope != null && key != null) {
            composeElement(scope, key, composer) { innerComposer -> composeQsFragmentBrightness(innerComposer) }
        } else {
            composeQsFragmentBrightness(composer)
        }
    }

    fun interface ComposableInnerBlock {
        fun invoke(composer: Any?)
    }

    private fun composableContent(fallbackComposer: Any?, block: ComposableInnerBlock): Any? {
        val f3Class = function3Class ?: return null
        return Proxy.newProxyInstance(
            f3Class.classLoader,
            arrayOf(f3Class)
        ) { proxy, method, args ->
            val methodName = method.name
            if ("invoke" == methodName) {
                val innerComposer = if (args != null && args.size > 1 && args[1] != null) args[1] else fallbackComposer
                block.invoke(innerComposer)
                return@newProxyInstance kotlinUnit
            } else if ("equals" == methodName) {
                return@newProxyInstance proxy === (if (args != null && args.isNotEmpty()) args[0] else null)
            } else if ("hashCode" == methodName) {
                return@newProxyInstance System.identityHashCode(proxy)
            } else if ("toString" == methodName) {
                return@newProxyInstance "PXBrightnessElement"
            }
            null
        }
    }

    private fun composeElement(scope: Any?, key: Any?, composer: Any?, content: ComposableInnerBlock?) {
        if (scope == null) {
            content?.invoke(composer)
            return
        }
        if (elementMethod == null) {
            for (m in scope.javaClass.methods) {
                if ("Element" == m.name && m.parameterTypes.size == 5) {
                    elementMethod = m
                    elementMethod?.isAccessible = true
                    break
                }
            }
        }
        val elementContent = composableContent(composer, content ?: ComposableInnerBlock {})

        val method = elementMethod
        if (method == null || elementContent == null) {
            content?.invoke(composer)
            return
        }

        try {
            method.invoke(scope, key, modifierCompanion, elementContent, composer, 0)
        } catch (ignored: Throwable) {
            content?.invoke(composer)
        }
    }

    private fun composeQsFragmentBrightness(composer: Any?) {
        val fragment = qsFragment ?: return
        var viewModel = getObjectFieldSilently(fragment, "viewModel")
        viewModel = getObjectFieldSilently(viewModel, "containerViewModel")
        viewModel = getObjectFieldSilently(viewModel, "brightnessSliderViewModel") ?: return
        composeBrightnessContainer(composer, viewModel)
    }

    private fun composeBrightnessContainer(composer: Any?, viewModel: Any?) {
        val method = brightnessContainerMethod ?: return
        if (viewModel == null) return
        try {
            val colors = containerColors() ?: return

            val parameterTypes = method.parameterTypes
            var composerIndex = -1
            for (i in parameterTypes.indices) {
                if (className("androidx.compose.runtime", "Composer") == parameterTypes[i].name) {
                    composerIndex = i
                    break
                }
            }
            if (composerIndex == -1) return

            var defaultMask = 0
            val args = arrayOfNulls<Any>(parameterTypes.size)

            for (index in parameterTypes.indices) {
                val type = parameterTypes[index]
                if (index == composerIndex) {
                    args[index] = composer
                } else if (index > composerIndex) {
                    args[index] = 0
                } else if (className("com.android.systemui.brightness.ui.viewmodel", "BrightnessSliderViewModel") == type.name) {
                    args[index] = viewModel
                } else if (className("androidx.compose.ui", "Modifier") == type.name) {
                    args[index] = modifierCompanion
                } else if (className("com.android.systemui.brightness.ui.compose", "ContainerColors") == type.name) {
                    args[index] = colors
                } else if (type == Boolean::class.javaPrimitiveType) {
                    defaultMask = defaultMask or (1 shl index)
                    args[index] = false
                } else {
                    defaultMask = defaultMask or (1 shl index)
                    args[index] = null
                }
            }

            if (parameterTypes.size - composerIndex - 1 == 2) {
                args[parameterTypes.size - 1] = defaultMask
            }

            method.invoke(null, *args)
        } catch (ignored: Throwable) {
        }
    }

    private fun containerColors(): Any? {
        val constructor = containerColorsConstructor ?: return null
        val mirrorColor = getThemedColor(mContext, "shade_panel_fallback")
        return try {
            constructor.newInstance(colorOf(0), colorOf(mirrorColor))
        } catch (t: Throwable) {
            null
        }
    }

    private fun colorOf(color: Int): Long {
        return (color.toLong() and 0xFFFFFFFFL) shl 32
    }

    fun interface Function0Block {
        fun invoke(): Any?
    }

    private fun function0Proxy(block: Function0Block): Any? {
        val f0Class = function0Class ?: return null
        return Proxy.newProxyInstance(
            f0Class.classLoader,
            arrayOf(f0Class)
        ) { proxy, method, args ->
            val methodName = method.name
            if ("invoke" == methodName) {
                return@newProxyInstance block.invoke()
            } else if ("equals" == methodName) {
                return@newProxyInstance proxy === (if (args != null && args.isNotEmpty()) args[0] else null)
            } else if ("hashCode" == methodName) {
                return@newProxyInstance System.identityHashCode(proxy)
            } else if ("toString" == methodName) {
                return@newProxyInstance "PXViewModelFactory"
            }
            null
        }
    }

    private fun startGroup(composer: Any?, key: Int) {
        if (composer == null) return
        try {
            callMethod(composer, "startReplaceGroup", key)
        } catch (t: Throwable) {
            try {
                callMethod(composer, "startReplaceableGroup", key)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun endGroup(composer: Any?) {
        if (composer == null) return
        try {
            callMethod(composer, "endReplaceGroup")
        } catch (t: Throwable) {
            try {
                callMethod(composer, "endReplaceableGroup")
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun emptySlot(): Any? {
        emptyComposableSlot?.let { return it }
        val slot = composableSlot { _, _ -> }
        if (slot != null) {
            emptyComposableSlot = slot
            arrangedFirstSlots.add(slot)
        }
        return slot
    }

    private fun emptyScopedSlot(): Any? {
        emptyScopedComposableSlot?.let { return it }
        val slot = composableScopedSlot { _, _, _ -> }
        if (slot != null) {
            emptyScopedComposableSlot = slot
            arrangedFirstSlots.add(slot)
        }
        return slot
    }

    private fun composeOriginalLayout(method: Method, brightness: Any?, tiles: Any?, media: Any?, mediaInRow: Boolean, composer: Any?) {
        val slots = arrayOf(brightness, tiles, media)
        var slotIndex = 0
        val pts = method.parameterTypes
        val args = arrayOfNulls<Any>(pts.size)
        for (i in pts.indices) {
            val type = pts[i]
            val name = type.name
            if (className("kotlin.jvm.functions", "Function2") == name) {
                args[i] = if (slotIndex < slots.size) slots[slotIndex++] else null
            } else if (type == Boolean::class.javaPrimitiveType) {
                args[i] = mediaInRow
            } else if (className("androidx.compose.runtime", "Composer") == name) {
                args[i] = composer
            } else if (type == Int::class.javaPrimitiveType) {
                args[i] = 0
            } else {
                args[i] = null
            }
        }
        try {
            method.isAccessible = true
            method.invoke(null, *args)
        } catch (ignored: Throwable) {
        }
    }

    private fun invokeScenePanelLayout(method: Method, originalArgs: Array<Any?>, slots: Array<Any?>, mediaInRowIndex: Int, mediaInRow: Boolean, modifier: Any?, composer: Any?) {
        val args = originalArgs.clone()
        for (i in slots.indices) {
            if (i < args.size) {
                args[i] = slots[i]
            }
        }
        args[mediaInRowIndex] = mediaInRow
        if (modifier != null) {
            val pts = method.parameterTypes
            for (i in pts.indices) {
                if (className("androidx.compose.ui", "Modifier") == pts[i].name) {
                    args[i] = modifier
                    break
                }
            }
        }
        var composerIndex = -1
        val pts = method.parameterTypes
        for (i in pts.indices) {
            if (className("androidx.compose.runtime", "Composer") == pts[i].name) {
                composerIndex = i
                break
            }
        }
        if (composerIndex != -1) {
            args[composerIndex] = composer
            for (i in composerIndex + 1 until args.size) {
                args[i] = 0
            }
        }
        try {
            method.isAccessible = true
            method.invoke(null, *args)
        } catch (ignored: Throwable) {
        }
    }

    fun interface ComposableContentScopedBlock {
        fun invoke(contentScope: Any?, composer: Any?, changed: Any?)
    }

    private fun composableScopedSlot(block: ComposableContentScopedBlock): Any? {
        val f3Class = function3Class ?: return null
        return Proxy.newProxyInstance(
            f3Class.classLoader,
            arrayOf(f3Class)
        ) { proxy, method, args ->
            val methodName = method.name
            if ("invoke" == methodName) {
                block.invoke(args[0], args[1], args[2])
                return@newProxyInstance kotlinUnit
            } else if ("equals" == methodName) {
                return@newProxyInstance proxy === (if (args != null && args.isNotEmpty()) args[0] else null)
            } else if ("hashCode" == methodName) {
                return@newProxyInstance System.identityHashCode(proxy)
            } else if ("toString" == methodName) {
                return@newProxyInstance "PXBrightnessElement"
            }
            null
        }
    }

    fun interface ComposableSlotBlock {
        fun invoke(composer: Any?, changed: Any?)
    }

    private fun composableSlot(block: ComposableSlotBlock): Any? {
        val f2Class = function2Class ?: return null
        return Proxy.newProxyInstance(
            f2Class.classLoader,
            arrayOf(f2Class)
        ) { proxy, method, args ->
            val methodName = method.name
            if ("invoke" == methodName) {
                block.invoke(args[0], args[1])
                return@newProxyInstance kotlinUnit
            } else if ("equals" == methodName) {
                return@newProxyInstance proxy === (if (args != null && args.isNotEmpty()) args[0] else null)
            } else if ("hashCode" == methodName) {
                return@newProxyInstance System.identityHashCode(proxy)
            } else if ("toString" == methodName) {
                return@newProxyInstance "PXBrightnessSlot"
            }
            null
        }
    }

    private fun getObjectFieldSilently(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        return try {
            getObjectField(obj, fieldName)
        } catch (t: Throwable) {
            null
        }
    }

    private fun getPaddedSliderModifier(): Any? {
        cachedPaddedModifier?.let { return it }
        val method = paddingMethod ?: return null
        val companion = modifierCompanion ?: return null
        return try {
            cachedPaddedModifier = method.invoke(null, companion, 0f, 0f, 52f, 0f)
            cachedPaddedModifier
        } catch (t: Throwable) {
            null
        }
    }

    private fun isMirrorShowing(viewModel: Any?): Boolean {
        if (viewModel == null) return false
        try {
            val delegate = getObjectFieldSilently(viewModel, "showMirror\$delegate")
            if (delegate != null) {
                val state = getObjectFieldSilently(delegate, "\$internalState")
                if (state != null) {
                    val value = callMethod(state, "getValue")
                    if (value is Boolean) {
                        return value
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        return false
    }

    private fun hookComposeBrightnessSlider() {
        val brightnessSliderKt = ReflectedClass.ofIfPossible("com.android.systemui.brightness.ui.compose.BrightnessSliderKt")
        if (brightnessSliderKt?.clazz == null) return

        brightnessSliderKt.before("BrightnessSliderContainer").run { param ->
            if (param.args != null && param.args.isNotEmpty()) {
                lastBrightnessViewModel = param.args[0]
                isBrightnessMirrorShowing = isMirrorShowing(lastBrightnessViewModel)
            }
        }

        brightnessSliderKt.after("BrightnessSliderContainer").run {
            isBrightnessMirrorShowing = false
        }

        brightnessSliderKt.before("BrightnessSlider").run { param ->
            val isMirror = isBrightnessMirrorShowing || isMirrorShowing(lastBrightnessViewModel)
            val modClass = modifierClass
            if (!autoBrightnessToggle || isMirror || modClass == null) return@run
            try {
                if (sliderModifierIndex == -1) {
                    val pts = (param.method as? Method)?.parameterTypes ?: return@run
                    for (i in pts.indices) {
                        if (modClass.isAssignableFrom(pts[i])) {
                            sliderModifierIndex = i
                            break
                        }
                    }
                }
                if (sliderModifierIndex != -1 && param.args != null && sliderModifierIndex < param.args.size) {
                    val padded = getPaddedSliderModifier()
                    if (padded != null) {
                        param.args[sliderModifierIndex] = padded
                    }
                }
            } catch (ignored: Throwable) {
            }
        }

        brightnessSliderKt.after("BrightnessSlider").run { param ->
            if (param.args == null || param.args.isEmpty()) return@run
            var composer: Any? = null
            if (sliderComposerIndex != -1 && sliderComposerIndex < param.args.size) {
                composer = param.args[sliderComposerIndex]
            } else {
                val compClass = composerClass
                if (compClass != null) {
                    for (i in param.args.indices) {
                        val arg = param.args[i]
                        if (arg != null && compClass.isInstance(arg)) {
                            composer = arg
                            sliderComposerIndex = i
                            break
                        }
                    }
                }
            }
            if (composer == null) return@run

            val isMirror = isBrightnessMirrorShowing || isMirrorShowing(lastBrightnessViewModel)

            try {
                startReplaceGroupMethod?.invoke(composer, 0x5058)

                if (autoBrightnessToggle && !isMirror && androidViewMethod != null && modifierCompanion != null &&
                    sizeMethod != null && alignMethod != null && boxScopeInstance != null && centerEndAlignment != null
                ) {
                    if (composeToggleModifier == null) {
                        val btnModifier = sizeMethod?.invoke(null, modifierCompanion, 44f)
                        composeToggleModifier = alignMethod?.invoke(boxScopeInstance, btnModifier, centerEndAlignment)
                    }
                    if (composeToggleFactory == null) {
                        composeToggleFactory = function1Proxy { context -> createToggleView(context as Context) }
                    }
                    if (composeToggleModifier != null && composeToggleFactory != null) {
                        if (androidViewArgCount == 6) {
                            androidViewMethod?.invoke(null, composeToggleFactory, composeToggleModifier, null, composer, 0, 4)
                        } else if (androidViewArgCount == 8) {
                            androidViewMethod?.invoke(null, composeToggleFactory, composeToggleModifier, null, null, null, composer, 0, 4 or 8 or 16)
                        }
                    }
                }

                endReplaceGroupMethod?.invoke(composer)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun hookBrightnessSliderView() {
        val brightnessSliderViewClass = ReflectedClass.ofIfPossible("com.android.systemui.settings.brightness.BrightnessSliderView")
        if (brightnessSliderViewClass?.clazz != null) {
            brightnessSliderViewClass.after("onFinishInflate").run { param ->
                val slider = getObjectFieldSilently(param.thisObject, "mSlider") as? View ?: return@run

                slider.post {
                    try {
                        val parent = slider.parent as? ViewGroup ?: return@post
                        if (parent.findViewWithTag<View>("px_brightness_toggle") != null) return@post

                        activeSliders.add(slider)

                        val toggleView = createToggleView(slider.context).apply {
                            tag = "px_brightness_toggle"
                        }

                        val toggleSize = getToggleSize(slider.context)
                        val toggleViewParams = FrameLayout.LayoutParams(
                            toggleSize,
                            toggleSize,
                            Gravity.END or Gravity.CENTER_VERTICAL
                        )
                        toggleView.layoutParams = toggleViewParams
                        parent.addView(toggleView)

                        val rawLp = slider.layoutParams
                        if (rawLp is ViewGroup.MarginLayoutParams) {
                            rawLp.marginEnd = getSliderEndMargin(slider.context)
                            slider.layoutParams = rawLp
                        }
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }
    }

    private fun getSliderEndMargin(context: Context?): Int {
        if (!autoBrightnessToggle || context == null) return 0
        return getToggleSize(context) + ResourceTools.dpToPx(context, 8)
    }

    private fun getToggleSize(context: Context?): Int {
        if (context == null) return 0
        val id = getResId(context, "brightness_mirror_height", "dimen")
        if (id != 0) {
            try {
                val size = context.resources.getDimensionPixelSize(id)
                if (size > 0) return size
            } catch (ignored: Throwable) {
            }
        }
        return ResourceTools.dpToPx(context, 44)
    }

    private fun registerBrightnessModeObserver() {
        try {
            mContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                false,
                object : ContentObserver(mMainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        updateAllToggleViews()
                    }
                }
            )
        } catch (ignored: Throwable) {
        }
    }

    private fun updateAllToggleViews() {
        mMainHandler.post {
            for (v in activeToggleViews.keys) {
                if (v != null) {
                    v.visibility = if (autoBrightnessToggle) View.VISIBLE else View.GONE
                    if (autoBrightnessToggle) {
                        setAutoBrightnessIcon(v)
                    }
                }
            }
            for (slider in activeSliders) {
                if (slider != null) {
                    try {
                        val rawLp = slider.layoutParams
                        if (rawLp is ViewGroup.MarginLayoutParams) {
                            val targetMargin = getSliderEndMargin(slider.context)
                            if (rawLp.marginEnd != targetMargin) {
                                rawLp.marginEnd = targetMargin
                                slider.layoutParams = rawLp
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                }
            }
        }
    }

    private fun createToggleView(context: Context): View {
        val toggleView = object : View(context) {
            override fun onConfigurationChanged(newConfig: Configuration) {
                super.onConfigurationChanged(newConfig)
                setAutoBrightnessIcon(this)
            }
        }
        activeToggleViews[toggleView] = java.lang.Boolean.TRUE

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        toggleView.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isDragging) {
                            val dx = event.x - downX
                            val dy = event.y - downY
                            if (dx * dx + dy * dy > touchSlop * touchSlop) {
                                isDragging = true
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            val now = SystemClock.uptimeMillis()
                            if (now > mLastAutoToggleClick + 500) {
                                mLastAutoToggleClick = now
                                try {
                                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                } catch (ignored: Throwable) {
                                }
                                toggleAutoBrightness()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                        return true
                    }
                }
                return false
            }
        })

        toggleView.visibility = if (autoBrightnessToggle) View.VISIBLE else View.GONE
        setAutoBrightnessIcon(toggleView)
        return toggleView
    }

    private fun setAutoBrightnessIcon(brightnessToggle: View?) {
        if (brightnessToggle == null) return
        try {
            val context = brightnessToggle.context ?: mContext

            val enabled = isAutoBrightnessEnabled()

            val backgroundDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(getBTBackgroundColor(context, enabled))
                cornerRadius = ResourceTools.dpToPx(context, if (enabled) 12 else 22).toFloat()
            }

            var iconDrawable: Drawable? = null
            var res = XPLauncher.moduleResources
            if (res == null && mContext != null) {
                try {
                    res = mContext.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).resources
                } catch (ignored: Throwable) {
                }
            }
            if (res != null) {
                try {
                    iconDrawable = ResourcesCompat.getDrawable(res, R.drawable.ic_brightness_auto, null)
                } catch (ignored: Throwable) {
                }
            }

            if (iconDrawable != null) {
                val mutated = iconDrawable.mutate()
                mutated.setTint(getBTIconColor(context, enabled))

                val layer = LayerDrawable(arrayOf(backgroundDrawable, mutated))
                val inset = ResourceTools.dpToPx(context, 8)
                layer.setLayerInset(1, inset, inset, inset, inset)
                brightnessToggle.background = layer
            } else {
                brightnessToggle.background = backgroundDrawable
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun getBTIconColor(context: Context, enabled: Boolean): Int {
        return getThemedColor(context, if (enabled) "materialColorOnPrimary" else "materialColorOnSurface")
    }

    private fun getBTBackgroundColor(context: Context, enabled: Boolean): Int {
        return getThemedColor(context, if (enabled) "materialColorPrimary" else "materialColorSurfaceContainerHigh")
    }

    private fun getResId(context: Context?, name: String?, type: String?): Int {
        if (context == null || name == null || type == null) return 0
        return try {
            val res = context.resources
            var id = res.getIdentifier(name, type, context.packageName)
            if (id != 0) return id
            id = res.getIdentifier(name, type, Constants.SYSTEM_UI_PACKAGE)
            if (id != 0) return id
            res.getIdentifier(name, type, "android")
        } catch (ignored: Throwable) {
            0
        }
    }

    private fun getThemedColor(context: Context?, name: String?): Int {
        if (context == null || name == null) return 0
        val attrId = getResId(context, name, "attr")
        if (attrId != 0) {
            val color = ColorUtils.getColorAttrDefaultColor(context, attrId, 0)
            if (color != 0) return color
        }
        val colorId = getResId(context, name, "color")
        if (colorId != 0) {
            try {
                return context.getColor(colorId)
            } catch (ignored: Throwable) {
            }
        }
        return 0
    }

    private fun isAutoBrightnessEnabled(): Boolean {
        return try {
            Settings.System.getInt(mContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (t: Throwable) {
            false
        }
    }

    private fun toggleAutoBrightness() {
        val target = !isAutoBrightnessEnabled()
        try {
            Settings.System.putInt(
                mContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (target) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            updateAllToggleViews()
        } catch (ignored: Throwable) {
        }
    }

    fun interface Function1Block {
        fun invoke(p1: Any?): Any?
    }

    private fun function1Proxy(block: Function1Block): Any? {
        val f1Class = function1Class ?: return null
        return Proxy.newProxyInstance(
            f1Class.classLoader,
            arrayOf(f1Class)
        ) { proxy, method, args ->
            val methodName = method.name
            if ("invoke" == methodName) {
                return@newProxyInstance block.invoke(if (args != null && args.isNotEmpty()) args[0] else null)
            } else if ("equals" == methodName) {
                return@newProxyInstance proxy === (if (args != null && args.isNotEmpty()) args[0] else null)
            } else if ("hashCode" == methodName) {
                return@newProxyInstance System.identityHashCode(proxy)
            } else if ("toString" == methodName) {
                return@newProxyInstance "PXFunction1"
            }
            null
        }
    }
}
