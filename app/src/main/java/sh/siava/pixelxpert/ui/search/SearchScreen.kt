package sh.siava.pixelxpert.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.data.PrefStore

/**
 * Search over every preference. Shows a query field, then either the recent
 * history or up to ten scored results rendered as title / summary /
 * breadcrumb; selecting one pushes the destination screen and hands it the
 * row's `searchKey`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
	store: PrefStore,
	onOpenResult: (SearchEntry) -> Unit,
) {
	val context = LocalContext.current
	val index = remember(context) { buildSearchIndex(context) }
	val history = remember(context) { SearchHistory(context) }

	var query by rememberSaveable { mutableStateOf("") }
	var terms by remember { mutableStateOf(history.load()) }

	// The dependency filter reads live preferences, so re-run on any write.
	val revision by store.revision.collectAsState()
	val results = remember(query, index, revision) { searchPreferences(index, query, store) }

	// The outer scaffold owns the app bar (title + back) for every route, so
	// this screen contributes content only - the same contract the preference
	// screens follow. Giving Search its own top bar would double the heading.
	Column(modifier = Modifier.fillMaxSize()) {
		OutlinedTextField(
			value = query,
			onValueChange = { query = it },
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 8.dp),
			label = { Text(stringResource(R.string.searchpreference_search)) },
			singleLine = true,
			trailingIcon = {
				if (query.isNotEmpty()) {
					IconButton(onClick = { query = "" }) {
						Icon(
							imageVector = Icons.Filled.Close,
							contentDescription = stringResource(R.string.searchpreference_clear),
						)
					}
				}
			},
		)

		if (query.isEmpty()) {
			HistoryList(
				terms = terms,
				onPick = { query = it },
				onClear = {
					history.clear()
					terms = emptyList()
				},
			)
		} else {
			ResultList(
				results = results,
				onOpen = { entry ->
					terms = history.push(query)
					onOpenResult(entry)
				},
			)
		}
	}
}

@Composable
private fun HistoryList(
	terms: List<String>,
	onPick: (String) -> Unit,
	onClear: () -> Unit,
) {
	if (terms.isEmpty()) return

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Spacer(Modifier.weight(1f))
		TextButton(onClick = onClear) {
			Text(stringResource(R.string.searchpreference_clear_history))
		}
	}

	terms.forEachIndexed { i, term ->
		if (i > 0) HorizontalDivider()
		ListItem(
			modifier = Modifier.clickable { onPick(term) },
			leadingContent = {
				Icon(Icons.Filled.History, contentDescription = null)
			},
			headlineContent = { Text(term) },
		)
	}
}

@Composable
private fun ResultList(
	results: List<SearchEntry>,
	onOpen: (SearchEntry) -> Unit,
) {
	if (results.isEmpty()) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(32.dp),
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = stringResource(R.string.searchpreference_no_results),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				style = MaterialTheme.typography.bodyLarge,
			)
		}
		return
	}

	// No explicit item key: a result set is rebuilt per query, so positional
	// identity is correct and a derived key could collide across screens.
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(bottom = 32.dp),
	) {
		items(results) { entry ->
			ListItem(
				modifier = Modifier.clickable { onOpen(entry) },
				headlineContent = { Text(entry.title) },
				supportingContent = {
					val summary = entry.summary
					Column {
						// Unformatted summaries are hidden rather than shown as a
					// bare "%s".
						if (!summary.isNullOrBlank() && !summary.contains("%s")) {
							Text(summary, modifier = Modifier.alpha(0.8f))
						}
						if (entry.breadcrumbs.isNotEmpty()) {
							Text(entry.breadcrumbs, modifier = Modifier.alpha(0.6f))
						}
					}
				},
			)
			HorizontalDivider()
		}
	}
}
