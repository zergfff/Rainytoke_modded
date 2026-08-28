package com.rainy.token.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.local.appSettingsStore
import com.rainy.token.data.local.SecureStorage
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.domain.model.CredentialStatus
import com.rainy.token.ui.widget.OpenCodeGoWidgetProvider
import com.rainy.token.ui.widget.WidgetElement
import com.rainy.token.ui.widget.WidgetElementStyle
import com.rainy.token.ui.widget.WidgetPeriodicWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiStateData(
    val themeKey: String = "strawberry",
    val appFontScale: Float = 1.0f,
    val widgetFontScale: Float = 1.0f,
    val widgetRefreshIntervalMin: Int = 15,
    val widgetSelectedServices: Set<String> = setOf("opencode_go", "commandcode_go", "codex", "ollama"),
    val weatherEnabled: Boolean = false,
    val qweatherKey: String = "",
    val qweatherHost: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val secureStorage: SecureStorage,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val application = context.applicationContext as android.app.Application
    private val settings = application.appSettingsStore

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * QWeather API Key 存在加密的 SecureStorage 里，不是 DataStore 的 qweatherKey。
     * 之前 UI 读的是 DataStore 那个从未被写入的字段，所以输入框永远显示空——
     * 看着像"填不进去"，实际是写进去了但读不回来。
     */
    private val _qweatherKey = MutableStateFlow("")

    private val baseSettings: StateFlow<SettingsUiStateData> = kotlinx.coroutines.flow.combine(
        settings.themeKey,
        settings.appFontScale,
        settings.widgetFontScale,
        settings.widgetRefreshIntervalMin,
        settings.widgetSelectedServices,
        settings.weatherEnabled,
        settings.qweatherHost
    ) { values: Array<Any> ->
        SettingsUiStateData(
            themeKey = values[0] as String,
            appFontScale = values[1] as Float,
            widgetFontScale = values[2] as Float,
            widgetRefreshIntervalMin = values[3] as Int,
            widgetSelectedServices = @Suppress("UNCHECKED_CAST") values[4] as Set<String>,
            weatherEnabled = values[5] as Boolean,
            qweatherHost = values[6] as String
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiStateData())

    val settingsState: StateFlow<SettingsUiStateData> =
        kotlinx.coroutines.flow.combine(baseSettings, _qweatherKey) { base, key ->
            base.copy(qweatherKey = key)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiStateData())

    /**
     * 元素 → 样式。
     * 声明必须在 init 之前：Kotlin 按声明顺序初始化属性，若放在类末尾，
     * init 里的 loadWidgetStyles() 会拿到 null 而崩溃。
     */
    private val _widgetStyles = MutableStateFlow<Map<WidgetElement, WidgetElementStyle>>(emptyMap())
    val widgetStyles: StateFlow<Map<WidgetElement, WidgetElementStyle>> = _widgetStyles.asStateFlow()

    /** 背景：颜色（null=默认）与透明度 0~255 */
    private val _widgetBackground = MutableStateFlow<Pair<Int?, Int>>(null to 255)
    val widgetBackground: StateFlow<Pair<Int?, Int>> = _widgetBackground.asStateFlow()

    init {
        // 进入设置页时把已保存的 Key 读出来填进输入框
        viewModelScope.launch {
            _qweatherKey.value = secureStorage.getQWeatherKey().orEmpty()
        }
        loadWidgetStyles()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val statuses = credentialRepository.statusForAll()
            _uiState.update { it.copy(loading = false, credentialStatuses = statuses) }
        }
    }

    fun setTheme(key: String) {
        viewModelScope.launch { settings.setThemeKey(key) }
    }

    fun setAppFontScale(v: Float) {
        viewModelScope.launch { settings.setAppFontScale(v) }
    }

    /** 小组件字体改变后需要立即重绘，否则要等下一个刷新周期才生效 */
    fun setWidgetFontScale(v: Float) {
        viewModelScope.launch {
            settings.setWidgetFontScale(v)
            refreshWidgets()
        }
    }

    /** 触发小组件重绘（勾选服务、字体等变更后立即生效） */
    private fun refreshWidgets() {
        try {
            val ids = android.appwidget.AppWidgetManager.getInstance(context)
                .getAppWidgetIds(android.content.ComponentName(context, OpenCodeGoWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                OpenCodeGoWidgetProvider().onUpdate(
                    context,
                    android.appwidget.AppWidgetManager.getInstance(context),
                    ids
                )
            }
        } catch (_: Exception) { }
    }

    fun setWidgetRefreshInterval(min: Int) {
        viewModelScope.launch {
            settings.setWidgetRefreshIntervalMin(min)
            WidgetPeriodicWorker.schedule(application, min)
        }
    }

    fun setWidgetSelectedServices(set: Set<String>) {
        viewModelScope.launch {
            settings.setWidgetSelectedServices(set)
            // 立即重绘小组件，否则取消勾选的服务要等下个刷新周期才消失
            refreshWidgets()
        }
    }

    /**
     * 天气开关：由 API Key 是否填写自动推导，不再由用户手动切换。
     * 写入后立即触发一次刷新，让天气尽快出现。
     */
    fun setWeatherEnabled(v: Boolean) {
        viewModelScope.launch {
            settings.setWeatherEnabled(v)
            if (v) WidgetPeriodicWorker.requestImmediate(application)
        }
    }

    fun setQWeatherKey(key: String) {
        viewModelScope.launch {
            secureStorage.setQWeatherKey(key)
            // 立刻回写到输入框，否则清空后 UI 仍显示旧值
            _qweatherKey.value = key
            // 填了 Key 就自动开启天气，无需额外开关
            val enabled = key.isNotBlank()
            settings.setWeatherEnabled(enabled)
            if (enabled) {
                WidgetPeriodicWorker.requestImmediate(application)
                // 立即重绘，让已缓存的天气（如果有）马上出现在小组件上
                refreshWidgets()
            }
        }
    }

    fun setQWeatherHost(host: String) {
        viewModelScope.launch {
            settings.setQweatherHost(host)
            if (host.isNotBlank()) {
                WidgetPeriodicWorker.requestImmediate(application)
                refreshWidgets()
            }
        }
    }

    // ─── 小组件元素样式 ───

    private fun loadWidgetStyles() {
        viewModelScope.launch {
            _widgetStyles.value =
                WidgetElement.values().associateWith { settings.widgetElementStyle(it) }
            _widgetBackground.value = settings.widgetBackground()
        }
    }

    fun setWidgetElementStyle(element: WidgetElement, style: WidgetElementStyle) {
        viewModelScope.launch {
            settings.setWidgetElementStyle(element, style)
            _widgetStyles.value = _widgetStyles.value.toMutableMap().apply { put(element, style) }
            refreshWidgets()
        }
    }

    fun setWidgetBackground(colorArgb: Int?, alpha: Int) {
        viewModelScope.launch {
            settings.setWidgetBackground(colorArgb, alpha)
            _widgetBackground.value = colorArgb to alpha
            refreshWidgets()
        }
    }

    fun resetWidgetStyles() {
        viewModelScope.launch {
            settings.resetWidgetStyles()
            loadWidgetStyles()
            refreshWidgets()
        }
    }
}

data class SettingsUiState(
    val loading: Boolean = false,
    val credentialStatuses: List<CredentialStatus> = emptyList()
)
