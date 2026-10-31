package hk.uwu.reareye.ui.config

import androidx.annotation.IntRange
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import hk.uwu.reareye.ui.components.config.AppListConfigInput
import hk.uwu.reareye.ui.components.config.BooleanConfigInput
import hk.uwu.reareye.ui.components.config.EnumSingleSelectConfigInput
import hk.uwu.reareye.ui.components.config.DensityDpiConfigInput
import hk.uwu.reareye.ui.components.config.FloatSliderConfigInput
import hk.uwu.reareye.ui.components.config.ManagerConfigInput
import hk.uwu.reareye.ui.components.config.MaskMultiSelectConfigInput
import hk.uwu.reareye.ui.components.config.RearStoreApiConfigInput
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

sealed class ConfigType {
    @Composable
    abstract fun RenderInput(
        item: ConfigItem,
        prefsManager: PrefsManager,
        onOpenAppList: (ConfigItem) -> Unit,
        onOpenManager: (ConfigItem) -> Unit,
        onPreferenceChanged: (ConfigItem) -> Unit,
    )

    open val defaultStringSet: Set<String> = emptySet()

    data class MaskOption(
        @param:StringRes val titleRes: Int,
        val maskValue: Int,
    )

    data class EnumOption(
        @param:StringRes val titleRes: Int,
        val value: Int,
    )

    data class BooleanVal(val defaultValue: Boolean = false) : ConfigType() {
        @Composable
        override fun RenderInput(
            item: ConfigItem,
            prefsManager: PrefsManager,
            onOpenAppList: (ConfigItem) -> Unit,
            onOpenManager: (ConfigItem) -> Unit,
            onPreferenceChanged: (ConfigItem) -> Unit,
        ) {
            BooleanConfigInput(
                item = item,
                defaultValue = defaultValue,
                prefsManager = prefsManager,
                onPreferenceChanged = onPreferenceChanged,
            )
        }
    }

    data class AppList(val defaultValues: Set<String> = emptySet()) : ConfigType() {
        override val defaultStringSet: Set<String>
            get() = defaultValues

        @Composable
        override fun RenderInput(
            item: ConfigItem,
            prefsManager: PrefsManager,
            onOpenAppList: (ConfigItem) -> Unit,
            onOpenManager: (ConfigItem) -> Unit,
            onPreferenceChanged: (ConfigItem) -> Unit,
        ) {
            AppListConfigInput(
                item = item,
                defaultValues = defaultValues,
                prefsManager = prefsManager,
                onClick = { onOpenAppList(item) },
            )
        }
    }

    data class MaskMultiSelect(
        val defaultValue: Int,
        val options: List<MaskOption>,
    ) : ConfigType() {
        @Composable
        override fun RenderInput(
            item: ConfigItem,
            prefsManager: PrefsManager,
            onOpenAppList: (ConfigItem) -> Unit,
            onOpenManager: (ConfigItem) -> Unit,
            onPreferenceChanged: (ConfigItem) -> Unit,
        ) {
            MaskMultiSelectConfigInput(
                item = item,
                defaultValue = defaultValue,
                options = options,
                prefsManager = prefsManager,
                onPreferenceChanged = onPreferenceChanged,
            )
        }
    }

    data class DensityDpi(val defaultValue: Int = 300) : ConfigType() {
        @Composable
        override fun RenderInput(
            item: ConfigItem,
            prefsManager: PrefsManager,
            onOpenAppList: (ConfigItem) -> Unit,
            onOpenManager: (ConfigItem) -> Unit,
            onPreferenceChanged: (ConfigItem) -> Unit,
        ) {
            DensityDpiConfigInput(item, defaultValue, prefsManager, onPreferenceChanged)
        }
    }

    data class EnumSingleSelect(
        val defaultValue: Int,
        val options: List<EnumOption>,
    ) : ConfigType() {
        @Composable
        override fun RenderInput(
            item: ConfigItem,
            prefsManager: PrefsManager,
            onOpenAppList: (ConfigItem) -> Unit,
            onOpenManager: (ConfigItem) -> Unit,
            onPreferenceChanged: (ConfigItem) -> Unit,
        ) {
            EnumSingleSelectConfigInput(
                item = item,
                defaultValue = defaultValue,
                options = options,
                prefsManager = prefsManager,
                onPreferenceChanged = onPreferenceChanged,
            )
        }
    }

    data class RearStoreApi(
        val defaultProviderValue: Int,
        val customDomainKey: String,
    ) : ConfigType() {
        @Composable
        override fun RenderInput(
            item: ConfigItem,
            prefsManager: PrefsManager,
            onOpenAppList: (ConfigItem) -> Unit,
            onOpenManager: (ConfigItem) -> Unit,
            onPreferenceChanged: (ConfigItem) -> Unit,
        ) {
            RearStoreApiConfigInput(
                item = item,
                apiConfig = this,
                prefsManager = prefsManager,
                onPreferenceChanged = onPreferenceChanged,
            )
        }
    }

    data class FloatSlider(
        val defaultValue: Float,
        val minValue: Float,
        val maxValue: Float,
        @param:IntRange(from = 0) val steps: Int = 0,
        @param:IntRange(from = 0) val decimalPlaces: Int = 2,
        val valueFormatter: ((Float) -> String)? = null,
    ) : ConfigType() {
        init {
            require(minValue < maxValue) { "minValue should be less than maxValue" }
            require(defaultValue in minValue..maxValue) { "defaultValue should be within [minValue, maxValue]" }
            require(steps >= 0) { "steps should be >= 0" }
            require(decimalPlaces >= 0) { "decimalPlaces should be >= 0" }
        }

        @Composable
        override fun RenderInput(
            item: ConfigItem,
            prefsManager: PrefsManager,
            onOpenAppList: (ConfigItem) -> Unit,
            onOpenManager: (ConfigItem) -> Unit,
            onPreferenceChanged: (ConfigItem) -> Unit,
        ) {
            FloatSliderConfigInput(
                item = item,
                sliderConfig = this,
                prefsManager = prefsManager,
                onPreferenceChanged = onPreferenceChanged,
            )
        }

        fun normalizeValue(value: Float): Float {
            val clampedValue = value.coerceIn(minValue, maxValue)
            val safePrecision = decimalPlaces.coerceIn(0, 6)
            if (safePrecision == 0) return clampedValue.roundToInt().toFloat()

            val factor = 10.0.pow(safePrecision.toDouble())
            return ((clampedValue * factor).roundToInt() / factor).toFloat()
        }

        fun formatValue(value: Float): String {
            val normalizedValue = normalizeValue(value)
            valueFormatter?.let { return it(normalizedValue) }

            val safePrecision = decimalPlaces.coerceIn(0, 6)
            return "%.${safePrecision}f".format(Locale.getDefault(), normalizedValue)
        }
    }

    enum class ManagerType {
        REAR_WALLPAPER,
        BUSINESS,
        SCENE_ROUTE,
        CARD,
        BUSINESS_EXTRA,
        BOUNDS,
        REAR_APP_DISPLAY,
        LYRICS,
    }

    data class Manager(val managerType: ManagerType) : ConfigType() {
        @Composable
        override fun RenderInput(
            item: ConfigItem,
            prefsManager: PrefsManager,
            onOpenAppList: (ConfigItem) -> Unit,
            onOpenManager: (ConfigItem) -> Unit,
            onPreferenceChanged: (ConfigItem) -> Unit,
        ) {
            ManagerConfigInput(
                item = item,
                onClick = { onOpenManager(item) }
            )
        }
    }

    // Additional types like StringVal, IntVal can be added here.
}

sealed interface ConfigNode {
    val key: String?

    @get:StringRes
    val titleRes: Int?

    @get:StringRes
    val descriptionRes: Int?
}

data class ConfigItem(
    override val key: String,
    @param:StringRes override val titleRes: Int,
    @param:StringRes override val descriptionRes: Int? = null,
    val type: ConfigType
) : ConfigNode

data class ConfigCategory(
    override val key: String? = null,
    @param:StringRes override val titleRes: Int,
    @param:StringRes override val descriptionRes: Int? = null,
    val icon: ConfigCategoryIcon? = null,
    val children: List<ConfigNode> = emptyList()
) : ConfigNode

data class ConfigGroup(
    override val key: String? = null,
    @param:StringRes override val titleRes: Int? = null,
    @param:StringRes override val descriptionRes: Int? = null,
    val children: List<ConfigNode> = emptyList()
) : ConfigNode

sealed interface ConfigCategoryIcon {
    data class Package(val packageName: String) : ConfigCategoryIcon
    data class Compose(val imageVector: ImageVector) : ConfigCategoryIcon
}
