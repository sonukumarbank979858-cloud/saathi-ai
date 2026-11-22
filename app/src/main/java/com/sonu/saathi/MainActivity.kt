package com.sonu.saathi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

val Bg = Color(0xFF0E0E16)
val Card = Color(0xFF1A1A26)
val Pink = Color(0xFFD946EF)
val Violet = Color(0xFF8B5CF6)
val Grad = Brush.horizontalGradient(listOf(Pink, Violet))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Pink, secondary = Violet, background = Bg, surface = Card)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App() }
            }
        }
    }
}

/** Small UI dictionary: Hindi (Devanagari) or English labels. */
class T(val lang: String) {
    private val hi = lang == "hi"
    val home = if (hi) "होम" else "Home"
    val gallery = if (hi) "गैलरी" else "Gallery"
    val messages = if (hi) "संदेश" else "Messages"
    val settings = if (hi) "सेटिंग्स" else "Settings"
    val noChats = if (hi) "अभी कोई चैट नहीं!" else "No chats yet!"
    val typeMsg = if (hi) "संदेश लिखें..." else "Message..."
    val loading = if (hi) "लोड हो रहा है..." else "Loading..."
    val retry = if (hi) "फिर से कोशिश करें" else "Retry"
    val language = if (hi) "भाषा" else "Language"
    val yourName = if (hi) "आपका नाम" else "Your name"
    val streak = if (hi) "दिन की लगातार बातचीत" else "day streak"
    val best = if (hi) "सबसे अच्छा" else "Best"
    val clearAll = if (hi) "सारी चैट मिटाएँ" else "Clear all chats"
    val customize = if (hi) "स्वभाव बदलें" else "Customize"
    val save = if (hi) "सेव करें" else "Save"
    val clearChat = if (hi) "यह चैट मिटाएँ" else "Clear this chat"
    val typing = if (hi) "लिख रही है..." else "typing..."
    val noKey = if (hi) "एडमिन पैनल में Gemini key नहीं डाली गई है।" else "Gemini key is not set in the admin panel."
    val traitsTitle = if (hi) "स्वभाव चुनें" else "Personality traits"
    val all = if (hi) "सभी" else "All"
    val chatNow = if (hi) "बात करें" else "Chat"
    val empty = if (hi) "अभी कोई किरदार नहीं। एडमिन पैनल से जोड़ें।" else "No characters yet. Add them from the admin panel."
}

val TRAITS = listOf("Sweet", "Sarcastic", "Shy", "Bold", "Clingy", "Independent", "Jealous", "Romantic",
    "Flirty", "Caring", "Playful", "Talkative", "Teasing", "Loyal", "Mysterious", "Funny", "Wise", "Motivating")

@Composable
fun App() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var lang by remember { mutableStateOf(store.language) }
    val t = T(lang)
    var chars by remember { mutableStateOf<List<Character>?>(null) }
    var config by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val streak = remember { store.touchStreak() }

    LaunchedEffect(reload) {
        error = null
        try {
            chars = Firestore.characters()
            config = try { Firestore.config() } catch (e: Exception) { emptyMap() }
        } catch (e: Exception) { error = e.message ?: "Error" }
    }

    var tab by remember { mutableIntStateOf(0) }
    var openChat by remember { mutableStateOf<Character?>(null) }
    var openCustom by remember { mutableStateOf<Character?>(null) }
    var viewImage by remember { mutableStateOf<String?>(null) }

    androidx.activity.compose.BackHandler(enabled = openChat != null || openCustom != null || viewImage != null) {
        when {
            viewImage != null -> viewImage = null
            openCustom != null -> openCustom = null
            else -> openChat = null
        }
    }

    when {
        viewImage != null -> ImageViewer(viewImage!!) { viewImage = null }
        openCustom != null -> CustomizeScreen(openCustom!!, store, t) { openCustom = null }
        openChat != null -> ChatScreen(openChat!!, store, t, lang, config,
            onBack = { openChat = null }, onCustomize = { openCustom = openChat })
        else -> Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Card) {
                    val items = listOf(
                        Triple(t.home, Icons.Filled.Home, 0), Triple(t.gallery, Icons.Filled.Image, 1),
                        Triple(t.messages, Icons.Filled.ChatBubble, 2), Triple(t.settings, Icons.Filled.Settings, 3))
                    items.forEach { (label, icon, i) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i },
                            icon = { Icon(icon, label) }, label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Pink, selectedTextColor = Pink,
                                indicatorColor = Color.Transparent, unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray))
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).statusBarsPadding()) {
                when {
                    error != null && chars == null -> Column(Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, color = Color.LightGray)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { reload++ }) { Text(t.retry) }
                    }
                    chars == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pink) }
                    else -> when (tab) {
                        0 -> HomeScreen(chars!!, t) { openChat = it }
                        1 -> GalleryScreen(chars!!, t) { viewImage = it }
                        2 -> MessagesScreen(chars!!, store, t) { openChat = it }
                        else -> SettingsScreen(store, t, streak, lang, { lang = it; store.language = it }) { reload++ }
                    }
                }
            }
        }
    }
}

@Composable
fun Title(text: String) = Text(text, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White,
    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 12.dp))

@Composable
fun HomeScreen(chars: List<Character>, t: T, open: (Character) -> Unit) {
    val cats = listOf(t.all) + chars.map { it.category }.filter { it.isNotBlank() }.distinct()
    var cat by remember { mutableStateOf(t.all) }
    val shown = if (cat == t.all) chars else chars.filter { it.category == cat }
    Column {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("साथी AI", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Pink)
        }
        if (cats.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cats) { c ->
                val sel = c == cat
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) Pink else Card)
                    .clickable { cat = c }.padding(horizontal = 16.dp, vertical = 8.dp)) { Text(c, color = Color.White) }
            }
        }
        if (shown.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(t.empty, color = Color.Gray) }
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { c -> CharCard(c, t) { open(c) } }
        }
    }
}

@Composable
fun CharCard(c: Character, t: T, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(0.62f).clip(RoundedCornerShape(18.dp)).background(Card).clickable(onClick = onClick)) {
        AsyncImage(c.imageUrl, c.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xEE000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(c.name + if (c.age.isNotBlank()) ", ${c.age}" else "", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (c.tagline.isNotBlank()) Text(c.tagline, color = Color(0xFFDDDDDD), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Grad).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ChatBubble, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text(t.chatNow, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun GalleryScreen(chars: List<Character>, t: T, view: (String) -> Unit) {
    val imgs = chars.flatMap { c -> (listOf(c.imageUrl) + c.gallery).filter { it.isNotBlank() } }.distinct()
    Column {
        Title(t.gallery)
        LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(imgs) { u ->
                AsyncImage(u, null, Modifier.aspectRatio(0.75f).clip(RoundedCornerShape(10.dp)).background(Card).clickable { view(u) },
                    contentScale = ContentScale.Crop)
            }
        }
    }
}

@Composable
fun ImageViewer(url: String, close: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = close)) {
        AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
fun MessagesScreen(chars: List<Character>, store: Store, t: T, open: (Character) -> Unit) {
    val ids = store.chatIds()
    val withChats = chars.filter { it.id in ids }.sortedByDescending { store.messages(it.id).lastOrNull()?.time ?: 0 }
    Column {
        Title(t.messages)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(chars) { c ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { open(c) }) {
                    AsyncImage(c.imageUrl, c.name, Modifier.size(70.dp).clip(CircleShape).border(2.dp, Pink, CircleShape), contentScale = ContentScale.Crop)
                    Text(c.name, color = Color.White, fontSize = 13.sp)
                }
            }
        }
        if (withChats.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(t.noChats, color = Color.Gray, fontSize = 18.sp) }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(withChats) { c ->
                val last = store.messages(c.id).lastOrNull()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).clickable { open(c) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(c.imageUrl, c.name, Modifier.size(52.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(c.name, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(last?.text ?: "", color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(c: Character, store: Store, t: T, lang: String, config: Map<String, Any?>, onBack: () -> Unit, onCustomize: () -> Unit) {
    val scope = rememberCoroutineScope()
    var msgs by remember(c.id) { mutableStateOf(store.messages(c.id)) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val key = config["geminiKey"]?.toString().orEmpty()
    val model = config["model"]?.toString()?.ifBlank { null } ?: "gemini-2.5-flash"

    LaunchedEffect(c.id) {
        if (msgs.isEmpty() && c.greeting.isNotBlank()) {
            msgs = listOf(ChatMessage(false, c.greeting, System.currentTimeMillis()))
        }
    }
    LaunchedEffect(msgs.size, busy) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1 + if (busy) 1 else 0) }

    fun send() {
        val text = input.trim(); if (text.isEmpty() || busy) return
        input = ""
        val now = msgs + ChatMessage(true, text, System.currentTimeMillis())
        msgs = now; store.saveMessages(c.id, now); busy = true
        scope.launch {
            val reply = if (key.isBlank()) t.noKey else try {
                Gemini.reply(key, model, Gemini.systemPrompt(c, store.custom(c.id), lang, store.userName), now)
            } catch (e: Exception) { "⚠️ " + (e.message ?: "Error") }
            val after = msgs + ChatMessage(false, reply, System.currentTimeMillis())
            msgs = after; store.saveMessages(c.id, after); busy = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        AsyncImage(c.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x33000000), Color(0xCC000000)))))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                AsyncImage(c.imageUrl, null, Modifier.size(42.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    if (busy) Text(t.typing, color = Color(0xFFDDDDDD), fontSize = 12.sp)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(t.customize) }, onClick = { menu = false; onCustomize() })
                        DropdownMenuItem(text = { Text(t.clearChat) }, onClick = { menu = false; store.clearChat(c.id); msgs = emptyList() })
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
                items(msgs) { m -> Bubble(m) }
                if (busy) item { Bubble(ChatMessage(false, "• • •", 0)) }
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(value = input, onValueChange = { input = it }, placeholder = { Text(t.typeMsg) },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(28.dp), maxLines = 4,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Card, unfocusedContainerColor = Card,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(54.dp).clip(CircleShape).background(Grad).clickable { send() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun Bubble(m: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        val shape = RoundedCornerShape(20.dp)
        Box(Modifier.widthIn(max = 290.dp).clip(shape)
            .then(if (m.fromUser) Modifier.background(Grad) else Modifier.background(Color(0xE61A1A26)))
            .padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(m.text, color = Color.White, fontSize = 16.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomizeScreen(c: Character, store: Store, t: T, close: () -> Unit) {
    var cu by remember { mutableStateOf(store.custom(c.id)) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentPadding = PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                Text("${t.customize}: ${c.name}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AsyncImage(c.imageUrl, null, Modifier.size(160.dp).clip(CircleShape).border(3.dp, Pink, CircleShape), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp)) {
                SliderRow("Shy", "Bold", cu.shyBold) { cu = cu.copy(shyBold = it) }
                SliderRow("Caring", "Playful", cu.caringPlayful) { cu = cu.copy(caringPlayful = it) }
                SliderRow("Cute", "Mature", cu.cuteMature) { cu = cu.copy(cuteMature = it) }
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp)) {
                Text(t.traitsTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TRAITS.forEach { tr ->
                        val sel = tr in cu.traits
                        Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) Pink else Color(0xFF2A2A38))
                            .clickable { cu = cu.copy(traits = if (sel) cu.traits - tr else cu.traits + tr) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)) { Text(tr, color = Color.White) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Grad)
                .clickable { store.saveCustom(c.id, cu); close() }.padding(16.dp), contentAlignment = Alignment.Center) {
                Text(t.save, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun SliderRow(a: String, b: String, v: Float, on: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(a, color = Color.LightGray); Text(b, color = Color.LightGray)
    }
    Slider(value = v, onValueChange = on, colors = SliderDefaults.colors(thumbColor = Pink, activeTrackColor = Pink))
}

@Composable
fun SettingRow(icon: ImageVector, title: String, sub: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF3B1F4A)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Pink)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = Color.Gray)
        }
    }
}

@Composable
fun SettingsScreen(store: Store, t: T, streak: Pair<Int, Int>, lang: String, setLang: (String) -> Unit, reload: () -> Unit) {
    var name by remember { mutableStateOf(store.userName) }
    var langDialog by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val langName = mapOf("hi" to "हिंदी (देवनागरी)", "hinglish" to "Hinglish", "en" to "English")
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(t.settings, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        item { SettingRow(Icons.Filled.LocalFireDepartment, "🔥 ${streak.first} ${t.streak}", "${t.best}: ${streak.second}") }
        item { SettingRow(Icons.Filled.Translate, t.language, langName[lang]) { langDialog = true } }
        item {
            OutlinedTextField(value = name, onValueChange = { name = it; store.userName = it }, label = { Text(t.yourName) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        item { SettingRow(Icons.Filled.Refresh, t.retry) { reload() } }
        item { SettingRow(Icons.Filled.DeleteSweep, t.clearAll) { confirmClear = true } }
        item { Text("साथी AI v${BuildConfig.VERSION_NAME}", color = Color.Gray, modifier = Modifier.padding(8.dp)) }
    }
    if (langDialog) AlertDialog(onDismissRequest = { langDialog = false }, confirmButton = {},
        title = { Text(t.language) },
        text = {
            Column {
                langName.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().clickable { setLang(k); langDialog = false }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = lang == k, onClick = { setLang(k); langDialog = false })
                        Text(v)
                    }
                }
            }
        })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        confirmButton = { TextButton(onClick = { store.clearAll(); confirmClear = false }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("✕") } },
        title = { Text(t.clearAll) })
}
