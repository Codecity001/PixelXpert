package sh.siava.pixelxpert.ui.prefs.node

sealed class PrefNode {
    abstract val key: String
    abstract val title: String
    abstract val summary: String?

    data class Header(
        override val key: String,
        override val title: String,
        override val summary: String? = null,
        val icon: Int? = null,
    ) : PrefNode()

    data class Category(
        override val key: String,
        override val title: String,
        override val summary: String? = null,
    ) : PrefNode()

    data class SwitchPref(
        override val key: String,
        override val title: String,
        override val summary: String? = null,
        val defaultValue: Boolean = false,
        val icon: Int? = null,
        val requiresSystemUIRestart: Boolean = false,
        val requiresDeviceRestart: Boolean = false,
        val enabled: Boolean = true,
        val dependencyKey: String? = null,
    ) : PrefNode()

    data class SliderPref(
        override val key: String,
        override val title: String,
        override val summary: String? = null,
        val min: Float,
        val max: Float,
        val step: Float = 1f,
        val defaultValue: Float,
        val valueFormatter: ((Float) -> String)? = null,
        val requiresSystemUIRestart: Boolean = false,
        val requiresDeviceRestart: Boolean = false,
        val enabled: Boolean = true,
        val dependencyKey: String? = null,
    ) : PrefNode()

    data class ListPref(
        override val key: String,
        override val title: String,
        override val summary: String? = null,
        val entries: List<String>,
        val entryValues: List<String>,
        val defaultValue: String,
        val requiresSystemUIRestart: Boolean = false,
        val requiresDeviceRestart: Boolean = false,
        val enabled: Boolean = true,
        val dependencyKey: String? = null,
    ) : PrefNode()

    data class ColorPref(
        override val key: String,
        override val title: String,
        override val summary: String? = null,
        val defaultValue: Int = 0,
        val requiresSystemUIRestart: Boolean = false,
        val requiresDeviceRestart: Boolean = false,
        val enabled: Boolean = true,
        val dependencyKey: String? = null,
    ) : PrefNode()

    data class ActionPref(
        override val key: String,
        override val title: String,
        override val summary: String? = null,
        val icon: Int? = null,
        val onClick: () -> Unit,
    ) : PrefNode()
}
