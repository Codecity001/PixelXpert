package sh.siava.pixelxpert.ui.prefs.node

/**
 * Node types making up a [sh.siava.pixelxpert.ui.prefs.PrefScreen].
 *
 * There is deliberately no per-screen composable: a single renderer in
 * `ui/prefs/PrefRenderer.kt` switches over this hierarchy, which is why 21
 * screens / 272 nodes cost a handful of files rather than 21 bespoke layouts.
 */
sealed interface PrefNode {
	/**
	 * Ignored nodes are still rendered but excluded from the search index.
	 * Only categories, illustrations and home tiles carry it, so search
	 * returns the leaf preference rather than a section header or a
	 * navigation tile.
	 */
	val ignore: Boolean get() = false

	/**
	 * `android:icon`, 0 when the row declares none.
	 *
	 * The field lives on PrefNode rather than on [MainTile] alone so any
	 * screen can carry one without a model change.
	 */
	val icon: Int get() = 0

	/**
	 * `android:title` as a `@string/` reference; 0 when the row declares none.
	 *
	 * Structural nodes - [Illustration], [SearchBar] - inherit the 0 and so
	 * render untitled.
	 */
	val title: Int get() = 0

	/**
	 * `android:title` written as a plain literal rather than a `@string/`;
	 * a resource id cannot carry that text, so the literal is kept here.
	 * It wins over [title] whenever a node has both.
	 */
	val titleText: String? get() = null
}

/**
 * Persists a value under [key] in `<applicationId>_preferences`.
 *
 * The hooks read the same file through `RemotePreferences`, so [key] and
 * [default] are a cross-process contract: they must match the keys and
 * defaults the hooks already read from the file on device - this is the
 * main reason the screens are generated rather than retyped.
 */
sealed interface ValuePref : PrefNode {
	val key: String
	override val title: Int
	val summary: Int
	val default: String?

	/** Key of another preference that must be true for this one to be enabled. */
	val dependency: String?
}

// ---------------------------------------------------------------- structural

/** Decorative Lottie header. */
data class Illustration(val key: String, val lottieRawRes: Int, override val ignore: Boolean = false) : PrefNode

/** In-screen search bar; renders the query surface for the screen's keys. */
data class SearchBar(val key: String, override val ignore: Boolean = false) : PrefNode

/**
 * Grouping header; [title] of 0 renders an untitled gap.
 *
 * The Google design paints the icons of a group in it, every row of a category has one colour
 */
data class Category(
	override val title: Int = 0,
	override val titleText: String? = null,
	val children: List<PrefNode>,
	val accent: Long = 0,
	override val ignore: Boolean = false,
) : PrefNode

// ------------------------------------------------------------- navigation

/** Row that navigates to another screen. [route] is a Navigation3 route id. */
data class NavRow(
	val key: String,
	override val title: Int = 0,
	override val titleText: String? = null,
	val summary: Int = 0,
	val route: String,
	override val icon: Int = 0,
	override val ignore: Boolean = false,
) : PrefNode

/** Plain clickable row with no destination. */
data class ActionRow(
	val key: String,
	override val title: Int = 0,
	override val titleText: String? = null,
	val summary: Int = 0,
	override val icon: Int = 0,
	override val ignore: Boolean = false,
) : PrefNode

/** Large tile on the module's home screen. */
data class MainTile(
	val key: String,
	override val title: Int = 0,
	override val titleText: String? = null,
	val summary: Int = 0,
	val route: String? = null,
	override val icon: Int = 0,
	override val ignore: Boolean = false,
) : PrefNode

// ------------------------------------------------------------------ values

data class SwitchPref(
	override val key: String,
	override val title: Int = 0,
	override val titleText: String? = null,
	override val summary: Int = 0,
	override val default: String? = null,
	override val dependency: String? = null,
	val summaryOn: Int = 0,
	val summaryOff: Int = 0,
	/** Visually emphasised switch. */
	val prominent: Boolean = false,
	/** Master switch for the whole feature. */
	val mainSwitch: Boolean = false,
	override val icon: Int = 0,
	override val ignore: Boolean = false,
) : ValuePref

/**
 * @property step       0 means continuous selection
 * @property valueCount 1 = single value, 2 = a low/high range
 * @property continuous commit on every frame
 */
data class SliderPref(
	override val key: String,
	override val title: Int = 0,
	override val titleText: String? = null,
	override val summary: Int = 0,
	override val default: String? = null,
	override val dependency: String? = null,
	val min: Float = 0f,
	val max: Float = 100f,
	val step: Float = 1f,
	val valueCount: Int = 1,
	val continuous: Boolean = false,
	override val icon: Int = 0,
	override val ignore: Boolean = false,
) : ValuePref

/** Single or multi choice list; entries/values are @ArrayRes. */
data class ListPref(
	override val key: String,
	override val title: Int = 0,
	override val titleText: String? = null,
	override val summary: Int = 0,
	override val default: String? = null,
	override val dependency: String? = null,
	val entries: Int = 0,
	val entryValues: Int = 0,
	val multiSelect: Boolean = false,
	override val icon: Int = 0,
	override val ignore: Boolean = false,
) : ValuePref

data class TextPref(
	override val key: String,
	override val title: Int = 0,
	override val titleText: String? = null,
	override val summary: Int = 0,
	override val default: String? = null,
	override val dependency: String? = null,
	/**
	 * These text dialogs scroll a format manual above the input instead of
	 * showing a bare field.
	 */
	val manual: Boolean = false,
	override val icon: Int = 0,
	override val ignore: Boolean = false,
) : ValuePref

data class ColorPref(
	override val key: String,
	override val title: Int = 0,
	override val titleText: String? = null,
	override val summary: Int = 0,
	override val default: String? = null,
	override val dependency: String? = null,
	override val icon: Int = 0,
	override val ignore: Boolean = false,
) : ValuePref

/** Value is minutes-since-midnight. */
data class TimePref(
	override val key: String,
	override val title: Int = 0,
	override val titleText: String? = null,
	override val summary: Int = 0,
	override val default: String? = null,
	override val dependency: String? = null,
	override val icon: Int = 0,
	override val ignore: Boolean = false,
) : ValuePref
