package app.xujing.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xujing.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XujingApp(state: AppState, runtime: UiRuntime, actions: AppActions) {
    XujingTheme(state.preferences) {
        var screen by rememberSaveable { mutableStateOf("sessions") }
        var libraryKind by rememberSaveable { mutableStateOf("worldbook") }
        var roleEditor by remember { mutableStateOf<RoleCard?>(null) }
        var documentEditor by remember { mutableStateOf<LibraryDocument?>(null) }
        var connectionEditor by remember { mutableStateOf<ApiConnection?>(null) }
        var showCreate by rememberSaveable { mutableStateOf(false) }
        var models by rememberSaveable { mutableStateOf(false) }
        val session = state.sessions.firstOrNull { it.id == runtime.activeSessionId }
        session?.let { app.xujing.runtime.SessionScriptRuntime(state,it) }
        // Card runtime asked for a library tab (presetManager.open*); leave the chat and show it.
        LaunchedEffect(runtime.openLibraryKind) {
            val kind=runtime.openLibraryKind ?: return@LaunchedEffect
            if(session!=null) actions.openSession(null)
            libraryKind=kind
            screen="library"
            actions.consumeOpenLibrary()
        }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(runtime.status) { if (runtime.status.isNotBlank() && !runtime.generating) snackbar.showSnackbar(runtime.status) }
        BackHandler(enabled = roleEditor != null || documentEditor != null || connectionEditor != null || models || session != null) {
            when { roleEditor != null -> roleEditor = null; documentEditor != null -> documentEditor = null; connectionEditor != null -> connectionEditor = null; models -> models = false; else -> actions.openSession(null) }
        }
        Surface(Modifier.fillMaxSize()) {
            when {
                roleEditor != null -> RoleEditor(roleEditor!!, onClose = { roleEditor = null }, onSave = { actions.saveRole(it); roleEditor = null })
                documentEditor != null -> DocumentEditor(documentEditor!!, onClose = { documentEditor = null }, onSave = { actions.saveLibrary(it); documentEditor = null })
                connectionEditor != null -> ConnectionEditor(connectionEditor!!, runtime, actions, onClose = { connectionEditor = null }, onSave = { connection, key -> actions.saveConnection(connection, key); connectionEditor = null })
                models -> ModelSettings(state, runtime, actions, onClose = { models = false })
                session != null -> ChatScreen(state, runtime, actions, session)
                else -> Scaffold(
                    topBar = { TopAppBar(title = { Column { Text(when (screen) { "roles" -> "角色"; "library" -> "资料库"; "settings" -> "设置"; else -> "残叙" }); if (screen == "sessions") Text("让故事在这里延续", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, actions = {
                        if (screen != "settings") IconButton(onClick = { actions.requestImport(if (screen == "roles") "role" else "auto") }) { Icon(Icons.Outlined.FileOpen, "导入资料") }
                    }) },
                    bottomBar = {
                        NavigationBar {
                            listOf(Triple("sessions", "会话", Icons.AutoMirrored.Outlined.Chat), Triple("roles", "角色", Icons.Outlined.People), Triple("library", "资料库", Icons.Outlined.AutoStories), Triple("settings", "设置", Icons.Outlined.Settings)).forEach { (key, title, icon) ->
                                NavigationBarItem(selected = screen == key, onClick = { screen = key }, icon = { Icon(icon, title) }, label = { Text(title) })
                            }
                        }
                    },
                    floatingActionButton = {
                        if (screen != "settings") ExtendedFloatingActionButton(onClick = {
                            when (screen) { "roles" -> roleEditor = RoleCard(); "library" -> documentEditor = LibraryDocument(); else -> showCreate = true }
                        }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(when (screen) { "roles" -> "创建角色"; "library" -> "新建资料"; else -> "开始故事" }) })
                    }, snackbarHost = { SnackbarHost(snackbar) },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (runtime.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        when (screen) {
                            "roles" -> RolesScreen(state, actions, onEdit = { roleEditor = it }, onCreate = { roleEditor = RoleCard() })
                            "library" -> LibraryScreen(state, actions, kind = libraryKind, onKind = { libraryKind = it }, onEdit = { documentEditor = it })
                            "settings" -> SettingsScreen(state, runtime, actions, onModels = { models = true }, onConnection = { connectionEditor = it })
                            else -> SessionsScreen(state, actions, onCreate = { showCreate = true })
                        }
                    }
                }
            }
        }
        if (showCreate) CreateSessionDialog(state.roles, onDismiss = { showCreate = false }, onCreate = { ids -> showCreate = false; actions.createSession(ids) })
        runtime.error?.let { error -> AlertDialog(onDismissRequest = actions::clearError, icon = { Icon(Icons.Outlined.ErrorOutline, null) }, title = { Text("操作未完成") }, text = { Text(error, Modifier.heightIn(max = 360.dp)) }, confirmButton = { TextButton(onClick = actions::clearError) { Text("知道了") } }) }
        runtime.promptPreview?.let { JsonPreview("本轮提示词与编排信息", it, actions::clearPrompt) }
    }
}

@Composable
private fun SessionsScreen(state: AppState, actions: AppActions, onCreate: () -> Unit) {
    var deleting by remember { mutableStateOf<ChatSession?>(null) }
    if (state.sessions.isEmpty()) EmptyState(Icons.AutoMirrored.Outlined.Chat, "一段新的故事", "选择角色开始扮演，或导入原有的角色与聊天资料。", "开始故事", onCreate)
    else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.sessions.sortedByDescending { it.updatedAt }, key = { it.id }) { session ->
            val role = state.roles.firstOrNull { it.id in session.roleIds }
            var menu by remember { mutableStateOf(false) }
            ElevatedCard(onClick = { actions.openSession(session.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(role?.name ?: session.title, role?.avatarPath)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(session.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(plainPreview(session.messages.lastOrNull()?.content ?: "故事尚未开始"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${session.messages.size} 条消息 · ${SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(session.updatedAt))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 5.dp))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "会话操作") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("导出聊天") }, onClick = { menu = false; actions.requestExport("chat", session.id) })
                            DropdownMenuItem(text = { Text("删除会话") }, onClick = { menu = false; deleting = session })
                        }
                    }
                }
            }
        }
    }
    deleting?.let { ConfirmDelete("会话「${it.title}」", "会话内的消息与剧情状态将一并删除。", { deleting = null }, { actions.deleteSession(it.id) }) }
}

@Composable
private fun RolesScreen(state: AppState, actions: AppActions, onEdit: (RoleCard) -> Unit, onCreate: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<RoleCard?>(null) }
    Column {
        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), placeholder = { Text("搜索角色") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
        val roles = state.roles.filter { it.name.contains(search, true) || it.description.contains(search, true) }
            .sortedByDescending { (it.raw["xujing_pinned"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" }
        if (state.roles.isEmpty()) EmptyState(Icons.Outlined.People, "为故事选择主角", "支持编辑角色设定、开场白和对话示例，也可导入角色卡 PNG 或 JSON。", "创建角色", onCreate)
        else if (roles.isEmpty()) EmptyState(Icons.Outlined.SearchOff, "没有匹配的角色", "换一个关键词试试。")
        else LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(roles, key = { it.id }) { role ->
                var menu by remember { mutableStateOf(false) }
                OutlinedCard(onClick = { onEdit(role) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(role.name, role.avatarPath)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(role.name, style = MaterialTheme.typography.titleMedium)
                                Text(plainPreview(role.description.ifBlank { role.personality.ifBlank { "尚未填写角色描述" } }), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "角色操作") }
                                DropdownMenu(menu, { menu = false }) {
                                    val pinned = (role.raw["xujing_pinned"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                                    DropdownMenuItem(text = { Text(if (pinned) "取消置顶" else "置顶角色") }, onClick = {
                                        menu = false
                                        actions.saveRole(role.copy(raw = kotlinx.serialization.json.JsonObject(role.raw + ("xujing_pinned" to kotlinx.serialization.json.JsonPrimitive(!pinned)))))
                                    })
                                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit(role) })
                                    DropdownMenuItem(text = { Text("复制角色") }, onClick = { menu = false; onEdit(role.copy(id = newId(), name = "${role.name} 副本")) })
                                    DropdownMenuItem(text = { Text("导出角色卡") }, onClick = { menu = false; actions.requestExport("role", role.id) })
                                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; deleting = role })
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = { actions.createSession(listOf(role.id)) }, modifier = Modifier.align(Alignment.End)) { Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("开始会话") }
                    }
                }
            }
        }
    }
    deleting?.let { ConfirmDelete("角色「${it.name}」", "角色设定将删除；已有会话的处理以会话保存的数据为准。", { deleting = null }, { actions.deleteRole(it.id) }) }
}

@Composable
private fun LibraryScreen(state: AppState, actions: AppActions, kind: String, onKind: (String) -> Unit, onEdit: (LibraryDocument) -> Unit) {
    var deleting by remember { mutableStateOf<LibraryDocument?>(null) }
    Column {
        ScrollableTabRow(selectedTabIndex = libraryKinds.indexOfFirst { it.first == kind }.coerceAtLeast(0), edgePadding = 16.dp) {
            libraryKinds.forEach { (id, name) -> Tab(selected = kind == id, onClick = { onKind(id) }, text = { Text(name) }) }
        }
        val documents = state.library.filter { it.kind == kind }
        if (documents.isEmpty()) EmptyState(Icons.Outlined.AutoStories, "还没有${kindName(kind)}", "导入原有资料，或新建 JSON 资料。未知扩展字段会随资料保留。", "新建${kindName(kind)}", { onEdit(LibraryDocument(kind = kind, name = "新${kindName(kind)}")) })
        else LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(documents, key = { it.id }) { document ->
                var menu by remember { mutableStateOf(false) }
                OutlinedCard(onClick = { onEdit(document) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(document.name, style = MaterialTheme.typography.titleMedium); Text(if (document.enabled) "已启用 · ${kindName(document.kind)}" else "已停用 · ${kindName(document.kind)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "资料操作") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(if (document.enabled) "停用" else "启用") }, onClick = { menu = false; actions.saveLibrary(document.copy(enabled = !document.enabled)) })
                                DropdownMenuItem(text = { Text("导出") }, onClick = { menu = false; actions.requestExport(document.kind, document.id) })
                                DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; deleting = document })
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { ConfirmDelete("资料「${it.name}」", onDismiss = { deleting = null }, onConfirm = { actions.deleteLibrary(it.id) }) }
}

@Composable
internal fun CreateSessionDialog(roles: List<RoleCard>, onDismiss: () -> Unit, onCreate: (List<String>) -> Unit) {
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("开始新的故事") }, text = {
        Column {
            Text("选择一个角色进行扮演，或选择多个角色开启本地群聊。", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 12.dp)) {
                items(roles, key = { it.id }) { role ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = if (role.id in chosen) chosen - role.id else chosen + role.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(role.id in chosen, onCheckedChange = { checked -> chosen = if (checked) chosen + role.id else chosen - role.id }); Avatar(role.name, role.avatarPath, Modifier.size(36.dp)); Text(role.name, Modifier.padding(start = 10.dp).weight(1f))
                    }
                }
            }
            if (chosen.size > 1) Text("已选择 ${chosen.size} 位成员", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        }
    }, confirmButton = { TextButton(onClick = { onCreate(chosen.toList()) }) { Text(if (chosen.isEmpty()) "创建空白会话" else "开始") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
