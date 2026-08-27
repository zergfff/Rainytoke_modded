package com.rainy.token.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.rainy.token.data.local.appSettingsStore
import com.rainy.token.data.local.SecureStorage
import com.rainy.token.data.repository.CredentialRepository
import com.rainy.token.domain.model.CredentialStatus
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
    val fontScale: Float = 1.0f,
    val widgetRefreshIntervalMin: Int = 15,
    val widgetSelectedServices: Set<String> = setOf("opencode_go", "commandcode_go", "codex", "ollama"),
    val weatherEnabled: Boolean = false,
    val qweatherKey: String = ""
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

    val settingsState: StateFlow<SettingsUiStateData> = kotlinx.coroutines.flow.combine(
        settings.themeKey,
        settings.fontScale,
        settings.widgetRefreshIntervalMin,
        settings.widgetSelectedServices,
        settings.weatherEnabled,
        settings.qweatherKey
    ) { values: Array<Any> ->
        SettingsUiStateData(
            themeKey = values[0] as String,
            fontScale = values[1] as Float,
            widgetRefreshIntervalMin = values[2] as Int,
            widgetSelectedServices = @Suppress("UNCHECKED_CAST") values[3] as Set<String>,
            weatherEnabled = values[4] as Boolean,
            qweatherKey = values[5] as String
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiStateData())

    init {
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

    fun setFontScale(v: Float) {
        viewModelScope.launch { settings.setFontScale(v) }
    }

    fun setWidgetRefreshInterval(min: Int) {
        viewModelScope.launch {
            settings.setWidgetRefreshIntervalMin(min)
            WidgetPeriodicWorker.schedule(application, min)
        }
    }

    fun setWidgetSelectedServices(set: Set<String>) {
        viewModelScope.launch { settings.setWidgetSelectedServices(set) }
    }

    fun setWeatherEnabled(v: Boolean) {
        viewModelScope.launch { settings.setWeatherEnabled(v) }
    }

    fun setQWeatherKey(key: String) {
        viewModelScope.launch { secureStorage.setQWeatherKey(key) }
    }
}

data class SettingsUiState(
    val loading: Boolean = false,
    val credentialStatuses: List<CredentialStatus> = emptyList()
)
