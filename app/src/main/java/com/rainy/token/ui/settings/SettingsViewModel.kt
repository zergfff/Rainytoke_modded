package com.rainy.token.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.local.appSettingsStore
import com.rainy.token.data.local.SecureStorage
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.data.repository.WeatherRepository
import com.rainy.token.data.weather.QWeatherCredential
import com.rainy.token.data.weather.loadQWeatherCredentialWithMigration
import com.rainy.token.data.weather.saveQWeatherCredential
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
import kotlinx.coroutines.flow.first
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
    val qweatherHost: String = "",
    val qweatherCredential: QWeatherCredential = QWeatherCredential(),
    val weatherTest: WeatherTestResult? = null
)

/** 设置页「测试连接」的结果，文案在 UI 层用 stringResource 组装 */
data class WeatherTestResult(
    val pending: Boolean = false,
    val ok: Boolean = false,
    val city: String = "",
    val temp: String = "",
    val text: String = "",
    val error: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val secureStorage: SecureStorage,
    private val weatherRepository: WeatherRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val application = context.applicationContext as android.app.Application
    private val settings = application.appSettingsStore

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * 和风天气凭据（JWT 三件套 + 私钥）存在加密的 SecureStorage 里，
     * DataStore 只留一个 host 字段给旧数据迁移兜底。
     */
    private val _qweatherCredential = MutableStateFlow(QWeatherCredential())

    /** 「测试连接」的临时结果 */
    private val _weatherTest = MutableStateFlow<WeatherTestResult?>(null)

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
        kotlinx.coroutines.flow.combine(
            baseSettings,
            _qweatherCredential,
            _weatherTest
        ) { base, credential, test ->
            base.copy(qweatherCredential = credential, weatherTest = test)
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
        // 进入设置页时把已保存的凭据读出来填进输入框（旧版 API Key 自动迁移）
        viewModelScope.launch {
            val legacyHost = settings.qweatherHost.first()
            _qweatherCredential.value = secureStorage.loadQWeatherCredentialWithMigration(legacyHost)
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

    /** 编辑任一字段都走这里：先更新内存态（输入框立刻响应），再落盘 */
    fun setQWeatherCredential(credential: QWeatherCredential) {
        _qweatherCredential.value = credential
        viewModelScope.launch {
            secureStorage.saveQWeatherCredential(credential)
            // host 同步写一份到 DataStore：旧版本迁移与 Worker 兜底要用
            settings.setQweatherHost(credential.apiHost)
            // 凭据齐全就自动开启天气，无需额外开关
            val enabled = credential.hasAuth
            settings.setWeatherEnabled(enabled)
            if (enabled) {
                WidgetPeriodicWorker.requestImmediate(application)
                // 立即重绘，让已缓存的天气（如果有）马上出现在小组件上
                refreshWidgets()
            }
        }
    }

    /**
     * 测一次真实请求：优先用上次定位坐标，没有就用北京坐标兜底——
     * 测试连接不该被"没给定位权限"挡住。
     * 成功时顺手写入小组件缓存，点完就能在桌面上看到天气。
     */
    fun testWeatherConnection() {
        val credential = _qweatherCredential.value
        _weatherTest.value = WeatherTestResult(pending = true)
        viewModelScope.launch {
            val lat = settings.weatherLatitude.first()
            val lon = settings.weatherLongitude.first()
            val latLon = if (lat != 0f || lon != 0f) {
                lat.toDouble() to lon.toDouble()
            } else {
                DEFAULT_TEST_LATITUDE to DEFAULT_TEST_LONGITUDE
            }
            val outcome = weatherRepository.fetchNow(credential, latLon, application)
            _weatherTest.value = when (outcome) {
                is WeatherRepository.Outcome.Ok -> WeatherTestResult(
                    ok = true,
                    city = outcome.snapshot.city,
                    temp = outcome.snapshot.tempC,
                    text = outcome.snapshot.text
                )
                is WeatherRepository.Outcome.Fail ->
                    WeatherTestResult(ok = false, error = outcome.reason)
            }
            if (outcome is WeatherRepository.Outcome.Ok) {
                settings.setWeatherSnapshot(
                    city = outcome.snapshot.city,
                    text = outcome.snapshot.text,
                    icon = outcome.snapshot.icon,
                    temp = outcome.snapshot.tempC
                )
                refreshWidgets()
            }
        }
    }

    fun clearWeatherTest() {
        _weatherTest.value = null
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

    private companion object {
        /** 测试连接用的兜底坐标：北京 */
        const val DEFAULT_TEST_LATITUDE = 39.90
        const val DEFAULT_TEST_LONGITUDE = 116.41
    }
}

data class SettingsUiState(
    val loading: Boolean = false,
    val credentialStatuses: List<CredentialStatus> = emptyList()
)
