package sh.siava.pixelxpert.ui.search

import android.content.Context
import android.content.SharedPreferences

/**
 * Recent search terms, persisted in the `preferenceSearch` file.
 *
 * Storage layout is an integer `history_size` plus `history_0`..`history_{n-1}`,
 * newest first, capped at [MAX_HISTORY]. The key names must not change: a user
 * upgrading in place finds their recent searches still there.
 */
class SearchHistory(context: Context) {

	private val prefs: SharedPreferences =
		context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

	/** Newest first. */
	fun load(): List<String> {
		val size = prefs.getInt("history_size", 0)
		return (0 until size).mapNotNull { prefs.getString("history_$it", null) }
	}

	/** Inserts [term] at the front, dropping any duplicate and the oldest entry. */
	fun push(term: String): List<String> {
		val trimmed = term.trim()
		if (trimmed.isEmpty()) return load()

		val existing = load().toMutableList()
		existing.removeAll { it.equals(trimmed, ignoreCase = true) }
		existing.add(0, trimmed)
		while (existing.size > MAX_HISTORY) existing.removeAt(existing.lastIndex)

		// Rewrite wholesale so a shrunken list cannot leave stale history_n keys.
		val editor = prefs.edit().clear()
		editor.putInt("history_size", existing.size)
		existing.forEachIndexed { i, t -> editor.putString("history_$i", t) }
		editor.apply()

		return existing
	}

	fun clear() {
		prefs.edit().clear().apply()
	}

	companion object {
		const val MAX_HISTORY = 5

		const val FILE_NAME = "preferenceSearch"
	}
}
