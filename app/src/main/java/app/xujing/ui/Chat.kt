package app.xujing.ui

import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xujing.core.*
import app.xujing.runtime.CardMessage
import app.xujing.runtime.SessionHooks
import app.xujing.core.engine.PromptEngine
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(state: AppState, runtime: UiRuntime, actions: AppActions, session: ChatSession) {
    var settings by rememberSaveable(session.id) { mutableStateOf(false) }
    var models by rememberSaveable(session.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var searching by rememberSaveable(session.id) { mutableStateOf(false) }
    var search by rememberSaveable(session.id) { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val backgroundPath=session.extra.text("backgroundImage")
    val backgroundImage by produceState<ImageBitmap?>(null,backgroundPath) {
        value=withContext(Dispatchers.IO) { runCatching {
            if(backgroundPath.isBlank()) return@runCatching null
            val file=File(context.filesDir,backgroundPath).canonicalFile
            require(file.toPath().startsWith(context.filesDir.canonicalFile.toPath()))
            val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeFile(file.path,options)
            options.inSampleSize=1
            while(maxOf(options.outWidth,options.outHeight)/options.inSampleSize>1920) options.inSampleSize*=2
            options.inJustDecodeBounds=false
            android.graphics.BitmapFactory.decodeFile(file.path,options)?.asImageBitmap()
        }.getOrNull() }
    }
    var input by rememberSaveable(session.id) { mutableStateOf("") }
    var target by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var deleting by remember { mutableStateOf<ChatMessage?>(null) }
    var rewinding by remember { mutableStateOf<ChatMessage?>(null) }
    val listState = rememberLazyListState()
    val members = state.roles.filter { it.id in session.roleIds }
    val scriptButtons = SessionHooks.buttons.filter { it.sessionId == session.id }
    val reading = session.readingMode != "bubble"
    val fontSize = (session.extra["fontSize"] as? JsonPrimitive)?.floatOrNull ?: state.preferences.fontSize
    val background = when (session.extra.text("background")) {
        "paper" -> Color(0xFFE2B666).copy(alpha = .09f).compositeOver(MaterialTheme.colorScheme.surface)
        "mist" -> Color(0xFF5DABA5).copy(alpha = .09f).compositeOver(MaterialTheme.colorScheme.surface)
        "rose" -> Color(0xFFD18DA5).copy(alpha = .09f).compositeOver(MaterialTheme.colorScheme.surface)
        else -> MaterialTheme.colorScheme.surface
    }
    BackHandler(enabled = settings || models) { if (models) models = false else settings = false }
    if (models) { ModelSettings(state, runtime, actions, onClose = { models = false }); return }
    if (settings) {
        SessionSettings(session, state, onClose = { settings = false }, onSave = { actions.updateSession(it.copy(messages = session.messages)); settings = false }, onModels = { models = true })
        return
    }
    LaunchedEffect(session.id) { if (session.messages.isNotEmpty()) listState.scrollToItem(session.messages.lastIndex) }
    LaunchedEffect(session.messages.size, session.messages.lastOrNull()?.content?.length) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (session.messages.isNotEmpty() && !listState.isScrollInProgress && lastVisible >= session.messages.lastIndex - 2) listState.scrollToItem(session.messages.lastIndex)
    }
    LaunchedEffect(session.roleIds) { if (target !in session.roleIds) target = null }
    Box(Modifier.fillMaxSize().background(background)) {
    backgroundImage?.let { Image(it,null,Modifier.matchParentSize(),contentScale=ContentScale.Crop,alpha=0.3f) }
    Scaffold(
        containerColor = if(backgroundImage==null) background else Color.Transparent,
        topBar = {
            TopAppBar(title = {
                Column {
                    Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(if (runtime.generating) "正在编织故事…" else members.joinToString(" · ") { it.name }.ifBlank { "空白会话" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, navigationIcon = { IconButton(onClick = { actions.openSession(null) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回会话列表") } }, actions = {
                IconButton(onClick = { actions.updateSession(session.copy(readingMode = if (reading) "bubble" else "reading")) }) { Icon(if (reading) Icons.Outlined.Forum else Icons.Outlined.MenuBook, if (reading) "切换为气泡模式" else "切换为阅读模式") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "会话菜单") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("搜索聊天记录") }, onClick = { menu=false; searching=true })
                        DropdownMenuItem(text = { Text("选择背景图片") }, enabled=!runtime.generating,onClick = { menu=false; actions.requestImport("background:${session.id}") })
                        DropdownMenuItem(text = { Text("会话设置") }, leadingIcon = { Icon(Icons.Outlined.Tune, null) }, onClick = { menu = false; settings = true })
                        DropdownMenuItem(text = { Text("查看提示词详情") }, onClick = { menu = false; actions.showPrompt() })
                        DropdownMenuItem(text = { Text("生成剧情摘要") }, enabled = !runtime.generating && session.messages.isNotEmpty(), onClick = { menu = false; actions.summarize() })
                        DropdownMenuItem(text = { Text("重建长期记忆索引") }, enabled = !runtime.busy && !runtime.generating, onClick = { menu = false; actions.rebuildMemory() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("导入聊天记录") }, onClick = { menu = false; actions.requestImport("chat") })
                        DropdownMenuItem(text = { Text("导出聊天记录") }, onClick = { menu = false; actions.requestExport("chat", session.id) })
                    }
                }
            })
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (scriptButtons.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        items(scriptButtons, key = { it.scriptId + ":" + it.name }) { button ->
                            SuggestionChip(
                                onClick = { SessionHooks.runButton(button.sessionId, button.scriptId, button.name) },
                                enabled = button.enabled && !runtime.generating && !runtime.busy,
                                label = { Text(button.scriptName + " · " + button.label, maxLines = 1) }
                            )
                        }
                    }
                    if (members.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        item { FilterChip(selected = target == null, onClick = { target = null }, label = { Text("轮流发言") }) }
                        items(members, key = { it.id }) { role -> FilterChip(selected = target == role.id, onClick = { target = role.id }, label = { Text(role.name) }) }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f), placeholder = { Text(if (target == null) "写下行动、对白或剧情方向…" else "发送给 ${members.firstOrNull { it.id == target }?.name.orEmpty()}…") }, maxLines = 6, shape = RoundedCornerShape(24.dp))
                        Spacer(Modifier.width(8.dp))
                        if (runtime.generating) FilledIconButton(onClick = actions::stop, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Stop, "停止生成") }
                        else FilledIconButton(onClick = { val text = input.trim(); if (text.isNotBlank()) { actions.send(text, target); input = "" } }, enabled = input.isNotBlank() && !runtime.busy, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.Send, "发送") }
                    }
                    if(runtime.suggestionSessionId==session.id && runtime.suggestions.isNotEmpty()) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(runtime.suggestions) { suggestion -> SuggestionChip(onClick={ input=suggestion; actions.clearSuggestions() },label={ Text(suggestion,maxLines=2,modifier=Modifier.widthIn(max=260.dp)) }) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row {
                            TextButton(onClick = actions::regenerate, enabled = !runtime.generating && session.messages.any { it.role == "assistant" }) { Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("重生成", style = MaterialTheme.typography.labelMedium) }
                            TextButton(onClick = actions::continueReply, enabled = !runtime.generating && session.messages.lastOrNull()?.role == "assistant") { Icon(Icons.Outlined.EditNote, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("续写", style = MaterialTheme.typography.labelMedium) }
                            TextButton(onClick=actions::suggestReplies,enabled=!runtime.generating && !runtime.busy && !runtime.suggesting) { Text(if(runtime.suggesting) "建议生成中…" else "回复建议",style=MaterialTheme.typography.labelMedium) }
                        }
                        Text(if (reading) "阅读模式" else "气泡模式", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (runtime.generating || runtime.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (session.messages.isEmpty()) EmptyState(Icons.Outlined.AutoStories, "故事由你开启", "发送第一句话，也可以在会话设置中调整人设、世界书和 API。", "会话设置", { settings = true })
            else LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = if (reading) 20.dp else 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(if (reading) 24.dp else 12.dp)) {
                items(session.messages, key = { it.id }) { message ->
                    MessageItem(message, session, state, reading, fontSize, streaming = runtime.generating && session.messages.lastOrNull()?.id == message.id,
                        onEdit = { editing = message }, onDelete = { deleting = message }, onRewind = { rewinding = message },
                        canGenerate = !runtime.busy && !runtime.generating && message.role == "assistant" && session.messages.lastOrNull()?.id == message.id,
                        canEdit = !runtime.busy && !runtime.generating,
                        onContinue = actions::continueReply, onRegenerate = actions::regenerate,
                        onSwipe = { actions.selectSwipe(message.id, it) },
                        onVariables = { variables -> actions.updateSession(session.copy(variables = variables, messages = session.messages.map { if (it.id == message.id) it.copy(variables = variables) else it })) },
                        onSend = { actions.send(it, target) })
                }
            }
        }
    }
    }
    if(searching) AlertDialog(onDismissRequest={ searching=false },title={ Text("搜索聊天记录") },text={
        Column {
            OutlinedTextField(search,{ search=it },label={ Text("关键词") },singleLine=true,modifier=Modifier.fillMaxWidth())
            val matches=remember(search,session.messages) { if(search.isBlank()) emptyList() else session.messages.filter { it.content.contains(search.trim(),ignoreCase=true) } }
            Text(if(search.isBlank()) "输入关键词查找当前会话" else "找到 ${matches.size} 条消息",modifier=Modifier.padding(vertical=8.dp))
            LazyColumn(Modifier.heightIn(max=360.dp)) { items(matches,key={ it.id }) { message ->
                Column(Modifier.fillMaxWidth().clickable {
                    searching=false
                    val index=session.messages.indexOfFirst { it.id==message.id }
                    if(index>=0) scope.launch { listState.scrollToItem(index) }
                }.padding(vertical=10.dp)) {
                    Text(message.name ?: message.role,style=MaterialTheme.typography.labelMedium)
                    val start=(message.content.indexOf(search.trim(),ignoreCase=true)-40).coerceAtLeast(0)
                    Text(message.content.drop(start).take(200),maxLines=4,overflow=TextOverflow.Ellipsis)
                }
            } }
        }
    },confirmButton={ TextButton(onClick={ searching=false }) { Text("关闭") } })
    editing?.let { message -> MessageEditor(message, { editing = null }, { content -> actions.editMessage(message.id, content); editing = null }) }
    deleting?.let { message -> ConfirmDelete("这条消息", "该消息将从当前会话中删除。", { deleting = null }, { actions.deleteMessage(message.id) }) }
    rewinding?.let { message -> AlertDialog(onDismissRequest = { rewinding = null }, title = { Text("回溯到这条消息？") }, text = { Text("后续消息将移除，剧情变量回到此节点保存的状态。建议先导出当前会话。") }, confirmButton = { TextButton(onClick = { actions.rewind(message.id); rewinding = null }) { Text("回溯") } }, dismissButton = { TextButton(onClick = { rewinding = null }) { Text("取消") } }) }
}

@Composable
private fun MessageItem(message: ChatMessage, session: ChatSession, state: AppState, reading: Boolean, fontSize: Float, streaming: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onRewind: () -> Unit, canGenerate: Boolean, canEdit: Boolean, onContinue: () -> Unit, onRegenerate: () -> Unit, onSwipe: (Int) -> Unit, onVariables: (JsonObject) -> Unit, onSend: (String) -> Unit) {
    val user = message.role == "user"
    val role = state.roles.firstOrNull { it.id == message.speakerId }
    val name = message.name ?: if (user) session.userName else role?.name ?: "角色"
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val opacity=((session.extra["bubbleOpacity"] as? JsonPrimitive)?.floatOrNull ?: 1f).coerceIn(0.1f,1f)
    val color = if (reading) Color.Transparent else (if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh).copy(alpha=opacity)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (!reading && user) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            if (!user && !reading) { Avatar(name, role?.avatarPath, Modifier.size(28.dp)); Spacer(Modifier.width(8.dp)) }
            Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (reading) { Spacer(Modifier.weight(1f)); if (message.role == "system") Text("系统", style = MaterialTheme.typography.labelSmall) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.MoreHoriz, "消息操作", Modifier.size(20.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("复制消息") }, onClick = { menu = false; clipboard.setText(AnnotatedString(message.content)) })
                    if (canGenerate) {
                        DropdownMenuItem(text = { Text("续写") }, onClick = { menu = false; onContinue() })
                        DropdownMenuItem(text = { Text("重生成") }, onClick = { menu = false; onRegenerate() })
                    }
                    DropdownMenuItem(text = { Text("编辑消息") }, enabled = canEdit, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("回溯至此") }, enabled = canEdit, onClick = { menu = false; onRewind() })
                    DropdownMenuItem(text = { Text("删除消息") }, enabled = canEdit, onClick = { menu = false; onDelete() })
                }
            }
        }
        Surface(color = color, shape = RoundedCornerShape(if (reading || session.extra.text("bubbleStyle")=="square") 0.dp else 20.dp), modifier = if (reading) Modifier.fillMaxWidth() else Modifier.widthIn(max = 600.dp).fillMaxWidth(if (user) .90f else .97f)) {
            Column(Modifier.padding(horizontal = if (reading) 0.dp else 16.dp, vertical = if (reading) 4.dp else 12.dp)) {
                val displayed = remember(message.content, message.extra, session.variables, session.regexIds, state.library, state.roles) { PromptEngine.displayText(message.content, state, session, message.role, processed = message.extra["xujing_processed_output"]?.jsonPrimitive?.booleanOrNull == true) }
                val segments = remember(displayed, streaming) { MessageContentParser.parse(displayed, allowHtml = !streaming) }
                val dark = (if (reading) MaterialTheme.colorScheme.background else color).luminance() < 0.5f
                val quoteColor = if (dark) Color(0xFFFF9F43) else Color(0xFFA34B00)
                val bracketColor = if (dark) Color(0xFF75B8FF) else Color(0xFF005EAD)
                segments.forEachIndexed { segmentIndex, segment ->
                    when (segment) {
                        is MessageSegment.Markdown -> if (segment.source.isNotBlank()) {
                            SelectionContainer { Text(basicMarkdown(segment.source, quoteColor, bracketColor), fontSize = fontSize.sp, lineHeight = (fontSize * 1.65f).sp, color = if (!reading && user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) }
                        }
                        is MessageSegment.Code -> Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                if (segment.language.isNotBlank()) Text(segment.language, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer { Text(segment.source.trimEnd(), fontFamily = FontFamily.Monospace, fontSize = (fontSize * .9f).sp, lineHeight = (fontSize * 1.4f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        is MessageSegment.Html -> CardMessage(
                            content = segment.source, documentMode = segment.document,
                            segmentIndex = segmentIndex,
                            runtimeToken = "${message.id}:${message.swipeIndex}:$segmentIndex:${segment.source.hashCode()}",
                            session = session, message = message, state = state, allowScripts = state.preferences.allowCardScripts,
                            onVariables = onVariables, onSend = onSend
                        )
                    }
                }
                if (message.swipes.size > 1) Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onSwipe(message.swipeIndex - 1) }, enabled = message.swipeIndex > 0) { Icon(Icons.Outlined.ChevronLeft, "上一个候选") }
                    Text("${message.swipeIndex + 1} / ${message.swipes.size}", style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = { onSwipe(message.swipeIndex + 1) }, enabled = message.swipeIndex < message.swipes.lastIndex) { Icon(Icons.Outlined.ChevronRight, "下一个候选") }
                }
            }
        }
    }
}

private fun basicMarkdown(source: String, quoteColor: Color, bracketColor: Color): AnnotatedString = buildAnnotatedString {
    val patterns = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|\\*([^*\\n]+)\\*|_([^_\\n]+)_|`([^`\\n]+)`|\\[([^]\\n]+)]\\((https?://[^\\s)]+)\\)")
    val protectedRanges = mutableListOf<IntRange>()
    var fenced = false
    source.split('\n').forEachIndexed { index, raw ->
        if (index > 0) append('\n')
        if (raw.trimStart().startsWith("```")) {
            fenced = !fenced
        } else if (fenced) {
            val start = length
            pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
            append(raw)
            pop()
            protectedRanges += start until length
        } else {
            val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(raw)
            val text = (heading?.groupValues?.get(2) ?: raw)
                .replace(Regex("^\\s*>\\s?"), "│ ")
                .replace(Regex("^(\\s*)[-+*]\\s+"), "$1• ")
            if (heading != null) pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            var cursor = 0
            patterns.findAll(text).forEach { match ->
                append(text.substring(cursor, match.range.first))
                val style = when { match.groups[1] != null || match.groups[2] != null -> SpanStyle(fontWeight = FontWeight.Bold); match.groups[5] != null -> SpanStyle(fontFamily = FontFamily.Monospace); match.groups[6] != null -> SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline); else -> SpanStyle(fontStyle = FontStyle.Italic) }
                val start = length
                pushStyle(style)
                append((1..6).firstNotNullOfOrNull { match.groups[it]?.value }.orEmpty())
                pop()
                if (match.groups[7] != null) append(" (${match.groups[7]!!.value})")
                if (match.groups[5] != null || match.groups[6] != null) protectedRanges += start until length
                cursor = match.range.last + 1
            }
            append(text.substring(cursor))
            if (heading != null) pop()
        }
    }
    val rendered = toAnnotatedString().text
    fun highlight(pattern: Regex, color: Color) {
        pattern.findAll(rendered).forEach { match ->
            if (protectedRanges.none { it.first <= match.range.last && match.range.first <= it.last }) {
                addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
            }
        }
    }
    highlight(Regex("“[^”]*?”|‘[^’]*?’|\"[^\"]*?\"|(?<![A-Za-z0-9_])'[^'\\n]+'(?![A-Za-z0-9_])|「[^」]*?」|『[^』]*?』|«[^»]*?»|„[^\"]*?\""), quoteColor)
    highlight(Regex("（[^）]*?）|【[^】]*?】|｛[^｝]*?｝|(?<!\\])\\([^)]*?\\)|(?<![!_\\[])\\[[^\\]]*?\\](?!\\()|\\{[^}]*?\\}"), bracketColor)
}

@Composable
private fun MessageEditor(message: ChatMessage, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(message.id) { mutableStateOf(message.content) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑消息") }, text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 420.dp), minLines = 6) }, confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
