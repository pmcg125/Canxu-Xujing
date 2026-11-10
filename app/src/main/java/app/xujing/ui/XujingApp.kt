package app.xujing.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xujing.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class MainPage { Stories, Library, Me }

private data class MainDestination(val page: MainPage, val label: String, val icon: ImageVector)

private val mainDestinations = listOf(
    MainDestination(MainPage.Stories, "故事", Icons.AutoMirrored.Outlined.Chat),
    MainDestination(MainPage.Library, "资料", Icons.Outlined.AutoStories),
    MainDestination(MainPage.Me, "我的", Icons.Outlined.Person),
)

/** Hub tabs: characters first, followed by the library document kinds. */
private val hubTabs = listOf("roles" to "角色") + libraryKinds

@Composable
fun XujingApp(state: AppState, runtime: UiRuntime, actions: AppActions) {
    XujingTheme(state.preferences) {
        var section by rememberSaveable { mutableStateOf(MainPage.Stories) }
        var hubTab by rememberSaveable { mutableStateOf("roles") }
        val pageState = rememberSaveableStateHolder()
        var libraryFromChat by rememberSaveable { mutableStateOf<String?>(null) }
        var libraryKind by rememberSaveable { mutableStateOf("worldbook") }
        var roleEditor by remember { mutableStateOf<RoleCard?>(null) }
        var documentEditor by remember { mutableStateOf<LibraryDocument?>(null) }
        var connectionEditor by remember { mutableStateOf<ApiConnection?>(null) }
        var showCreate by rememberSaveable { mutableStateOf(false) }
        var models by rememberSaveable { mutableStateOf(false) }
        val session = state.sessions.firstOrNull { it.id == runtime.activeSessionId }
        session?.let { app.xujing.runtime.SessionScriptRuntime(state, it) }
        val browsingFromChat = session != null && libraryFromChat == session.id
        LaunchedEffect(runtime.openLibraryKind) {
            val kind = runtime.openLibraryKind ?: return@LaunchedEffect
            libraryFromChat = session?.id
            libraryKind = libraryKinds.firstOrNull { it.first == kind }?.first ?: "worldbook"
            if (session == null) { section = MainPage.Library; hubTab = libraryKind } else section = MainPage.Stories
            actions.consumeOpenLibrary()
        }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(runtime.status) { if (runtime.status.isNotBlank() && !runtime.generating) snackbar.showSnackbar(runtime.status) }
        BackHandler(enabled = roleEditor != null || documentEditor != null || connectionEditor != null || models || session != null) {
            when { roleEditor != null -> roleEditor = null; documentEditor != null -> documentEditor = null; connectionEditor != null -> connectionEditor = null; models -> models = false; browsingFromChat -> libraryFromChat = null; else -> actions.openSession(null) }
        }
        Surface(Modifier.fillMaxSize()) {
            when {
                roleEditor != null -> RoleEditor(roleEditor!!, onClose = { roleEditor = null }, onSave = { val id = it.id; actions.saveRole(it) { if (roleEditor?.id == id) roleEditor = null } })
                documentEditor != null -> DocumentEditor(documentEditor!!, onClose = { documentEditor = null }, onSave = { val id = it.id; actions.saveLibrary(it) { if (documentEditor?.id == id) documentEditor = null } })
                connectionEditor != null -> ConnectionEditor(connectionEditor!!, runtime, actions, onClose = { connectionEditor = null }, onSave = { connection, key -> actions.saveConnection(connection, key) { if (connectionEditor?.id == connection.id) connectionEditor = null } })
                models -> ModelSettings(state, runtime, actions, onClose = { models = false })
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val medium = maxWidth >= 600.dp
                    val wide = maxWidth >= 840.dp
                    val chatSession = session
                    when {
                        // Phones keep the immersive full-screen chat; from tablets upward the rail stays beside it.
                        chatSession != null && !browsingFromChat && !medium -> pageState.SaveableStateProvider("chat:${chatSession.id}") {
                            ChatScreen(state, runtime, actions, chatSession)
                        }
                        !medium -> AppPages(
                            state = state, runtime = runtime, actions = actions, snackbar = snackbar, pageState = pageState,
                            section = section,
                            onSection = { if (runtime.restoringSessionId != null) actions.openSession(null); section = it },
                            hubTab = hubTab, onHubTab = { hubTab = it },
                            browsing = browsingFromChat, browseKind = libraryKind, onBrowseKind = { libraryKind = it }, onExitBrowsing = { libraryFromChat = null },
                            showBottomBar = true,
                            onCreateRole = { roleEditor = RoleCard() }, onEditRole = { roleEditor = it },
                            onCreateDocument = { documentEditor = newLibraryDocument(it) }, onEditDocument = { documentEditor = it },
                            onCreateSession = { showCreate = true },
                            onModels = { models = true }, onConnection = { connectionEditor = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> Row(Modifier.fillMaxSize()) {
                            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                                Spacer(Modifier.height(12.dp))
                                mainDestinations.forEach { destination ->
                                    NavigationRailItem(
                                        selected = section == destination.page,
                                        onClick = {
                                            if (section != destination.page) {
                                                if (destination.page != MainPage.Stories) libraryFromChat = null
                                                if (!wide && runtime.restoringSessionId != null) actions.openSession(null)
                                                section = destination.page
                                            }
                                        },
                                        icon = { Icon(destination.icon, destination.label) },
                                        label = { Text(destination.label) },
                                    )
                                }
                            }
                            VerticalDivider()
                            when {
                                wide && section == MainPage.Stories -> StoriesSplit(
                                    modifier = Modifier.weight(1f),
                                    state = state, runtime = runtime, actions = actions, snackbar = snackbar, pageState = pageState,
                                    browsing = browsingFromChat, browseKind = libraryKind, onBrowseKind = { libraryKind = it }, onExitBrowsing = { libraryFromChat = null },
                                    onEditDocument = { documentEditor = it }, onCreateSession = { showCreate = true },
                                )
                                chatSession != null && !browsingFromChat && section == MainPage.Stories -> Box(Modifier.weight(1f)) {
                                    pageState.SaveableStateProvider("chat:${chatSession.id}") {
                                        ChatScreen(state, runtime, actions, chatSession)
                                    }
                                }
                                else -> AppPages(
                                    state = state, runtime = runtime, actions = actions, snackbar = snackbar, pageState = pageState,
                                    section = section, onSection = { section = it },
                                    hubTab = hubTab, onHubTab = { hubTab = it },
                                    browsing = browsingFromChat && !wide, browseKind = libraryKind, onBrowseKind = { libraryKind = it }, onExitBrowsing = { libraryFromChat = null },
                                    showBottomBar = false,
                                    onCreateRole = { roleEditor = RoleCard() }, onEditRole = { roleEditor = it },
                                    onCreateDocument = { documentEditor = newLibraryDocument(it) }, onEditDocument = { documentEditor = it },
                                    onCreateSession = { showCreate = true },
                                    onModels = { models = true }, onConnection = { connectionEditor = it },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (showCreate && state.roles.isEmpty()) AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("先添加一位角色") },
            confirmButton = { TextButton(onClick = { showCreate = false; roleEditor = RoleCard() }) { Text("创建角色") } },
            dismissButton = { TextButton(onClick = { showCreate = false; actions.requestImport("role") }) { Text("导入角色") } },
        ) else if (showCreate) CreateSessionDialog(state.roles, onDismiss = { showCreate = false }, onCreate = { ids -> showCreate = false; actions.createSession(ids) })
        runtime.importResult?.let { JsonPreview("导入结果", it, actions::clearImportResult) }
        runtime.error?.let { error -> AlertDialog(onDismissRequest = actions::clearError, icon = { Icon(Icons.Outlined.ErrorOutline, null) }, title = { Text("操作未完成") }, text = { Text(error, Modifier.heightIn(max = 360.dp)) }, confirmButton = { TextButton(onClick = actions::clearError) { Text("知道了") } }) }
        runtime.promptPreview?.let { JsonPreview("本轮提示词与编排信息", it, actions::clearPrompt) }
    }
}

/** Page host shared by the phone bottom bar and the tablet/desktop rail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPages(
    state: AppState, runtime: UiRuntime, actions: AppActions, snackbar: SnackbarHostState, pageState: SaveableStateHolder,
    section: MainPage, onSection: (MainPage) -> Unit,
    hubTab: String, onHubTab: (String) -> Unit,
    browsing: Boolean, browseKind: String, onBrowseKind: (String) -> Unit, onExitBrowsing: () -> Unit,
    showBottomBar: Boolean,
    onCreateRole: () -> Unit, onEditRole: (RoleCard) -> Unit,
    onCreateDocument: (String) -> Unit, onEditDocument: (LibraryDocument) -> Unit,
    onCreateSession: () -> Unit,
    onModels: () -> Unit, onConnection: (ApiConnection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when { browsing -> "资料库"; section == MainPage.Stories -> "残叙"; section == MainPage.Library -> "资料"; else -> "我的" }
    val subtitle = when { browsing -> kindName(browseKind); section == MainPage.Stories -> "让故事在这里延续"; section == MainPage.Library -> "角色与写作资料"; else -> null }
    val importKind = when {
        browsing -> "auto"
        section == MainPage.Stories -> "auto"
        section == MainPage.Library -> if (hubTab == "roles") "role" else "auto"
        else -> null
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = { if (browsing) TextButton(onClick = onExitBrowsing) { Text("返回聊天") } },
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                actions = { if (importKind != null) IconButton(onClick = { actions.requestImport(importKind) }) { Icon(Icons.Outlined.FileOpen, "导入资料") } },
            )
        },
        bottomBar = {
            if (showBottomBar && !browsing) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                mainDestinations.forEach { destination ->
                    NavigationBarItem(selected = section == destination.page, onClick = { onSection(destination.page) }, icon = { Icon(destination.icon, destination.label) }, label = { Text(destination.label) })
                }
            }
        },
        floatingActionButton = {
            when {
                browsing -> Unit
                section == MainPage.Stories -> ExtendedFloatingActionButton(onClick = onCreateSession, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("开始故事") })
                section == MainPage.Library -> ExtendedFloatingActionButton(
                    onClick = { if (hubTab == "roles") onCreateRole() else onCreateDocument(hubTab) },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text(if (hubTab == "roles") "创建角色" else "新建资料") },
                )
                else -> Unit
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (runtime.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val contentKey = if (browsing) "browse:$browseKind" else "page:${section.name}"
            pageState.SaveableStateProvider(contentKey) {
                when {
                    browsing -> LibraryScreen(state, actions, kind = browseKind, onKind = onBrowseKind, onEdit = onEditDocument, showTabs = true)
                    section == MainPage.Stories -> SessionsScreen(state, runtime.restoringSessionId, actions, onCreate = onCreateSession)
                    section == MainPage.Library -> HubScreen(
                        state = state, actions = actions, hubTab = hubTab, onHubTab = onHubTab, pageState = pageState,
                        onCreateRole = onCreateRole, onEditRole = onEditRole, onEditDocument = onEditDocument,
                    )
                    else -> SettingsScreen(state, runtime, actions, onModels = onModels, onConnection = onConnection)
                }
            }
        }
    }
}

/** 资料 hub: one tab row merging characters and the library document kinds. */
@Composable
private fun HubScreen(
    state: AppState, actions: AppActions,
    hubTab: String, onHubTab: (String) -> Unit, pageState: SaveableStateHolder,
    onCreateRole: () -> Unit, onEditRole: (RoleCard) -> Unit,
    onEditDocument: (LibraryDocument) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = hubTabs.indexOfFirst { it.first == hubTab }.coerceAtLeast(0),
            edgePadding = 16.dp,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            hubTabs.forEach { (id, name) -> Tab(selected = hubTab == id, onClick = { onHubTab(id) }, text = { Text(name) }) }
        }
        pageState.SaveableStateProvider("hub:$hubTab") {
            if (hubTab == "roles") RolesScreen(state, actions, onEdit = onEditRole, onCreate = onCreateRole)
            else LibraryScreen(state, actions, kind = hubTab, onKind = onHubTab, onEdit = onEditDocument, showTabs = false)
        }
    }
}

/** Expanded desktop layout: persistent 280dp session list on the left, chat or welcome detail on the right. */
@Composable
private fun StoriesSplit(
    modifier: Modifier = Modifier,
    state: AppState, runtime: UiRuntime, actions: AppActions, snackbar: SnackbarHostState, pageState: SaveableStateHolder,
    browsing: Boolean, browseKind: String, onBrowseKind: (String) -> Unit, onExitBrowsing: () -> Unit,
    onEditDocument: (LibraryDocument) -> Unit, onCreateSession: () -> Unit,
) {
    Row(modifier.fillMaxSize()) {
        StoriesPane(
            state = state, restoringSessionId = runtime.restoringSessionId, activeSessionId = runtime.activeSessionId, busy = runtime.busy,
            actions = actions, onCreate = onCreateSession, snackbar = snackbar,
            modifier = Modifier.width(280.dp).fillMaxHeight(),
        )
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val session = state.sessions.firstOrNull { it.id == runtime.activeSessionId }
            when {
                browsing && session != null -> Column(Modifier.fillMaxSize()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onExitBrowsing) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回聊天") }
                            Column(Modifier.weight(1f)) {
                                Text(kindName(browseKind), style = MaterialTheme.typography.titleMedium)
                                Text("资料库", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    pageState.SaveableStateProvider("browse:$browseKind") {
                        LibraryScreen(state, actions, kind = browseKind, onKind = onBrowseKind, onEdit = onEditDocument, showTabs = true)
                    }
                }
                session != null -> pageState.SaveableStateProvider("chat:${session.id}") { ChatScreen(state, runtime, actions, session) }
                else -> WelcomePane(state, actions, runtime.restoringSessionId, onCreate = onCreateSession, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun WelcomePane(state: AppState, actions: AppActions, restoringSessionId: String?, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    val latest = state.sessions.maxByOrNull { it.updatedAt }
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 440.dp)) {
                Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(18.dp))
                Text("残叙", style = MaterialTheme.typography.headlineMedium)
                Text("让故事在这里延续", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(28.dp))
                if (restoringSessionId != null) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在恢复剧情变量…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    latest?.let { session ->
                        ResumeStoryCard(session, state.roles.firstOrNull { role -> role.id in session.roleIds }, onClick = { actions.openSession(session.id) })
                        Spacer(Modifier.height(16.dp))
                    }
                    FilledTonalButton(onClick = onCreate) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("开始新的故事") }
                    Spacer(Modifier.height(20.dp))
                    Text("从左侧选择一个会话，聊天将在这里展开。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun StoriesPane(
    state: AppState, restoringSessionId: String?, activeSessionId: String?, busy: Boolean,
    actions: AppActions, onCreate: () -> Unit, snackbar: SnackbarHostState, modifier: Modifier = Modifier,
) {
    var deleting by remember { mutableStateOf<ChatSession?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    val sessions = state.sessions.filter { it.title.contains(search.trim(), ignoreCase = true) }.sortedByDescending { it.updatedAt }
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("故事", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { actions.requestImport("auto") }) { Icon(Icons.Outlined.FileOpen, "导入资料") }
                    IconButton(onClick = onCreate) { Icon(Icons.Outlined.Add, "开始故事") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.sessions.isNotEmpty()) ListSearch(search, { search = it }, "搜索会话")
                when {
                    state.sessions.isEmpty() -> EmptyState(Icons.AutoMirrored.Outlined.Chat, "一段新的故事", "选择角色开始扮演，或导入原有的角色与聊天资料。", "开始故事", onCreate)
                    sessions.isEmpty() -> EmptyState(Icons.Outlined.SearchOff, "没有匹配的会话", "换一个关键词试试。")
                    else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(sessions, key = { it.id }) { session ->
                            SessionListRow(
                                state, session, restoring = restoringSessionId == session.id, selected = activeSessionId == session.id,
                                onClick = { actions.openSession(session.id) },
                                onExport = { actions.requestExport("chat", session.id) },
                                onDelete = { deleting = session },
                            )
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(8.dp))
        }
    }
    deleting?.let { ConfirmDelete("会话「${it.title}」", "会话内的消息与剧情状态将一并删除。", { deleting = null }, { actions.deleteSession(it.id) }) }
}

@Composable
private fun SessionListRow(
    state: AppState, session: ChatSession, restoring: Boolean, selected: Boolean,
    onClick: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
) {
    val role = state.roles.firstOrNull { it.id in session.roleIds }
    var menu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(role?.name ?: session.title, role?.avatarPath, Modifier.size(38.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(session.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (restoring) "正在恢复剧情变量…" else plainPreview(session.messages.lastOrNull()?.content ?: "故事尚未开始"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.MoreVert, "会话操作") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("导出聊天") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("删除会话") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun SessionsScreen(state: AppState, restoringSessionId: String?, actions: AppActions, onCreate: () -> Unit) {
    var deleting by remember { mutableStateOf<ChatSession?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    val latest = state.sessions.maxByOrNull { it.updatedAt }
    val sessions = state.sessions.filter { it.title.contains(search.trim(), ignoreCase = true) }.sortedByDescending { it.updatedAt }
    Column {
        if (state.sessions.isNotEmpty()) {
            if (search.isBlank() && latest != null) {
                val role = state.roles.firstOrNull { it.id in latest.roleIds }
                ResumeStoryCard(latest, role, restoring = restoringSessionId == latest.id, onClick = { actions.openSession(latest.id) }, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            }
            ListSearch(search, { search = it }, "搜索会话")
        }
        if (state.sessions.isEmpty()) EmptyState(Icons.AutoMirrored.Outlined.Chat, "一段新的故事", "选择角色开始扮演，或导入原有的角色与聊天资料。", "开始故事", onCreate)
        else if (sessions.isEmpty()) EmptyState(Icons.Outlined.SearchOff, "没有匹配的会话", "换一个关键词试试。")
        else LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions, key = { it.id }) { session ->
                val role = state.roles.firstOrNull { it.id in session.roleIds }
                var menu by remember { mutableStateOf(false) }
                ElevatedCard(onClick = { actions.openSession(session.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(role?.name ?: session.title, role?.avatarPath)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(session.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (restoringSessionId == session.id) "正在恢复剧情变量…" else plainPreview(session.messages.lastOrNull()?.content ?: "故事尚未开始"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    }
    deleting?.let { ConfirmDelete("会话「${it.title}」", "会话内的消息与剧情状态将一并删除。", { deleting = null }, { actions.deleteSession(it.id) }) }
}

@Composable
private fun RolesScreen(state: AppState, actions: AppActions, onEdit: (RoleCard) -> Unit, onCreate: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<RoleCard?>(null) }
    Column {
        ListSearch(search, { search = it }, "搜索角色")
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
private fun LibraryScreen(state: AppState, actions: AppActions, kind: String, onKind: (String) -> Unit, onEdit: (LibraryDocument) -> Unit, showTabs: Boolean = true) {
    var deleting by remember { mutableStateOf<LibraryDocument?>(null) }
    var search by rememberSaveable(kind) { mutableStateOf("") }
    Column {
        if (showTabs) ScrollableTabRow(selectedTabIndex = libraryKinds.indexOfFirst { it.first == kind }.coerceAtLeast(0), edgePadding = 16.dp) {
            libraryKinds.forEach { (id, name) -> Tab(selected = kind == id, onClick = { onKind(id) }, text = { Text(name) }) }
        }
        val category = state.library.filter { it.kind == kind }
        val documents = category.filter { it.name.contains(search.trim(), ignoreCase = true) }.sortedBy { it.name }
        if (category.isNotEmpty()) ListSearch(search, { search = it }, "搜索${kindName(kind)}")
        if (category.isEmpty()) EmptyState(Icons.Outlined.AutoStories, "还没有${kindName(kind)}", "导入原有资料，或新建 JSON 资料。未知扩展字段会随资料保留。", "新建${kindName(kind)}", { onEdit(newLibraryDocument(kind)) })
        else if (documents.isEmpty()) EmptyState(Icons.Outlined.SearchOff, "没有匹配的资料", "换一个关键词试试。")
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
    }, confirmButton = { TextButton(enabled = roles.any { it.id in chosen }, onClick = { onCreate(roles.filter { it.id in chosen }.map { it.id }) }) { Text("开始") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun newLibraryDocument(kind: String) = LibraryDocument(kind = kind, name = "新${kindName(kind)}")

@Composable
private fun ListSearch(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(label) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        singleLine = true,
    )
}
