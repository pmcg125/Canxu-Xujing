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
    var memoryQuery by remember(session.id) { mutableStateOf(((session.extra["memoryQueryMessages"] as? JsonPrimitive)?.intOrNull ?: 3).coerceIn(1,30).toString()) }
    var summaryInterval by remember(session.id) { mutableStateOf((session.extra["summaryInterval"] as? JsonPrimitive)?.intOrNull?.toString() ?: "10") }
    var summaryKeep by remember(session.id) { mutableStateOf((session.extra["summaryKeepMessages"] as? JsonPrimitive)?.intOrNull?.toString() ?: "12") }
    var fontSize by remember(session.id) { mutableFloatStateOf(session.extra["fontSize"]?.jsonPrimitive?.floatOrNull ?: state.preferences.fontSize) }
    // Show the effective values across all supported containers and aliases. Unedited data is left untouched.
    val worldInfoOriginal=remember(session.id) { effectiveWorldInfo(session.extra) }
    val worldInfoEdited=remember(session.id) { mutableStateMapOf<String,Boolean>() }
    val worldInfoWritable=remember(session.id) { worldInfoContainers.any { session.extra[it]==null || session.extra[it] is JsonObject } }
    var scanDepthText by remember(session.id) { mutableStateOf(worldInfoOriginal.numberText("scanDepth")) }
    var maxScanDepthText by remember(session.id) { mutableStateOf(worldInfoOriginal.numberText("maxScanDepth")) }
    var minActivationsText by remember(session.id) { mutableStateOf(worldInfoOriginal.numberText("minActivations")) }
    var maxRecursionStepsText by remember(session.id) { mutableStateOf(worldInfoOriginal.numberText("maxRecursionSteps")) }
    var budgetPercentText by remember(session.id) { mutableStateOf(worldInfoOriginal.numberText("budgetPercent")) }
    var budgetCapText by remember(session.id) { mutableStateOf(worldInfoOriginal.numberText("budgetCap")) }
    var recursiveText by remember(session.id) { mutableStateOf<String?>(worldInfoOriginal.flagText("recursive")) }
    var groupScoringText by remember(session.id) { mutableStateOf<String?>(worldInfoOriginal.flagText("useGroupScoring")) }
    val parsedVariables=remember(variables) { runCatching { AppJson.parseToJsonElement(variables).jsonObject } }
    val selectedStyles=draft.extra.array("styleIds").map { it.jsonPrimitive.content }
    val selectedPersona=draft.extra.text("personaId").ifBlank { null }
    val worldInfoValid=numberFieldValid(scanDepthText,worldInfoOriginal,"scanDepth",0..1000) && numberFieldValid(maxScanDepthText,worldInfoOriginal,"maxScanDepth",0..1000) && numberFieldValid(minActivationsText,worldInfoOriginal,"minActivations",0..1000) && numberFieldValid(maxRecursionStepsText,worldInfoOriginal,"maxRecursionSteps",0..32) && numberFieldValid(budgetPercentText,worldInfoOriginal,"budgetPercent",0..100,decimal=true) && numberFieldValid(budgetCapText,worldInfoOriginal,"budgetCap",0..1000000)
    EditorScaffold("会话设置",onClose,onSave={
        val worldInfoExtra=worldInfoMerge(draft.extra,worldInfoEdited.keys,mapOf<String,String?>(
            "scanDepth" to scanDepthText,"maxScanDepth" to maxScanDepthText,"minActivations" to minActivationsText,
            "maxRecursionSteps" to maxRecursionStepsText,"budgetPercent" to budgetPercentText,"budgetCap" to budgetCapText,
            "recursive" to recursiveText,"useGroupScoring" to groupScoringText,
        ))
        onSave(draft.copy(variables=parsedVariables.getOrThrow(),extra=JsonObject(draft.extra+mapOf<String,JsonElement>("maxHistoryMessages" to JsonPrimitive(history.toInt()),"memoryQueryMessages" to JsonPrimitive(memoryQuery.toInt()),"fontSize" to JsonPrimitive(fontSize),"summaryInterval" to JsonPrimitive(summaryInterval.toInt()),"summaryKeepMessages" to JsonPrimitive(summaryKeep.toInt()))+worldInfoExtra)))
    },enabled=draft.title.isNotBlank() && draft.roleIds.isNotEmpty() && parsedVariables.isSuccess && (history.toIntOrNull() ?: 0) in 1..10000 && (memoryQuery.toIntOrNull() ?: 0) in 1..30 && (summaryInterval.toIntOrNull() ?: 0) in 1..1000 && (summaryKeep.toIntOrNull() ?: 0) in 2..1000 && worldInfoValid && (worldInfoWritable || worldInfoEdited.isEmpty())) {
        OutlinedTextField(draft.title,{ draft=draft.copy(title=it) },label={ Text("会话名称") },singleLine=true,modifier=Modifier.fillMaxWidth())
        CollapsibleSection("参与角色", initiallyExpanded = true) {
            if(state.roles.isEmpty()) Text("请先创建或导入角色。")
            MultiSelection(state.roles.map { it.id to it.name },draft.roleIds) { draft=draft.copy(roleIds=it) }
        }
        CollapsibleSection("模型与生成", initiallyExpanded = true) {
            ChoiceField("聊天 API",draft.connectionId,state.connections.map { it.id to it.name },"使用全局默认") { draft=draft.copy(connectionId=it) }
            ChoiceField("摘要 API",draft.summaryConnectionId,state.connections.map { it.id to it.name },"使用会话聊天连接") { draft=draft.copy(summaryConnectionId=it) }
            OutlinedTextField(history,{ history=it.filter(Char::isDigit) },label={ Text("保留近期消息数（1–10000）") },singleLine=true,modifier=Modifier.fillMaxWidth())
        }
        CollapsibleSection("人设") {
            OutlinedTextField(draft.userName,{ draft=draft.copy(userName=it) },label={ Text("你的称呼") },singleLine=true,modifier=Modifier.fillMaxWidth())
            ChoiceField("从资料库选择人设",selectedPersona,state.library.filter { it.kind=="persona" }.map { it.id to it.name },"自定义人设") { id ->
                val doc=state.library.find { it.id==id }
                val obj=doc?.data as? JsonObject
                val body=obj?.get("data") as? JsonObject ?: obj
                val text=body?.text("text",body.text("description",body.text("content"))) ?: (doc?.data as? JsonPrimitive)?.contentOrNull.orEmpty()
                draft=draft.copy(persona=if(id==null) draft.persona else text,extra=JsonObject(draft.extra+("personaId" to (id?.let(::JsonPrimitive) ?: JsonNull))))
            }
            EditorText("本会话的人设",draft.persona,4) { draft=draft.copy(persona=it) }
        }
        CollapsibleSection("提示词资料") {
            SettingSwitch("运行角色独立脚本","为此会话加载角色卡中的 TavernHelper 脚本；同时需要在全局设置允许角色卡脚本。",(draft.extra["sessionScriptsEnabled"] as? JsonPrimitive)?.booleanOrNull==true) {
                draft=draft.copy(extra=JsonObject(draft.extra+("sessionScriptsEnabled" to JsonPrimitive(it))))
            }
            val sessionScriptsOn=(draft.extra["sessionScriptsEnabled"] as? JsonPrimitive)?.booleanOrNull==true
            val allScripts=app.xujing.core.compat.SessionScripts.collect(state,draft,includeDisabled=true).size
            val enabledScripts=app.xujing.core.compat.SessionScripts.collect(state,draft).size
            Text(if(sessionScriptsOn) "本会话启用 $enabledScripts / $allScripts 个脚本" else "角色卡内共 $allScripts 个脚本，本会话未启用",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(sessionScriptsOn) {
                val disabled=draft.extra.array("disabledSessionScriptIds").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                app.xujing.core.compat.SessionScripts.collect(state,draft,includeDisabled=true).forEach { script ->
                    SettingSwitch(script.name,"在当前会话中启用",script.id !in disabled) { enabled ->
                        val next=if(enabled) disabled-script.id else disabled+script.id
                        draft=draft.copy(extra=JsonObject(draft.extra+("disabledSessionScriptIds" to JsonArray(next.map(::JsonPrimitive)))))
                    }
                }
            }
            ChoiceField("提示词预设",draft.presetId,state.library.filter { it.kind=="preset" && it.enabled }.map { it.id to it.name },"使用默认编排") { draft=draft.copy(presetId=it) }
            SettingSwitch("使用预设采样参数","启用后，预设中的温度、top_p、惩罚、输出上限等有效参数会覆盖聊天连接参数；摘要仍使用摘要连接参数。",(draft.extra["applyPresetSampling"] as? JsonPrimitive)?.booleanOrNull==true) {
                draft=draft.copy(extra=JsonObject(draft.extra+("applyPresetSampling" to JsonPrimitive(it))))
            }
            SectionTitle("世界书", "选择会话绑定的世界书；角色卡内嵌世界书由兼容引擎读取。")
            MultiSelection(state.library.filter { it.kind=="worldbook" && it.enabled }.map { it.id to it.name },draft.worldbookIds) { draft=draft.copy(worldbookIds=it) }
            CollapsibleSection("世界书高级设置","扫描深度、递归、预算与组评分。留空表示不写入该键，继续继承。") {
                Text("继承优先级：条目显式值 → 书级 scan_depth → 会话高级设置 → 内置默认。留空等于删除该键（继承），显式 0 不等于“未设置”。这些值写入本会话的 worldInfo；条目和书级配置的归属不变。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                WorldbookNumberField("全局扫描深度",scanDepthText,worldInfoOriginal,"scanDepth","0 表示不扫描聊天历史；留空沿用内置默认。",0..1000,{ scanDepthText=it; worldInfoEdited["scanDepth"]=true },{ scanDepthText=""; worldInfoEdited["scanDepth"]=true })
                WorldbookNumberField("最大扫描深度",maxScanDepthText,worldInfoOriginal,"maxScanDepth","用于最少激活条目不足时的扩扫。正值是包含在内的末端索引，按全局扫描深度计算扩大量，目标为该数值加 1 条；留空或 0 可扩至现有历史。书级深度使用相同扩大量，条目显式深度不扩大；最多扫描 1000 条。",0..1000,{ maxScanDepthText=it; worldInfoEdited["maxScanDepth"]=true },{ maxScanDepthText=""; worldInfoEdited["maxScanDepth"]=true })
                WorldbookNumberField("最少激活条目",minActivationsText,worldInfoOriginal,"minActivations","0 表示不因条目数量不足而扩大扫描窗口。",0..1000,{ minActivationsText=it; worldInfoEdited["minActivations"]=true },{ minActivationsText=""; worldInfoEdited["minActivations"]=true })
                WorldbookNumberField("递归步数",maxRecursionStepsText,worldInfoOriginal,"maxRecursionSteps","已激活条目可继续触发新匹配的轮数上限；0 表示不设固定轮数上限，仍受安全限制。启用递归且为 0 时可进行最少激活扩扫。",0..32,{ maxRecursionStepsText=it; worldInfoEdited["maxRecursionSteps"]=true },{ maxRecursionStepsText=""; worldInfoEdited["maxRecursionSteps"]=true })
                WorldbookNumberField("预算比例",budgetPercentText,worldInfoOriginal,"budgetPercent","占聊天上下文的百分比；0 表示不使用比例预算。",0..100,{ budgetPercentText=it; worldInfoEdited["budgetPercent"]=true },{ budgetPercentText=""; worldInfoEdited["budgetPercent"]=true },decimal=true)
                WorldbookNumberField("预算上限",budgetCapText,worldInfoOriginal,"budgetCap","世界书估算 token 的固定上限；0 表示不设固定上限，比例预算仍生效。",0..1000000,{ budgetCapText=it; worldInfoEdited["budgetCap"]=true },{ budgetCapText=""; worldInfoEdited["budgetCap"]=true })
                SettingSwitch("递归扫描","允许已激活条目触发新的关键词匹配；关闭后只做首轮激活。",recursiveText?.toBoolean() ?: true) { on -> recursiveText=on.toString(); worldInfoEdited["recursive"]=true }
                if(recursiveText!=null || worldInfoOriginal.containsKey("recursive")) TextButton(onClick={ recursiveText=null; worldInfoEdited["recursive"]=true }) { Text("恢复默认（递归开启）") }
                SettingSwitch("互斥组关键词评分","同一互斥组内按主/副关键词命中情况评分选择，而不是只看权重随机。",groupScoringText?.toBoolean() ?: false) { on -> groupScoringText=on.toString(); worldInfoEdited["useGroupScoring"]=true }
                if(groupScoringText!=null || worldInfoOriginal.containsKey("useGroupScoring")) TextButton(onClick={ groupScoringText=null; worldInfoEdited["useGroupScoring"]=true }) { Text("恢复默认（评分关闭）") }
                Text("世界书最终激活条目和排除原因可在本轮提示词详情中核对；普通聊天正文不显示这些调试信息。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("修改某项时，会清除该项在其他兼容配置中的同义键并保存新值；未修改项和其他字段保持原样。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(!worldInfoWritable) Text("导入的四个世界书配置均不是对象，无法安全保存高级设置；请先修复原始数据。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            }
            SectionTitle("正则")
            MultiSelection(state.library.filter { it.kind=="regex" && it.enabled }.map { it.id to it.name },draft.regexIds) { draft=draft.copy(regexIds=it) }
            SectionTitle("回复文风")
            MultiSelection(state.library.filter { it.kind=="style" && it.enabled }.map { it.id to it.name },selectedStyles) { ids -> draft=draft.copy(extra=JsonObject(draft.extra+("styleIds" to JsonArray(ids.map(::JsonPrimitive))))) }
        }
        CollapsibleSection("剧情记忆") {
            val autoSummary=(draft.extra["autoSummaryEnabled"] as? JsonPrimitive)?.booleanOrNull==true
            SettingSwitch("自动整理剧情摘要","发送新消息前，将积累的较早剧情分批整理，近期原文继续保留。会额外调用摘要 API。",autoSummary) {
                draft=draft.copy(extra=JsonObject(draft.extra+("autoSummaryEnabled" to JsonPrimitive(it))))
            }
            if(autoSummary) {
                OutlinedTextField(summaryInterval,{ summaryInterval=it.filter(Char::isDigit) },label={ Text("积累多少条较早角色回复后摘要（1–1000）") },singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(summaryKeep,{ summaryKeep=it.filter(Char::isDigit) },label={ Text("至少保留多少条近期原文（2–1000）") },singleLine=true,modifier=Modifier.fillMaxWidth())
            }
            SettingSwitch("启用长期语义记忆","使用设置中选定的本地或远程嵌入模型。",draft.memoryEnabled) { draft=draft.copy(memoryEnabled=it) }
            OutlinedTextField(memoryQuery,{ memoryQuery=it.filter(Char::isDigit) },label={ Text("用最近多少条消息检索记忆（1–30）") },singleLine=true,modifier=Modifier.fillMaxWidth(),isError=(memoryQuery.toIntOrNull() ?: 0) !in 1..30,supportingText={ Text("默认为 3 条；查询过长时会受模型输入长度限制。") })
            OutlinedButton(onClick=onModels) { Icon(Icons.Outlined.Memory,null); Spacer(Modifier.width(8.dp)); Text("嵌入模型与检索设置") }
            EditorText("剧情摘要",draft.summary,5) { draft=draft.copy(summary=it) }
            OutlinedTextField(variables,{ variables=it },label={ Text("剧情变量 JSON") },isError=parsedVariables.isFailure,modifier=Modifier.fillMaxWidth().height(240.dp),supportingText={ if(parsedVariables.isFailure) Text("需要有效的 JSON 对象") })
        }
        CollapsibleSection("阅读外观") {
            Text("背景图片可在聊天菜单中选择。",style=MaterialTheme.typography.bodySmall)
            if(draft.extra.text("backgroundImage").isNotBlank()) TextButton(onClick={ draft=draft.copy(extra=JsonObject(draft.extra-"backgroundImage")) }) { Text("移除背景图片") }
            val blur=((draft.extra["backgroundBlur"] as? JsonPrimitive)?.floatOrNull ?: 0f).coerceIn(0f,24f)
            val dim=((draft.extra["backgroundDim"] as? JsonPrimitive)?.floatOrNull ?: 0f).coerceIn(0f,0.7f)
            Text("背景模糊 ${blur.toInt()}")
            Slider(value=blur,onValueChange={ draft=draft.copy(extra=JsonObject(draft.extra+("backgroundBlur" to JsonPrimitive(it)))) },valueRange=0f..24f)
            Text("背景暗化 ${(dim*100).toInt()}%")
            Slider(value=dim,onValueChange={ draft=draft.copy(extra=JsonObject(draft.extra+("backgroundDim" to JsonPrimitive(it)))) },valueRange=0f..0.7f)
            SettingSwitch("主题色跟随背景","从背景图的主打色（或预设底色）提取强调色，只影响聊天界面。",(draft.extra["backgroundTint"] as? JsonPrimitive)?.booleanOrNull != false) {
                draft=draft.copy(extra=JsonObject(draft.extra+("backgroundTint" to JsonPrimitive(it))))
            }
            val opacity=((draft.extra["bubbleOpacity"] as? JsonPrimitive)?.floatOrNull ?: 1f).coerceIn(0.1f,1f)
            Text("气泡不透明度 ${(opacity*100).toInt()}%")
            Slider(value=opacity,onValueChange={ draft=draft.copy(extra=JsonObject(draft.extra+("bubbleOpacity" to JsonPrimitive(it)))) },valueRange=0.1f..1f)
            ChoiceField("气泡样式",draft.extra.text("bubbleStyle","rounded"),listOf("rounded" to "圆润","square" to "方角")) { value -> draft=draft.copy(extra=JsonObject(draft.extra+("bubbleStyle" to JsonPrimitive(value ?: "rounded")))) }
            ChoiceField("显示模式",draft.readingMode,listOf("reading" to "阅读模式","bubble" to "气泡模式")) { draft=draft.copy(readingMode=it ?: "reading") }
            Text("字号 ${fontSize.toInt()}")
            Slider(value=fontSize,onValueChange={ fontSize=it },valueRange=13f..28f,steps=14)
            ChoiceField("背景",draft.extra.text("background","default"),listOf("default" to "跟随主题","paper" to "暖纸","mist" to "薄雾","rose" to "浅玫瑰","celadon" to "青瓷","forest" to "森野","cream" to "奶油","dusk" to "暮色","aurora" to "极光")) { value -> draft=draft.copy(extra=JsonObject(draft.extra+("background" to JsonPrimitive(value ?: "default")))) }
        }
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

private val worldInfoBooleanKeys=setOf("recursive","useGroupScoring")
// Alias order matches WorldbookEngine; changing one setting clears only its own aliases.
private val worldInfoAliases=linkedMapOf(
    "scanDepth" to listOf("depth","scanDepth","world_info_depth","scan_depth"),
    "maxScanDepth" to listOf("maxScanDepth","max_scan_depth","maxDepth","max_depth"),
    "minActivations" to listOf("minActivations","min_activations"),
    "maxRecursionSteps" to listOf("maxRecursionSteps","max_recursion_steps","max_recursion"),
    "budgetPercent" to listOf("budgetPercent","budget_percent","world_info_budget"),
    "budgetCap" to listOf("budgetCap","budget_cap","world_info_budget_cap"),
    "recursive" to listOf("recursive","recursion","world_info_recursive"),
    "useGroupScoring" to listOf("useGroupScoring","use_group_scoring"),
)
private val worldInfoContainers=listOf("worldInfo","world_info","worldbookSettings","worldbook_settings")

private fun effectiveWorldInfo(extra:JsonObject):JsonObject {
    val merged=linkedMapOf<String,JsonElement>()
    worldInfoContainers.forEach { name -> (extra[name] as? JsonObject)?.let { merged.putAll(it) } }
    val result=mutableMapOf<String,JsonElement>()
    worldInfoAliases.forEach { (key,aliases) ->
        val values=aliases.mapNotNull { merged[it] }
        val selected=when(key) {
            // scanDepthOverride stops on the first non-null primitive, even if it is invalid.
            "scanDepth","maxScanDepth" -> values.firstOrNull { it is JsonPrimitive && it !is JsonNull }
            in worldInfoBooleanKeys -> values.firstOrNull { value ->
                val p=value as? JsonPrimitive
                p != null && (p.booleanOrNull!=null || p.intOrNull!=null || p.content.lowercase() in setOf("true","false","yes","no","on","off"))
            }
            else -> values.firstOrNull { (it as? JsonPrimitive)?.doubleOrNull!=null }
        } ?: values.firstOrNull()
        if(selected!=null) result[key]=selected
    }
    return JsonObject(result)
}

/** Compact text for an existing number; blank means the key is absent (inherit). */
private fun JsonObject.numberText(key:String):String = when(val p=this[key] as? JsonPrimitive) {
    null -> ""
    else -> p.doubleOrNull?.takeIf { it.isFinite() }?.let { if(it==it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: ""
}

/** Explicit boolean text for an existing key, using the same leniency as the engine (1/0, yes/on). */
private fun JsonObject.flagText(key:String):String? {
    val p=this[key] as? JsonPrimitive ?: return null
    val value=p.booleanOrNull ?: p.intOrNull?.let { it!=0 } ?: when(p.content.lowercase()) { "true","yes","on" -> true; "false","no","off" -> false; else -> null }
    return value?.toString()
}

private fun intInRange(text:String,range:IntRange):Boolean = text.isBlank() || text.toIntOrNull()?.let { it in range }==true
private fun decimalInRange(text:String,range:IntRange):Boolean = text.isBlank() || text.toDoubleOrNull()?.let { it>=range.first && it<=range.last }==true
/** An untouched imported value must never block saving the session, even if it is outside the editing range. */
private fun numberFieldValid(value:String,original:JsonObject,key:String,range:IntRange,decimal:Boolean=false):Boolean =
    value==original.numberText(key) || if(decimal) decimalInRange(value,range) else intInRange(value,range)
private fun numberValue(text:String):JsonElement = text.toDoubleOrNull()?.let { d -> if(d==d.toLong().toDouble()) JsonPrimitive(d.toLong()) else JsonPrimitive(d) } ?: JsonPrimitive(0)

/**
 * Only edited settings are normalized. Other fields and their original containers are preserved.
 * Blank/null explicitly removes all aliases so a hidden older value cannot override inheritance.
 */
private fun worldInfoMerge(extra:JsonObject,edited:Set<String>,edits:Map<String,String?>):Map<String,JsonElement> {
    val original=effectiveWorldInfo(extra)
    val changedKeys=edited.filterTo(mutableSetOf()) { key ->
        val value=edits[key]
        val initial=if(key in worldInfoBooleanKeys) original.flagText(key) else original.numberText(key)
        value.isNullOrBlank() || value!=initial
    }
    if(changedKeys.isEmpty()) return emptyMap()
    val target=worldInfoContainers.firstOrNull { extra[it]==null || extra[it] is JsonObject }
        ?: return emptyMap()
    val changed=mutableMapOf<String,JsonElement>()
    worldInfoContainers.forEach { name ->
        val original=extra[name] as? JsonObject ?: return@forEach
        val out=original.toMutableMap()
        changedKeys.forEach { key -> worldInfoAliases[key].orEmpty().forEach { alias -> out.remove(alias) } }
        if(out!=original) changed[name]=JsonObject(out)
    }
    val targetObject=(changed[target] ?: extra[target]) as? JsonObject ?: JsonObject(emptyMap())
    val out=targetObject.toMutableMap()
    changedKeys.forEach { key -> edits[key]?.takeUnless(String::isBlank)?.let { value ->
        out[key]=if(key in worldInfoBooleanKeys) JsonPrimitive(value.toBoolean()) else numberValue(value)
    } }
    if(out!=targetObject) changed[target]=JsonObject(out)
    return changed
}

@Composable
private fun WorldbookNumberField(label:String,value:String,original:JsonObject,key:String,hint:String,range:IntRange,onValue:(String)->Unit,onRestore:()->Unit,decimal:Boolean=false) {
    val valid=numberFieldValid(value,original,key,range,decimal)
    OutlinedTextField(value,{ onValue(it.take(32)) },
        label={ Text("$label（${range.first}–${range.last}）") },singleLine=true,isError=!valid,modifier=Modifier.fillMaxWidth(),
        supportingText={ Text(if(valid) hint else "请输入 ${range.first}–${range.last} 的${if(decimal) "数值" else "整数"}；留空表示继承。") })
    if(value.isNotBlank() || original.containsKey(key)) TextButton(onClick=onRestore) { Text("恢复继承/默认") }
}
