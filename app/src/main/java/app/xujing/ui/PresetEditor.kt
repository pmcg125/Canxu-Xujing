package app.xujing.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xujing.core.*
import app.xujing.core.compat.PresetDocuments
import kotlinx.serialization.json.*

private fun presetBody(value: JsonElement?): JsonObject {
    return PresetDocuments.body(value ?: EmptyObject)
}
private fun presetGroup(body: JsonObject): Int {
    val groups = body.array("prompt_order")
    return groups.indexOfFirst { (it as? JsonObject)?.text("character_id") == "100001" }.takeIf { it >= 0 }
        ?: groups.indexOfFirst { (it as? JsonObject)?.array("order")?.isNotEmpty() == true }.coerceAtLeast(0)
}
private fun presetOrder(body: JsonObject) = (body.array("prompt_order").getOrNull(presetGroup(body)) as? JsonObject)?.array("order") ?: JsonArray(emptyList())
private fun withOrder(body: JsonObject, order: List<JsonElement>): JsonObject {
    val groups = body.array("prompt_order").toMutableList()
    val index = presetGroup(body)
    if (groups.isEmpty()) groups.add(buildJsonObject { put("character_id", 100001) })
    val group = groups[index] as? JsonObject ?: EmptyObject
    groups[index] = JsonObject(group + ("order" to JsonArray(order)))
    return JsonObject(body + ("prompt_order" to JsonArray(groups)))
}

/** Edits only known fields; author extensions and unused prompt nodes remain intact. */
@Composable
internal fun PresetEntriesEditor(value: JsonElement?, onChange: (JsonElement) -> Unit) {
    val body = presetBody(value)
    val root = value as? JsonObject ?: EmptyObject
    fun publish(next: JsonObject) = onChange(PresetDocuments.replaceBody(root, next))
    var regexTab by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var shown by remember(search, regexTab) { mutableIntStateOf(20) }
    var edit by remember { mutableStateOf<JsonObject?>(null) }
    var editIndex by remember { mutableIntStateOf(-1) }
    var importing by remember { mutableStateOf(false) }
    var unused by remember { mutableStateOf(false) }
    val prompts = body.array("prompts")
    val order = presetOrder(body)
    val extensions = body["extensions"] as? JsonObject ?: EmptyObject
    val rules = extensions.array("regex_scripts")
    fun saveRules(next: List<JsonElement>) = publish(JsonObject(body + ("extensions" to JsonObject(extensions + ("regex_scripts" to JsonArray(next))))))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!regexTab, { regexTab = false }, { Text("提示词条目") })
        FilterChip(regexTab, { regexTab = true }, { Text("预设正则") })
    }
    OutlinedTextField(search, { search = it }, label = { Text("搜索名称或内容") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    if (!regexTab) SettingSwitch("显示顺序表外的节点", "启用后会加入当前顺序表", unused, { unused = it })
    val nodes = if (regexTab) rules.mapIndexedNotNull { i, item -> (item as? JsonObject)?.let { i to it } }
        else order.mapNotNull { entry ->
            val id = (entry as? JsonObject)?.text("identifier") ?: return@mapNotNull null
            val index = prompts.indexOfFirst { (it as? JsonObject)?.text("identifier") == id }
            (prompts.getOrNull(index) as? JsonObject)?.let { index to it }
        }.let { listed -> if (!unused) listed else listed + prompts.mapIndexedNotNull { i, item -> (item as? JsonObject)?.takeIf { node -> listed.none { it.second.text("identifier") == node.text("identifier") } }?.let { i to it } } }
    val filtered = nodes.filter { (_, node) -> search.isBlank() || listOf(node.text("name"), node.text("content"), node.text("scriptName"), node.text("findRegex")).any { it.contains(search, true) } }
    Text("${filtered.size} 项${if (!regexTab) " · ${prompts.size} 个已保存节点" else ""}", style = MaterialTheme.typography.labelMedium)
    filtered.take(shown).forEach { (index, node) ->
        val id = node.text("identifier")
        val orderIndex = order.indexOfFirst { (it as? JsonObject)?.text("identifier") == id }
        val entry = order.getOrNull(orderIndex) as? JsonObject ?: EmptyObject
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(if (regexTab) node.text("scriptName", "未命名正则") else node.text("name", id), style = MaterialTheme.typography.titleSmall)
                Text((if (regexTab) node.text("findRegex") else node.text("content")).take(160), maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Switch(if (regexTab) (node["disabled"] as? JsonPrimitive)?.booleanOrNull != true && (node["enabled"] as? JsonPrimitive)?.booleanOrNull != false else orderIndex >= 0 && (entry["enabled"] as? JsonPrimitive)?.booleanOrNull != false, { checked ->
                        if (regexTab) saveRules(rules.toMutableList().apply { set(index, JsonObject(node + ("disabled" to JsonPrimitive(!checked)) + if ("enabled" in node) mapOf("enabled" to JsonPrimitive(checked)) else emptyMap())) })
                        else publish(withOrder(body, order.toMutableList().apply { if (orderIndex < 0) add(buildJsonObject { put("identifier", id); put("enabled", checked) }) else set(orderIndex, JsonObject(entry + ("enabled" to JsonPrimitive(checked)))) }))
                    })
                    TextButton({ editIndex = index; edit = node }) { Text("编辑") }
                    if (regexTab) {
                        TextButton({ saveRules(rules.toMutableList().apply { val item = removeAt(index); add(index - 1, item) }) }, enabled = index > 0) { Text("上移") }
                        TextButton({ saveRules(rules.toMutableList().apply { val item = removeAt(index); add(index + 1, item) }) }, enabled = index < rules.lastIndex) { Text("下移") }
                    }
                    if (!regexTab) {
                        TextButton({ publish(withOrder(body, order.toMutableList().apply { val item = removeAt(orderIndex); add(orderIndex - 1, item) })) }, enabled = orderIndex > 0) { Text("上移") }
                        TextButton({ publish(withOrder(body, order.toMutableList().apply { val item = removeAt(orderIndex); add(orderIndex + 1, item) })) }, enabled = orderIndex >= 0 && orderIndex < order.lastIndex) { Text("下移") }
                    }
                }
            }
        }
    }
    if (filtered.size > shown) TextButton({ shown += 20 }) { Text("显示更多") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({
            editIndex = -1
            edit = if (regexTab) buildJsonObject { put("id", newId()); put("scriptName", "新正则"); put("findRegex", ""); put("replaceString", ""); put("placement", buildJsonArray { add(2) }) }
                else buildJsonObject { put("identifier", newId()); put("name", "新提示词"); put("role", "system"); put("content", ""); put("system_prompt", false); put("injection_position", 0); put("injection_depth", 0); put("injection_order", 100) }
        }) { Text(if (regexTab) "添加正则" else "添加提示词") }
        TextButton({ importing = true }) { Text("合并 / 替换 JSON") }
    }
    PresetDocuments.diagnostics(body).take(8).forEach { Text(it, color = MaterialTheme.colorScheme.error) }
    edit?.let { original ->
        PresetNodeDialog(original, regexTab, { edit = null }, { next ->
            if (regexTab) saveRules(rules.toMutableList().apply { if (editIndex < 0) add(next) else set(editIndex, next) })
            else {
                var nextBody = JsonObject(body + ("prompts" to JsonArray(prompts.toMutableList().apply { if (editIndex < 0) add(next) else set(editIndex, next) })))
                if (editIndex < 0) nextBody = withOrder(nextBody, order + buildJsonObject { put("identifier", next.text("identifier")); put("enabled", true) })
                publish(nextBody)
            }
            edit = null
        }, onDelete = if (editIndex < 0 || (!regexTab && (original.text("identifier") == "main" || (original["system_prompt"] as? JsonPrimitive)?.booleanOrNull != false))) null else ({
            if (regexTab) saveRules(rules.filterIndexed { i, _ -> i != editIndex })
            else {
                val removed = original.text("identifier")
                val groups = body.array("prompt_order").map { group ->
                    if (group !is JsonObject) group else JsonObject(group + ("order" to JsonArray(group.array("order").filter { (it as? JsonObject)?.text("identifier") != removed })))
                }
                publish(JsonObject(body + mapOf("prompts" to JsonArray(prompts.filterIndexed { i, _ -> i != editIndex }), "prompt_order" to JsonArray(groups))))
            }
            edit = null
        }))
    }
    if (importing) PresetImportDialog(body, { importing = false }) { publish(it); importing = false }
}

@Composable
private fun PresetNodeDialog(original: JsonObject, regex: Boolean, close: () -> Unit, save: (JsonObject) -> Unit, onDelete: (() -> Unit)?) {
    var draft by remember(original) { mutableStateOf(original) }
    var delete by remember { mutableStateOf(false) }
    fun set(key: String, value: JsonElement) { draft = JsonObject(draft + (key to value)) }
    val numberKeys = if (regex) listOf("minDepth", "maxDepth") else listOf("injection_depth", "injection_order")
    var numbers by remember(original) { mutableStateOf(numberKeys.associateWith { original.text(it) }) }
    var placements by remember(original) { mutableStateOf(original.array("placement").joinToString(",") { (it as? JsonPrimitive)?.content.orEmpty() }) }
    fun validNumber(text: String) = text.isBlank() || text.toIntOrNull()?.let { n -> if (regex) n >= -1 else n in 0..9999 } == true
    val validNumbers = numbers.values.all(::validNumber)
    val placementValues = placements.split(',').map(String::trim).filter(String::isNotBlank)
    val valid = validNumbers && (if (!regex) draft.text("name").isNotBlank() else draft.text("scriptName").isNotBlank() && draft.text("findRegex").isNotBlank() && placementValues.isNotEmpty() && placementValues.all { it.toIntOrNull()?.let { n -> n in 1..6 } == true })
    AlertDialog(onDismissRequest = close, title = { Text(if (regex) "编辑预设正则" else "编辑提示词") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val fields = if (regex) listOf("scriptName" to "名称", "findRegex" to "查找表达式", "replaceString" to "替换内容") else listOf("name" to "名称", "content" to "提示词正文")
            fields.forEach { (key, label) -> OutlinedTextField(draft.text(key), { set(key, JsonPrimitive(it)) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = if (key in listOf("content", "replaceString")) 4 else 1) }
            if (!regex) {
                ChoiceField("发送身份", draft.text("role", "system"), listOf("system" to "系统", "user" to "用户", "assistant" to "角色")) { it?.let { role -> set("role", JsonPrimitive(role)) } }
                ChoiceField("注入位置", draft.text("injection_position", "0"), listOf("0" to "相对位置 / 深度", "1" to "历史之后")) { it?.let { position -> set("injection_position", JsonPrimitive(position.toInt())) } }
                Text("深度 0 为普通位置；正数按历史深度注入。历史之后的条目按顺序排列。", style = MaterialTheme.typography.bodySmall)
                val triggers = draft.array("injection_trigger").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                listOf("normal" to "正常回复", "continue" to "续写", "regenerate" to "重生成").forEach { (key, label) -> SettingSwitch(label, "全部关闭表示所有模式", key in triggers, { checked -> set("injection_trigger", JsonArray((if (checked) (triggers + key).distinct() else triggers - key).map(::JsonPrimitive))) }) }
            } else {
                OutlinedTextField(placements, { placements = it }, label = { Text("作用位置（1–6，以逗号分隔）") }, modifier = Modifier.fillMaxWidth())
                Text("1 用户输入 · 2 模型输出 · 3 斜杠命令 · 4 旧显示位置 · 5 世界书 · 6 推理", style = MaterialTheme.typography.bodySmall)
                ChoiceField("查找式中的宏", draft.text("substituteRegex", "0"), listOf("0" to "不展开", "1" to "直接展开", "2" to "展开并转义")) { it?.let { mode -> set("substituteRegex", JsonPrimitive(mode.toInt())) } }
                OutlinedTextField(draft.array("trimStrings").joinToString("\n") { (it as? JsonPrimitive)?.content.orEmpty() }, { text -> set("trimStrings", JsonArray(text.lines().filter(String::isNotEmpty).map(::JsonPrimitive))) }, label = { Text("移除匹配内容中的文本（每行一项）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                listOf("markdownOnly" to "仅显示阶段", "promptOnly" to "仅提示词阶段", "runOnEdit" to "编辑时执行").forEach { (key, label) -> SettingSwitch(label, checked = (draft[key] as? JsonPrimitive)?.booleanOrNull == true, onChecked = { set(key, JsonPrimitive(it)) }) }
            }
            numberKeys.forEach { key -> OutlinedTextField(numbers[key].orEmpty(), { numbers = numbers + (key to it) }, label = { Text(when (key) { "minDepth" -> "最小深度（留空或 -1 不限）"; "maxDepth" -> "最大深度（留空或 -1 不限）"; "injection_depth" -> "注入深度"; else -> "注入顺序" }) }, isError = !validNumber(numbers[key].orEmpty()), modifier = Modifier.fillMaxWidth()) }
            if (onDelete != null) TextButton({ delete = true }) { Text("删除此条目", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({
        var next = draft
        numbers.forEach { (key, text) -> next = if (text.isBlank()) JsonObject(next - key) else JsonObject(next + (key to JsonPrimitive(text.toInt()))) }
        if (regex) next = JsonObject(next + ("placement" to JsonArray(placementValues.map { JsonPrimitive(it.toInt()) })))
        save(next)
    }, enabled = valid) { Text("应用") } }, dismissButton = { TextButton(close) { Text("取消") } })
    if (delete && onDelete != null) ConfirmDelete("条目", onDismiss = { delete = false }, onConfirm = onDelete)
}

@Composable
private fun PresetImportDialog(current: JsonObject, close: () -> Unit, apply: (JsonObject) -> Unit) {
    var text by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val parsed = remember(text) { runCatching {
        val root = AppJson.parseToJsonElement(text) as? JsonObject ?: error("需要 JSON 对象")
        val body = presetBody(root)
        require(body["prompts"] is JsonArray || body["prompt_order"] is JsonArray) { "缺少 prompts 或 prompt_order" }
        body
    } }
    val staged = remember(current, parsed, replace) { parsed.mapCatching { incoming -> if (replace) incoming else PresetDocuments.merge(current, incoming) } }
    fun commit() = apply(staged.getOrThrow())
    AlertDialog(onDismissRequest = close, title = { Text("导入预设 JSON") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("粘贴预设正文或设置备份中的单项。合并按标识更新顺序表中的条目及其启用状态，保留当前扩展；替换使用整份导入内容。")
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().height(220.dp), label = { Text("预设 JSON") }, isError = text.isNotBlank() && parsed.isFailure)
            SettingSwitch("替换当前内容", "关闭时合并顺序表中的条目", replace, { replace = it })
            if (text.isNotBlank() && staged.isFailure) Text(staged.exceptionOrNull()?.message.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton({ if (replace) confirm = true else commit() }, enabled = staged.isSuccess) { Text("应用导入") } }, dismissButton = { TextButton(close) { Text("取消") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("替换全部预设内容？") }, text = { Text("当前尚未保存的条目、正则和扩展将被替换，返回编辑器后仍需保存。") }, confirmButton = { TextButton({ commit() }) { Text("替换") } }, dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
}
