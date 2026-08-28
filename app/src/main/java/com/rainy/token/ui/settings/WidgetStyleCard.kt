package com.rainy.token.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rainy.token.ui.widget.WidgetElement
import com.rainy.token.ui.widget.WidgetElementStyle
import com.rainy.token.ui.widget.WidgetStyleDefaults

/**
 * 小组件元素样式自定义。
 *
 * 按元素分组，每组可调 字号 / 颜色 / 字族。
 * 字号留空（开关关闭）时跟随"小组件字体大小"的整体缩放。
 */
@Composable
fun WidgetStyleCard(
    styles: Map<WidgetElement, WidgetElementStyle>,
    background: Pair<Int?, Int>,
    onStyleChanged: (WidgetElement, WidgetElementStyle) -> Unit,
    onBackgroundChanged: (Int?, Int) -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "小组件样式",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onReset) {
                    Text("恢复默认")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            WidgetElement.values().forEach { element ->
                ElementStyleRow(
                    element = element,
                    style = styles[element] ?: WidgetElementStyle(),
                    onStyleChanged = { onStyleChanged(element, it) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            BackgroundStyleRow(
                colorArgb = background.first,
                alpha = background.second,
                onChanged = onBackgroundChanged
            )
        }
    }
}

@Composable
private fun ElementStyleRow(
    element: WidgetElement,
    style: WidgetElementStyle,
    onStyleChanged: (WidgetElementStyle) -> Unit
) {
    Column {
        Text(
            text = elementLabel(element),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(6.dp))

        // 字号：开关 + 滑块
        val customSize = style.sizeSp != null
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "字号",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp)
            )
            Switch(
                checked = customSize,
                onCheckedChange = { enabled ->
                    onStyleChanged(
                        style.copy(sizeSp = if (enabled) element.defaultSizeSp else null)
                    )
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary
                )
            )
            Text(
                text = if (customSize) "${style.sizeSp?.toInt()}sp" else "跟随",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (customSize) {
            Slider(
                value = style.sizeSp ?: element.defaultSizeSp,
                onValueChange = { onStyleChanged(style.copy(sizeSp = it)) },
                valueRange = 8f..60f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 颜色
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "颜色",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp)
            )
            ColorPickerRow(
                selected = style.colorArgb,
                onSelected = { onStyleChanged(style.copy(colorArgb = it)) }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 字族
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "样式",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp)
            )
            TextStyleRow(
                element = element,
                selected = style.styleOrDefault(element),
                isCustomized = style.textStyle != null,
                onSelected = { onStyleChanged(style.copy(textStyle = it)) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextStyleRow(
    element: WidgetElement,
    selected: String,
    isCustomized: Boolean,
    onSelected: (String?) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // 未自定义时额外给一个"默认"选项，点了回到元素自带样式（时间为粗体）
        if (!isCustomized) {
            FilterChip(
                selected = true,
                onClick = { onSelected(null) },
                label = { Text("默认") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
        WidgetStyleDefaults.STYLE_OPTIONS.forEach { opt ->
            FilterChip(
                selected = selected == opt,
                onClick = { onSelected(opt) },
                label = {
                    Text(
                        text = styleLabel(opt),
                        fontWeight = if (opt == WidgetStyleDefaults.STYLE_BOLD) FontWeight.Bold else null,
                        fontStyle = if (opt == WidgetStyleDefaults.STYLE_ITALIC)
                            androidx.compose.ui.text.font.FontStyle.Italic
                        else androidx.compose.ui.text.font.FontStyle.Normal
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPickerRow(selected: Int?, onSelected: (Int?) -> Unit) {
    val presets = listOf(
        null to "默认",
        0xFFFFFFFF.toInt() to "白",
        0xFF000000.toInt() to "黑",
        0xFFFF6B9D.toInt() to "粉",
        0xFF4A90D9.toInt() to "蓝",
        0xFF5BC0A8.toInt() to "绿",
        0xFFB07CD6.toInt() to "紫",
        0xFFFFB454.toInt() to "橙"
    )

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.forEach { (argb, label) ->
            val isSelected = selected == argb
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        if (argb == null) MaterialTheme.colorScheme.surfaceVariant
                        else Color(argb)
                    )
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                CircleShape
                            )
                        } else Modifier
                    )
                    .clickable { onSelected(argb) }
            )
        }
    }
}

@Composable
private fun BackgroundStyleRow(
    colorArgb: Int?,
    alpha: Int,
    onChanged: (Int?, Int) -> Unit
) {
    Column {
        Text(
            text = "背景",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "颜色",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp)
            )
            ColorPickerRow(selected = colorArgb, onSelected = { onChanged(it, alpha) })
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "透明",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp)
            )
            Text(
                text = "${(alpha * 100 / 255)}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = alpha.toFloat(),
            onValueChange = { onChanged(colorArgb, it.toInt()) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

private fun elementLabel(e: WidgetElement): String = when (e) {
    WidgetElement.TIME -> "时间"
    WidgetElement.WEEKDAY -> "星期"
    WidgetElement.DATE -> "日期"
    WidgetElement.WEATHER -> "天气（含图标）"
    WidgetElement.TITLE -> "标题"
    WidgetElement.ROW_LABEL -> "5h / 本周 / 本月"
    WidgetElement.PERCENT -> "百分比"
    WidgetElement.RESET -> "剩余时间"
}

private fun styleLabel(key: String): String = when (key) {
    WidgetStyleDefaults.STYLE_BOLD -> "粗体"
    WidgetStyleDefaults.STYLE_ITALIC -> "斜体"
    else -> "常规"
}
