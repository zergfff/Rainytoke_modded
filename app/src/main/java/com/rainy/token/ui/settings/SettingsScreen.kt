package com.rainy.token.ui.settings

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.domain.model.CredentialStatus
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.components.ServiceIcon
import com.rainy.token.ui.components.StatusChip
import com.rainy.token.ui.components.StatusLevel
import com.rainy.token.ui.components.StatusStyle
import com.rainy.token.R
import com.rainy.token.ui.theme.InkMuted
import com.rainy.token.ui.theme.StrawberryPink
import com.rainy.token.ui.theme.ThemeKeys
import com.rainy.token.ui.theme.ThemePresets
import com.rainy.token.util.LocaleManager

/**
 * 设置页（雨晴风格重做版）。
 *
 * 包含：主题选择、字体缩放、Widget 服务选择、刷新间隔、天气配置、
 * 凭据管理列表、语言切换、调试日志入口。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onEditCredential: (ServiceType) -> Unit,
    onOpenDebugLog: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle()
    var showLanguageDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.title_settings),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        if (uiState.loading && uiState.credentialStatuses.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ─── Widget 外观 ───
                item {
                    Text(
                        text = stringResource(R.string.title_widget_appearance),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }

                // 主题选择
                item {
                    ThemeCard(
                        currentTheme = settingsState.themeKey,
                        onThemeSelected = { viewModel.setTheme(it) }
                    )
                }

                // 字体缩放
                item {
                    FontScaleCard(
                        currentScale = settingsState.fontScale,
                        onScaleChanged = { viewModel.setFontScale(it) }
                    )
                }

                // Widget 服务选择
                item {
                    WidgetServicesCard(
                        selectedServices = settingsState.widgetSelectedServices,
                        onToggleService = { serviceKey, enabled ->
                            val current = settingsState.widgetSelectedServices.toMutableSet()
                            if (enabled) current.add(serviceKey) else current.remove(serviceKey)
                            viewModel.setWidgetSelectedServices(current)
                        }
                    )
                }

                // 刷新间隔
                item {
                    RefreshIntervalCard(
                        intervalMin = settingsState.widgetRefreshIntervalMin,
                        onIntervalChanged = { viewModel.setWidgetRefreshInterval(it) }
                    )
                }

                // 天气配置
                item {
                    WeatherConfigCard(
                        weatherEnabled = settingsState.weatherEnabled,
                        qweatherKey = settingsState.qweatherKey,
                        onWeatherEnabledChanged = { viewModel.setWeatherEnabled(it) },
                        onQWeatherKeyChanged = { viewModel.setQWeatherKey(it) }
                    )
                }

                // ─── 凭据管理 ───
                item {
                    Text(
                        text = stringResource(R.string.title_credentials),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }
                items(uiState.credentialStatuses, key = { it.service.name }) { status ->
                    CredentialStatusCard(
                        status = status,
                        onClick = { onEditCredential(status.service) }
                    )
                }

                // ─── 其他 ───
                item {
                    LanguageCard(onClick = { showLanguageDialog = true })
                }
                item {
                    DebugLogCard(onClick = { onOpenDebugLog() })
                }
                item {
                    Spacer(modifier = Modifier.padding(top = 8.dp))
                    AboutCard()
                }
            }
        }
    }

    if (showLanguageDialog) {
        LanguageDialog(onDismiss = { showLanguageDialog = false })
    }
}

// ─── 主题选择卡片 ───
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeCard(
    currentTheme: String,
    onThemeSelected: (String) -> Unit
) {
    val themes = listOf(
        ThemeKeys.STRAWBERRY to stringResource(R.string.theme_strawberry),
        ThemeKeys.SKY to stringResource(R.string.theme_sky),
        ThemeKeys.MINT to stringResource(R.string.theme_mint),
        ThemeKeys.LILAC to stringResource(R.string.theme_lilac),
        ThemeKeys.MATERIAL_YOU to stringResource(R.string.theme_material_you)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.title_theme),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                themes.forEach { (key, label) ->
                    FilterChip(
                        selected = currentTheme == key,
                        onClick = { onThemeSelected(key) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    }
}

// ─── 字体缩放卡片 ───
@Composable
private fun FontScaleCard(
    currentScale: Float,
    onScaleChanged: (Float) -> Unit
) {
    val label = when {
        currentScale <= 0.85f -> stringResource(R.string.font_small)
        currentScale >= 1.3f -> stringResource(R.string.font_extra_large)
        currentScale >= 1.15f -> stringResource(R.string.font_large)
        else -> stringResource(R.string.font_normal)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.title_font_size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Slider(
                value = currentScale,
                onValueChange = onScaleChanged,
                valueRange = 0.85f..1.3f,
                steps = 2,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// ─── Widget 服务选择卡片 ───
@Composable
private fun WidgetServicesCard(
    selectedServices: Set<String>,
    onToggleService: (String, Boolean) -> Unit
) {
    val services = listOf(
            ServiceType.OPENCODE_GO to ServiceType.OPENCODE_GO.displayName,
            ServiceType.COMMANDCODE_GO to ServiceType.COMMANDCODE_GO.displayName,
            ServiceType.CODEX to ServiceType.CODEX.displayName,
            ServiceType.DEEPSEEK to ServiceType.DEEPSEEK.displayName,
            ServiceType.OLLAMA to ServiceType.OLLAMA.displayName
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.title_widget_services),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.widget_switch_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                services.forEach { (service, name) ->
                    val key = service.storageKey
                    Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // 至少保留一个服务
                            if (selectedServices.contains(key) && selectedServices.size > 1) {
                                onToggleService(key, false)
                            } else if (!selectedServices.contains(key)) {
                                onToggleService(key, true)
                            }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selectedServices.contains(key),
                        onCheckedChange = { enabled ->
                            if (enabled || selectedServices.size > 1) {
                                onToggleService(key, enabled)
                            }
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

// ─── 刷新间隔卡片 ───
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RefreshIntervalCard(
    intervalMin: Int,
    onIntervalChanged: (Int) -> Unit
) {
    val options = listOf(15, 30, 45, 60)
    val label = stringResource(R.string.refresh_interval, intervalMin)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.title_widget_refresh),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { min ->
                    FilterChip(
                        selected = intervalMin == min,
                        onClick = { onIntervalChanged(min) },
                        label = { Text("${min}min") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    }
}

// ─── 天气配置卡片 ───
@Composable
private fun WeatherConfigCard(
    weatherEnabled: Boolean,
    qweatherKey: String,
    onWeatherEnabledChanged: (Boolean) -> Unit,
    onQWeatherKeyChanged: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.title_weather),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = weatherEnabled,
                    onCheckedChange = onWeatherEnabledChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
            if (weatherEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.weather_qweather_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextField(
                    value = qweatherKey,
                    onValueChange = onQWeatherKeyChanged,
                    placeholder = { Text(stringResource(R.string.weather_qweather_key_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }
    }
}

// ─── 语言卡片 ───
@Composable
private fun LanguageCard(onClick: () -> Unit) {
    val context = LocalContext.current
    val current = LocaleManager.getLocaleCode(context)
    val labelRes = when (current) {
        "zh" -> R.string.app_locale_zh
        "zh-Hant" -> R.string.app_locale_zh_hant
        "en" -> R.string.app_locale_en
        else -> R.string.app_locale_follow_system
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🌐",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.title_language),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = LocaleManager.getLocaleCode(context)
    val options: List<Pair<String?, Int>> = listOf(
        null to R.string.app_locale_follow_system,
        "zh" to R.string.app_locale_zh,
        "zh-Hant" to R.string.app_locale_zh_hant,
        "en" to R.string.app_locale_en
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_language_title)) },
        text = {
            Column {
                options.forEach { (code, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                LocaleManager.saveLocale(context, code)
                                onDismiss()
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    (context as? Activity)?.recreate()
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (current == code) {
                            Text(
                                "✓",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

// ─── 凭据状态卡片 ───
@Composable
private fun CredentialStatusCard(status: CredentialStatus, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ServiceIcon(service = status.service, size = 40)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status.service.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stateLabel(status.state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            StatusChip(style = stateToChip(status.state))
        }
    }
}

// ─── 关于卡片 ───
@Composable
private fun AboutCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.title_about),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.padding(top = 8.dp))
            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── 调试日志卡片 ───
@Composable
private fun DebugLogCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🔍",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.title_debug_log),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.debug_log_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun stateLabel(state: CredentialStatus.State): String = when (state) {
    CredentialStatus.State.NOT_CONFIGURED -> stringResource(R.string.status_not_configured_add)
    CredentialStatus.State.OK -> stringResource(R.string.status_ok_valid)
    CredentialStatus.State.EXPIRED -> stringResource(R.string.status_expired_reconfigure)
    CredentialStatus.State.WARNING -> stringResource(R.string.status_need_reverify)
}

private fun stateToChip(state: CredentialStatus.State): StatusStyle = when (state) {
    CredentialStatus.State.NOT_CONFIGURED -> StatusStyle(R.string.status_not_configured, StatusLevel.WARNING)
    CredentialStatus.State.OK -> StatusStyle(R.string.status_configured, StatusLevel.OK)
    CredentialStatus.State.EXPIRED -> StatusStyle(R.string.status_expired, StatusLevel.ERROR)
    CredentialStatus.State.WARNING -> StatusStyle(R.string.status_relogin, StatusLevel.WARNING)
}
