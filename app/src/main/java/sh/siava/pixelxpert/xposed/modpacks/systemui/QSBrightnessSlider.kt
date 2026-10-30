package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Pair
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.findClassIfExists
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getStaticObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.invokeOriginalMethod
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

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
 * The three preferences:
 * - `qs_brightness_slider_bottom` - draw the slider below the tiles instead of
 *   above them.
 * - `qqs_brightness_slider` - also draw it in collapsed (quick-quick) QS, and
 *   share one instance with the expanded panel via a scene `ElementKey` so the
 *   two do not fight over the same view model.
 * - `QSAutoBrightnessToggle` - an adaptive brightness button at the end of the
 *   slider. The track's own `Modifier` is padded at the call site to make room for it
 *   see [hookBrightnessSlider].
 *
 * [hookSceneContainer] also hooks `QuickSettingsShadeOverlayKt.QuickSettingsLayoutContent`,
 * a third way into the panel layout. Without it the slider stays above the
 * tiles on builds that draw through the overlay.
 */
@SystemUIModPack
class QSBrightnessSlider(context: Context) : XposedModPack(context) {

	private var brightnessBelowTiles = false
	private var brightnessInQqs = false
	private var autoBrightnessToggle = false
	private var mediaOnTop = false
	private var qsLayoutOrder: List<String> = listOf("brightness", "qs", "media")

	//region resolved compose APIs - all null until resolveComposeApis() finds them
	private var function0Class: Class<*>? = null
	private var function1Class: Class<*>? = null
	private var function2Class: Class<*>? = null
	private var function3Class: Class<*>? = null
	private var kotlinUnit: Any? = null
	private var modifierCompanion: Any? = null
	private var sharedElementKey: Any? = null
	private var brightnessContainerMethod: Method? = null
	private var brightnessSliderMethod: Method? = null
	private var sliderModifierIndex = -1
	private var sliderComposerIndex = -1
	private var androidViewMethod: Method? = null
	private var paddingMethod: Method? = null
	private var paddingModifierMethod: Method? = null
	private var sizeMethod: Method? = null
	private var alignMethod: Method? = null
	private var modifierThenMethod: Method? = null
	private var modifierFoldInMethod: Method? = null
	private var boxScopeInstance: Any? = null
	private var centerEndAlignment: Any? = null
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

	// Padded slider modifiers
	// the slider recomposes on every frame of a brightness animation
	private val paddedSliderModifiers = WeakHashMap<Any, Any>()
	private val sliderModifierOrigins = WeakHashMap<Any, Any>()

	// Whether a chain is mirrored - the hook is handed the same back on frames of an animation.
	private val sliderMirrorStates = WeakHashMap<Any, Boolean>()

	// The lambdas `AndroidView` needs are built once
	private var androidViewFactorySlot: Any? = null
	private var androidViewUpdateSlot: Any? = null

	private fun className(pkg: String, cls: String): String = "$pkg.$cls"

	override fun onPreferenceUpdated(vararg key: String?) {
		val rawOrder = if (Xprefs.contains("qs_layout_order")) {
			Xprefs.getString("qs_layout_order", "brightness,qs,media") ?: "brightness,qs,media"
		} else {
			val bottom = Xprefs.getBoolean("qs_brightness_slider_bottom", false)
			val mTop = Xprefs.getBoolean("qs_media_player_top", false)
			when {
				mTop && bottom -> "media,qs,brightness"
				mTop && !bottom -> "media,brightness,qs"
				!mTop && bottom -> "qs,brightness,media"
				else -> "brightness,qs,media"
			}
		}
		val parsed = rawOrder.split(",").map { it.trim() }.filter { it.isNotEmpty() }
		val valid = listOf("brightness", "qs", "media")
		val ordered = parsed.filter { it in valid }.toMutableList()
		valid.forEach { if (it !in ordered) ordered.add(it) }
		qsLayoutOrder = ordered

		val brightnessIndex = qsLayoutOrder.indexOf("brightness")
		val qsIndex = qsLayoutOrder.indexOf("qs")
		val mediaIndex = qsLayoutOrder.indexOf("media")

		brightnessBelowTiles = brightnessIndex > qsIndex
		mediaOnTop = mediaIndex < qsIndex
		brightnessInQqs = Xprefs.getBoolean("qqs_brightness_slider", false)
		autoBrightnessToggle = Xprefs.getBoolean("QSAutoBrightnessToggle", false)

		qqsTilesSlots.clear()
		shadeQqsSlots.clear()
		arrangedFirstSlots.clear()
		mediaRowSlots.clear()
		sceneMediaRowSlots.clear()
		qsBrightnessSlots.clear()

		if (key.any { it == "qs_layout_order" || it == "qqs_brightness_slider" }) {
			detachMediaHostViews()
			SystemUtils.doubleToggleDarkMode()
		}
	}

	private fun detachMediaHostViews() {
		try {
			val fragment = qsFragment
			val mediaHosts = mutableListOf<Any>()
			if (fragment != null) {
				getObjectFieldSilently(fragment, "qsMediaHost")?.let { mediaHosts.add(it) }
				getObjectFieldSilently(fragment, "qqsMediaHost")?.let { mediaHosts.add(it) }
				val vm = getObjectFieldSilently(fragment, "viewModel")
				if (vm != null) {
					getObjectFieldSilently(vm, "qsMediaHost")?.let { mediaHosts.add(it) }
					getObjectFieldSilently(vm, "qqsMediaHost")?.let { mediaHosts.add(it) }
				}
			}
			if (mediaHosts.isEmpty()) return

			val detachAction = Runnable {
				for (mh in mediaHosts) {
					try {
						val hostView = getObjectFieldSilently(mh, "hostView") as? View ?: continue
						val parent = hostView.parent as? ViewGroup ?: continue
						parent.removeView(hostView)
					} catch (_: Throwable) {
					}
				}
			}

			if (Looper.myLooper() == Looper.getMainLooper()) {
				detachAction.run()
			} else {
				Handler(Looper.getMainLooper()).post(detachAction)
			}
		} catch (_: Throwable) {
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		resolveComposeApis()
		hookQsFragmentCompose()
		hookSceneContainer()
		hookBrightnessSlider()
	}

	private fun applyLayoutOrder(slots: Array<Any?>, brightness: Any, tiles: Any, media: Any) {
		val map = mapOf("brightness" to brightness, "qs" to tiles, "media" to media)
		val order = if (qsLayoutOrder.size == 3) qsLayoutOrder else listOf("brightness", "qs", "media")
		for (i in 0 until 3) {
			val k = order.getOrNull(i) ?: "brightness"
			slots[i] = map[k] ?: brightness
		}
		arrangedFirstSlots.add(slots[0]!!)
	}

	private fun List<*>.withMediaFirst(): List<*>? {
		val media = firstOrNull { (it as? Enum<*>)?.name == QS_SHADE_MEDIA_COMPONENT }
			?: return null
		if (firstOrNull() === media) return null
		return listOf(media) + filter { it !== media }
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
			if (!brightnessBelowTiles && !brightnessInQqs && !mediaOnTop) return@run
			if (param.args.size < 3) return@run

			val brightness = param.args[0] ?: return@run
			if (brightness in arrangedFirstSlots) return@run

			val tiles = param.args[1] ?: return@run

			val sharedBrightness = if (brightnessInQqs) sharedQsBrightnessSlot(brightness) else null
			val brightnessSlot: Any = sharedBrightness ?: brightness

			// The media row is the only Boolean argument; its position is not
			// stable across releases, so locate it rather than assume it.
			val mediaInRowIndex = param.args.indexOfFirst { it is Boolean }
			val mediaInRow: Boolean? = if (mediaInRowIndex != -1) param.args[mediaInRowIndex] as? Boolean else null
			val media = param.args.getOrNull(2)

			if (mediaInRow == false && media != null) {
				applyLayoutOrder(param.args, brightnessSlot, tiles, media)
				return@run
			}

			if (!brightnessBelowTiles) {
				param.args[0] = brightnessSlot
				return@run
			}
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
			if (!brightnessInQqs && !mediaOnTop) return@run

			val mediaInRow = param.args.firstOrNull { it is Boolean } as? Boolean
			if (mediaInRow != false) return@run

			val tiles = param.args.getOrNull(0) ?: return@run
			if (qqsTilesSlots.containsValue(tiles)) return@run

			val tilesSlot = if (brightnessInQqs) {
				qqsTilesSlots[tiles] ?: composableSlot { composer, changed ->
					if (brightnessBelowTiles) {
						callMethod(tiles, "invoke", composer, changed)
						composeQqsBrightness(composer)
					} else {
						composeQqsBrightness(composer)
						callMethod(tiles, "invoke", composer, changed)
					}
				}?.also { qqsTilesSlots[tiles] = it } ?: return@run
			} else {
				tiles
			}
			param.args[0] = tilesSlot

			if (!mediaOnTop) return@run

			val parameterTypes = (param.method as Method).parameterTypes
			val slotIndices = parameterTypes.indices
				.filter { parameterTypes[it].name == className("kotlin.jvm.functions", "Function2") }
			if (slotIndices.size != 2) return@run

			val media = param.args.getOrNull(slotIndices[1]) ?: return@run
			param.args[slotIndices[0]] = media
			param.args[slotIndices[1]] = tilesSlot
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
					if (!brightnessBelowTiles && !mediaOnTop) return@run
					if (param.args.size < 3) return@run

					val brightness = param.args[0] ?: return@run
					if (brightness in arrangedFirstSlots) return@run
					val tiles = param.args[1] ?: return@run

					val method = param.method as Method
					val pts = method.parameterTypes

					val mediaInRowIndex = pts.indexOfFirst { it == java.lang.Boolean.TYPE }
					val mediaInRow: Boolean? = if (mediaInRowIndex != -1) param.args[mediaInRowIndex] as? Boolean else null
					val mediaVisible = pts.indices
						.filter { pts[it] == java.lang.Boolean.TYPE }
						.getOrNull(1)
						?.let { param.args.getOrNull(it) as? Boolean }
					val media = param.args.getOrNull(2)

					if (mediaInRow == false && mediaVisible == true && media != null) {
						applyLayoutOrder(param.args, brightness, tiles, media)
						return@run
					}

					if (!brightnessBelowTiles) return@run
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
								method,
								originalArgs,
								arrayOf(emptySlot, tiles, media),
								mediaInRowIndex,
								true,
								modifierCompanion,
								rowComposer,
							)
						}
						if (tilesAndMediaRow == null) return@run
						sceneMediaRowSlots[tiles] = Pair(media, tilesAndMediaRow)
						arrangedFirstSlots.add(tilesAndMediaRow)
					}

					startGroup(composer!!, GROUP_SCENE_TILES_MEDIA)
					try {
						invokeScenePanelLayout(
							method,
							originalArgs,
							arrayOf(tilesAndMediaRow, brightness, emptySlot),
							mediaInRowIndex,
							false,
							null,
							composer,
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

		hookShadeTransitionBrightnessFade()

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
				if ((!brightnessInQqs && !mediaOnTop) || isSplitShade()) return@run

				val mediaInRow = param.args.firstOrNull { it is Boolean } as? Boolean
				if (mediaInRow != false) return@run

				val qqs = param.args.getOrNull(0) ?: return@run
				if (shadeQqsSlots.containsValue(qqs)) return@run

				val qqsSlot = if (brightnessInQqs) {
					shadeQqsSlots[qqs] ?: composableSlot { composer, changed ->
						if (brightnessBelowTiles) {
							callMethod(qqs, "invoke", composer, changed)
							composeShadeBrightness(composer)
						} else {
							composeShadeBrightness(composer)
							callMethod(qqs, "invoke", composer, changed)
						}
					}?.also { shadeQqsSlots[qqs] = it } ?: return@run
				} else {
					qqs
				}
				param.args[0] = qqsSlot

				if (!mediaOnTop) return@run

				val orderIndex = (param.method as Method).parameterTypes
					.indexOfFirst { it == List::class.java }
				val order = param.args.getOrNull(orderIndex) as? List<*>
				if (order != null) {
					order.withMediaFirst()?.let { param.args[orderIndex] = it }
				} else {
					val media = param.args.getOrNull(1) ?: return@run
					param.args[0] = media
					param.args[1] = qqsSlot
				}
			}
		}
	}

	private fun hookShadeTransitionBrightnessFade() {
		val builderClass = ReflectedClass.ofIfPossible(TRANSITION_BUILDER_CLASS)
		if (builderClass.clazz == null) return

		val fadeMethod = builderClass.clazz!!.declaredMethods.firstOrNull { method ->
			method.name.startsWith("fade") &&
				Modifier.isStatic(method.modifiers) &&
				method.parameterTypes.size == 2 &&
				method.parameterTypes[0] == builderClass.clazz &&
				method.parameterTypes[1].name == ELEMENT_MATCHER_CLASS
		}?.apply { isAccessible = true } ?: return

		val qsElementsClass = ReflectedClass.ofIfPossible(QS_ELEMENTS_CLASS)
		val qsContentKey = if (qsElementsClass.clazz !=
			null
		) {
			getStaticObjectField(qsElementsClass.clazz, "QuickSettingsContent")
		} else {
			null
		}
		val gridAnchorKey = if (qsElementsClass.clazz !=
			null
		) {
			getStaticObjectField(qsElementsClass.clazz, "GridAnchor")
		} else {
			null
		}

		if (qsContentKey != null && gridAnchorKey != null) {
			builderClass
				.before("anchoredTranslate")
				.run { param ->
					if (!brightnessBelowTiles) return@run
					if (param.args.getOrNull(0) !== qsContentKey) return@run
					if (param.args.getOrNull(1) !== gridAnchorKey) return@run

					param.setResult(null)
				}
		}

		ReflectedClass.ofIfPossible(TO_SINGLE_SHADE_TRANSITION_CLASS)
			.after("toSingleShadeTransition")
			.run { param ->
				if (!brightnessInQqs) return@run

				val builder = param.args.firstOrNull() ?: return@run
				val key = sceneBrightnessElementKey ?: sharedElementKey ?: return@run
				try {
					fadeMethod.invoke(null, builder, key)
				} catch (ignored: Throwable) {
				}
			}
	}

	private fun sceneSliderModifier(): Any? {
		val empty = modifierCompanion
		val resources = mContext.resources
		val id = resources.getIdentifier(QS_BRIGHTNESS_MARGIN_DIMEN, "dimen", Constants.SYSTEM_UI_PACKAGE)
		if (id == 0) return empty

		val verticalDp = resources.getDimension(id) / resources.displayMetrics.density
		return try {
			paddingModifierMethod?.invoke(null, empty, 0f, verticalDp)
				?: paddingMethod?.invoke(null, empty, 0f, verticalDp, 0f, verticalDp)
				?: empty
		} catch (_: Throwable) {
			empty
		}
	}

	//region adaptive brightness toggle

	/**
	 * Puts an adaptive brightness button at the end of every brightness slider.
	 *
	 * The call site is `BrightnessSlider` - the track.
	 * Hooking the track covers SystemUI's slider and the
	 * moved slider with a single hook - the button's
	 * `align(CenterEnd)` is resolved by the container's `Box`.
	 *
	 * A mirrored slider - one SystemUI is repainting above the panel for the
	 * "brightness mirror" - skips the button.
	 */
	private fun hookBrightnessSlider() {
		val slider = brightnessSliderMethod ?: return

		// The toggle composes through Compose interop
		val missing = ArrayList<String>()
		if (sliderModifierIndex == -1 || sliderComposerIndex == -1) missing.add("slider arguments")
		if (androidViewMethod == null) missing.add("AndroidView")
		if (boxScopeInstance == null || alignMethod == null) missing.add("align")
		if (centerEndAlignment == null) missing.add("CenterEnd")
		if (sizeMethod == null) missing.add("size")
		if (paddingMethod == null) missing.add("padding")
		if (modifierThenMethod == null) missing.add("then")
		if (modifierFoldInMethod == null || function2Class == null) missing.add("foldIn")
		if (function1Class == null) missing.add("Function1")
		if (modifierCompanion == null) missing.add("Modifier")
		if (missing.isNotEmpty()) {
			log("QSBrightnessSlider: adaptive brightness toggle missing ${missing.joinToString(", ")}")
		}

		val brightnessSliderKt = ReflectedClass.ofIfPossible(
			className("com.android.systemui.brightness.ui.compose", "BrightnessSliderKt"),
		)
		if (brightnessSliderKt.clazz == null) return

		brightnessSliderKt.before(slider).run { param ->
			if (!autoBrightnessToggle) return@run
			if (sliderModifierIndex == -1) return@run

			val incoming = param.args.getOrNull(sliderModifierIndex) ?: return@run
			val known = sliderModifierOrigins[incoming]
			val original = known ?: incoming
			val padded = paddedSliderModifiers[original] ?: paddedSliderModifier(original) ?: return@run
			paddedSliderModifiers[original] = padded
			sliderModifierOrigins[padded] = original
			param.args[sliderModifierIndex] = padded
		}

		brightnessSliderKt.after(slider).run { param ->
			if (!autoBrightnessToggle) return@run

			val sliderModifier = param.args.getOrNull(sliderModifierIndex)
			if (sliderModifier != null && sliderIsMirroring(sliderModifier)) return@run

			val composer = param.args.getOrNull(sliderComposerIndex) ?: return@run

			startGroup(composer, GROUP_QS_AUTO_BRIGHTNESS)
			try {
				composeAutoBrightnessToggle(composer)
			} catch (t: Throwable) {
				log("QSBrightnessSlider: adaptive brightness toggle could not be composed", t)
			} finally {
				endGroup(composer)
			}
		}
	}

	/**
	 * `Modifier.padding(end = toggle + gap).then(original)`.
	 *
	 * The padding is prepended - wraps everything the slider draws
	 * keeps the animated frame behind the track from running under the button.
	 */
	private fun paddedSliderModifier(original: Any): Any? {
		val padding = paddingMethod ?: return null
		val then = modifierThenMethod ?: return null
		val modifier = modifierCompanion ?: return null

		return try {
			val end = AUTO_BRIGHTNESS_TOGGLE_DP + AUTO_BRIGHTNESS_GAP_DP
			val padded = padding.invoke(null, modifier, 0f, 0f, end, 0f)
			then.invoke(padded, original)
		} catch (t: Throwable) {
			null
		}
	}

	/** Compose [AutoBrightnessButton] as a attachment of slider - at end. */
	private fun composeAutoBrightnessToggle(composer: Any) {
		val androidView = androidViewMethod ?: return
		val scope = boxScopeInstance ?: return
		val align = alignMethod ?: return
		val centerEnd = centerEndAlignment ?: return
		val size = sizeMethod ?: return
		val empty = modifierCompanion ?: return
		val factory = androidViewFactory() ?: return
		val update = androidViewUpdate() ?: return

		try {
			val sized = size.invoke(null, empty, AUTO_BRIGHTNESS_TOGGLE_DP)
			val aligned = align.invoke(scope, sized, centerEnd)
			androidView.invoke(null, factory, aligned, update, composer, 0, 0)
		} catch (t: Throwable) {
		}
	}

	/**
	 * Whether the slider is drawing into the mirror overlay.
	 */
	private fun sliderIsMirroring(modifier: Any): Boolean {
		sliderMirrorStates[modifier]?.let { return it }

		val mirroring = modifierElementNames(modifier).any { name ->
			val simple = name.substringAfterLast('.')
			name.startsWith(MIRROR_ELEMENT_PACKAGE) &&
				(
					simple.startsWith("DrawInContainer") || simple.startsWith("DrawInOverlay") ||
						simple == "ContainerElement"
					)
		}
		sliderMirrorStates[modifier] = mirroring
		return mirroring
	}

	/** The class names of every element in a `Modifier` chain, in order. */
	private fun modifierElementNames(modifier: Any): List<String> {
		val foldIn = modifierFoldInMethod ?: return emptyList()
		val names = ArrayList<String>()
		val operation = function2Proxy { _, element ->
			if (element != null) {
				names.add(element.javaClass.name)
			}
			names
		} ?: return emptyList()

		return try {
			foldIn.invoke(modifier, names, operation)
			names
		} catch (t: Throwable) {
			emptyList()
		}
	}

	private fun androidViewFactory(): Any? {
		androidViewFactorySlot?.let { return it }
		val function1 = function1Class ?: return null

		val factory = Proxy.newProxyInstance(
			function1.classLoader,
			arrayOf(function1),
			InvocationHandler { proxy, method, args ->
				when (method.name) {
					"invoke" -> (args?.getOrNull(0) as? Context)?.let { AutoBrightnessButton(it) }
					"equals" -> proxy === args?.getOrNull(0)
					"hashCode" -> System.identityHashCode(proxy)
					"toString" -> "PXAutoBrightnessFactory"
					else -> null
				}
			},
		)

		androidViewFactorySlot = factory
		return factory
	}

	/**
	 * `AndroidView`'s update lambda
	 * Compose hands it the button on every recomposition - where the toggle re-reads the setting. It is not
	 * the only path - the button also observes it itself.
	 */
	private fun androidViewUpdate(): Any? {
		androidViewUpdateSlot?.let { return it }
		val function1 = function1Class ?: return null

		val update = Proxy.newProxyInstance(
			function1.classLoader,
			arrayOf(function1),
			InvocationHandler { proxy, method, args ->
				when (method.name) {
					"invoke" -> {
						(args?.getOrNull(0) as? AutoBrightnessButton)?.sync(animate = true)
						kotlinUnit
					}

					"equals" -> proxy === args?.getOrNull(0)

					"hashCode" -> System.identityHashCode(proxy)

					"toString" -> "PXAutoBrightnessUpdate"

					else -> null
				}
			},
		)

		androidViewUpdateSlot = update
		return update
	}

	// The Kotlin APIs that are not composables,
	private fun function2Proxy(block: (first: Any?, second: Any?) -> Any?): Any? {
		val function2 = function2Class ?: return null
		return Proxy.newProxyInstance(
			function2.classLoader,
			arrayOf(function2),
			InvocationHandler { proxy, method, args ->
				when (method.name) {
					"invoke" -> block(args?.getOrNull(0), args?.getOrNull(1))
					"equals" -> proxy === args?.getOrNull(0)
					"hashCode" -> System.identityHashCode(proxy)
					"toString" -> "PXAutoBrightnessFold"
					else -> null
				}
			},
		)
	}
	//endregion

	private fun composeShadeBrightness(composer: Any) {
		val containerViewModel = rememberShadeContainerViewModel(composer) ?: return
		val brightnessViewModel = getObjectFieldSilently(containerViewModel, "brightnessSliderViewModel") ?: return

		val scope = shadeScope
		val key = sceneBrightnessElementKey ?: sharedElementKey

		if (scope != null && key != null) {
			composeElement(scope, key, composer) { innerComposer ->
				composeBrightnessContainer(innerComposer, brightnessViewModel, sceneSliderModifier())
			}
		} else {
			composeBrightnessContainer(composer, brightnessViewModel, sceneSliderModifier())
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
			function1Class = findClassIfExists(className("kotlin.jvm.functions", "Function1"), cl)
		} catch (t: Throwable) {
		}

		try {
			val qsElementsClass = findClassIfExists(
				className("com.android.systemui.qs.shared.ui", "QuickSettings\$Elements"),
				cl,
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
				className("com.android.compose.animation.scene", "ElementKey"),
				cl,
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
				className("com.android.systemui.brightness.ui.compose", "BrightnessSliderKt"),
				cl,
			)
			brightnessContainerMethod = brightnessSliderKtClass?.declaredMethods
				?.firstOrNull { it.name == "BrightnessSliderContainer" }
				?.also { it.isAccessible = true }

			// The track inside the container's `Box`
			brightnessSliderMethod = brightnessSliderKtClass?.declaredMethods
				?.firstOrNull {
					it.name == "BrightnessSlider" &&
						it.parameterTypes.any { type -> className("androidx.compose.ui", "Modifier") == type.name } &&
						it.parameterTypes.any { type -> className("androidx.compose.runtime", "Composer") == type.name }
				}
				?.also { it.isAccessible = true }

			brightnessSliderMethod?.let { slider ->
				sliderModifierIndex = slider.parameterTypes.indexOfFirst {
					className("androidx.compose.ui", "Modifier") == it.name
				}
				sliderComposerIndex = slider.parameterTypes.indexOfFirst {
					className("androidx.compose.runtime", "Composer") == it.name
				}
			}
		} catch (t: Throwable) {
		}

		try {
			val androidViewKt = findClassIfExists(
				className("androidx.compose.ui.viewinterop", "AndroidView_androidKt"),
				cl,
			)
			androidViewMethod = androidViewKt?.declaredMethods
				?.firstOrNull {
					it.name == "AndroidView" && it.parameterTypes.size == 6 &&
						className("kotlin.jvm.functions", "Function1") == it.parameterTypes[0].name &&
						className("androidx.compose.ui", "Modifier") == it.parameterTypes[1].name
				}
				?.also { it.isAccessible = true }
		} catch (t: Throwable) {
		}

		try {
			val boxScopeClass = findClassIfExists(
				className("androidx.compose.foundation.layout", "BoxScopeInstance"),
				cl,
			)
			boxScopeInstance = getStaticObjectField(boxScopeClass, "INSTANCE")
			alignMethod = boxScopeClass?.declaredMethods?.firstOrNull {
				it.name == "align" && it.parameterTypes.size == 2
			}
		} catch (t: Throwable) {
		}

		try {
			val alignmentCompanionClass = findClassIfExists(
				className("androidx.compose.ui", "Alignment\$Companion"),
				cl,
			)
			centerEndAlignment = getStaticObjectField(alignmentCompanionClass, "CenterEnd")
		} catch (t: Throwable) {
		}

		try {
			val sizeKtClass = findClassIfExists(
				className("androidx.compose.foundation.layout", "SizeKt"),
				cl,
			)
			sizeMethod = sizeKtClass?.declaredMethods?.firstOrNull {
				it.name.startsWith("size-") && it.parameterTypes.size == 2 &&
					className("androidx.compose.ui", "Modifier") == it.parameterTypes[0].name &&
					it.parameterTypes[1] == java.lang.Float.TYPE
			}
		} catch (t: Throwable) {
		}

		try {
			val paddingKtClass = findClassIfExists(
				className("androidx.compose.foundation.layout", "PaddingKt"),
				cl,
			)
			paddingMethod = paddingKtClass?.declaredMethods?.firstOrNull {
				it.name.startsWith("padding-") && it.parameterTypes.size == 5 &&
					className("androidx.compose.ui", "Modifier") == it.parameterTypes[0].name &&
					it.parameterTypes.drop(1).all { type -> type == java.lang.Float.TYPE }
			}
			paddingModifierMethod = paddingKtClass?.declaredMethods?.firstOrNull {
				it.name.startsWith("padding-") && it.parameterTypes.size == 3 &&
					className("androidx.compose.ui", "Modifier") == it.parameterTypes[0].name &&
					it.parameterTypes.drop(1).all { type -> type == java.lang.Float.TYPE }
			}
		} catch (t: Throwable) {
		}

		try {
			val modifierClass = findClassIfExists(className("androidx.compose.ui", "Modifier"), cl)
			modifierThenMethod = modifierClass?.declaredMethods?.firstOrNull {
				it.name == "then" && it.parameterTypes.size == 1
			}
			modifierFoldInMethod = modifierClass?.declaredMethods?.firstOrNull {
				it.name == "foldIn" && it.parameterTypes.size == 2
			}
		} catch (t: Throwable) {
		}

		try {
			val containerColorsClass = findClassIfExists(
				className("com.android.systemui.brightness.ui.compose", "ContainerColors"),
				cl,
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
			composeElement(scope, key, composer) { innerComposer ->
				callMethod(brightness, "invoke", innerComposer, changed)
			}
		} ?: return null

		qsBrightnessSlots[brightness] = slot
		return slot
	}

	private fun composeQqsBrightness(composer: Any) {
		val scope = qqsScope
		val key = sharedElementKey
		if (scope != null && key != null) {
			composeElement(scope, key, composer) { innerComposer -> composeQsFragmentBrightness(innerComposer) }
		} else {
			composeQsFragmentBrightness(composer)
		}
	}

	/**
	 * Wraps [content] in a scene element, falling back to running it inline
	 * when the scene API is unavailable - the slider is still worth drawing
	 * even if it cannot animate as a shared element.
	 */
	private fun composeElement(scope: Any, key: Any, composer: Any, content: (composer: Any) -> Unit) {
		val elementMethod = scope.javaClass.methods
			.firstOrNull { it.name == "Element" && it.parameterTypes.size == 5 }
		val elementContent = composableContent(composer, content)

		if (elementMethod == null || elementContent == null) {
			content(composer)
			return
		}

		try {
			elementMethod.invoke(scope, key, modifierCompanion, elementContent, composer, 0)
		} catch (ignored: Throwable) {
			content(composer)
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
	private fun composeBrightnessContainer(composer: Any, viewModel: Any?, modifier: Any? = modifierCompanion) {
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

					className("androidx.compose.ui", "Modifier") == type.name -> args[index] = modifier ?: modifierCompanion

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

	/**
	 * `Element` composes content with a composer of its own so that is the one to draw into.
	 * [fallback] is for when there is none.
	 */
	private fun composableContent(fallback: Any, block: (composer: Any) -> Unit): Any? {
		val function3 = function3Class ?: return null
		return Proxy.newProxyInstance(
			function3.classLoader,
			arrayOf(function3),
			InvocationHandler { proxy, method, args ->
				when (method.name) {
					"invoke" -> {
						block(args?.getOrNull(1) ?: fallback)
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
		private const val QS_SHADE_MEDIA_COMPONENT = "MEDIA"
		private const val QS_BRIGHTNESS_MARGIN_DIMEN = "qs_brightness_margin_top"
		private const val TRANSITION_BUILDER_CLASS =
			"com.android.compose.animation.scene.TransitionBuilderImpl"
		private const val ELEMENT_MATCHER_CLASS =
			"com.android.compose.animation.scene.ElementMatcher"
		private const val QS_ELEMENTS_CLASS =
			"com.android.systemui.qs.shared.ui.QuickSettings\$Elements"
		private const val TO_SINGLE_SHADE_TRANSITION_CLASS =
			"com.android.systemui.scene.ui.composable.transitions.ToSingleShadeTransitionKt"

		/** Group keys chosen so they do not collide with SystemUI's own. */
		private const val GROUP_QS_TILES_MEDIA = 0x1C0B5A1D
		private const val GROUP_SCENE_TILES_MEDIA = 0x1C0B5A1E
		private const val GROUP_QS_AUTO_BRIGHTNESS = 0x1C0B5A1F

		/**
		 * The adaptive brightness button - gap between it and the end of the slider.
		 * Both are `Dp` values
		 */
		private const val AUTO_BRIGHTNESS_TOGGLE_DP = 44f
		private const val AUTO_BRIGHTNESS_GAP_DP = 8f

		/** Where `drawInContainer`/`drawInOverlay` put their modifier elements. */
		private const val MIRROR_ELEMENT_PACKAGE = "com.android.compose.ui.graphics"
	}
}
