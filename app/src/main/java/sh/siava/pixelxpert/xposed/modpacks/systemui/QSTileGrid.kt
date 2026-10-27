package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getAdditionalInstanceField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setAdditionalInstanceField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setObjectField
import sh.siava.pixelxpert.xposed.utils.toolkit.ComposeFontUtils
import sh.siava.pixelxpert.xposed.utils.toolkit.FakeIntegerResource

/**
 * Resizes the Quick Settings tile grid to the configured row and column
 * counts, in portrait and landscape separately, plus the quick-QS row count
 * and the tile label typography.
 *
 * The values are injected where each panel generation reads them: the
 * compose-based panels get their constructor's `FakeIntegerResource` argument
 * replaced (columns, quick-QS rows) or a short-lived `Resources.getInteger`
 * hook installed around `TileGrid` (rows, unhooked as soon as the grid is
 * built), and tile labels are scaled once each - guarded by an additional
 * instance field so a label is never scaled twice. Every preference change
 * that alters geometry triggers a dark-mode double toggle, because SystemUI
 * only rebuilds the panel on that configuration blip.
 */
@SystemUIModPack
class QSTileGrid(context: Context) : XposedModPack(context) {

	companion object {
		private const val NOT_SET = 0
		private const val QS_COL_NOT_SET = 1

		private var QSRowQty = NOT_SET
		private var QSColQty = QS_COL_NOT_SET

		private var QSRowQtyL = NOT_SET
		private var QSColQtyL = QS_COL_NOT_SET

		private var QSLabelScaleFactor = 1f
		private var QSSecondaryLabelScaleFactor = 1f

		private var QQSTileRows = NOT_SET
		private var QQSTileRowsL = NOT_SET
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		if (key.isNotEmpty() && key[0] == "VerticalQSTile") {
			SystemUtils.doubleToggleDarkMode()
		}

		QSRowQty = Xprefs.getSliderInt("QSRowQty", NOT_SET)
		QSColQty = Xprefs.getSliderInt("QSColQty", QS_COL_NOT_SET)
		if (QSColQty < QS_COL_NOT_SET) QSColQty = QS_COL_NOT_SET

		QSRowQtyL = Xprefs.getSliderInt("QSRowQtyL", NOT_SET)
		QSColQtyL = Xprefs.getSliderInt("QSColQtyL", QS_COL_NOT_SET)
		if (QSColQtyL < QS_COL_NOT_SET) QSColQtyL = QS_COL_NOT_SET

		QQSTileRows = Xprefs.getSliderInt("QQSRows", NOT_SET)
		QQSTileRowsL = Xprefs.getSliderInt("QQSRowsL", NOT_SET)

		QSLabelScaleFactor = (Xprefs.getSliderFloat("QSLabelScaleFactor", 0f) + 100) / 100f
		QSSecondaryLabelScaleFactor = (Xprefs.getSliderFloat("QSSecondaryLabelScaleFactor", 0f) + 100) / 100f

		if (key.isNotEmpty() &&
			(
				key[0] == "QSRowQty" || key[0] == "QSColQty" || key[0] == "QQSTileQty" || key[0] == "QSRowQtyL" ||
					key[0] == "QSColQtyL" ||
					key[0] == "QQSTileQtyL" ||
					key[0] == "QQSRows"
				)
		) {
			SystemUtils.doubleToggleDarkMode()
		}
	}

	@SuppressLint("DiscouragedApi")
	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val PaginatedGridLayoutClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.qs.panels.ui.compose.PaginatedGridLayout",
		)
		val QSColumnsRepositoryClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.qs.panels.data.repository.QSColumnsRepository",
		)
		val QuickQuickSettingsRowRepositoryClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.qs.panels.data.repository.QuickQuickSettingsRowRepository",
		)
		val QuickQuickSettingsRowInteractorClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.qs.panels.domain.interactor.QuickQuickSettingsRowInteractor",
		)
		val CommonTileKtClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileKt",
		)

		//noinspection unchecked
		val QSRowsHooks = arrayOfNulls<Set<XposedInterface.HookHandle>>(1)

		PaginatedGridLayoutClass
			.before("TileGrid")
			.run { param ->
				QSRowsHooks[0] = ReflectedClass.of(Resources::class.java)
					.before("getInteger")
					.run { param1 ->
						if (param1.args[0]!!.equals(
								mContext.resources.getIdentifier("quick_settings_paginated_grid_num_rows", "integer", mContext.packageName),
							)
						) {
							val isLandscape = mContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

							if (isLandscape && QSRowQtyL != NOT_SET) {
								param1.setResult(QSRowQtyL)
							}
							if (!isLandscape && QSRowQty != NOT_SET) {
								param1.setResult(QSRowQty)
							}
						}
					}
			}

		CommonTileKtClass
			.before("TileLabel")
			.run { param ->
				val spanStyle: Any? = getObjectField(param.args[2], "spanStyle")

				if (java.lang.Boolean.TRUE.equals(getAdditionalInstanceField(param.args[2], "Scaled"))) {
					return@run
				}

				setAdditionalInstanceField(param.args[2], "Scaled", true)

				val longFontSize = getObjectField(spanStyle, "fontSize") as Long

				setObjectField(
					spanStyle,
					"fontSize",
					ComposeFontUtils.scaleTileFont(mContext, longFontSize, QSLabelScaleFactor, QSSecondaryLabelScaleFactor),
				)
			}

		PaginatedGridLayoutClass
			.after("TileGrid")
			.run { param ->
				try {
					QSRowsHooks[0]!!.forEach { it.unhook() }
				} finally {
					QSRowsHooks[0] = null
				}
			}

		QSColumnsRepositoryClass
			.beforeConstruction()
			.run { param ->
				param.args[0] = object : FakeIntegerResource(mContext) {
					override fun getInteger(id: Int): Int {
						if (mContext.resources.getResourceName(id).endsWith("quick_settings_infinite_grid_num_columns")) {
							val isLandscape = mContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
							if (isLandscape && QSColQtyL != QS_COL_NOT_SET) {
								return QSColQtyL
							}
							if (!isLandscape && QSColQty != QS_COL_NOT_SET) {
								return QSColQty
							}
						}

						return mContext.resources.getInteger(id)
					}
				}
			}

		val fakeQuickQSRowsResource = object : FakeIntegerResource(mContext) {
			override fun getInteger(id: Int): Int {
				try {
					if (mContext.resources.getResourceName(id).endsWith("quick_qs_paginated_grid_num_rows")) {
						val isLandscape = mContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
						if (isLandscape && QQSTileRowsL != NOT_SET) {
							return QQSTileRowsL
						} else if (!isLandscape && QQSTileRows != NOT_SET) {
							return QQSTileRows
						}
					}
				} catch (_: Throwable) {
				}
				return mContext.resources.getInteger(id)
			}
		}

		// Targets builds where the repository constructor is declared
		QuickQuickSettingsRowRepositoryClass
			.beforeConstruction()
			.run { param ->
				if (param.args.isNotEmpty() && param.args[0] is Resources) {
					param.args[0] = fakeQuickQSRowsResource
				}
			}

		// Targets builds where the repository constructor was inlined into Dagger by R8
		QuickQuickSettingsRowInteractorClass
			.beforeConstruction()
			.run { param ->
				val repo = param.args.getOrNull(0)
				if (repo != null) {
					setObjectField(repo, "resources", fakeQuickQSRowsResource)
				}
			}
	}
}
