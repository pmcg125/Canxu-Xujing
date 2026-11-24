package app.xujing.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.xujing.core.*
import kotlinx.serialization.json.*
import java.net.URI

@Composable
internal fun RoleEditor(role: RoleCard, onClose: () -> Unit, onSave: (RoleCard) -> Unit) {
    var draft by remember(role.id) { mutableStateOf(role) }
    var raw by remember(role.id) { mutableStateOf(AppJson.encodeToString(JsonObject.serializer(), role.raw)) }
    var advanced by remember { mutableStateOf(false) }
    val parsedRaw = remember(raw) { runCatching { AppJson.parseToJsonElement(raw) as JsonObject } }
    val hugeRaw = raw.length > 200_000
    EditorScaffold("编辑角色", onClose, onSave = { onSave(draft.copy(raw = parsedRaw.getOrThrow())) }, enabled = draft.name.isNotBlank() && parsedRaw.isSuccess, dirty = draft != role || parsedRaw.getOrNull() != role.raw) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Avatar(draft.name, draft.avatarPath); Text("角色设定将参与提示词编排。导入角色卡中的额外字段会保留。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("角色名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        EditorText("角色描述", draft.description) { draft = draft.copy(description = it) }
        EditorText("性格", draft.personality) { draft = draft.copy(personality = it) }
        EditorText("场景与背景", draft.scenario) { draft = draft.copy(scenario = it) }
        SectionTitle("开场白", "支持 {{char}}、{{user}} 等宏。备用开场白作为独立候选保存。")
        EditorText("默认开场白", draft.firstMessage, 5) { draft = draft.copy(firstMessage = it) }
        draft.alternateGreetings.forEachIndexed { index, greeting ->
            OutlinedCard {
                Column(Modifier.padding(12.dp)) {
                    Row { Text("备用开场 ${index + 1}", Modifier.weight(1f)); IconButton(onClick = { draft = draft.copy(alternateGreetings = draft.alternateGreetings.filterIndexed { i, _ -> i != index }) }) { Icon(Icons.Outlined.DeleteOutline, "删除开场") } }
                    EditorText("开场内容", greeting, 4) { value -> draft = draft.copy(alternateGreetings = draft.alternateGreetings.mapIndexed { i, old -> if (i == index) value else old }) }
                }
            }
        }
        OutlinedButton(onClick = { draft = draft.copy(alternateGreetings = draft.alternateGreetings + "") }) { Icon(Icons.Outlined.Add, null); Text("添加备用开场") }
        EditorText("对话示例", draft.examples, 5) { draft = draft.copy(examples = it) }
        EditorText("角色系统提示词", draft.systemPrompt) { draft = draft.copy(systemPrompt = it) }
        EditorText("历史消息后指令", draft.postHistoryInstructions) { draft = draft.copy(postHistoryInstructions = it) }
        TextButton(onClick = { advanced = !advanced }) { Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null); Text("原始扩展数据") }
        if (advanced) {
            Text("供高级编辑使用。未知字段会保留在导出数据中；修改前请先备份。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hugeRaw) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("原始数据较大（${raw.length} 字符），已关闭原始 JSON 编辑", style = MaterialTheme.typography.titleSmall)
                        Text("在输入框里渲染数 MB 文本会让界面卡死。请导出角色卡后在电脑上编辑，再重新导入；当前内容保持不变。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SelectionContainer {
                    Text(raw.take(4000) + "…", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                }
            } else {
                OutlinedTextField(raw, { raw = it }, label = { Text("原始 JSON 对象") }, modifier = Modifier.fillMaxWidth().height(320.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), isError = parsedRaw.isFailure, supportingText = { if (parsedRaw.isFailure) Text("必须是有效的 JSON 对象") })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun EditorText(label: String, value: String, minLines: Int = 3, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, minLines = minLines)
}

@Composable
internal fun DocumentEditor(document: LibraryDocument, onClose: () -> Unit, onSave: (LibraryDocument) -> Unit) {
    var name by remember(document.id) { mutableStateOf(document.name) }
    var kind by remember(document.id) { mutableStateOf(document.kind) }
    var enabled by remember(document.id) { mutableStateOf(document.enabled) }
    var json by remember(document.id) { mutableStateOf(AppJson.encodeToString(JsonElement.serializer(), document.data)) }
    val huge = json.length > 200_000
    val parsed = remember(json) { runCatching { AppJson.parseToJsonElement(json) } }
    EditorScaffold("编辑${kindName(kind)}", onClose, onSave = { onSave(document.copy(name = name.trim(), kind = kind, enabled = enabled, data = parsed.getOrThrow())) }, enabled = name.isNotBlank() && parsed.isSuccess, dirty = name != document.name || kind != document.kind || enabled != document.enabled || parsed.getOrNull() != document.data) {
        OutlinedTextField(name, { name = it }, label = { Text("资料名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        ChoiceField("资料类型", kind, libraryKinds, onChoose = { kind = it ?: kind })
        SettingSwitch("启用资料", "还需要在会话设置中选择要使用的世界书、预设或正则。", enabled, { enabled = it })
        Text("保留原有 JSON 结构与扩展字段。JSON 格式正确代表文件可以保存，具体条目是否可执行取决于兼容引擎支持范围。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { parsed.getOrNull()?.let { json = AppJson.encodeToString(JsonElement.serializer(), it) } }, enabled = parsed.isSuccess && !huge) { Icon(Icons.Outlined.DataObject, null, Modifier.size(18.dp)); Text("格式化") }
            if (document.data == EmptyObject && json.trim() == "{}") TextButton(onClick = { json = templateFor(kind) }) { Text("插入基础模板") }
        }
        if (kind == "worldbook") WorldbookEntriesEditor(parsed.getOrNull()) { next ->
            json = AppJson.encodeToString(JsonElement.serializer(), next)
        }
        if (kind == "preset") key(document.id) { PresetEntriesEditor(parsed.getOrNull()) { next ->
            json = AppJson.encodeToString(JsonElement.serializer(), next)
        } }
        if (huge) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("文档较大（${json.length} 字符），已关闭原始 JSON 编辑", style = MaterialTheme.typography.titleSmall)
                    Text(if (kind == "worldbook" || kind == "preset") "请用上方的条目列表逐条编辑；需要整体替换时可导出后在电脑上编辑再导入。" else "在输入框里渲染数 MB 文本会让界面卡死。请先用「导出」把资料存成文件，在电脑上编辑后再导入；当前内容保持不变。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (kind != "worldbook") SelectionContainer {
                Text(json.take(4000) + "…", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
            }
        } else {
            OutlinedTextField(json, { json = it }, label = { Text("JSON 内容") }, modifier = Modifier.fillMaxWidth().height(340.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), isError = parsed.isFailure,
                supportingText = { if (parsed.isFailure) Text(parsed.exceptionOrNull()?.message?.take(180) ?: "JSON 格式不正确") })
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun templateFor(kind: String): String = when (kind) {
    "worldbook" -> """{"entries":{"0":{"uid":0,"key":["关键词"],"keysecondary":[],"comment":"新条目","content":"在这里填写世界设定","constant":false,"selective":false,"enabled":true,"disable":false,"order":100,"position":0,"depth":4}}}"""
    "preset" -> basicPresetTemplate()
    "regex" -> """{"scripts":[{"id":"new-regex","scriptName":"新正则","findRegex":"需要匹配的内容","replaceString":"替换内容","placement":[2],"disabled":false,"markdownOnly":false,"promptOnly":false}]}"""
    "persona" -> """{"name":"我","description":"在这里填写你的角色设定"}"""
    else -> """{"name":"新文风","content":"在这里填写写作风格要求"}"""
}.let { AppJson.encodeToString(JsonElement.serializer(), AppJson.parseToJsonElement(it)) }

private fun basicPresetTemplate(): String {
    val nodes = listOf("main" to "系统指令", "worldInfoBefore" to "前置世界书", "personaDescription" to "用户人设", "charDescription" to "角色描述", "charPersonality" to "角色性格", "scenario" to "场景", "worldInfoAfter" to "后置世界书", "dialogueExamples" to "对话示例", "chatHistory" to "聊天历史", "jailbreak" to "后置指令")
    return AppJson.encodeToString(JsonElement.serializer(), buildJsonObject {
        put("prompts", JsonArray(nodes.map { (id, name) -> buildJsonObject {
            put("identifier", id); put("name", name); put("role", "system"); put("system_prompt", true)
            put("marker", id != "main" && id != "jailbreak"); put("content", ""); put("injection_position", 0); put("injection_depth", 0); put("injection_order", 100)
        } }))
        put("prompt_order", buildJsonArray { add(buildJsonObject { put("character_id", 100001); put("order", JsonArray(nodes.map { (id, _) -> buildJsonObject { put("identifier", id); put("enabled", true) } })) }) })
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionEditor(connection: ApiConnection, runtime: UiRuntime, actions: AppActions, onClose: () -> Unit, onSave: (ApiConnection, String?) -> Unit) {
    var draft by remember(connection.id) { mutableStateOf(connection) }
    var key by remember(connection.id) { mutableStateOf("") }
    var keyChanged by remember(connection.id) { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    val options = if (runtime.modelOptionsConnectionId == draft.id) runtime.modelOptions else emptyList()
    // Re-open on every successful fetch: the list itself may be identical between fetches.
    LaunchedEffect(runtime.modelFetchVersion) { if (options.isNotEmpty()) showModels = true }
    val providers = listOf(
        "OpenAI" to "https://api.openai.com/v1",
        "DeepSeek" to "https://api.deepseek.com/v1",
        "Moonshot" to "https://api.moonshot.cn/v1",
        "智谱" to "https://open.bigmodel.cn/api/paas/v4",
        "硅基流动" to "https://api.siliconflow.cn/v1",
    )
    var headers by remember(connection.id) { mutableStateOf(AppJson.encodeToString(JsonObject.serializer(), JsonObject(connection.headers.mapValues { JsonPrimitive(it.value) }))) }
    var parameters by remember(connection.id) { mutableStateOf(AppJson.encodeToString(JsonObject.serializer(), connection.parameters)) }
    var context by remember(connection.id) { mutableStateOf(connection.contextTokens.toString()) }
    val parsedHeaders = remember(headers) { runCatching { (AppJson.parseToJsonElement(headers) as JsonObject).mapValues { (_, value) -> (value as JsonPrimitive).content } } }
    val parsedParameters = remember(parameters) { runCatching { AppJson.parseToJsonElement(parameters) as JsonObject } }
    val validUrl = remember(draft.baseUrl) { runCatching { URI(draft.baseUrl).let { it.scheme in listOf("http", "https") && !it.host.isNullOrBlank() } }.getOrDefault(false) }
    val valid = draft.name.isNotBlank() && validUrl && draft.model.isNotBlank() && parsedHeaders.isSuccess && parsedParameters.isSuccess && (context.toIntOrNull() ?: 0) > 0
    EditorScaffold("API 连接", onClose, onSave = { onSave(draft.copy(name = draft.name.trim(), baseUrl = draft.baseUrl.trim(), model = draft.model.trim(), headers = parsedHeaders.getOrThrow(), parameters = parsedParameters.getOrThrow(), contextTokens = context.toInt()), if (keyChanged) key else null) }, enabled = valid, dirty = draft != connection || keyChanged || parsedHeaders.getOrNull() != connection.headers || parsedParameters.getOrNull() != connection.parameters || context != connection.contextTokens.toString()) {
        Text("适用于 OpenAI 兼容服务。请按服务商说明填写 URL、模型名称及请求头。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("常用服务商", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            providers.forEach { (label, url) ->
                AssistChip(onClick = { draft = draft.copy(baseUrl = url) }, label = { Text(label) })
            }
        }
        OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("连接名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.baseUrl, { draft = draft.copy(baseUrl = it) }, label = { Text("基础 URL 或完整端点") }, placeholder = { Text("https://example.com/v1") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = draft.baseUrl.isNotBlank() && !validUrl, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.model, { draft = draft.copy(model = it) }, label = { Text("模型 ID") }, placeholder = { Text("填写服务商提供的模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { actions.fetchModels(draft, if (keyChanged) key else null) }, enabled = validUrl && !runtime.fetchingModels) {
                if (runtime.fetchingModels) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (runtime.fetchingModels) "拉取中…" else "拉取模型列表")
            }
            if (options.isNotEmpty()) TextButton(onClick = { showModels = true }) { Text("${options.size} 个模型") }
        }
        OutlinedTextField(key, { key = it; keyChanged = true }, label = { Text("API 密钥") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "隐藏密钥" else "显示密钥") } },
            supportingText = { Text(if (!keyChanged) "不修改此项将保留已保存的密钥。" else if (key.isEmpty()) "保存后会清除已保存的密钥。" else "保存后替换此连接的密钥。") })
        TextButton(onClick = { key = ""; keyChanged = true }) { Text("清除已保存的密钥") }
        SettingSwitch("流式生成", "逐步显示模型回复。", draft.stream) { draft = draft.copy(stream = it) }
        SettingSwitch("支持视觉输入", "为多模态模型开启后，聊天可附加图片并随消息发送；纯文本模型请保持关闭。", draft.supportsVision) { draft = draft.copy(supportsVision = it) }
        OutlinedTextField(context, { context = it.filter(Char::isDigit) }, label = { Text("上下文预算（tokens）") }, supportingText = { Text("用于提示词裁剪，请按模型实际窗口设置。") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        SectionTitle("自定义请求头", "JSON 对象，值为字符串。需要时可填写服务商特定的认证头。")
        OutlinedTextField(headers, { headers = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), isError = parsedHeaders.isFailure, supportingText = { if (parsedHeaders.isFailure) Text("请求头必须是字符串值组成的 JSON 对象") })
        SectionTitle("生成参数", "例如 temperature、top_p、max_tokens。model、messages 与 stream 由连接和会话管理。")
        OutlinedTextField(parameters, { parameters = it }, modifier = Modifier.fillMaxWidth(), minLines = 6, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), isError = parsedParameters.isFailure, supportingText = { if (parsedParameters.isFailure) Text("参数必须是有效的 JSON 对象") })
        Spacer(Modifier.height(24.dp))
    }
    if (showModels) AlertDialog(onDismissRequest = { showModels = false }, title = { Text("选择模型") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(options) { id ->
                Text(id, modifier = Modifier.fillMaxWidth().clickable { draft = draft.copy(model = id); showModels = false }.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }, confirmButton = { TextButton(onClick = { showModels = false }) { Text("关闭") } })
}
