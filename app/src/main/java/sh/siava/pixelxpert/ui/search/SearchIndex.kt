package sh.siava.pixelxpert.ui.search

import android.content.Context
import org.apache.commons.text.similarity.FuzzyScore
import sh.siava.pixelxpert.data.PrefStore
import sh.siava.pixelxpert.ui.prefs.node.ActionRow
import sh.siava.pixelxpert.ui.prefs.node.Category
import sh.siava.pixelxpert.ui.prefs.node.ListPref
import sh.siava.pixelxpert.ui.prefs.node.MainTile
import sh.siava.pixelxpert.ui.prefs.node.NavRow
import sh.siava.pixelxpert.ui.prefs.node.PrefNode
import sh.siava.pixelxpert.ui.prefs.node.ValuePref
import sh.siava.pixelxpert.ui.screens.preferenceScreens
import sh.siava.pixelxpert.ui.screens.searchScreenTitles
import java.util.Locale

/**
 * Field separator used when joining title, summary, entries and breadcrumbs
 * into the string that gets matched and scored. U+00F8 (o-slash) cannot
 * appear in a real preference string, so a query can never accidentally match
 * across a field boundary.
 */
private const val SEP = "ø"

/** Upper bound on scored results returned per query. */
const val SEARCH_MAX_RESULTS = 10

/** Joins breadcrumb levels: "Screen > Category > Sub-category". */
private const val CRUMB_SEP = " > "

/**
 * One row of the preference search index.
 *
 * Strings are resolved to their display form once, when the index is built,
 * because the index is static for the process lifetime whereas `@StringRes`
 * would otherwise be resolved on every keystroke. [key] is kept so a tapped
 * result can hand the destination the `searchKey` it scrolls to and
 * highlights.
 */
data class SearchEntry(
	/** Preference XML basename of the screen holding this row. */
	val screenKey: String,
	/** `@StringRes` title of that screen - the breadcrumb root. */
	val screenTitle: Int,
	/** `android:key`, used to scroll/highlight in the destination. */
	val key: String,
	val title: String,
	val summary: String?,
	/**
	 * `app:entries` expanded and joined with "," so every entry is searchable.
	 */
	val entries: String?,
	/** "Screen > Category > Sub-category", built during the index walk. */
	val breadcrumbs: String,
	/** `app:dependency`; resolved against [PrefStore] at query time. */
	val dependency: String?,
) {
	/**
	 * The haystack: title, summary, entries and breadcrumbs joined with [SEP].
	 */
	val info: String = StringBuilder().apply {
		if (title.isNotEmpty()) append(SEP).append(title)
		if (!summary.isNullOrEmpty()) append(SEP).append(summary)
		if (!entries.isNullOrEmpty()) append(SEP).append(entries)
		if (breadcrumbs.isNotEmpty()) append(SEP).append(breadcrumbs)
	}.toString()
}

/** Fields lifted off a node before resolution; null means "not searchable". */
private data class Extracted(
	val key: String,
	val title: Int,
	val summary: Int,
	val entries: Int,
	val dependency: String?,
)

private fun extract(node: PrefNode): Extracted? = when (node) {
	is ValuePref -> Extracted(
		key = node.key,
		title = node.title,
		summary = node.summary,
		entries = (node as? ListPref)?.entries ?: 0,
		dependency = node.dependency,
	)

	is NavRow -> Extracted(node.key, node.title, node.summary, 0, null)
	is ActionRow -> Extracted(node.key, node.title, node.summary, 0, null)
	is MainTile -> Extracted(node.key, node.title, node.summary, 0, null)

	// Illustration and SearchBar carry neither title nor summary, so they are
	// never searchable.
	else -> null
}

/**
 * Builds the index over 20 of the 21 screens - `header_preferences` itself is
 * the launcher and is not searchable, expressed by its absence from
 * `searchScreenTitles`.
 *
 * Two rules apply:
 *
 *  - a node marked `search:ignore` is skipped as a result but, when it is a
 *    [Category], still contributes its title as a breadcrumb;
 *  - a row with neither title nor summary is not searchable.
 */
fun buildSearchIndex(context: Context): List<SearchEntry> {
	val out = ArrayList<SearchEntry>(180)

	for ((screenKey, screen) in preferenceScreens) {
		val screenTitleRes = searchScreenTitles[screenKey] ?: continue
		val screenTitle = context.getString(screenTitleRes)

		fun walk(nodes: List<PrefNode>, crumbs: List<String>) {
			for (node in nodes) {
				if (node is Category) {
					val crumb = if (node.title != 0) context.getString(node.title) else ""
					walk(node.children, crumbs + crumb)
					continue
				}
				if (node.ignore) continue

				val fields = extract(node) ?: continue
				if (fields.title == 0 && fields.summary == 0) continue

				out += SearchEntry(
					screenKey = screenKey,
					screenTitle = screenTitleRes,
					key = fields.key,
					title = if (fields.title != 0) context.getString(fields.title) else "",
					summary = if (fields.summary != 0) context.getString(fields.summary) else null,
					entries = runCatching {
						if (fields.entries != 0) {
							context.resources.getStringArray(fields.entries).joinToString(",")
						} else null
					}.getOrNull(),
					breadcrumbs = crumbs.filter(String::isNotEmpty).joinToString(CRUMB_SEP),
					dependency = fields.dependency,
				)
			}
		}

		walk(screen.nodes, listOf(screenTitle))
	}
	return out
}

/**
 * Visibility rule: a row is shown when its own `app:dependency` boolean is
 * set, or when it declares none.
 *
 * The model carries no separate visibility table, so the dependency recorded
 * in the XML is the only signal available: a row whose enabling switch is off
 * is not worth surfacing.
 */
private fun isVisible(entry: SearchEntry, store: PrefStore): Boolean {
	val dep = entry.dependency ?: return true
	if (dep.isEmpty()) return true
	return runCatching { store.getBoolean(dep, false) }.getOrDefault(true)
}

/**
 * Runs one query: `contains()` for membership, `FuzzyScore` for ordering.
 *
 * Membership is deliberately a plain case-insensitive substring test - a row
 * either contains the term or it is not a result, and fuzzy matching does not
 * admit extra rows. `FuzzyScore` (commons-text) only decides *rank*,
 * normalised by `(len + 1) * 3 - 2`.
 */
fun searchPreferences(
	index: List<SearchEntry>,
	query: String,
	store: PrefStore,
): List<SearchEntry> {
	if (query.isEmpty()) return emptyList()

	val locale = Locale.getDefault()
	val needle = query.lowercase(locale)
	val maxScore = ((query.length + 1) * 3 - 2).toFloat()
	if (maxScore <= 0f) return emptyList()
	val scorer = FuzzyScore(locale)

	return index.asSequence()
		.filter { isVisible(it, store) }
		.filter { it.info.lowercase(locale).contains(needle) }
		.map { entry ->
			val raw: Int? = runCatching { scorer.fuzzyScore(entry.info, SEP + query) }.getOrNull()
			entry to ((raw ?: 0).toFloat() / maxScore)
		}
		.sortedByDescending { it.second }
		.take(SEARCH_MAX_RESULTS)
		.map { it.first }
		.toList()
}
