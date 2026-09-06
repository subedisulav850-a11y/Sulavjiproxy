package com.sulav.proxy

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private val Orange = Color(0xFFFF7A00)
private val OrangeDark = Color(0xFFE05D00)
private val Bg = Color(0xFFFFF8F2)
private val Card = Color.White
private val Ink = Color(0xFF25211E)
private val Muted = Color(0xFF81766E)
private val Danger = Color(0xFFD94A3A)

private const val DEFAULT_TARGET = "https://client.ind.freefiremobile.com"
private const val ADMIN_PASSWORD_HASH = "881d4062413649bfe019c1364cc7dd6d75ea399226d7a0224dc772e348f57668"
// The password itself is intentionally not stored in plaintext. The expected admin password is the one supplied by the owner.

// NOTE: The hash above is a placeholder generated for the packaged UI gate. Change it in one place if you want a different password.

data class Traffic(
    val method: String,
    val endpoint: String,
    val status: Int,
    val requestHex: String,
    val responseHex: String,
    val headers: List<Pair<String, String>>,
    val requestSize: Int,
    val responseSize: Int
)

enum class Screen { PROXY, DECODER, SETTINGS, ADMIN }

enum class CreditType { NONE, DAILY, WEEKLY, MONTHLY, UNLIMITED }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OrangeProxyApp() }
    }
}

@Composable
fun OrangeProxyApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("orange_proxy", 0) }
    var target by remember { mutableStateOf(prefs.getString("target", DEFAULT_TARGET) ?: DEFAULT_TARGET) }
    var host by remember { mutableStateOf(prefs.getString("host", "127.0.0.1") ?: "127.0.0.1") }
    var port by remember { mutableStateOf(prefs.getString("port", "8080") ?: "8080") }
    var running by remember { mutableStateOf(false) }
    var traffic by remember { mutableStateOf(listOf<Traffic>()) }
    var selected by remember { mutableStateOf<Traffic?>(null) }
    var screen by remember { mutableStateOf(Screen.PROXY) }
    var status by remember { mutableStateOf("Ready") }
    var decoderInput by remember { mutableStateOf("") }
    var decoderOutput by remember { mutableStateOf("") }
    var decoderMode by remember { mutableStateOf("HEX → TEXT") }
    var server by remember { mutableStateOf<ProxyServer?>(null) }
    var adminUnlocked by remember { mutableStateOf(false) }

    var heroBitmap by remember { mutableStateOf(loadHeroBitmap(context)) }
    var heroUri by remember { mutableStateOf(prefs.getString("hero_image", null)) }

    var maintenance by remember { mutableStateOf(prefs.getBoolean("maintenance", false)) }
    var creditType by remember { mutableStateOf(prefs.getString("credit_type", CreditType.NONE.name) ?: CreditType.NONE.name) }
    var broadcast by remember { mutableStateOf(prefs.getString("broadcast", "") ?: "") }
    var adminIps by remember { mutableStateOf(prefs.getString("admin_ips", "103.13.194.86") ?: "103.13.194.86") }
    var activeAdminIp by remember { mutableStateOf(prefs.getString("active_admin_ip", "103.13.194.86") ?: "103.13.194.86") }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            val json = buildAdminConfigJson(host, port, target, maintenance, creditType, broadcast, adminIps, activeAdminIp)
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).encodeToByteArray()) }
            Toast.makeText(context, "Config exported", Toast.LENGTH_SHORT).show()
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            val file = File(context.filesDir, "hero_image.bin")
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            prefs.edit().putString("hero_image", file.absolutePath).apply()
            heroUri = file.absolutePath
            heroBitmap = BitmapFactory.decodeFile(file.absolutePath)
            Toast.makeText(context, "First-page photo updated", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(context, "Could not load image", Toast.LENGTH_SHORT).show() }
    }

    fun persistAdmin() {
        prefs.edit()
            .putBoolean("maintenance", maintenance)
            .putString("credit_type", creditType)
            .putString("broadcast", broadcast)
            .putString("admin_ips", adminIps)
            .putString("active_admin_ip", activeAdminIp)
            .apply()
    }

    fun start() {
        val p = port.toIntOrNull()
        if (p !in 1..65535) { status = "Invalid port"; return }
        prefs.edit().putString("target", target).putString("host", host).putString("port", port).apply()
        val s = ProxyServer(host, p!!, target,
            onEvent = { e -> traffic = traffic + Traffic(e.method, e.endpoint, e.status, e.requestHex, e.responseHex, e.headers, e.requestSize, e.responseSize) },
            onError = { status = it }
        )
        server = s
        s.start(); running = true; status = "Running • $host:$port"
    }
    fun stop() { server?.stop(); server = null; running = false; status = "Stopped" }

    DisposableEffect(Unit) { onDispose { server?.stop() } }

    MaterialTheme(colorScheme = lightColorScheme(primary = Orange, secondary = OrangeDark, background = Bg, surface = Card, onSurface = Ink)) {
        Scaffold(containerColor = Bg, bottomBar = {
            BottomBar(screen) { screen = it; selected = null }
        }) { pad ->
            when (screen) {
                Screen.PROXY -> ProxyScreen(pad, target, { target = it }, host, { host = it }, port, { port = it }, running, status, traffic, selected, { selected = it }, { if (running) stop() else start() }, { traffic = emptyList() }, heroBitmap, maintenance)
                Screen.DECODER -> DecoderScreen(pad, decoderInput, { decoderInput = it }, decoderOutput, { decoderOutput = it }, decoderMode, { decoderMode = it })
                Screen.SETTINGS -> SettingsScreen(pad, target, { target = it }, host, { host = it }, port, { port = it }, exportLauncher, prefs, heroBitmap, imageLauncher, adminUnlocked) {
                    adminUnlocked = false
                    screen = Screen.ADMIN
                }
                Screen.ADMIN -> AdminScreen(pad, maintenance, { maintenance = it }, creditType, { creditType = it }, broadcast, { broadcast = it }, adminIps, { adminIps = it }, activeAdminIp, { activeAdminIp = it }, heroBitmap, imageLauncher, exportLauncher, persistAdmin, { adminUnlocked = false; screen = Screen.SETTINGS })
            }
        }
    }
}

private fun loadHeroBitmap(context: android.content.Context): android.graphics.Bitmap? {
    val path = context.getSharedPreferences("orange_proxy", 0).getString("hero_image", null) ?: return null
    return BitmapFactory.decodeFile(path)
}

private fun buildAdminConfigJson(host: String, port: String, target: String, maintenance: Boolean, creditType: String, broadcast: String, ips: String, activeIp: String): JSONObject = JSONObject().apply {
    put("serverLoginUrl", "http://$host:$port/")
    put("forwardTarget", target)
    put("maintenance", maintenance)
    put("creditType", creditType)
    put("updateAllUsers", broadcast)
    put("adminIps", ips.split(',').map { it.trim() }.filter { it.isNotBlank() })
    put("activeAdminIp", activeIp)
}

@Composable
fun Header(title: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 14.sp)
    }
}

@Composable
fun Hero(bitmap: android.graphics.Bitmap?, maintenance: Boolean) {
    Card(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
        Box(Modifier.height(190.dp).fillMaxWidth()) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(Orange))
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)))
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Text("ORANGE", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("Proxy Console", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text(if (maintenance) "Maintenance mode" else "Ready for local traffic", color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun ProxyScreen(pad: PaddingValues, target: String, setTarget: (String) -> Unit, host: String, setHost: (String) -> Unit, port: String, setPort: (String) -> Unit, running: Boolean, status: String, traffic: List<Traffic>, selected: Traffic?, setSelected: (Traffic?) -> Unit, toggle: () -> Unit, clear: () -> Unit, hero: android.graphics.Bitmap?, maintenance: Boolean) {
    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 18.dp)) {
        item { Header("Orange Proxy", "A clean local HTTP forwarding console") }
        item { Hero(hero, maintenance) }
        item { Spacer(Modifier.height(8.dp)) }
        item { CardBlock {
            Label("FORWARD TO")
            OutlinedTextField(target, setTarget, Modifier.fillMaxWidth(), singleLine = true, enabled = !running, shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(host, setHost, Modifier.weight(1f), label = { Text("Listen host") }, enabled = !running, singleLine = true)
                OutlinedTextField(port, setPort, Modifier.width(120.dp), label = { Text("Port") }, enabled = !running, singleLine = true)
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = toggle, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = if (running) Danger else Orange)) { Text(if (running) "STOP PROXY" else "START PROXY", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(if (running) Color(0xFF2FA84F) else Color.Gray)); Spacer(Modifier.width(8.dp)); Text(status, color = Muted, fontSize = 13.sp)
            }
        } }
        if (maintenance) item { CardBlock { Text("MAINTENANCE", color = Danger, fontWeight = FontWeight.ExtraBold); Text("Admin has enabled maintenance mode.", color = Muted, fontSize = 13.sp) } }
        item { Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("CAPTURED", fontWeight = FontWeight.Bold, Modifier.weight(1f)); Text("${traffic.size} requests", color = Muted, fontSize = 13.sp); Spacer(Modifier.width(8.dp)); IconButton(onClick = clear) { Icon(Icons.Default.Delete, "Clear") } } }
        if (traffic.isEmpty()) item { EmptyState() }
        items(traffic.reversed()) { event -> TrafficCard(event) { setSelected(event) } }
        selected?.let { event -> item { TrafficDetail(event, setSelected) } }
    }
}

@Composable fun TrafficCard(t: Traffic, onClick: () -> Unit) {
    val ok = t.status in 200..399
    Card(Modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(58.dp).clip(RoundedCornerShape(10.dp)).background(if (t.method == "POST") Orange else Color(0xFFF0E9E2)).padding(vertical = 7.dp), contentAlignment = Alignment.Center) { Text(t.method, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (t.method == "POST") Color.White else Ink) }
            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(t.endpoint, fontWeight = FontWeight.SemiBold, maxLines = 1); Text("${t.requestSize} B → ${t.responseSize} B", color = Muted, fontSize = 12.sp) }
            Text(t.status.toString(), color = if (ok) Color(0xFF258A42) else Danger, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable fun TrafficDetail(t: Traffic, close: (Traffic?) -> Unit) { Card(Modifier.padding(20.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text("REQUEST DETAIL", fontWeight = FontWeight.ExtraBold, Modifier.weight(1f)); TextButton({ close(null) }) { Text("CLOSE") } }; Text("${t.method} ${t.endpoint}", fontWeight = FontWeight.Bold); Text("Status ${t.status}", color = Muted); Spacer(Modifier.height(12.dp)); Text("HEADERS", fontWeight = FontWeight.Bold, color = Orange); t.headers.forEach { (k, v) -> Text("$k: $v", fontSize = 12.sp) }; Spacer(Modifier.height(12.dp)); Text("REQUEST HEX", fontWeight = FontWeight.Bold, color = Orange); Text(t.requestHex, fontSize = 10.sp); Spacer(Modifier.height(10.dp)); Text("RESPONSE HEX", fontWeight = FontWeight.Bold, color = Orange); Text(t.responseHex, fontSize = 10.sp) } } }

@Composable fun EmptyState() { Card(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Shield, null, tint = Orange, Modifier.size(42.dp)); Spacer(Modifier.height(8.dp)); Text("No traffic yet", fontWeight = FontWeight.Bold); Text("Start the proxy, then send HTTP requests from an app or service you control.", color = Muted, fontSize = 13.sp) } } }

@Composable
fun DecoderScreen(pad: PaddingValues, input: String, setInput: (String) -> Unit, output: String, setOutput: (String) -> Unit, mode: String, setMode: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Header("Packet Decoder", "HEX ↔ text utility") }
        item { CardBlock { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(mode == "HEX → TEXT", { setMode("HEX → TEXT") }, label = { Text("HEX → TEXT") }); FilterChip(mode == "TEXT → HEX", { setMode("TEXT → HEX") }, label = { Text("TEXT → HEX") }) }; Spacer(Modifier.height(12.dp)); OutlinedTextField(input, setInput, Modifier.fillMaxWidth().height(180.dp), label = { Text("Input") }); Spacer(Modifier.height(10.dp)); Button({ setOutput(decode(mode, input)) }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("CONVERT", fontWeight = FontWeight.Bold) } } }
        item { CardBlock { Row(verticalAlignment = Alignment.CenterVertically) { Text("OUTPUT", fontWeight = FontWeight.Bold, Modifier.weight(1f)); IconButton({ clipboard.setText(AnnotatedString(output)) }) { Icon(Icons.Default.ContentCopy, "Copy") } }; Spacer(Modifier.height(8.dp)); Text(if (output.isBlank()) "No output" else output, fontSize = 12.sp) } }
    }
}

fun decode(mode: String, input: String): String = runCatching { if (mode == "TEXT → HEX") input.encodeToByteArray().joinToString(" ") { "%02X".format(it.toInt() and 255) } else { val clean = input.replace("0x", "", true).replace(" ", "").replace("\n", "").replace("\r", ""); require(clean.length % 2 == 0); clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray().decodeToString() } }.getOrElse { "Conversion error: ${it.message}" }

@Composable
fun SettingsScreen(pad: PaddingValues, target: String, setTarget: (String) -> Unit, host: String, setHost: (String) -> Unit, port: String, setPort: (String) -> Unit, exportLauncher: androidx.activity.result.ActivityResultLauncher<String>, prefs: android.content.SharedPreferences, hero: android.graphics.Bitmap?, imageLauncher: androidx.activity.result.ActivityResultLauncher<String>, adminUnlocked: Boolean, openAdmin: () -> Unit) {
    var showPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Header("Settings", "Configuration and protected admin tools") }
        item { CardBlock {
            Label("FORWARD URL"); OutlinedTextField(target, setTarget, Modifier.fillMaxWidth(), singleLine = true); Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(host, setHost, Modifier.weight(1f), label = { Text("Host") }); OutlinedTextField(port, setPort, Modifier.width(120.dp), label = { Text("Port") }) }
            Spacer(Modifier.height(14.dp)); Button({ prefs.edit().putString("target", target).putString("host", host).putString("port", port).apply() }, Modifier.fillMaxWidth()) { Text("SAVE SETTINGS") }; Spacer(Modifier.height(8.dp)); OutlinedButton({ exportLauncher.launch("orange-proxy-config.json") }, Modifier.fillMaxWidth()) { Text("EXPORT CONFIG") }
        } }
        item { CardBlock {
            Text("FIRST PAGE PHOTO", fontWeight = FontWeight.Bold); Spacer(Modifier.height(10.dp)); if (hero != null) Image(hero.asImageBitmap(), null, Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) else Text("No photo selected", color = Muted); Spacer(Modifier.height(10.dp)); OutlinedButton({ imageLauncher.launch("image/*") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(8.dp)); Text("UPLOAD PHOTO") }
        } }
        item { CardBlock { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Settings, null, tint = Orange); Spacer(Modifier.width(12.dp)); Column { Text("Privacy", fontWeight = FontWeight.Bold); Text("Authorization headers are not stored in the traffic log.", color = Muted, fontSize = 12.sp) } } } }
        item { Spacer(Modifier.height(10.dp)); CardBlock {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Lock, null, tint = Orange); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("ADMIN PANEL", fontWeight = FontWeight.ExtraBold); Text("Protected controls • owner access", color = Muted, fontSize = 12.sp) } }
            Spacer(Modifier.height(10.dp)); OutlinedTextField(password, { password = it; error = "" }, Modifier.fillMaxWidth(), label = { Text("Admin password") }, singleLine = true)
            Spacer(Modifier.height(8.dp)); Button({ if (verifyAdminPassword(password)) { openAdmin(); password = "" } else error = "Wrong password" }, Modifier.fillMaxWidth()) { Text("OPEN ADMIN PANEL") }
            if (error.isNotBlank()) Text(error, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (adminUnlocked) Text("Admin unlocked", color = Color(0xFF258A42), fontSize = 12.sp)
        } }
    }
}

private fun verifyAdminPassword(input: String): Boolean {
    val hash = MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    return hash == ADMIN_PASSWORD_HASH
}

@Composable
fun AdminScreen(pad: PaddingValues, maintenance: Boolean, setMaintenance: (Boolean) -> Unit, creditType: String, setCreditType: (String) -> Unit, broadcast: String, setBroadcast: (String) -> Unit, adminIps: String, setAdminIps: (String) -> Unit, activeIp: String, setActiveIp: (String) -> Unit, hero: android.graphics.Bitmap?, imageLauncher: androidx.activity.result.ActivityResultLauncher<String>, exportLauncher: androidx.activity.result.ActivityResultLauncher<String>, save: () -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    var updateState by remember { mutableStateOf("") }
    val ips = adminIps.split(',').map { it.trim() }.filter { it.isNotBlank() }
    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("Admin Panel", "Owner controls • protected area") }
        item { CardBlock { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("MAINTENANCE MODE", fontWeight = FontWeight.Bold); Text(if (maintenance) "ON — app shows maintenance state" else "OFF — normal operation", color = Muted, fontSize = 12.sp) }; Switch(maintenance, setMaintenance) } } }
        item { CardBlock { Text("UPDATE ALL USERS", fontWeight = FontWeight.Bold); Text("Prepare a broadcast message for your user configuration/publishing workflow.", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(8.dp)); OutlinedTextField(broadcast, setBroadcast, Modifier.fillMaxWidth().height(120.dp), label = { Text("Update message") }); Spacer(Modifier.height(8.dp)); Button({ save(); updateState = "Update saved to local admin configuration" }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("PUBLISH UPDATE") }; if (updateState.isNotBlank()) Text(updateState, color = Color(0xFF258A42), fontSize = 12.sp, Modifier.padding(top = 6.dp)) } }
        item { CardBlock { Text("ADMIN / SERVER IPs", fontWeight = FontWeight.Bold); Text("103.13.194.86 is included by default. Add more comma-separated IPs as needed.", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(8.dp)); OutlinedTextField(adminIps, setAdminIps, Modifier.fillMaxWidth(), label = { Text("Allowed / configured IPs") }); Spacer(Modifier.height(8.dp)); Text("Active IP", fontWeight = FontWeight.SemiBold); ips.forEach { ip -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(activeIp == ip, { setActiveIp(ip) }); Text(ip) } }; Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ if (!adminIps.contains("103.13.194.86")) setAdminIps(if (adminIps.isBlank()) "103.13.194.86" else "$adminIps,103.13.194.86") }, Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("ADD DEFAULT IP") }; Button({ save() }, Modifier.weight(1f)) { Text("SAVE IPs") } } } }
        item { CardBlock { Text("CREDIT TYPE", fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); CreditType.values().forEach { type -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(creditType == type.name, { setCreditType(type.name) }); Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) } } } }
        item { CardBlock { Text("APP FIRST PAGE", fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); if (hero != null) Image(hero.asImageBitmap(), null, Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop); Spacer(Modifier.height(8.dp)); OutlinedButton({ imageLauncher.launch("image/*") }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(8.dp)); Text("UPLOAD / REPLACE PHOTO") } } }
        item { CardBlock { Button({ save(); exportLauncher.launch("orange-admin-config.json") }, Modifier.fillMaxWidth()) { Text("SAVE + EXPORT ADMIN CONFIG") }; Spacer(Modifier.height(8.dp)); OutlinedButton(close, Modifier.fillMaxWidth()) { Text("LOCK ADMIN PANEL") } } }
    }
}

@Composable fun CardBlock(content: @Composable ColumnScope.() -> Unit) { Card(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(16.dp), content = content) } }
@Composable fun Label(text: String) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Orange); Spacer(Modifier.height(7.dp)) }

@Composable
fun BottomBar(current: Screen, select: (Screen) -> Unit) {
    NavigationBar(containerColor = Color.White) {
        NavigationBarItem(current == Screen.PROXY, { select(Screen.PROXY) }, icon = { Icon(Icons.Default.Shield, null) }, label = { Text("PROXY") })
        NavigationBarItem(current == Screen.DECODER, { select(Screen.DECODER) }, icon = { Text("<>", fontWeight = FontWeight.Bold) }, label = { Text("DECODER") })
        NavigationBarItem(current == Screen.SETTINGS || current == Screen.ADMIN, { select(Screen.SETTINGS) }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("SETTINGS") })
    }
}
