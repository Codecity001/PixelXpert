package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getAdditionalInstanceField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setAdditionalInstanceField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.toolkit.ComposeFontUtils
import sh.siava.pixelxpert.xposed.utils.toolkit.FakeIntegerResource

@SystemUIModPack
class QSTileGrid(context: Context) : XposedModPack(context) {

    companion object {
        private const val NOT_SET = 0
        private const val QS_COL_NOT_SET = 1

        private var qsRowQty = NOT_SET
        private var qsColQty = QS_COL_NOT_SET

        private var qsRowQtyL = NOT_SET
        private var qsColQtyL = QS_COL_NOT_SET

        private var qsLabelScaleFactor = 1f
        private var qsSecondaryLabelScaleFactor = 1f

        private var qqsTileRows = NOT_SET
        private var qqsTileRowsL = NOT_SET
    }

    override fun onPreferenceUpdated(vararg key: String) {
        if (key.isNotEmpty() && key[0] == "VerticalQSTile") {
            SystemUtils.doubleToggleDarkMode()
        }

        qsRowQty = Xprefs.getSliderInt("QSRowQty", NOT_SET)
        qsColQty = Xprefs.getSliderInt("QSColQty", QS_COL_NOT_SET)
        if (qsColQty < QS_COL_NOT_SET) qsColQty = QS_COL_NOT_SET

        qsRowQtyL = Xprefs.getSliderInt("QSRowQtyL", NOT_SET)
        qsColQtyL = Xprefs.getSliderInt("QSColQtyL", QS_COL_NOT_SET)
        if (qsColQtyL < QS_COL_NOT_SET) qsColQtyL = QS_COL_NOT_SET

        qqsTileRows = Xprefs.getSliderInt("QQSRows", NOT_SET)
        qqsTileRowsL = Xprefs.getSliderInt("QQSRowsL", NOT_SET)

        qsLabelScaleFactor = (Xprefs.getSliderFloat("QSLabelScaleFactor", 0f) + 100) / 100f
        qsSecondaryLabelScaleFactor = (Xprefs.getSliderFloat("QSSecondaryLabelScaleFactor", 0f) + 100) / 100f

        if (key.isNotEmpty() && listOf(
                "QSRowQty", "QSColQty", "QQSTileQty", "QSRowQtyL", "QSColQtyL", "QQSTileQtyL", "QQSRows"
            ).contains(key[0])
        ) {
            SystemUtils.doubleToggleDarkMode()
        }
    }

    @SuppressLint("DiscouragedApi")
    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val paginatedGridLayoutClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.panels.ui.compose.PaginatedGridLayout")
            val qsColumnsRepositoryClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.panels.data.repository.QSColumnsRepository")
            val quickQuickSettingsRowRepositoryClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.panels.data.repository.QuickQuickSettingsRowRepository")
            val quickQuickSettingsRowInteractorClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.panels.domain.interactor.QuickQuickSettingsRowInteractor")
            val commonTileKtClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileKt")

            var qsRowsHooks: Set<XposedInterface.HookHandle>? = null

            paginatedGridLayoutClass
                .before("TileGrid")
                .run {
                    qsRowsHooks = ReflectedClass.of(Resources::class.java)
                        .before("getInteger")
                        .run { hookParam1 ->
                            val targetId = mContext.resources.getIdentifier("quick_settings_paginated_grid_num_rows", "integer", mContext.packageName)
                            if (hookParam1.args.getOrNull(0) == targetId) {
                                val isLandscape = mContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                                if (isLandscape && qsRowQtyL != NOT_SET) {
                                    hookParam1.result = qsRowQtyL
                                } else if (!isLandscape && qsRowQty != NOT_SET) {
                                    hookParam1.result = qsRowQty
                                }
                            }
                        }
                }

            commonTileKtClass
                .before("TileLabel")
                .run { hookParam ->
                    val arg2 = hookParam.args.getOrNull(2) ?: return@run
                    val spanStyle = getObjectField(arg2, "spanStyle") ?: return@run

                    if (getAdditionalInstanceField(arg2, "Scaled") == true) {
                        return@run
                    }

                    setAdditionalInstanceField(arg2, "Scaled", true)

                    val longFontSize = getObjectField(spanStyle, "fontSize") as? Long ?: return@run
                    setObjectField(
                        spanStyle,
                        "fontSize",
                        ComposeFontUtils.scaleTileFont(mContext, longFontSize, qsLabelScaleFactor, qsSecondaryLabelScaleFactor)
                    )
                }

            paginatedGridLayoutClass
                .after("TileGrid")
                .run {
                    try {
                        qsRowsHooks?.forEach { it.unhook() }
                    } finally {
                        qsRowsHooks = null
                    }
                }

            qsColumnsRepositoryClass
                .beforeConstruction()
                .run { hookParam ->
                    hookParam.args[0] = object : FakeIntegerResource(mContext) {
                        override fun getInteger(id: Int): Int {
                            if (mContext.resources.getResourceName(id).endsWith("quick_settings_infinite_grid_num_columns")) {
                                val isLandscape = mContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                                if (isLandscape && qsColQtyL != QS_COL_NOT_SET) {
                                    return qsColQtyL
                                }
                                if (!isLandscape && qsColQty != QS_COL_NOT_SET) {
                                    return qsColQty
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
                            if (isLandscape && qqsTileRowsL != NOT_SET) {
                                return qqsTileRowsL
                            } else if (!isLandscape && qqsTileRows != NOT_SET) {
                                return qqsTileRows
                            }
                        }
                    } catch (_: Throwable) {
                    }
                    return mContext.resources.getInteger(id)
                }
            }

            // Targets builds where the repository constructor is declared
            quickQuickSettingsRowRepositoryClass
                .beforeConstruction()
                .run { hookParam ->
                    if (hookParam.args.isNotEmpty() && hookParam.args[0] is Resources) {
                        hookParam.args[0] = fakeQuickQSRowsResource
                    }
                }

            // Targets builds where the repository constructor was inlined into Dagger by R8
            quickQuickSettingsRowInteractorClass
                .beforeConstruction()
                .run { hookParam ->
                    val repo = hookParam.args.getOrNull(0)
                    if (repo != null) {
                        setObjectField(repo, "resources", fakeQuickQSRowsResource)
                    }
                }
        } catch (ignored: Throwable) {
        }
    }
}
