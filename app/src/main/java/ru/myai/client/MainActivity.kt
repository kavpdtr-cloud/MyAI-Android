package ru.myai.client

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.File
import java.net.URI

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MyAiApp() } }
}

data class ChatMessage(val role: String, val content: String, val image: String? = null)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MyAiApp() {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    var baseUrl by remember { mutableStateOf(prefs.baseUrl) }
    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var model by remember { mutableStateOf(prefs.model) }
    var imageModel by remember { mutableStateOf(prefs.imageModel) }
    var showSettings by remember { mutableStateOf(false) }
    var showImagePrompt by remember { mutableStateOf(false) }
    var imagePrompt by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val attachments = remember { mutableStateListOf<ChatAttachment>() }
    val messages = remember { mutableStateListOf<ChatMessage>().apply { addAll(prefs.loadMessages()) } }
    val scope = rememberCoroutineScope()
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        val engine = TextToSpeech(context) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU") }
        tts = engine
        onDispose { engine.stop(); engine.shutdown() }
    }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) draft = listOf(draft, spoken).filter { it.isNotBlank() }.joinToString(" ")
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { try { attachments.add(AttachmentReader.read(context, uri, true)); error = null } catch (e: Exception) { error = e.message } }
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { try { attachments.add(AttachmentReader.read(context, uri, false)); error = null } catch (e: Exception) { error = e.message } }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF315DCE), background = Color(0xFFF5F6FA))) {
        Scaffold(topBar = {
            TopAppBar(title = { Column { Text("Мой AI", fontWeight = FontWeight.Bold); Text("Личный AI-ассистент", style = MaterialTheme.typography.labelSmall, color = Color.Gray) } },
                actions = { IconButton(onClick = { messages.clear(); prefs.saveMessages(messages) }) { Icon(Icons.Default.Add, "Новый чат") }; IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Настройки") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF5F6FA)))
        }, bottomBar = {
            Column(Modifier.fillMaxWidth().background(Color.White).padding(10.dp)) {
                if (attachments.isNotEmpty()) {
                    attachments.forEachIndexed { index, file ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📎 ${file.name}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            TextButton(onClick = { attachments.removeAt(index) }) { Text("Убрать") }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = { docPicker.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/plain", "text/markdown")) }) { Icon(Icons.Default.AttachFile, "Документ") }
                    IconButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) { Icon(Icons.Default.Image, "Фото") }
                    IconButton(onClick = { showImagePrompt = true }) { Text("AI", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU") }
                        try { speechLauncher.launch(intent) } catch (_: Exception) { error = "Нет доступного сервиса распознавания речи" }
                    }) { Icon(Icons.Default.Mic, "Голосовой ввод") }
                    Box(Modifier.weight(1f).background(Color(0xFFF1F3F8), RoundedCornerShape(22.dp)).padding(horizontal = 12.dp, vertical = 12.dp)) {
                        if (draft.isBlank()) Text("Напишите сообщение…", color = Color.Gray)
                        BasicTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                    }
                    IconButton(enabled = !busy && (draft.isNotBlank() || attachments.isNotEmpty()), onClick = {
                        val text = draft.trim().ifBlank { "Проанализируй прикреплённые файлы." }
                        val files = attachments.toList(); attachments.clear(); draft = ""
                        messages.add(ChatMessage("user", text)); prefs.saveMessages(messages); busy = true; error = null
                        scope.launch { try { val answer = withContext(Dispatchers.IO) { ChatApi.send(baseUrl, apiKey, model, messages.toList(), files) }; messages.add(ChatMessage("assistant", answer)); prefs.saveMessages(messages) } catch (e: Exception) { error = e.message ?: "Ошибка запроса" } finally { busy = false } }
                    }) { Icon(Icons.Default.Send, "Отправить") }
                }
                Text("Документы · Фото · Генерация изображений · VPN", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (error != null) Text(error!!, color = Color(0xFFB3261E), modifier = Modifier.padding(10.dp))
                if (messages.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Мой AI", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Text("Задайте вопрос, прикрепите документ или фотографию", color = Color.Gray) } }
                else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(messages) { msg ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.role == "user") Arrangement.End else Arrangement.Start) {
                            Column(Modifier.widthIn(max = 340.dp).background(if (msg.role == "user") Color(0xFFE3EBFF) else Color.White, RoundedCornerShape(16.dp)).padding(13.dp)) {
                                Text(if (msg.role == "user") "Вы" else "Мой AI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp)); Text(msg.content)
                                if (msg.image != null) GeneratedImage(msg.image, context)
                                if (msg.role == "assistant") TextButton(onClick = { tts?.speak(msg.content, TextToSpeech.QUEUE_FLUSH, null, "reply") }) { Icon(Icons.Default.VolumeUp, null); Text("Озвучить") }
                            }
                        }
                    }
                    if (busy) item { Text("ИИ формирует ответ…", color = Color.Gray) }
                }
            }
        }
    }

    if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("Настройки подключения") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("HTTPS-адрес API") }, singleLine = true)
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API-ключ") }, singleLine = true)
            OutlinedTextField(model, { model = it }, label = { Text("Модель чата с поддержкой изображений") }, singleLine = true)
            OutlinedTextField(imageModel, { imageModel = it }, label = { Text("Модель генерации изображений") }, singleLine = true)
            Text("Провайдер должен поддерживать Chat Completions с изображениями и /images/generations. Доступность зависит от провайдера.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }, confirmButton = { TextButton(onClick = { prefs.baseUrl = baseUrl.trim().trimEnd('/'); prefs.apiKey = apiKey.trim(); prefs.model = model.trim(); prefs.imageModel = imageModel.trim(); showSettings = false }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Отмена") } })

    if (showImagePrompt) AlertDialog(onDismissRequest = { showImagePrompt = false }, title = { Text("Создание изображения") }, text = {
        OutlinedTextField(imagePrompt, { imagePrompt = it }, label = { Text("Опишите, что нужно нарисовать") }, minLines = 3)
    }, confirmButton = { TextButton(enabled = !busy && imagePrompt.isNotBlank(), onClick = {
        val prompt = imagePrompt.trim(); showImagePrompt = false; busy = true; error = null
        scope.launch { try { val result = withContext(Dispatchers.IO) { ChatApi.generateImage(baseUrl, apiKey, imageModel, prompt) }; val stored = withContext(Dispatchers.IO) { persistGeneratedImage(context, result) }; messages.add(ChatMessage("assistant", "Изображение по запросу: $prompt", stored)); prefs.saveMessages(messages) } catch (e: Exception) { error = e.message ?: "Не удалось создать изображение" } finally { busy = false } }
    }) { Text("Создать") } }, dismissButton = { TextButton(onClick = { showImagePrompt = false }) { Text("Отмена") } })
}

@Composable
private fun GeneratedImage(source: String, context: Context) {
    val bitmap = remember(source) { if (source.startsWith("data:image")) try { BitmapFactory.decodeByteArray(android.util.Base64.decode(source.substringAfter("base64,"), android.util.Base64.DEFAULT), 0, android.util.Base64.decode(source.substringAfter("base64,"), android.util.Base64.DEFAULT).size) } catch (_: Exception) { null } else null }
    if (bitmap != null) androidx.compose.foundation.Image(bitmap.asImageBitmap(), contentDescription = "Сгенерированное изображение", modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
    else AsyncImage(model = if (source.startsWith("file://")) File(URI(source)) else source, contentDescription = "Сгенерированное изображение", modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
    TextButton(onClick = { saveImage(context, source) }) { Text("Сохранить изображение") }
}

private fun saveImage(context: Context, source: String) {
    try {
        val resolver = context.contentResolver
        val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "MyAI-${System.currentTimeMillis()}.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MyAI") }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        when {
            source.startsWith("file://") -> resolver.openInputStream(Uri.parse(source))?.use { input -> resolver.openOutputStream(uri)?.use { input.copyTo(it) } }
            source.startsWith("data:image") -> {
                val bytes = android.util.Base64.decode(source.substringAfter("base64,"), android.util.Base64.DEFAULT)
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
            }
            else -> {
                val request = DownloadManager.Request(Uri.parse(source)).setTitle("Изображение MyAI").setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "MyAI-${System.currentTimeMillis()}.png")
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                resolver.delete(uri, null, null)
            }
        }
    } catch (_: Exception) { }
}


private fun persistGeneratedImage(context: Context, source: String): String {
    if (!source.startsWith("data:image") && !source.startsWith("https://")) return source
    val directory = File(context.filesDir, "generated_images").apply { mkdirs() }
    val file = File(directory, "image-${System.currentTimeMillis()}.png")
    if (source.startsWith("data:image")) {
        val bytes = android.util.Base64.decode(source.substringAfter("base64,"), android.util.Base64.DEFAULT)
        file.writeBytes(bytes)
    } else {
        java.net.URL(source).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    return Uri.fromFile(file).toString()
}

private fun engineLanguage(engine: TextToSpeech?) { engine?.language = Locale("ru", "RU") }

private class AppPrefs(context: Context) {
    private val p = context.getSharedPreferences("my_ai", Context.MODE_PRIVATE)
    var baseUrl: String get() = p.getString("base", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"; set(v) { p.edit().putString("base", v).apply() }
    var apiKey: String get() = SecureStore.decrypt(p.getString("key_enc", null)) ?: ""; set(v) { p.edit().putString("key_enc", SecureStore.encrypt(v)).apply() }
    var model: String get() = p.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini"; set(v) { p.edit().putString("model", v).apply() }
    var imageModel: String get() = p.getString("image_model", "gpt-image-1") ?: "gpt-image-1"; set(v) { p.edit().putString("image_model", v).apply() }
    fun saveMessages(list: List<ChatMessage>) { val arr = org.json.JSONArray(); list.takeLast(300).forEach { arr.put(org.json.JSONObject().put("role", it.role).put("content", it.content).apply { if (it.image != null) put("image", it.image) }) }; p.edit().putString("messages", arr.toString()).apply() }
    fun loadMessages(): List<ChatMessage> = try { val arr = org.json.JSONArray(p.getString("messages", "[]")); (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); ChatMessage(o.getString("role"), o.getString("content"), o.optString("image").takeIf { it.isNotBlank() }) } } catch (_: Exception) { emptyList() }
}
