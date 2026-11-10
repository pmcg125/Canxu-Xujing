package app.xujing.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.xujing.BuildConfig
import app.xujing.core.*

@Composable
internal fun SettingsScreen(state: AppState, runtime: UiRuntime, actions: AppActions, onModels: () -> Unit, onConnection: (ApiConnection) -> Unit) {
    var deleting by remember { mutableStateOf<ApiConnection?>(null) }
    var name by remember(state.preferences.userName) { mutableStateOf(state.preferences.userName) }
    var persona by remember(state.preferences.persona) { mutableStateOf(state.preferences.persona) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(state.preferences.userName.ifBlank { "我" }, state.preferences.userAvatarPath)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(state.preferences.userName.ifBlank { "我" }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("故事与资料保存在此设备", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                }
                Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        CollapsibleSection("外观", "MD3 应用界面；角色卡的 HTML 与 CSS 保留作者样式。", initiallyExpanded = true) {
            ChoiceField("主题", state.preferences.darkMode, listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), onChoose = { actions.savePreferences(state.preferences.copy(darkMode = it ?: "system")) })
            SettingSwitch("系统动态配色", "Android 12 及以上可使用壁纸配色。", state.preferences.dynamicColor) { actions.savePreferences(state.preferences.copy(dynamicColor = it)) }
            Text("默认正文字号 ${state.preferences.fontSize.toInt()}", style = MaterialTheme.typography.bodyLarge)
            Slider(value = state.preferences.fontSize, onValueChange = { actions.savePreferences(state.preferences.copy(fontSize = it)) }, valueRange = 13f..26f, steps = 12)
        }
        CollapsibleSection("默认人设", "新会话使用这里的设定，可在每个会话中单独修改。") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name.ifBlank { "我" }, state.preferences.userAvatarPath)
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    OutlinedButton(onClick = { actions.requestImport("useravatar") }) { Text(if (state.preferences.userAvatarPath == null) "选择头像" else "更换头像") }
                    if (state.preferences.userAvatarPath != null) TextButton(onClick = { actions.savePreferences(state.preferences.copy(userAvatarPath = null)) }) { Text("移除头像") }
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("称呼") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            EditorText("人设描述", persona) { persona = it }
            OutlinedButton(onClick = { actions.savePreferences(state.preferences.copy(userName = name.ifBlank { "我" }, persona = persona)) }, enabled = name != state.preferences.userName || persona != state.preferences.persona) { Text("保存默认人设") }
        }
        CollapsibleSection("API 连接", "支持多个 OpenAI 兼容服务商；聊天与摘要可分别选择。", initiallyExpanded = true) {
            state.connections.forEach { connection ->
                OutlinedCard(onClick = { onConnection(connection) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Api, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(connection.name, style = MaterialTheme.typography.titleSmall); Text(connection.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = { deleting = connection }) { Icon(Icons.Outlined.DeleteOutline, "删除连接") }
                    }
                }
            }
            OutlinedButton(onClick = { onConnection(ApiConnection()) }) { Icon(Icons.Outlined.Add, null); Text("添加 API 连接") }
            ChoiceField("默认聊天连接", state.preferences.defaultConnectionId, state.connections.map { it.id to it.name }, "未设置") { actions.savePreferences(state.preferences.copy(defaultConnectionId = it)) }
        }
        CollapsibleSection("长期记忆", "本地模型或远程 Embeddings，用于长期记忆检索。") {
            OutlinedCard(onClick = onModels, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Memory, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("嵌入模型与记忆检索", style = MaterialTheme.typography.titleMedium)
                        Text(if (state.embedding.provider == "remote") "远程 Embeddings · ${state.embedding.remoteModel.ifBlank { "未配置模型" }}" else "本地模型 · ${runtime.localModels.firstOrNull { it.id == state.embedding.localModelId }?.name ?: "未配置"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
        CollapsibleSection("角色卡执行", "角色卡脚本可以读取当前会话并修改变量，只对可信来源开启。") {
            SettingSwitch("允许角色卡脚本", "仅为可信来源开启。角色卡脚本可能请求操作当前会话与剧情变量。", state.preferences.allowCardScripts) { actions.savePreferences(state.preferences.copy(allowCardScripts = it)) }
        }
        CollapsibleSection("导入与导出", "角色、世界书、预设、正则、人设、文风、聊天与 PiuPiu 备份分别保留其格式。") {
            OutlinedButton(onClick = { actions.requestImport("auto") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("导入角色或资料") }
            OutlinedButton(onClick = { actions.requestImport("backup") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FolderZip, null); Spacer(Modifier.width(8.dp)); Text("导入 PiuPiu / 残叙备份") }
            OutlinedButton(onClick = { actions.requestExport("backup") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.SaveAlt, null); Spacer(Modifier.width(8.dp)); Text("导出角色与会话备份") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.requestImport("settings") }, modifier = Modifier.weight(1f)) { Text("导入设置") }
                OutlinedButton(onClick = { actions.requestExport("settings") }, modifier = Modifier.weight(1f)) { Text("导出设置") }
            }
        }
        CollapsibleSection("关于", "版本 ${BuildConfig.VERSION_NAME}") {
            Text("故事和资料保存在此设备。模型回复由你配置的服务提供；本地嵌入模型仅用于记忆检索。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("资料格式兼容与脚本执行兼容分别演进。请保留原始备份，复杂角色卡的呈现取决于支持的脚本接口。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
    deleting?.let { ConfirmDelete("连接「${it.name}」", "使用该连接的会话需要重新选择 API。", { deleting = null }, { actions.deleteConnection(it.id) }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSettings(state: AppState, runtime: UiRuntime, actions: AppActions, onClose: () -> Unit) {
    var draft by remember(state.embedding) { mutableStateOf(state.embedding) }
    var topK by remember(state.embedding.topK) { mutableStateOf(state.embedding.topK.toString()) }
    var threshold by remember(state.embedding.threshold) { mutableStateOf(state.embedding.threshold.toString()) }
    var chunkChars by remember(state.embedding.chunkChars) { mutableStateOf(state.embedding.chunkChars.toString()) }
    var deleting by remember { mutableStateOf<LocalModelPackage?>(null) }
    val validNumbers = (topK.toIntOrNull() ?: 0) in 1..100 && (threshold.toDoubleOrNull() ?: -1.0) in 0.0..1.0 && (chunkChars.toIntOrNull() ?: 0) in 128..16000
    val currentDraft = draft.copy(topK = topK.toIntOrNull() ?: draft.topK, threshold = threshold.toDoubleOrNull() ?: draft.threshold, chunkChars = chunkChars.toIntOrNull() ?: draft.chunkChars)
    val dirty = currentDraft != state.embedding
    Scaffold(topBar = { TopAppBar(title = { Text("嵌入模型与记忆") }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }, actions = { TextButton(onClick = { actions.saveEmbedding(currentDraft) }, enabled = validNumbers && dirty) { Text("保存") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("安装包不含模型权重。本地与远程模型的向量索引分别保存；切换模型后需要重新建立对应索引。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceField("检索提供方", draft.provider, listOf("local" to "本地嵌入模型", "remote" to "自定义 Embeddings API"), onChoose = { draft = draft.copy(provider = it ?: "local") })
            if (draft.provider == "remote") {
                SectionTitle("远程 Embeddings", "复用连接的 URL、请求头和密钥，模型可与聊天模型不同。")
                ChoiceField("远程连接", draft.connectionId, state.connections.map { it.id to it.name }, "请选择已配置的连接") { draft = draft.copy(connectionId = it) }
                OutlinedTextField(draft.remoteModel, { draft = draft.copy(remoteModel = it) }, label = { Text("嵌入模型 ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("生成记忆向量时，相关聊天片段会发送到此服务。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SectionTitle("本地模型")
                ChoiceField("当前模型", draft.localModelId, runtime.localModels.map { it.id to it.name }, "尚未选择模型") { draft = draft.copy(localModelId = it) }
                Text("优先选择中文适配、体积较小的专用嵌入模型。缺少模型不影响普通聊天和剧情摘要。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            SectionTitle("已安装模型", "手动导入完整模型包，包含权重、分词器和模型配置。")
            if (runtime.localModels.isEmpty()) Text("尚未导入本地模型", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            runtime.localModels.forEach { model ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(model.name, style = MaterialTheme.typography.titleMedium)
                            Text("${model.language} · ${model.dimensions.takeIf { it > 0 }?.let { "$it 维" } ?: "维度随模型确定"} · ${model.pooling}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (model.license.isNotBlank()) Text(model.license, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { deleting = model }) { Icon(Icons.Outlined.DeleteOutline, "删除模型") }
                    }
                }
            }
            FilledTonalButton(onClick = actions::importModel, enabled = !runtime.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("导入完整模型 ZIP 包") }
            SectionTitle("模型下载来源", "优先打开镜像页面，原站可作后备。请选择兼容的 ONNX 权重，并连同分词器、配置一起打包；项目打包脚本支持断点续传。")
            ModelSourceCard("BGE small 中文", "面向中文检索的轻量嵌入模型", "https://huggingface.co/BAAI/bge-small-zh-v1.5", actions)
            ModelSourceCard("BGE small 中文 · ONNX", "社区转换版，可选 INT8 权重；配合项目提供的模型包清单导入", "https://huggingface.co/Xenova/bge-small-zh-v1.5/tree/main/onnx", actions)
            ModelSourceCard("GTE small 中文", "另一种中文嵌入模型选择", "https://huggingface.co/thenlper/gte-small-zh", actions)
            HorizontalDivider()
            SectionTitle("检索参数")
            OutlinedTextField(topK, { topK = it.filter(Char::isDigit) }, label = { Text("最多检索片段数（1–100）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(threshold, { threshold = it }, label = { Text("最低相似度（0–1）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(chunkChars, { chunkChars = it.filter(Char::isDigit) }, label = { Text("片段字符数（128–16000）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!validNumbers) Text("请检查检索参数范围。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { actions.saveEmbedding(currentDraft) }, enabled = validNumbers && dirty, modifier = Modifier.fillMaxWidth()) { Text("保存记忆配置") }
            OutlinedButton(onClick = actions::rebuildMemory, enabled = runtime.activeSessionId != null && !dirty && validNumbers && !runtime.busy && !runtime.generating, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("为当前会话重建记忆索引") }
            Text("先保存配置，再回到需要使用记忆的会话重建索引。聊天原文不会因为更换模型而被删除。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (runtime.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (runtime.status.isNotBlank()) Text(runtime.status, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }
    deleting?.let { ConfirmDelete("模型「${it.name}」", "模型文件将从本机移除，聊天原文不受影响。", { deleting = null }, { actions.deleteModel(it.id) }) }
}

@Composable
private fun ModelSourceCard(name: String, description: String, url: String, actions: AppActions) {
    OutlinedCard(onClick = { actions.openModelSource(url.replace("https://huggingface.co/", "https://hf-mirror.com/")) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(name, style = MaterialTheme.typography.titleSmall); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Outlined.OpenInNew, "打开镜像下载页面", Modifier.padding(start = 12.dp))
        }
        TextButton(onClick = { actions.openModelSource(url) }, modifier = Modifier.align(Alignment.End).padding(end = 8.dp)) { Text("打开原站") }
    }
}
