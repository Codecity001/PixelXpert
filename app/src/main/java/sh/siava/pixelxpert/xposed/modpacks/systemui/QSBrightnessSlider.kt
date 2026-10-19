package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.util.Pair
import de.robv.android.xposed.XposedBridge.invokeOriginalMethod
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.findClassIfExists
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.getStaticObjectField
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Moves SystemUI's own brightness slider somewhere it was not built to be.
 *
 * The most reflection-heavy pack in the module: SystemUI draws the QS panel
 * with Compose, so there is no view to reorder. Instead the layouts' composable
 * arguments are intercepted before they run, wrapped in cached `Function2` /
 * `Function3` proxies, and re-invoked in a different order through
 * `XposedBridge.invokeOriginalMethod` - leaving SystemUI's own composables
 * untouched and merely presenting them in a new arrangement.
 *
 * Because everything below is keyed off method signatures that AOSP renames
 * between releases, the whole surface is resolved reflectively in
 * [resolveComposeApis] and every lookup fails soft: a missing class leaves the
 * corresponding field null and the feature simply does not engage, rather than
 * taking SystemUI down with it.
 *
 * The two preferences:
 * - `qs_brightness_slider_bottom` - draw the slider below the tiles instead of
 *   above them.
 * - `qqs_brightness_slider` - also draw it in collapsed (quick-quick) QS, and
 *   share one instance with the expanded panel via a scene `ElementKey` so the
 *   two do not fight over the same view model.
 *
 * [hookSceneContainer] also hooks `QuickSettingsShadeOverlayKt.QuickSettingsLayoutContent`,
 * a third way into the panel layout. Without it the slider stays above the
 * tiles on builds that draw through the overlay.
 */
@SystemUIModPack
class QSBrightnessSlider(context: Context) : XposedModPack(context) {

	private var brightnessBelowTiles = false
	private var brightnessInQqs = false

	//region resolved compose APIs - all null until resolveComposeApis() finds them
	private var function0Class: Class<*>? = null
	private var function2Class: Class<*>? = null
	private var function3Class: Class<*>? = null
	private var kotlinUnit: Any? = null
	private var modifierCompanion: Any? = null
	private var sharedElementKey: Any? = null
	private var brightnessContainerMethod: Method? = null
	private var containerColorsConstructor: Constructor<*>? = null
	private var rememberViewModelMethod: Method? = null
	private var sceneBrightnessElementKey: Any? = null
	//endregion

	private var qsFragment: Any? = null
	private var qqsScope: Any? = null
	private var qsScope: Any? = null

	// Every slot below is a proxy standing in for a composable that SystemUI
	// will call back. They are cached per originating object because wrapping
	// the same lambda twice would compose it twice.
	private val qsBrightnessSlots = WeakHashMap<Any, Any>()
	private val qqsTilesSlots = WeakHashMap<Any, Any>()
	private val mediaRowSlots = WeakHashMap<Any, Pair<Any, Any>>()
	private val sceneMediaRowSlots = WeakHashMap<Any, Pair<Any, Any>>()

	// Slots already placed first in a row, so a hook that runs again for the
	// same argument must not re-wrap and re-order it a second time.
	private val arrangedFirstSlots: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())

	private var emptyComposableSlot: Any? = null
	private var emptyScopedComposableSlot: Any? = null

	private var shadeSceneViewModel: Any? = null
	private var shadeScope: Any? = null
	private val shadeQqsSlots = WeakHashMap<Any, Any>()

	private fun className(pkg: String, cls: String): String = "$pkg.$cls"

	override fun onPreferenceUpdated(vararg key: String?) {
		brightnessBelowTiles = Xprefs.getBoolean("qs_brightness_slider_bottom", false)
		brightnessInQqs = Xprefs.getBoolean("qqs_brightness_slider", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		resolveComposeApis()
		hookQsFragmentCompose()
		hookSceneContainer()
	}

	private fun hookQsFragmentCompose() {
		val qsFragmentClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.composefragment.QSFragmentCompose")
		val qsLayoutClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.composefragment.QSFragmentComposeKt")

		if (qsFragmentClass.clazz == null || qsLayoutClass.clazz == null) return

		// Capture the fragment and whichever scope is current, so the slider
		// we compose later can be placed inside the right scene.
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
			if (brightness in arrangedFirstSlots) return@run

			val tiles = param.args[1] ?: return@run

			val sharedBrightness = if (brightnessInQqs) sharedQsBrightnessSlot(brightness) else null
			val brightnessSlot: Any = sharedBrightness ?: brightness

			if (!brightnessBelowTiles) {
				param.args[0] = brightnessSlot
				return@run
			}

			// The media row is the only Boolean argument; its position is not
			// stable across releases, so locate it rather than assume it.
			val mediaInRowIndex = param.args.indexOfFirst { it is Boolean }
			val mediaInRow: Boolean? = if (mediaInRowIndex != -1) param.args[mediaInRowIndex] as Boolean else null
			val media = param.args[2]
			val emptySlot = emptySlot()

			// Without a live media row there is nothing to interleave with, so
			// fall back to swapping the two leading slots.
			if (mediaInRow == null || !mediaInRow || media == null || emptySlot == null) {
				arrangedFirstSlots.add(tiles)
				param.args[0] = tiles
				param.args[1] = brightnessSlot
				return@run
			}

			val method = param.method as Method
			val composerIndex = method.parameterTypes.indexOfFirst {
				className("androidx.compose.runtime", "Composer") == it.name
			}
			if (composerIndex == -1) return@run
			val composer = param.args[composerIndex] ?: return@run

			val cached = mediaRowSlots[tiles]
			var tilesAndMediaRow: Any?
			if (cached != null && cached.first === media) {
				tilesAndMediaRow = cached.second
			} else {
				tilesAndMediaRow = composableSlot { rowComposer, _ ->
					composeOriginalLayout(method, emptySlot, tiles, media, true, rowComposer)
				}
				if (tilesAndMediaRow == null) return@run
				mediaRowSlots[tiles] = Pair(media, tilesAndMediaRow)
				arrangedFirstSlots.add(tilesAndMediaRow)
			}

			startGroup(composer, GROUP_QS_TILES_MEDIA)
			try {
				composeOriginalLayout(method, tilesAndMediaRow, brightnessSlot, emptySlot, false, composer)
			} finally {
				endGroup(composer)
			}
			param.setResult(null)
		}

		qsLayoutClass.before("QuickQuickSettingsLayout").run { param ->
			if (!brightnessInQqs) return@run

			val mediaInRow = param.args.firstOrNull { it is Boolean } as Boolean?
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

	/**
	 * Scene path: newer builds draw the panel through
	 * `QuickSettingsContentKt` / `ShadeSceneKt` instead, which take a
	 * `ContentScope` and want a scoped `Function3` rather than a plain
	 * `Function2`.
	 */
	private fun hookSceneContainer() {
		val qsContentKtClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.ui.composable.QuickSettingsContentKt")
		if (qsContentKtClass.clazz != null) {
			// The layout is overloaded per release; hook every variant rather
			// than pick one name.
			for (m in qsContentKtClass.clazz!!.declaredMethods) {
				if (!m.name.startsWith("QuickSettingsPanelLayout")) continue

				qsContentKtClass.before(m.name).run { param ->
					if (!brightnessBelowTiles || param.args.size < 2) return@run
					val brightness = param.args[0] ?: return@run
					if (brightness in arrangedFirstSlots) return@run
					val tiles = param.args[1] ?: return@run

					val method = param.method as Method
					val pts = method.parameterTypes

					val mediaInRowIndex = pts.indexOfFirst { it == java.lang.Boolean.TYPE }
					val mediaInRow: Boolean? = if (mediaInRowIndex != -1) param.args[mediaInRowIndex] as Boolean else null
					val media = param.args.getOrNull(2)
					val emptySlot = emptyScopedSlot()

					val composerIndex = pts.indexOfFirst {
						className("androidx.compose.runtime", "Composer") == it.name
					}
					val composer = if (composerIndex != -1) param.args[composerIndex] else null

					if (mediaInRow == null || !mediaInRow || media == null || emptySlot == null || composer == null) {
						arrangedFirstSlots.add(tiles)
						param.args[0] = tiles
						param.args[1] = brightness
						return@run
					}

					val originalArgs = param.args.copyOf()
					val cached = sceneMediaRowSlots[tiles]
					var tilesAndMediaRow: Any?
					if (cached != null && cached.first === media) {
						tilesAndMediaRow = cached.second
					} else {
						tilesAndMediaRow = composableScopedSlot { _, rowComposer, changed ->
							invokeScenePanelLayout(
								method, originalArgs, arrayOf(emptySlot, tiles, media),
								mediaInRowIndex, true, modifierCompanion, rowComposer,
							)
						}
						if (tilesAndMediaRow == null) return@run
						sceneMediaRowSlots[tiles] = Pair(media, tilesAndMediaRow)
						arrangedFirstSlots.add(tilesAndMediaRow)
					}

					startGroup(composer!!, GROUP_SCENE_TILES_MEDIA)
					try {
						invokeScenePanelLayout(
							method, originalArgs, arrayOf(tilesAndMediaRow, brightness, emptySlot),
							mediaInRowIndex, false, null, composer,
						)
					} finally {
						endGroup(composer)
					}
					param.setResult(null)
				}
			}
		}

		// The overlay entry point: on builds that draw the panel through this
		// overlay the slider would otherwise stay where SystemUI put it.
		//
		// Unlike the content path this is a straight argument swap rather than
		// a rebuild: the three composables are located by type, and the second
		// and third are exchanged so tiles precede brightness. It is also
		// self-limiting - a second pass reads `args[slots[1]]`, finds the tiles
		// the first pass moved there, sees them already in
		// [arrangedFirstSlots], and leaves the row alone.
		val shadeOverlayClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.qs.ui.composable.QuickSettingsShadeOverlayKt",
		)
		if (shadeOverlayClass.clazz != null) {
			shadeOverlayClass.before("QuickSettingsLayoutContent").run { param ->
				if (!brightnessBelowTiles) return@run

				val slots = (param.method as Method).parameterTypes
					.withIndex()
					.filter { it.value.name == className("kotlin.jvm.functions", "Function3") }
					.map { it.index }
				if (slots.size != 3) return@run

				val brightness = param.args[slots[1]] ?: return@run
				if (brightness in arrangedFirstSlots) return@run
				val tiles = param.args[slots[2]] ?: return@run

				arrangedFirstSlots.add(tiles)
				param.args[slots[1]] = tiles
				param.args[slots[2]] = brightness
			}
		}

		val shadeSceneClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.ui.composable.ShadeSceneKt")
		if (shadeSceneClass.clazz == null) return

		// Grab the scene's content scope and view model while they are in
		// flight; the brightness slider needs both to render inside the scene.
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

		for (m in shadeSceneClass.clazz!!.declaredMethods) {
			if (!m.name.startsWith("MediaAndQqsLayout")) continue

			shadeSceneClass.before(m.name).run { param ->
				val split = isSplitShade()

				val mediaInRow = param.args.firstOrNull { it is Boolean } as Boolean?

				if (!brightnessInQqs) return@run
				// Split shade keeps QQS and the media row in separate columns,
				// so there is no shared row to inject into.
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

	private fun composeShadeBrightness(composer: Any) {
		val containerViewModel = rememberShadeContainerViewModel(composer) ?: return
		val brightnessViewModel = getObjectFieldSilently(containerViewModel, "brightnessSliderViewModel") ?: return

		val scope = shadeScope
		val key = sceneBrightnessElementKey ?: sharedElementKey

		if (scope != null && key != null) {
			composeElement(scope, key, composer) {
				composeBrightnessContainer(composer, brightnessViewModel)
			}
		} else {
			composeBrightnessContainer(composer, brightnessViewModel)
		}
	}

	/**
	 * Replays the shape of SystemUI's own `rememberViewModel` call, building
	 * the argument list from the real signature so a renamed parameter does not
	 * break it: everything after the `Composer` is a default-mask int, the
	 * first `String` is the key, and the `Function0` is the factory.
	 */
	private fun rememberShadeContainerViewModel(composer: Any): Any? {
		val rememberMethod = rememberViewModelMethod ?: return null
		val factory = getObjectFieldSilently(shadeSceneViewModel, "qsContainerViewModelFactory") ?: return null

		val provider = function0Proxy {
			try {
				callMethod(factory, "create", false)
			} catch (t: Throwable) {
				null
			}
		} ?: return null

		val parameterTypes = rememberMethod.parameterTypes
		val composerIndex = parameterTypes.indexOfFirst {
			className("androidx.compose.runtime", "Composer") == it.name
		}
		if (composerIndex == -1) return null

		var defaultMask = 0
		var stringFilled = false
		val args = arrayOfNulls<Any>(parameterTypes.size)

		for (index in parameterTypes.indices) {
			val type = parameterTypes[index]
			when {
				index == composerIndex -> args[index] = composer
				index > composerIndex -> args[index] = 0
				type == String::class.java && !stringFilled -> {
					stringFilled = true
					args[index] = "iconify_qqs_brightness"
				}
				className("kotlin.jvm.functions", "Function0") == type.name -> args[index] = provider
				else -> {
					defaultMask = defaultMask or (1 shl index)
					args[index] = null
				}
			}
		}

		if (parameterTypes.size - composerIndex - 1 == 2) {
			args[parameterTypes.size - 1] = defaultMask
		}

		return try {
			rememberMethod.invoke(null, *args)
		} catch (t: Throwable) {
			null
		}
	}

	/** Whether [arg] is a scene `ContentScope`, by interface or by shape. */
	private fun isContentScope(arg: Any): Boolean {
		for (iface in arg.javaClass.interfaces) {
			if (className("com.android.compose.animation.scene", "ContentScope") == iface.name) return true
		}
		// The interface is internal on some builds, so fall back to the
		// five-argument `Element` it always exposes.
		for (m in arg.javaClass.methods) {
			if (m.name == "Element" && m.parameterTypes.size == 5) return true
		}
		return false
	}

	private fun isSplitShade(): Boolean {
		val id = mContext.resources.getIdentifier(
			"config_use_split_notification_shade",
			"bool",
			Constants.SYSTEM_UI_PACKAGE,
		)
		return id != 0 && mContext.resources.getBoolean(id)
	}

	/**
	 * SystemUI's own class loader, not the module's.
	 *
	 * Every class below lives in SystemUI, so resolving them through the app's
	 * loader would find nothing - and the proxies have to be created in the
	 * same loader as the `Function2` interface they implement.
	 */
	private fun sysUiClassLoader(): ClassLoader {
		val anchor = ReflectedClass.ofIfPossible("com.android.systemui.shade.ui.composable.ShadeSceneKt")
		return anchor.clazz?.classLoader ?: mContext.classLoader
	}

	private fun resolveComposeApis() {
		val cl = sysUiClassLoader()

		try {
			function0Class = findClassIfExists(className("kotlin.jvm.functions", "Function0"), cl)
			function2Class = findClassIfExists(className("kotlin.jvm.functions", "Function2"), cl)
			function3Class = findClassIfExists(className("kotlin.jvm.functions", "Function3"), cl)
		} catch (t: Throwable) {
		}

		try {
			val sysUiViewModelKt = findClassIfExists(className("com.android.systemui.lifecycle", "SysUiViewModelKt"), cl)
			rememberViewModelMethod = sysUiViewModelKt?.declaredMethods?.firstOrNull { it.name == "rememberViewModel" }
		} catch (t: Throwable) {
		}

		try {
			val qsElementsClass = findClassIfExists(
				className("com.android.systemui.qs.shared.ui", "QuickSettings\$Elements"), cl,
			)
			val brightnessSliderField = qsElementsClass!!.getDeclaredField("BrightnessSlider")
			brightnessSliderField.isAccessible = true
			sceneBrightnessElementKey = brightnessSliderField.get(null)
		} catch (t: Throwable) {
		}

		try {
			val unitClass = findClassIfExists(className("kotlin", "Unit"), cl)
			kotlinUnit = getStaticObjectField(unitClass, "INSTANCE")
			val modifierClass = findClassIfExists(className("androidx.compose.ui", "Modifier"), cl)
			modifierCompanion = getStaticObjectField(modifierClass, "Companion")
		} catch (t: Throwable) {
		}

		try {
			val elementKeyClass = findClassIfExists(
				className("com.android.compose.animation.scene", "ElementKey"), cl,
			)
			val c = elementKeyClass!!.declaredConstructors.firstOrNull { it.parameterTypes.size == 6 }
			if (c != null) {
				c.isAccessible = true
				sharedElementKey = c.newInstance("PXBrightnessSlider", null, null, false, 14, null)
			}
		} catch (t: Throwable) {
		}

		try {
			val brightnessSliderKtClass = findClassIfExists(
				className("com.android.systemui.brightness.ui.compose", "BrightnessSliderKt"), cl,
			)
			brightnessContainerMethod = brightnessSliderKtClass?.declaredMethods
				?.firstOrNull { it.name == "BrightnessSliderContainer" }
				?.also { it.isAccessible = true }
		} catch (t: Throwable) {
		}

		try {
			val containerColorsClass = findClassIfExists(
				className("com.android.systemui.brightness.ui.compose", "ContainerColors"), cl,
			)
			val c = containerColorsClass?.declaredConstructors?.firstOrNull {
				it.parameterTypes.size == 2 &&
					it.parameterTypes[0] == java.lang.Long.TYPE &&
					it.parameterTypes[1] == java.lang.Long.TYPE
			}
			if (c != null) {
				c.isAccessible = true
				containerColorsConstructor = c
			}
		} catch (t: Throwable) {
		}
	}

	/**
	 * Wraps [brightness] in a slot that draws it inside the QS scene, so the
	 * same slider instance can be shared between collapsed and expanded QS.
	 */
	private fun sharedQsBrightnessSlot(brightness: Any): Any? {
		if (qsBrightnessSlots.containsValue(brightness)) return brightness
		qsBrightnessSlots[brightness]?.let { return it }

		val scope = qsScope ?: return null
		val key = sharedElementKey ?: return null

		val slot = composableSlot { composer, changed ->
			composeElement(scope, key, composer) {
				callMethod(brightness, "invoke", composer, changed)
			}
		} ?: return null

		qsBrightnessSlots[brightness] = slot
		return slot
	}

	private fun composeQqsBrightness(composer: Any) {
		val scope = qqsScope
		val key = sharedElementKey
		if (scope != null && key != null) {
			composeElement(scope, key, composer) { composeQsFragmentBrightness(composer) }
		} else {
			composeQsFragmentBrightness(composer)
		}
	}

	/**
	 * Wraps [content] in a scene element, falling back to running it inline
	 * when the scene API is unavailable - the slider is still worth drawing
	 * even if it cannot animate as a shared element.
	 */
	private fun composeElement(scope: Any, key: Any, composer: Any, content: () -> Unit) {
		val elementMethod = scope.javaClass.methods
			.firstOrNull { it.name == "Element" && it.parameterTypes.size == 5 }
		val elementContent = composableContent(content)

		if (elementMethod == null || elementContent == null) {
			content()
			return
		}

		try {
			elementMethod.invoke(scope, key, modifierCompanion, elementContent, composer, 0)
		} catch (ignored: Throwable) {
			content()
		}
	}

	private fun composeQsFragmentBrightness(composer: Any) {
		var viewModel = getObjectFieldSilently(qsFragment, "viewModel")
		viewModel = getObjectFieldSilently(viewModel, "containerViewModel")
		viewModel = getObjectFieldSilently(viewModel, "brightnessSliderViewModel")
			?: return
		composeBrightnessContainer(composer, viewModel)
	}

	/**
	 * Replays `BrightnessSliderContainer` with the view model and a pair of
	 * colours we supply, filling the rest from the live signature the same way
	 * [rememberShadeContainerViewModel] does.
	 */
	private fun composeBrightnessContainer(composer: Any, viewModel: Any?) {
		val container = brightnessContainerMethod ?: return
		if (viewModel == null) return

		try {
			val colors = containerColors() ?: return

			val parameterTypes = container.parameterTypes
			val composerIndex = parameterTypes.indexOfFirst {
				className("androidx.compose.runtime", "Composer") == it.name
			}
			if (composerIndex == -1) return

			var defaultMask = 0
			val args = arrayOfNulls<Any>(parameterTypes.size)

			for (index in parameterTypes.indices) {
				val type = parameterTypes[index]
				when {
					index == composerIndex -> args[index] = composer
					index > composerIndex -> args[index] = 0
					className("com.android.systemui.brightness.ui.viewmodel", "BrightnessSliderViewModel") == type.name ->
						args[index] = viewModel
					className("androidx.compose.ui", "Modifier") == type.name -> args[index] = modifierCompanion
					className("com.android.systemui.brightness.ui.compose", "ContainerColors") == type.name ->
						args[index] = colors
					type == java.lang.Boolean.TYPE -> {
						defaultMask = defaultMask or (1 shl index)
						args[index] = false
					}
					else -> {
						defaultMask = defaultMask or (1 shl index)
						args[index] = null
					}
				}
			}

			if (parameterTypes.size - composerIndex - 1 == 2) {
				args[parameterTypes.size - 1] = defaultMask
			}

			container.invoke(null, *args)
		} catch (ignored: Throwable) {
		}
	}

	/**
	 * Mirrors SystemUI's own panel colours so the moved slider does not stand
	 * out against the surface it now sits on. The transparent entry is what
	 * keeps the slider legible when it is no longer on the usual tile strip.
	 */
	private fun containerColors(): Any? {
		val mirrorColorId = mContext.resources.getIdentifier(
			"shade_panel_fallback",
			"color",
			Constants.SYSTEM_UI_PACKAGE,
		)
		var mirrorColor = 0
		if (mirrorColorId != 0) {
			mirrorColor = mContext.getColor(mirrorColorId)
		}

		return try {
			containerColorsConstructor?.newInstance(colorOf(0), colorOf(mirrorColor))
		} catch (t: Throwable) {
			null
		}
	}

	/** Packs an ARGB int into the `ULong` colour representation Compose uses. */
	private fun colorOf(color: Int): Long = ((color.toLong()) and 0xFFFFFFFFL) shl 32

	//region composable proxies
	//
	// Compose calls these as `Function0` / `Function2` / `Function3`. They are
	// built with `java.lang.reflect.Proxy` rather than Kotlin lambdas because
	// the interface classes are loaded by SystemUI's own class loader, which
	// the module's copy of `kotlin.jvm.functions` is not.
	//
	// `invoke` is dispatched by name; identity methods are answered by hand
	// because a proxy's defaults would make two references to the same slot
	// unequal and defeat the caches above.

	private fun function0Proxy(block: () -> Any?): Any? {
		val function0 = function0Class ?: return null
		return Proxy.newProxyInstance(
			function0.classLoader,
			arrayOf(function0),
			InvocationHandler { proxy, method, args ->
				when (method.name) {
					"invoke" -> block()
					"equals" -> proxy === args?.getOrNull(0)
					"hashCode" -> System.identityHashCode(proxy)
					"toString" -> "PXViewModelFactory"
					else -> null
				}
			},
		)
	}

	/**
	 * Wraps a two-argument composable.
	 *
	 * A null `composer` is skipped rather than asserted: Compose never sends
	 * one, but this runs inside SystemUI's composition, so a throw here would
	 * be SystemUI's problem rather than ours.
	 */
	private fun composableSlot(block: (composer: Any, changed: Any?) -> Unit): Any? {
		val function2 = function2Class ?: return null
		return Proxy.newProxyInstance(
			function2.classLoader,
			arrayOf(function2),
			InvocationHandler { proxy, method, args ->
				when (method.name) {
					"invoke" -> {
						val composer = args?.getOrNull(0)
						if (composer != null) block(composer, args?.getOrNull(1))
						kotlinUnit
					}
					"equals" -> proxy === args?.getOrNull(0)
					"hashCode" -> System.identityHashCode(proxy)
					"toString" -> "PXBrightnessSlot"
					else -> null
				}
			},
		)
	}

	private fun composableScopedSlot(block: (scope: Any?, composer: Any?, changed: Any?) -> Unit): Any? {
		val function3 = function3Class ?: return null
		return Proxy.newProxyInstance(
			function3.classLoader,
			arrayOf(function3),
			InvocationHandler { proxy, method, args ->
				when (method.name) {
					"invoke" -> {
						block(args?.getOrNull(0), args?.getOrNull(1), args?.getOrNull(2))
						kotlinUnit
					}
					"equals" -> proxy === args?.getOrNull(0)
					"hashCode" -> System.identityHashCode(proxy)
					"toString" -> "PXBrightnessElement"
					else -> null
				}
			},
		)
	}

	private fun composableContent(block: () -> Unit): Any? {
		val function3 = function3Class ?: return null
		return Proxy.newProxyInstance(
			function3.classLoader,
			arrayOf(function3),
			InvocationHandler { proxy, method, args ->
				when (method.name) {
					"invoke" -> {
						block()
						kotlinUnit
					}
					"equals" -> proxy === args?.getOrNull(0)
					"hashCode" -> System.identityHashCode(proxy)
					"toString" -> "PXBrightnessElement"
					else -> null
				}
			},
		)
	}
	//endregion

	//region composer group helpers

	// Compose renamed `startReplaceableGroup` -> `startReplaceGroup` in 1.6;
	// try both so the pack survives either SystemUI.
	private fun startGroup(composer: Any, key: Int) {
		try {
			callMethod(composer, "startReplaceGroup", key)
		} catch (t: Throwable) {
			try {
				callMethod(composer, "startReplaceableGroup", key)
			} catch (ignored: Throwable) {
			}
		}
	}

	private fun endGroup(composer: Any) {
		try {
			callMethod(composer, "endReplaceGroup")
		} catch (t: Throwable) {
			try {
				callMethod(composer, "endReplaceableGroup")
			} catch (ignored: Throwable) {
			}
		}
	}
	//endregion

	//region slots

	/** A composable that draws nothing, used to hold a position in a row. */
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
	//endregion

	/**
	 * Re-runs the original layout function with [brightness], [tiles] and
	 * [media] substituted in that order.
	 *
	 * The real signature is walked rather than copied, because the parameter
	 * order is not stable: the three composables are found by type and filled
	 * in sequence, the `Boolean` becomes [mediaInRow], and everything else
	 * takes the value the original call would have had.
	 */
	private fun composeOriginalLayout(
		method: Method,
		brightness: Any?,
		tiles: Any?,
		media: Any?,
		mediaInRow: Boolean,
		composer: Any?,
	) {
		val slots = arrayOf(brightness, tiles, media)
		var slotIndex = 0
		val pts = method.parameterTypes
		val args = arrayOfNulls<Any>(pts.size)
		for (i in pts.indices) {
			val type = pts[i]
			val name = type.name
			args[i] = when {
				className("kotlin.jvm.functions", "Function2") == name ->
					if (slotIndex < slots.size) slots[slotIndex++] else null
				type == java.lang.Boolean.TYPE -> mediaInRow
				className("androidx.compose.runtime", "Composer") == name -> composer
				type == java.lang.Integer.TYPE -> 0
				else -> null
			}
		}
		try {
			invokeOriginalMethod(method, null, args)
		} catch (ignored: Throwable) {
		}
	}

	/**
	 * The scene counterpart of [composeOriginalLayout]: rather than rebuild the
	 * argument list from types, it keeps the original arguments and overwrites
	 * only the slots, the media flag, and - when asked - the `Modifier`.
	 */
	private fun invokeScenePanelLayout(
		method: Method,
		originalArgs: Array<Any?>,
		slots: Array<Any?>,
		mediaInRowIndex: Int,
		mediaInRow: Boolean,
		modifier: Any?,
		composer: Any?,
	) {
		val args = originalArgs.copyOf()
		for (i in slots.indices) {
			if (i >= args.size) break
			args[i] = slots[i]
		}
		args[mediaInRowIndex] = mediaInRow

		val pts = method.parameterTypes
		if (modifier != null) {
			val modifierIndex = pts.indexOfFirst {
				className("androidx.compose.ui", "Modifier") == it.name
			}
			if (modifierIndex != -1) args[modifierIndex] = modifier
		}

		val composerIndex = pts.indexOfFirst {
			className("androidx.compose.runtime", "Composer") == it.name
		}
		if (composerIndex != -1) {
			args[composerIndex] = composer
			for (i in composerIndex + 1 until args.size) {
				args[i] = 0
			}
		}

		try {
			invokeOriginalMethod(method, null, args)
		} catch (ignored: Throwable) {
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

	private companion object {
		/** Group keys chosen so they do not collide with SystemUI's own. */
		private const val GROUP_QS_TILES_MEDIA = 0x1C0B5A1D
		private const val GROUP_SCENE_TILES_MEDIA = 0x1C0B5A1E
	}
}
