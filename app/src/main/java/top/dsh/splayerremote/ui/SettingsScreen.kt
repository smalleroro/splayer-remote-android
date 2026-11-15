package top.dsh.splayerremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.dsh.splayerremote.data.AppSettings
import top.dsh.splayerremote.data.CoverSize
import top.dsh.splayerremote.data.LyricFontSize
import top.dsh.splayerremote.data.ThemeMode

/**
 * 设置页。
 *
 * 分五组：连接 / 外观 / 歌词 / 通知 / 行为。
 * 每一项改动立刻生效并写盘，没有"保存"按钮——遥控器这种工具，
 * 用户改完就想看到效果，多一步确认反而烦。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onDiscover: () -> Unit,
    isDiscovering: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("返回", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            // 占位，让标题居中
            Spacer(Modifier.width(64.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionTitle("连接")

            ServerField(
                current = settings.baseUrl,
                onApply = { url -> onUpdate { it.copy(baseUrl = url) } },
            )

            ActionRow(
                title = "自动发现",
                subtitle = if (isDiscovering) "正在扫描局域网…" else "在当前网段搜索 SPlayer",
                onClick = onDiscover,
                enabled = !isDiscovering,
            )

            SwitchRow(
                title = "启动时自动连接",
                subtitle = "打开应用立即尝试连接，不等手动点",
                checked = settings.autoConnect,
                onCheckedChange = { v -> onUpdate { it.copy(autoConnect = v) } },
            )

            SwitchRow(
                title = "找不到时自动扫描",
                subtitle = "连接失败后自动在局域网里找 SPlayer",
                checked = settings.autoDiscover,
                onCheckedChange = { v -> onUpdate { it.copy(autoDiscover = v) } },
            )

            SliderRow(
                title = "刷新间隔",
                valueText = "${settings.pollIntervalMs} ms",
                value = settings.pollIntervalMs.toFloat(),
                range = 500f..5000f,
                steps = 8,
                onChange = { v ->
                    // 归整到 500ms 的倍数，避免出现 1234ms 这种无意义值
                    val rounded = (v / 500f).toInt() * 500L
                    onUpdate { it.copy(pollIntervalMs = rounded.coerceIn(500L, 5000L)) }
                },
            )

            SectionTitle("外观")

            OptionRow(
                title = "主题",
                options = listOf(
                    ThemeMode.System to "跟随系统",
                    ThemeMode.Dark to "深色",
                    ThemeMode.Light to "浅色",
                ),
                selected = settings.themeMode,
                onSelect = { v -> onUpdate { it.copy(themeMode = v) } },
            )

            OptionRow(
                title = "封面尺寸",
                options = listOf(
                    CoverSize.Small to "小",
                    CoverSize.Medium to "中",
                    CoverSize.Large to "大",
                ),
                selected = settings.coverSize,
                onSelect = { v -> onUpdate { it.copy(coverSize = v) } },
            )

            SectionTitle("歌词")

            SwitchRow(
                title = "显示翻译",
                subtitle = "有中文翻译时在原文下方显示",
                checked = settings.showTranslation,
                onCheckedChange = { v -> onUpdate { it.copy(showTranslation = v) } },
            )

            OptionRow(
                title = "字号",
                options = listOf(
                    LyricFontSize.Small to "小",
                    LyricFontSize.Medium to "中",
                    LyricFontSize.Large to "大",
                ),
                selected = settings.lyricFontSize,
                onSelect = { v -> onUpdate { it.copy(lyricFontSize = v) } },
            )

            SwitchRow(
                title = "自动滚动",
                subtitle = "当前行自动滚到屏幕中间",
                checked = settings.autoScrollLyrics,
                onCheckedChange = { v -> onUpdate { it.copy(autoScrollLyrics = v) } },
            )

            SectionTitle("通知")

            SwitchRow(
                title = "常驻通知",
                subtitle = "后台保持控制，锁屏也能暂停切歌",
                checked = settings.persistentNotification,
                onCheckedChange = { v -> onUpdate { it.copy(persistentNotification = v) } },
            )

            SwitchRow(
                title = "通知显示封面",
                subtitle = "在通知里显示专辑封面",
                checked = settings.notificationCover,
                onCheckedChange = { v -> onUpdate { it.copy(notificationCover = v) } },
            )

            SectionTitle("行为")

            SwitchRow(
                title = "退出时停止服务",
                subtitle = "关闭应用后不再后台轮询",
                checked = settings.stopServiceOnExit,
                onCheckedChange = { v -> onUpdate { it.copy(stopServiceOnExit = v) } },
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

/* ------------------------------------------------------------------ 组件 */

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ServerField(current: String, onApply: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            label = { Text("SPlayer 地址", style = MaterialTheme.typography.labelSmall) },
            placeholder = { Text("192.168.1.100:14558") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { onApply(text) }) {
            Text("应用", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun <T> OptionRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

/** Box 在 OptionRow 里用到，这里补一个轻量别名避免多引一个 import */
@Composable
private fun Box(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier = modifier) { content() }
}
