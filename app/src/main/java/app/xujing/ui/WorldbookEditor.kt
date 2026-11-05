package app.xujing.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xujing.core.*
import kotlinx.serialization.json.*

/** One worldbook entry, keeping its container key so edits can be written back in place. */
private data class WorldEntryRow(
    val key: String,
    val obj: JsonObject,
    val name: String,
    val keys: String,
    val content: String,
    val enabled: Boolean,
)

private fun entryName(obj: JsonObject, key: String): String =
    obj.text("comment").ifBlank { obj.text("name").ifBlank { obj.text("title").ifBlank { "条目 $key" } } }

private fun JsonElement.stringList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    is JsonPrimitive -> contentOrNull?.takeIf(String::isNotBlank)?.let(::listOf) ?: emptyList()
    else -> emptyList()
}

private fun entryKeys(obj: JsonObject): String =
    (obj["key"] ?: obj["keys"])?.stringList().orEmpty().joinToString("，")

private fun entryContent(obj: JsonObject): String = obj.text("content", obj.text("text"))

private fun entryEnabled(obj: JsonObject): Boolean =
    obj["disable"]?.jsonPrimitive?.booleanOrNull != true && obj["enabled"]?.jsonPrimitive?.booleanOrNull != false

private fun readEntries(root: JsonElement?): List<WorldEntryRow> {
    val container = (root as? JsonObject)?.get("entries") ?: return emptyList()
    return when (container) {
        is JsonArray -> container.mapIndexedNotNull { index, value ->
            (value as? JsonObject)?.let { obj ->
                WorldEntryRow(index.toString(), obj, entryName(obj, index.toString()), entryKeys(obj), entryContent(obj), entryEnabled(obj))
            }
        }
        is JsonObject -> container.entries.mapNotNull { (key, value) ->
            (value as? JsonObject)?.let { obj ->
                WorldEntryRow(key, obj, entryName(obj, key), entryKeys(obj), entryContent(obj), entryEnabled(obj))
            }
        }
        else -> emptyList()
    }
}

private fun writeEntry(root: JsonObject, key: String, entry: JsonObject): JsonObject {
    val container = root["entries"] ?: return root
    val updated: JsonElement = when (container) {
        is JsonArray -> JsonArray(container.mapIndexed { index, value -> if (index.toString() == key) entry else value })
        is JsonObject -> JsonObject(container.toMutableMap().apply { put(key, entry) })
        else -> container
    }
    return JsonObject(root.toMutableMap().apply { put("entries", updated) })
}

/**
 * Entry-by-entry worldbook editing. A book can hold thousands of entries and several MB of JSON,
 * so rendering the whole document in a text field is not viable; each entry is edited in isolation.
 */
@Composable
internal fun WorldbookEntriesEditor(root: JsonElement?, onChange: (JsonObject) -> Unit) {
    val entries = remember(root) { readEntries(root) }
    if (entries.isEmpty()) return
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<WorldEntryRow?>(null) }
    val filtered = remember(entries, query) {
        if (query.isBlank()) entries else entries.filter {
            it.name.contains(query, true) || it.keys.contains(query, true) || it.content.contains(query, true)
        }
    }
    SectionTitle("条目（${entries.size}）", "逐条编辑；未知字段会原样保留。")
    OutlinedTextField(query, { query = it }, label = { Text("筛选条目") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
        items(filtered, key = { it.key }) { entry ->
            OutlinedCard(onClick = { editing = entry }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.keys.isNotBlank()) Text(entry.keys, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.content.replace('\n', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (entry.enabled) "已启用" else "已停用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
    editing?.let { entry ->
        WorldEntryDialog(entry, onDismiss = { editing = null }, onSave = { updated ->
            val obj = root as? JsonObject
            if (obj != null) onChange(writeEntry(obj, entry.key, updated))
            editing = null
        })
    }
}

@Composable
private fun WorldEntryDialog(entry: WorldEntryRow, onDismiss: () -> Unit, onSave: (JsonObject) -> Unit) {
    var name by remember(entry.key) { mutableStateOf(entry.name) }
    var keys by remember(entry.key) { mutableStateOf(entry.keys) }
    var content by remember(entry.key) { mutableStateOf(entry.content) }
    var enabled by remember(entry.key) { mutableStateOf(entry.enabled) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑条目") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("备注 / 名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(keys, { keys = it }, label = { Text("关键词（逗号分隔）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(content, { content = it }, label = { Text("内容") }, modifier = Modifier.fillMaxWidth().height(260.dp))
            SettingSwitch("启用此条目", "", enabled) { enabled = it }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val updated = JsonObject(entry.obj.toMutableMap().apply {
                put("comment", JsonPrimitive(name))
                put("key", JsonArray(keys.split(',', '，').map(String::trim).filter(String::isNotEmpty).map(::JsonPrimitive)))
                put("content", JsonPrimitive(content))
                put("enabled", JsonPrimitive(enabled))
                if (enabled) remove("disable") else put("disable", JsonPrimitive(true))
            })
            onSave(updated)
        }) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
