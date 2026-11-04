package app.xujing.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.xujing.core.*

@Composable
internal fun Avatar(name: String, path: String? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(path) { path?.let { runCatching {
        val file = java.io.File(it.removePrefix("file://")).let { candidate -> if(candidate.isAbsolute) candidate else java.io.File(context.filesDir, it) }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 256).coerceAtLeast(1)
        options.inJustDecodeBounds = false
        BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
    }.getOrNull() } }
    Surface(modifier = modifier.size(48.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        if (bitmap != null) Image(bitmap, name, contentScale = ContentScale.Crop)
        else Box(contentAlignment = Alignment.Center) { Text(name.take(1).ifBlank { "叙" }, style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
internal fun EmptyState(icon: ImageVector, title: String, description: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (action != null && onAction != null) { Spacer(Modifier.height(20.dp)); FilledTonalButton(onClick = onAction) { Text(action) } }
    }
}

@Composable
internal fun SectionTitle(title: String, description: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)) {
        Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
        if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
internal fun CollapsibleSection(title: String, description: String? = null, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (expanded) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
internal fun SettingSwitch(title: String, description: String = "", checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
internal fun ChoiceField(label: String, value: String?, options: List<Pair<String, String>>, noneLabel: String? = null, onChoose: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val displayed = options.firstOrNull { it.first == value }?.second ?: noneLabel ?: "请选择"
    Box(Modifier.fillMaxWidth()) {
        OutlinedCard(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(displayed, modifier = Modifier.padding(top = 4.dp))
                }
                Icon(Icons.Outlined.ExpandMore, null)
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (noneLabel != null) DropdownMenuItem(text = { Text(noneLabel) }, onClick = { expanded = false; onChoose(null) })
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, leadingIcon = { if (id == value) Icon(Icons.Outlined.Check, null) }, onClick = { expanded = false; onChoose(id) }) }
        }
    }
}

@Composable
internal fun ConfirmDelete(name: String, description: String = "此操作无法撤销。", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Outlined.Delete, null) }, title = { Text("删除$name？") },
        text = { Text(description) }, confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScaffold(title: String, onClose: () -> Unit, onSave: () -> Unit, enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            actions = { TextButton(onClick = onSave, enabled = enabled) { Text("保存") } })
    }) { padding -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
internal fun JsonPreview(title: String, value: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(value, modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

internal fun plainPreview(value: String): String = value.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").take(120)
internal val libraryKinds = listOf("worldbook" to "世界书", "preset" to "预设", "regex" to "正则", "persona" to "人设", "style" to "文风")
internal fun kindName(kind: String) = libraryKinds.firstOrNull { it.first == kind }?.second ?: kind
