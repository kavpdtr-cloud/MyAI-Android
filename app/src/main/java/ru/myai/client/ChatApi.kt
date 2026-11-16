package ru.myai.client

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ChatApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun send(baseUrl: String, apiKey: String, model: String, messages: List<ChatMessage>, attachments: List<ChatAttachment> = emptyList()): String {
        validate(baseUrl, apiKey, model)
        val array = JSONArray()
        messages.takeLast(40).forEachIndexed { index, message ->
            val isLast = index == messages.takeLast(40).lastIndex
            val content: Any = if (isLast && message.role == "user" && attachments.isNotEmpty()) {
                JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", message.content + documentText(attachments)))
                    attachments.filter { it.base64 != null }.forEach { file ->
                        put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${file.mimeType};base64,${file.base64}")))
                    }
                }
            } else message.content
            array.put(JSONObject().put("role", message.role).put("content", content))
        }
        val payload = JSONObject().put("model", model).put("messages", array)
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(body, response.code)
            val content = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").get("content")
            return when (content) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull { i -> content.optJSONObject(i)?.optString("text") }.joinToString("\n")
                else -> content.toString()
            }
        }
    }

    fun generateImage(baseUrl: String, apiKey: String, model: String, prompt: String): String {
        validate(baseUrl, apiKey, model)
        require(prompt.isNotBlank()) { "Введите описание изображения" }
        val payload = JSONObject().put("model", model).put("prompt", prompt).put("n", 1).put("size", "1024x1024")
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/images/generations")
            .header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(body, response.code)
            val item = JSONObject(body).getJSONArray("data").getJSONObject(0)
            return item.optString("url").takeIf { it.isNotBlank() }
                ?: item.optString("b64_json").takeIf { it.isNotBlank() }?.let { "data:image/png;base64,$it" }
                ?: throw IllegalStateException("Провайдер не вернул изображение")
        }
    }

    private fun documentText(files: List<ChatAttachment>) = files.filter { it.extractedText != null }.joinToString("\n\n") {
        "\n\n[Документ: ${it.name}]\n${it.extractedText}"
    }

    private fun validate(baseUrl: String, apiKey: String, model: String) {
        require(baseUrl.startsWith("https://")) { "Укажите HTTPS-адрес API" }
        require(apiKey.isNotBlank()) { "Сначала укажите API-ключ в настройках" }
        require(model.isNotBlank()) { "Укажите название модели" }
    }

    private fun apiError(body: String, code: Int): Exception {
        val detail = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
        return IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "Ошибка API: HTTP $code")
    }
}
