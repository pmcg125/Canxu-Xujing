package app.xujing.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xujing.core.*
import kotlinx.serialization.json.*

@Composable
internal fun SessionSettings(session:ChatSession,state:AppState,onClose:()->Unit,onSave:(ChatSession)->Unit,onModels:()->Unit) {
    var draft by remember(session.id) { mutableStateOf(session) }
    var variables by remember(session.id) { mutableStateOf(AppJson.encodeToString(JsonObject.serializer(),session.variables)) }
    var history by remember(session.id) { mutableStateOf(session.extra["maxHistoryMessages"]?.jsonPrimitive?.intOrNull?.toString() ?: "40") }
    var summaryInterval by remember(session.id) { mutableStateOf((session.extra["summaryInterval"] as? JsonPrimitive)?.intOrNull?.toString() ?: "10") }
    var summaryKeep by remember(session.id) { mutableStateOf((session.extra["summaryKeepMessages"] as? JsonPrimitive)?.intOrNull?.toString() ?: "12") }
    var fontSize by remember(session.id) { mutableFloatStateOf(session.extra["fontSize"]?.jsonPrimitive?.floatOrNull ?: state.preferences.fontSize) }
    val parsedVariables=remember(variables) { runCatching { AppJson.parseToJsonElement(variables).jsonObject } }
    val selectedStyles=draft.extra.array("styleIds").map { it.jsonPrimitive.content }
    val selectedPersona=draft.extra.text("personaId").ifBlank { null }
    EditorScaffold("会话设置",onClose,onSave={
        onSave(draft.copy(variables=parsedVariables.getOrThrow(),extra=JsonObject(draft.extra+mapOf("maxHistoryMessages" to JsonPrimitive(history.toInt()),"fontSize" to JsonPrimitive(fontSize),"summaryInterval" to JsonPrimitive(summaryInterval.toInt()),"summaryKeepMessages" to JsonPrimitive(summaryKeep.toInt())))))
    },enabled=draft.title.isNotBlank() && draft.roleIds.isNotEmpty() && parsedVariables.isSuccess && (history.toIntOrNull() ?: 0) in 1..10000 && (summaryInterval.toIntOrNull() ?: 0) in 1..1000 && (summaryKeep.toIntOrNull() ?: 0) in 2..1000) {
        OutlinedTextField(draft.title,{ draft=draft.copy(title=it) },label={ Text("会话名称") },singleLine=true,modifier=Modifier.fillMaxWidth())
        SectionTitle("参与角色")
        if(state.roles.isEmpty()) Text("请先创建或导入角色。")
        MultiSelection(state.roles.map { it.id to it.name },draft.roleIds) { draft=draft.copy(roleIds=it) }
        HorizontalDivider()
        SectionTitle("模型连接")
        ChoiceField("聊天 API",draft.connectionId,state.connections.map { it.id to it.name },"使用全局默认") { draft=draft.copy(connectionId=it) }
        ChoiceField("摘要 API",draft.summaryConnectionId,state.connections.map { it.id to it.name },"使用会话聊天连接") { draft=draft.copy(summaryConnectionId=it) }
        OutlinedTextField(history,{ history=it.filter(Char::isDigit) },label={ Text("保留近期消息数（1–10000）") },singleLine=true,modifier=Modifier.fillMaxWidth())
        HorizontalDivider()
        SectionTitle("用户人设")
        OutlinedTextField(draft.userName,{ draft=draft.copy(userName=it) },label={ Text("你的称呼") },singleLine=true,modifier=Modifier.fillMaxWidth())
        ChoiceField("从资料库选择人设",selectedPersona,state.library.filter { it.kind=="persona" }.map { it.id to it.name },"自定义人设") { id ->
            val doc=state.library.find { it.id==id }
            val obj=doc?.data as? JsonObject
            val body=obj?.get("data") as? JsonObject ?: obj
            val text=body?.text("text",body.text("description",body.text("content"))) ?: (doc?.data as? JsonPrimitive)?.contentOrNull.orEmpty()
            draft=draft.copy(persona=if(id==null) draft.persona else text,extra=JsonObject(draft.extra+("personaId" to (id?.let(::JsonPrimitive) ?: JsonNull))))
        }
        EditorText("本会话的人设",draft.persona,4) { draft=draft.copy(persona=it) }
        HorizontalDivider()
        SectionTitle("提示词资料")
        ChoiceField("提示词预设",draft.presetId,state.library.filter { it.kind=="preset" && it.enabled }.map { it.id to it.name },"使用默认编排") { draft=draft.copy(presetId=it) }
        SettingSwitch("使用预设采样参数","启用后，预设中的温度、top_p、惩罚、输出上限等有效参数会覆盖聊天连接参数；摘要仍使用摘要连接参数。",(draft.extra["applyPresetSampling"] as? JsonPrimitive)?.booleanOrNull==true) {
            draft=draft.copy(extra=JsonObject(draft.extra+("applyPresetSampling" to JsonPrimitive(it))))
        }
        SectionTitle("世界书", "选择会话绑定的世界书；角色卡内嵌世界书由兼容引擎读取。")
        MultiSelection(state.library.filter { it.kind=="worldbook" && it.enabled }.map { it.id to it.name },draft.worldbookIds) { draft=draft.copy(worldbookIds=it) }
        SectionTitle("正则")
        MultiSelection(state.library.filter { it.kind=="regex" && it.enabled }.map { it.id to it.name },draft.regexIds) { draft=draft.copy(regexIds=it) }
        SectionTitle("回复文风")
        MultiSelection(state.library.filter { it.kind=="style" && it.enabled }.map { it.id to it.name },selectedStyles) { ids -> draft=draft.copy(extra=JsonObject(draft.extra+("styleIds" to JsonArray(ids.map(::JsonPrimitive))))) }
        HorizontalDivider()
        SectionTitle("剧情记忆")
        val autoSummary=(draft.extra["autoSummaryEnabled"] as? JsonPrimitive)?.booleanOrNull==true
        SettingSwitch("自动整理剧情摘要","发送新消息前，将积累的较早剧情分批整理，近期原文继续保留。会额外调用摘要 API。",autoSummary) {
            draft=draft.copy(extra=JsonObject(draft.extra+("autoSummaryEnabled" to JsonPrimitive(it))))
        }
        if(autoSummary) {
            OutlinedTextField(summaryInterval,{ summaryInterval=it.filter(Char::isDigit) },label={ Text("积累多少条较早角色回复后摘要（1–1000）") },singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(summaryKeep,{ summaryKeep=it.filter(Char::isDigit) },label={ Text("至少保留多少条近期原文（2–1000）") },singleLine=true,modifier=Modifier.fillMaxWidth())
        }
        SettingSwitch("启用长期语义记忆","使用设置中选定的本地或远程嵌入模型。",draft.memoryEnabled) { draft=draft.copy(memoryEnabled=it) }
        OutlinedButton(onClick=onModels) { Icon(Icons.Outlined.Memory,null); Spacer(Modifier.width(8.dp)); Text("嵌入模型与检索设置") }
        EditorText("剧情摘要",draft.summary,5) { draft=draft.copy(summary=it) }
        OutlinedTextField(variables,{ variables=it },label={ Text("剧情变量 JSON") },minLines=5,isError=parsedVariables.isFailure,modifier=Modifier.fillMaxWidth(),supportingText={ if(parsedVariables.isFailure) Text("需要有效的 JSON 对象") })
        HorizontalDivider()
        SectionTitle("阅读外观")
        ChoiceField("显示模式",draft.readingMode,listOf("reading" to "阅读模式","bubble" to "气泡模式")) { draft=draft.copy(readingMode=it ?: "reading") }
        Text("字号 ${fontSize.toInt()}")
        Slider(value=fontSize,onValueChange={ fontSize=it },valueRange=13f..28f,steps=14)
        ChoiceField("背景",draft.extra.text("background","default"),listOf("default" to "跟随主题","paper" to "暖纸","mist" to "薄雾","rose" to "浅玫瑰")) { value -> draft=draft.copy(extra=JsonObject(draft.extra+("background" to JsonPrimitive(value ?: "default")))) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun MultiSelection(options:List<Pair<String,String>>,selected:List<String>,onChange:(List<String>)->Unit) {
    if(options.isEmpty()) Text("暂无可选资料",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    options.forEach { (id,name) -> Row(Modifier.fillMaxWidth()) {
        Checkbox(checked=id in selected,onCheckedChange={ onChange(if(it) (selected+id).distinct() else selected-id) })
        Text(name,modifier=Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodyMedium)
    } }
}
