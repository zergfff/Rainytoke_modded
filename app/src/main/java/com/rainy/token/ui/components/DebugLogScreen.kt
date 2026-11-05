package com.rainy.token.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rainy.token.R
import com.rainy.token.data.debug.DebugLog
import com.rainy.token.data.debug.FileDebugLog
import com.rainy.token.ui.theme.InkMuted
import com.rainy.token.ui.theme.StrawberryPink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    val entries by DebugLog.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 小组件日志在文件里（跨进程），进页面时读一次尾部
    var widgetLines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        widgetLines = withContext(Dispatchers.IO) { FileDebugLog.tail(context) }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.title_debug_log),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = StrawberryPink
                        )
                    }
                },
                actions = {
                    // 一键复制：小组件文件日志 + 内存日志，直接粘贴给开发者
                    TextButton(onClick = { copyAllLogs(context, widgetLines, entries) }) {
                        Text(stringResource(R.string.action_copy), color = StrawberryPink)
                    }
                    TextButton(onClick = {
                        DebugLog.clear()
                        FileDebugLog.clear(context)
                        widgetLines = emptyList()
                    }) {
                        Text(stringResource(R.string.action_clear), color = StrawberryPink)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        if (entries.isEmpty() && widgetLines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.debug_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkMuted,
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (widgetLines.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.widget_name),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = StrawberryPink
                        )
                    }
                    items(widgetLines.asReversed()) { line ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
                items(entries, key = { it.timestamp.toString() + it.tag + it.message }) { entry ->
                    val levelColor = when (entry.level) {
                        DebugLog.Level.INFO -> InkMuted
                        DebugLog.Level.WARN -> Color(0xFFFFA726)
                        DebugLog.Level.ERROR -> Color(0xFFE91E63)
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = entry.level.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = levelColor
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = entry.tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = InkMuted
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = entry.toString().substringBefore(' '),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkMuted.copy(alpha = 0.6f)
                                )
                            }
                            Spacer(modifier = Modifier.padding(top = 4.dp))
                            Text(
                                text = entry.message,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 把小组件文件日志 + 内存日志拼成纯文本放进剪贴板，方便直接粘贴给开发者 */
private fun copyAllLogs(
    context: Context,
    widgetLines: List<String>,
    entries: List<DebugLog.Entry>
) {
    val text = buildString {
        appendLine("=== widget file log (oldest -> newest, ${widgetLines.size} lines) ===")
        widgetLines.forEach { appendLine(it) }
        appendLine("=== app memory log (newest first, ${entries.size} entries) ===")
        entries.forEach { appendLine(it.toString()) }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("rainytoken-debug-log", text))
    Toast.makeText(
        context,
        "Copied ${widgetLines.size + entries.size} lines",
        Toast.LENGTH_SHORT
    ).show()
}
