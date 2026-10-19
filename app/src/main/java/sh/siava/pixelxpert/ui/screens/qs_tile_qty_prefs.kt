package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val qs_tile_qty_prefs: PrefScreen = PrefScreen(
	key = "qs_tile_qty_prefs",
	nodes = listOf(
			Illustration(key = "qs_tile_illustration", lottieRawRes = R.raw.qs_tile, ignore = true),
			Category(
	title = R.string.qs_tile_qty_portrait,
	ignore = true,
	children = listOf(
				SliderPref(key = "QQSRows", title = R.string.qqs_rows_number, default = "2", min = 1f, max = 4f),
				SliderPref(key = "QSRowQty", title = R.string.qs_rows_number, default = "4", min = 0f, max = 8f),
				SliderPref(key = "QSColQty", title = R.string.qs_cols_number, default = "4", min = 2f, max = 8f),
	),
			),
			Category(
	title = R.string.qs_tile_qty_landscape,
	ignore = true,
	children = listOf(
				SliderPref(key = "QQSRowsL", title = R.string.qqs_rows_number, default = "1", min = 1f, max = 4f),
				SliderPref(key = "QSRowQtyL", title = R.string.qs_rows_number, default = "2", min = 0f, max = 8f),
				SliderPref(key = "QSColQtyL", title = R.string.qs_cols_number, default = "8", min = 4f, max = 12f),
	),
			),
	),
)
